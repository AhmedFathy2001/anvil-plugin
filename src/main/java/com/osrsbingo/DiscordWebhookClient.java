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

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Uploads an on-demand OBS replay clip straight to a Discord webhook URL that the USER pastes into
 * the plugin config (Clips section). Nothing else posts to Discord from the plugin: deaths, kills,
 * rare drops and CAs all go through {@link BingoApiClient#postNotification} to our own server, which
 * forwards them. Clips are the one exception because a multi-MB video can't be proxied through the
 * site's request-body limit — and a user-supplied webhook URL is allowed by the plugin hub (unlike a
 * URL handed to us inside a server response).
 *
 * Kept deliberately separate from {@link BingoApiClient}: this talks to discord.com, carries no
 * site auth, and never persists — the upload is throwaway, unlike bingo drops.
 *
 * The upload uses OkHttp's async dispatcher so the round-trip runs off both the game thread and the
 * plugin's executor. Nothing here can stall the client.
 *
 * Rate limits: on a 429 we re-enqueue with the server's Retry-After plus randomized jitter, so
 * simultaneous clients don't retry in lockstep and re-collide (thundering herd).
 */
@Slf4j
@Singleton
public class DiscordWebhookClient
{
	// Discord hard-caps webhook message content at 2000 chars.
	private static final int MAX_CONTENT = 2000;
	// A throwaway notification isn't worth hammering Discord over — a couple of polite retries is plenty.
	private static final int MAX_RETRIES = 2;
	private static final long MAX_RETRY_MS = 10_000L;
	private static final long DEFAULT_RETRY_MS = 1_000L;
	private static final long MAX_JITTER_MS = 500L;

	// Clips are multi-MB video uploads; the shared RuneLite client's short write timeout aborts them
	// mid-upload on slower connections. Give file posts their own generous timeouts (pool/dispatcher
	// are still shared via newBuilder, so this is cheap).
	private final OkHttpClient uploadClient;
	private final ScheduledExecutorService retryScheduler =
		Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread t = new Thread(r, "anvil-webhook-retry");
			t.setDaemon(true);
			return t;
		});

	@Inject
	public DiscordWebhookClient(OkHttpClient client)
	{
		this.uploadClient = client.newBuilder()
			.callTimeout(Duration.ofSeconds(120))
			.writeTimeout(Duration.ofSeconds(120))
			.readTimeout(Duration.ofSeconds(60))
			.build();
	}

	/**
	 * Post a text message with a file attached (e.g. a video clip) via Discord multipart, streaming
	 * straight from disk on the long-timeout upload client. {@code onComplete} receives true only on a
	 * 2xx (after any retries) so the caller can report real success/failure instead of guessing.
	 * Discord infers preview from the filename extension; {@code contentType} is the part's media type.
	 */
	public void sendWithFile(String webhookUrl, String content, File file, String filename, String contentType, Consumer<Boolean> onComplete)
	{
		if (isBlank(webhookUrl) || file == null || !file.exists() || file.length() == 0)
		{
			if (onComplete != null)
			{
				onComplete.accept(false);
			}
			return;
		}
		MediaType type = MediaType.parse(contentType != null ? contentType : "application/octet-stream");
		JsonObject payload = buildPayload(content, null);
		MultipartBody multipart = new MultipartBody.Builder()
			.setType(MultipartBody.FORM)
			.addFormDataPart("payload_json", payload.toString())
			.addFormDataPart("files[0]", filename, RequestBody.create(type, file))
			.build();
		enqueue(uploadClient, new Request.Builder().url(webhookUrl).post(multipart).build(), onComplete);
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

	private void enqueue(OkHttpClient client, Request request, Consumer<Boolean> onComplete)
	{
		enqueueWithRetry(client, request, 0, onComplete);
	}

	private void enqueueWithRetry(OkHttpClient client, Request request, int attempt, Consumer<Boolean> onComplete)
	{
		client.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Anvil webhook post failed: {}", e.getMessage());
				complete(onComplete, false);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				// Drain + close so the connection can be reused.
				try (Response r = response)
				{
					if (r.code() == 429 && attempt < MAX_RETRIES)
					{
						long delayMs = retryDelayMs(r);
						log.debug("Anvil webhook rate-limited, retry {} in {}ms", attempt + 1, delayMs);
						retryScheduler.schedule(
							() -> enqueueWithRetry(client, request, attempt + 1, onComplete),
							delayMs, TimeUnit.MILLISECONDS);
						return;
					}
					if (!r.isSuccessful())
					{
						log.debug("Anvil webhook returned HTTP {}", r.code());
						complete(onComplete, false);
						return;
					}
					complete(onComplete, true);
				}
			}
		});
	}

	private static void complete(Consumer<Boolean> onComplete, boolean ok)
	{
		if (onComplete != null)
		{
			onComplete.accept(ok);
		}
	}

	/** Honor Discord's Retry-After (seconds, may be fractional), capped, plus jitter to de-sync clients. */
	private static long retryDelayMs(Response r)
	{
		long base = DEFAULT_RETRY_MS;
		String header = r.header("Retry-After");
		if (header != null && !header.isEmpty())
		{
			try
			{
				base = (long) Math.ceil(Double.parseDouble(header.trim()) * 1000.0);
			}
			catch (NumberFormatException ignored)
			{
				// Header wasn't a number — stick with the default.
			}
		}
		base = Math.max(0L, Math.min(base, MAX_RETRY_MS));
		return base + ThreadLocalRandom.current().nextLong(MAX_JITTER_MS + 1);
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
