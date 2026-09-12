package com.anvil.api;

import com.google.gson.JsonParser;
import com.anvil.api.dto.ClipRelayResult;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * The three requests that carry a file: a clan notification, a proof screenshot, and a clip.
 *
 * <p>All of them go to the clan's OWN site, which forwards to whatever Discord channel is configured
 * server-side. The plugin never holds or calls a Discord webhook URL itself — the server owns it —
 * which is what keeps every request the plugin makes pointed at the one configured base URL, as the
 * plugin hub requires.</p>
 *
 * <p>Notifications are fire-and-forget on OkHttp's async dispatcher: a Discord post that fails is
 * not worth blocking a game tick, and there is nothing useful to do about it if it does.</p>
 */
@Slf4j
@Singleton
public class MediaUploads
{
	private final BingoApiClient api;

	@Inject
	MediaUploads(BingoApiClient api)
	{
		this.api = api;
	}

	/**
	 * Fire-and-forget: POST a clan notification to our own site, which forwards it to the Discord
	 * channel configured server-side. {@code channel} is one of "deaths", "pvpKills", "rareDrops",
	 * "combatAchievements". Either {@code content} or {@code embed} may be null; {@code png} may be
	 * null (text/embed-only). The plugin never holds or calls the Discord webhook URL itself — the
	 * server owns it — which keeps every plugin request pointed at the one configured base URL
	 * (RuneLite plugin-hub rule). Never blocks the caller: uses OkHttp's async dispatcher.
	 */
	public void postNotification(String channel, String content, JsonObject embed, byte[] png, String filename)
	{
		if (!api.isConfigured() || channel == null || channel.isEmpty())
		{
			return;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("channel", channel);
		// The player is on a Leagues world. The plugin reports only that fact — whether the clan has a
		// separate Leagues channel, and what a api.isSeasonal() post looks like, is the server's decision, so
		// either can change without waiting for a plugin release. Carried as ambient state like the RSN
		// and account hash, so every notification path gets it without threading a flag through each.
		if (api.isSeasonal())
		{
			payload.addProperty("seasonal", true);
		}
		if (content != null && !content.isEmpty())
		{
			payload.addProperty("content", content);
		}
		if (embed != null)
		{
			payload.add("embed", embed);
		}

		RequestBody body;
		if (png != null && png.length > 0)
		{
			body = new MultipartBody.Builder()
				.setType(MultipartBody.FORM)
				.addFormDataPart("payload_json", payload.toString())
				.addFormDataPart("file", filename != null && !filename.isEmpty() ? filename : "image.png",
					RequestBody.create(BingoApiClient.PNG, png))
				.build();
		}
		else
		{
			body = RequestBody.create(BingoApiClient.JSON, payload.toString());
		}

		Request request = api.authedRequest(api.clanUrl("/api/plugin/notify")).post(body).build();
		api.newCall(request).enqueue(new Callback()
		{
			// WARN, not debug. RuneLite logs at INFO, so both of these were invisible: a notification
			// that the server rejected looked exactly like one that was never sent, and the only
			// honest answer to "why didn't my 99 post" was that nobody could tell.
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Anvil: '{}' notification never reached the site: {}", channel, e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful())
					{
						log.warn("Anvil: the site refused a '{}' notification (HTTP {}).", channel, r.code());
					}
				}
			}
		});
	}

	/**
	 * POST /api/plugin/clip — upload a saved clip and let the SERVER post it to the clan's clips
	 * channel. This is the one file upload that goes through the site rather than straight to
	 * Discord: it means members don't each have to paste a webhook URL into their plugin config,
	 * and it still never involves a URL a server response handed us — this is the same configured
	 * base URL every other request uses.
	 *
	 * Streams the file from disk on the long-timeout upload client. Blocking, so callers run it off
	 * the client thread; {@code moment} is the plugin's own summary of what the clip caught.
	 */
	public ClipRelayResult postClip(File file, String moment, String eventName, int seconds, String contentType)
	{
		return postClip(file, moment, eventName, seconds, contentType, 0, 0);
	}

	/**
	 * As above, plus the clipper's current standing — which the plugin already holds for its own
	 * sidebar, so sending it costs nothing and saves the server re-deriving the board on an upload.
	 */
	public ClipRelayResult postClip(File file, String moment, String eventName, int seconds, String contentType,
		int rank, long points)
	{
		if (!api.isConfigured() || file == null || !file.exists() || file.length() == 0)
		{
			return ClipRelayResult.UNSUPPORTED;
		}
		JsonObject payload = new JsonObject();
		if (moment != null && !moment.isEmpty())
		{
			payload.addProperty("moment", moment);
		}
		if (eventName != null && !eventName.isEmpty())
		{
			payload.addProperty("eventName", eventName);
		}
		if (seconds > 0)
		{
			payload.addProperty("seconds", seconds);
		}
		if (rank > 0)
		{
			payload.addProperty("rank", rank);
			payload.addProperty("points", points);
		}
		MediaType type = MediaType.parse(contentType != null ? contentType : "application/octet-stream");
		MultipartBody multipart = new MultipartBody.Builder()
			.setType(MultipartBody.FORM)
			.addFormDataPart("payload_json", payload.toString())
			.addFormDataPart("file", file.getName(), RequestBody.create(type, file))
			.build();
		Request request = api.authedRequest(api.clanUrl("/api/plugin/clip")).post(multipart).build();
		try (Response response = api.newUploadCall(request).execute())
		{
			if (response.isSuccessful())
			{
				return ClipRelayResult.POSTED;
			}
			switch (response.code())
			{
				// 404 = a site that predates the route. Capability gating should have caught it, but
				// a stale config poll can race a downgrade, so treat it as "not available here".
				case 404:
					return ClipRelayResult.UNSUPPORTED;
				case 501:
					return ClipRelayResult.NO_CHANNEL;
				case 413:
					return ClipRelayResult.TOO_LARGE;
				default:
					log.debug("clip relay returned HTTP {}", response.code());
					return ClipRelayResult.FAILED;
			}
		}
		catch (IOException e)
		{
			log.debug("clip relay failed: {}", e.getMessage());
			return ClipRelayResult.FAILED;
		}
	}

	/**
	 * GET /api/plugin/config — fetches event, team, player, codeword, tracked drops.
	 */

	/**
	 * POST /api/upload — uploads a BingoApiClient.PNG screenshot, returns the image URL.
	 */
	public String uploadImage(byte[] pngBytes, String filename) throws IOException
	{
		RequestBody fileBody = RequestBody.create(BingoApiClient.PNG, pngBytes);
		MultipartBody multipart = new MultipartBody.Builder()
			.setType(MultipartBody.FORM)
			.addFormDataPart("file", filename, fileBody)
			.build();

		Request request = api.authedRequest(api.clanUrl("/api/upload"))
			.post(multipart)
			.build();

		try (Response response = api.newCall(request).execute())
		{
			if (!response.isSuccessful())
			{
				throw new IOException("Image upload failed: HTTP " + response.code());
			}
			String body = response.body().string();
			JsonObject json = new JsonParser().parse(body).getAsJsonObject();
			return json.get("url").getAsString();
		}
	}
}
