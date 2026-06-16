package com.osrsbingo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;

/**
 * Posts fire-and-forget notifications straight to Discord webhook URLs (deaths, rare drops).
 *
 * Kept deliberately separate from {@link BingoApiClient}: this talks to discord.com, carries no
 * site auth, and never persists or retries — these messages are throwaway, unlike bingo drops.
 *
 * Every send uses OkHttp's async dispatcher so the network round-trip runs off both the game
 * thread and the plugin's executor. Nothing here can stall the client.
 */
@Slf4j
@Singleton
public class DiscordWebhookClient
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final MediaType PNG = MediaType.parse("image/png");
	// Discord hard-caps webhook message content at 2000 chars.
	private static final int MAX_CONTENT = 2000;

	private final OkHttpClient httpClient;

	@Inject
	public DiscordWebhookClient(OkHttpClient client)
	{
		this.httpClient = client;
	}

	/**
	 * Post a text and/or embed message. Either may be null. No-op if the URL is blank.
	 */
	public void send(String webhookUrl, String content, JsonObject embed)
	{
		if (isBlank(webhookUrl))
		{
			return;
		}
		JsonObject payload = buildPayload(content, embed);
		RequestBody body = RequestBody.create(JSON, payload.toString());
		enqueue(webhookUrl, new Request.Builder().url(webhookUrl).post(body).build());
	}

	/**
	 * Post a text/embed message with a PNG screenshot attached via Discord multipart. To render the
	 * shot inside the embed, the caller should set embed.image.url = "attachment://" + filename.
	 */
	public void sendWithImage(String webhookUrl, String content, JsonObject embed, byte[] png, String filename)
	{
		if (isBlank(webhookUrl))
		{
			return;
		}
		if (png == null || png.length == 0)
		{
			// Nothing to attach — fall back to a plain message rather than send an empty file part.
			send(webhookUrl, content, embed);
			return;
		}
		JsonObject payload = buildPayload(content, embed);
		MultipartBody multipart = new MultipartBody.Builder()
			.setType(MultipartBody.FORM)
			.addFormDataPart("payload_json", payload.toString())
			.addFormDataPart("files[0]", filename, RequestBody.create(PNG, png))
			.build();
		enqueue(webhookUrl, new Request.Builder().url(webhookUrl).post(multipart).build());
	}

	private JsonObject buildPayload(String content, JsonObject embed)
	{
		JsonObject payload = new JsonObject();
		if (!isBlank(content))
		{
			payload.addProperty("content", truncate(content));
		}
		if (embed != null)
		{
			JsonArray embeds = new JsonArray();
			embeds.add(embed);
			payload.add("embeds", embeds);
		}
		return payload;
	}

	private void enqueue(String url, Request request)
	{
		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Anvil webhook post failed: {}", e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				// Drain + close so the connection can be reused. Log non-2xx (incl. 429) at debug
				// only — these are throwaway notifications, no retry.
				try (Response r = response)
				{
					if (!r.isSuccessful())
					{
						log.debug("Anvil webhook returned HTTP {}", r.code());
					}
				}
			}
		});
	}

	private static String truncate(String s)
	{
		return s.length() <= MAX_CONTENT ? s : s.substring(0, MAX_CONTENT);
	}

	private static boolean isBlank(String s)
	{
		return s == null || s.isEmpty();
	}
}
