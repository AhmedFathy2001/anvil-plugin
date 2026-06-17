package com.osrsbingo;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.function.Consumer;

/**
 * Minimal obs-websocket v5 client — connects to OBS, saves the replay buffer on demand, and reports
 * the saved file path back via {@code onClipSaved} so the plugin can post it. Independent of the
 * "Save Replay Buffer for OBS" plugin (we run our own connection because we need the saved path,
 * which that plugin never exposes). All callbacks fire off the client thread.
 *
 * <p>Protocol: Hello(0) → Identify(1) [with SHA-256 auth if OBS requires it] → Identified(2). We
 * subscribe to the Outputs event category so we receive the {@code ReplayBufferSaved} event.
 */
@Slf4j
public class ObsReplayClient extends WebSocketListener
{
	// EventSubscription::Outputs (1 << 6) — the category that contains ReplayBufferSaved.
	private static final int EVENT_SUB_OUTPUTS = 1 << 6;

	private final OkHttpClient http;
	private final Gson gson;
	private final String url;
	private final String password;
	private final Consumer<String> onClipSaved; // savedReplayPath
	private final Runnable onConnected;
	private final Consumer<String> onError;
	private final java.util.function.IntSupplier clipSeconds;
	private final java.util.function.Supplier<String> clipFormat; // OBS RecFormat value, or null to leave as-is

	private WebSocket webSocket;
	private volatile boolean connected;

	public ObsReplayClient(OkHttpClient http, Gson gson, String host, int port, String password,
		Consumer<String> onClipSaved, Runnable onConnected, Consumer<String> onError,
		java.util.function.IntSupplier clipSeconds, java.util.function.Supplier<String> clipFormat)
	{
		this.http = http;
		this.gson = gson;
		this.url = "ws://" + host + ":" + port;
		this.password = password == null ? "" : password;
		this.onClipSaved = onClipSaved;
		this.onConnected = onConnected;
		this.onError = onError;
		this.clipSeconds = clipSeconds;
		this.clipFormat = clipFormat;
	}

	public void connect()
	{
		log.debug("Anvil OBS: connecting to {}", url);
		webSocket = http.newWebSocket(new Request.Builder().url(url).build(), this);
	}

	public void disconnect()
	{
		connected = false;
		if (webSocket != null)
		{
			webSocket.close(1000, "Normal Shutdown");
			webSocket = null;
		}
	}

	public boolean isConnected()
	{
		return connected;
	}

	/** Ask OBS to flush the replay buffer to disk. The path comes back via the ReplayBufferSaved event. */
	public void saveReplayBuffer()
	{
		if (!connected)
		{
			return;
		}
		sendRequest("SaveReplayBuffer", "anvil-clip");
	}

	/**
	 * Change the replay-buffer length/format. RecRBTime only applies when the buffer (re)starts, so we
	 * stop it and let the StopReplayBuffer *response* trigger a restart (see the op-7 handler). Doing
	 * Stop+Start back-to-back is racy — OBS rejects a Start while the output is still stopping — which
	 * is exactly what left the buffer dead before. Safe whether or not it's currently running.
	 */
	public void applyClipLength()
	{
		if (!connected)
		{
			return;
		}
		sendRequest("StopReplayBuffer", "anvil-rb-stop");
	}

	/**
	 * Write the configured replay-buffer length + recording format into the active profile. These only
	 * take effect when the buffer (re)starts, so we always call this immediately before a start. Set
	 * across both Simple and Advanced output modes (the inapplicable one no-ops).
	 */
	private void applyParams()
	{
		String format = clipFormat == null ? null : clipFormat.get();
		if (format != null && !format.isEmpty())
		{
			setProfileParameter("SimpleOutput", "RecFormat2", format);
			setProfileParameter("SimpleOutput", "RecFormat", format);
			setProfileParameter("AdvOut", "RecFormat2", format);
			setProfileParameter("AdvOut", "RecFormat", format);
		}
		if (clipSeconds != null)
		{
			int secs = Math.max(5, Math.min(600, clipSeconds.getAsInt()));
			setProfileParameter("SimpleOutput", "RecRBTime", Integer.toString(secs));
			setProfileParameter("AdvOut", "RecRBTime", Integer.toString(secs));
		}
	}

	/** Apply params, then start. Only call when the buffer is known stopped (status check / post-stop). */
	private void startBuffer()
	{
		applyParams();
		sendRequest("StartReplayBuffer", "anvil-rb-start");
	}

	private void setProfileParameter(String category, String name, String value)
	{
		JsonObject data = new JsonObject();
		data.addProperty("parameterCategory", category);
		data.addProperty("parameterName", name);
		data.addProperty("parameterValue", value);
		sendRequest("SetProfileParameter", "anvil-rbtime", data);
	}

	private void sendRequest(String requestType, String requestId)
	{
		sendRequest(requestType, requestId, null);
	}

	private void sendRequest(String requestType, String requestId, JsonObject requestData)
	{
		if (webSocket == null)
		{
			return;
		}
		JsonObject d = new JsonObject();
		d.addProperty("requestType", requestType);
		d.addProperty("requestId", requestId);
		if (requestData != null)
		{
			d.add("requestData", requestData);
		}
		JsonObject req = new JsonObject();
		req.addProperty("op", 6);
		req.add("d", d);
		webSocket.send(gson.toJson(req));
	}

	@Override
	public void onMessage(WebSocket ws, String text)
	{
		final JsonObject msg;
		try
		{
			msg = gson.fromJson(text, JsonObject.class);
		}
		catch (Exception e)
		{
			return;
		}
		if (msg == null || !msg.has("op"))
		{
			return;
		}
		int op = msg.get("op").getAsInt();
		JsonObject d = msg.has("d") && msg.get("d").isJsonObject() ? msg.getAsJsonObject("d") : null;

		switch (op)
		{
			case 0: // Hello → Identify
			{
				JsonObject id = new JsonObject();
				id.addProperty("rpcVersion", 1);
				id.addProperty("eventSubscriptions", EVENT_SUB_OUTPUTS);
				// authentication is only present when OBS has auth enabled.
				if (d != null && d.has("authentication") && d.get("authentication").isJsonObject())
				{
					JsonObject auth = d.getAsJsonObject("authentication");
					id.addProperty("authentication",
						computeAuth(auth.get("salt").getAsString(), auth.get("challenge").getAsString()));
				}
				JsonObject identify = new JsonObject();
				identify.addProperty("op", 1);
				identify.add("d", id);
				ws.send(gson.toJson(identify));
				break;
			}
			case 2: // Identified
				connected = true;
				log.info("Anvil OBS: identified — checking replay-buffer status");
				if (onConnected != null)
				{
					onConnected.run();
				}
				// Don't blindly stop/start (racy + disrupts a healthy buffer). Ask OBS whether the
				// buffer is active; the op-7 handler starts it only if it's stopped.
				sendRequest("GetReplayBufferStatus", "anvil-rb-status");
				break;
			case 5: // Event
				if (d != null && "ReplayBufferSaved".equals(optString(d, "eventType")))
				{
					JsonObject ed = d.has("eventData") && d.get("eventData").isJsonObject() ? d.getAsJsonObject("eventData") : null;
					String path = ed == null ? null : optString(ed, "savedReplayPath");
					log.info("Anvil OBS: ReplayBufferSaved → {}", path);
					if (path != null && onClipSaved != null)
					{
						onClipSaved.accept(path);
					}
				}
				break;
			case 7: // RequestResponse
			{
				String rt = optString(d, "requestType");
				JsonObject status = d != null && d.has("requestStatus") && d.get("requestStatus").isJsonObject()
					? d.getAsJsonObject("requestStatus") : null;
				boolean ok = status != null && status.has("result") && status.get("result").getAsBoolean();
				String comment = status != null && status.has("comment") && !status.get("comment").isJsonNull()
					? status.get("comment").getAsString() : "";
				if ("SaveReplayBuffer".equals(rt))
				{
					log.info("Anvil OBS: SaveReplayBuffer result={} {}", ok, comment);
					if (!ok && onError != null)
					{
						onError.accept("OBS could not save the clip — is the Replay Buffer started?");
					}
				}
				else if ("GetReplayBufferStatus".equals(rt))
				{
					JsonObject rd = d != null && d.has("responseData") && d.get("responseData").isJsonObject()
						? d.getAsJsonObject("responseData") : null;
					boolean active = rd != null && rd.has("outputActive") && rd.get("outputActive").getAsBoolean();
					log.info("Anvil OBS: replay buffer active={}", active);
					if (!active)
					{
						startBuffer();
					}
				}
				else if ("StopReplayBuffer".equals(rt))
				{
					// We stopped it (to apply a new length) — now restart with params applied.
					log.info("Anvil OBS: stop done (result={}) — restarting buffer", ok);
					startBuffer();
				}
				else if ("StartReplayBuffer".equals(rt))
				{
					log.info("Anvil OBS: StartReplayBuffer result={} {}", ok, comment);
				}
				break;
			}
			default:
				break;
		}
	}

	@Override
	public void onClosing(WebSocket ws, int code, String reason)
	{
		connected = false;
	}

	@Override
	public void onFailure(WebSocket ws, Throwable t, Response response)
	{
		// Quiet on purpose — connection problems are reported when the user actually presses the
		// clip hotkey (captureClip says "OBS isn't connected"). Chatting on every reconnect attempt
		// (e.g. OBS simply not running at login) would spam the game chat. onError is reserved for
		// per-save failures (the op-7 path) so the player learns *why* a save they triggered failed.
		connected = false;
		log.debug("Anvil OBS: connection failed: {}", t == null ? "?" : t.getMessage());
	}

	private static String optString(JsonObject o, String key)
	{
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
	}

	// obs-websocket v5 auth: base64(sha256( base64(sha256(password + salt)) + challenge )).
	private String computeAuth(String salt, String challenge)
	{
		try
		{
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] secretHash = digest.digest((password + salt).getBytes(StandardCharsets.UTF_8));
			String encodedSecret = Base64.getEncoder().encodeToString(secretHash);
			byte[] resultHash = digest.digest((encodedSecret + challenge).getBytes(StandardCharsets.UTF_8));
			return Base64.getEncoder().encodeToString(resultHash);
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
}
