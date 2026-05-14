package com.osrsbingo;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Slf4j
public class BingoApiClient
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final MediaType PNG = MediaType.parse("image/png");
	private static final Gson GSON = new Gson();

	private final OkHttpClient httpClient;
	private String apiUrl;
	private String playerToken;
	// In-game RSN of the locally logged-in account. Sent as `X-RSN` on every player-token
	// request so the server can scope the per-user plugin token to the correct clan_member
	// (and reject drops on accounts that aren't signed up for the active event).
	private volatile String currentRsn;

	public BingoApiClient()
	{
		// Read timeout is generous because clan-sync reconciliation against a 100+ member
		// roster does a lot of round-trips to Turso and can comfortably take 30+ seconds.
		// Tighter timeouts here will trip even on healthy servers.
		this.httpClient = new OkHttpClient.Builder()
			.connectTimeout(10, TimeUnit.SECONDS)
			.readTimeout(90, TimeUnit.SECONDS)
			.writeTimeout(30, TimeUnit.SECONDS)
			.build();
	}

	public void configure(String apiUrl, String playerToken)
	{
		this.apiUrl = normalizeBaseUrl(apiUrl);
		this.playerToken = playerToken;
	}

	/**
	 * Sets the in-game RSN of the locally logged-in account. The plugin should call this
	 * on every login (and clear it on logout). Null/empty values are tolerated — the
	 * server will fall back to "any active event" matching, but cross-account safety
	 * degrades.
	 */
	public void setCurrentRsn(String rsn)
	{
		this.currentRsn = (rsn == null || rsn.isEmpty()) ? null : rsn;
	}

	private Request.Builder authedRequest(String url)
	{
		Request.Builder b = new Request.Builder().url(url)
			.header("Authorization", "Bearer " + playerToken);
		String rsn = currentRsn;
		if (rsn != null && !rsn.isEmpty()) b.header("X-RSN", rsn);
		return b;
	}

	/**
	 * Trim whitespace and strip any trailing slashes so callers can safely append
	 * "/api/..." without producing "//" or other malformed URLs. Returns "" for
	 * null/blank input so isConfigured() can detect it.
	 */
	static String normalizeBaseUrl(String raw)
	{
		if (raw == null) return "";
		String s = raw.trim();
		while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
		return s;
	}

	public boolean isConfigured()
	{
		return apiUrl != null && !apiUrl.isEmpty()
			&& playerToken != null && !playerToken.isEmpty();
	}

	/**
	 * GET /api/plugin/config — fetches event, team, player, codeword, tracked drops.
	 */
	public PluginConfigResponse fetchConfig() throws IOException
	{
		Request request = authedRequest(apiUrl + "/api/plugin/config")
			.get()
			.build();

		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful())
			{
				throw new IOException("Config fetch failed: HTTP " + response.code());
			}
			String body = response.body().string();
			return GSON.fromJson(body, PluginConfigResponse.class);
		}
	}

	/**
	 * POST /api/upload — uploads a PNG screenshot, returns the image URL.
	 */
	public String uploadImage(byte[] pngBytes, String filename) throws IOException
	{
		RequestBody fileBody = RequestBody.create(PNG, pngBytes);
		MultipartBody multipart = new MultipartBody.Builder()
			.setType(MultipartBody.FORM)
			.addFormDataPart("file", filename, fileBody)
			.build();

		Request request = authedRequest(apiUrl + "/api/upload")
			.post(multipart)
			.build();

		try (Response response = httpClient.newCall(request).execute())
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

	/**
	 * POST /api/plugin/link — exchange a short-lived admin code + RSN for a long-lived admin token.
	 * Returns the admin token string. Throws IOException on HTTP error (caller should show the status).
	 */
	public LinkResponse linkAdmin(String code, String rsn) throws IOException
	{
		if (apiUrl == null || apiUrl.isEmpty())
		{
			throw new IOException("Site URL is not configured");
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("code", code);
		payload.addProperty("rsn", rsn);

		RequestBody body = RequestBody.create(JSON, payload.toString());
		Request request = new Request.Builder()
			.url(apiUrl + "/api/plugin/link")
			.post(body)
			.build();

		try (Response response = httpClient.newCall(request).execute())
		{
			String responseBody = response.body() != null ? response.body().string() : "";
			if (!response.isSuccessful())
			{
				throw new IOException("HTTP " + response.code() + " — " + responseBody);
			}
			return GSON.fromJson(responseBody, LinkResponse.class);
		}
	}

	/**
	 * GET /api/plugin/active-weekly — returns the currently live weekly competition, or null.
	 * Unauthenticated. Never throws — returns null on any failure.
	 */
	public ActiveWeekly fetchActiveWeekly()
	{
		if (apiUrl == null || apiUrl.isEmpty())
		{
			return null;
		}
		Request request = new Request.Builder()
			.url(apiUrl + "/api/plugin/active-weekly")
			.get()
			.build();
		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful())
			{
				return null;
			}
			String body = response.body().string();
			if (body == null || body.isEmpty() || "null".equals(body.trim()))
			{
				return null;
			}
			return GSON.fromJson(body, ActiveWeekly.class);
		}
		catch (IOException e)
		{
			log.debug("active-weekly fetch failed: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * POST /api/plugin/weekly/enroll — enrolls the given RSN in the live weekly competition.
	 * Unauthenticated. Returns null on transport failure.
	 */
	public EnrollResponse enrollWeekly(String rsn)
	{
		if (apiUrl == null || apiUrl.isEmpty())
		{
			return null;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("rsn", rsn);
		RequestBody body = RequestBody.create(JSON, payload.toString());
		Request request = new Request.Builder()
			.url(apiUrl + "/api/plugin/weekly/enroll")
			.post(body)
			.build();
		try (Response response = httpClient.newCall(request).execute())
		{
			String responseBody = response.body() != null ? response.body().string() : "";
			if (!response.isSuccessful())
			{
				log.debug("weekly/enroll returned HTTP {} — {}", response.code(), responseBody);
				return null;
			}
			return GSON.fromJson(responseBody, EnrollResponse.class);
		}
		catch (IOException e)
		{
			log.debug("weekly/enroll failed: {}", e.getMessage());
			return null;
		}
	}

	public static class ActiveWeekly
	{
		public int id;
		public String title;
		public String type;
		public String metric;
		public String startDate;
		public String endDate;
	}

	/**
	 * GET /api/plugin/schedule — returns upcoming + active bingo events and weekly competitions.
	 * Unauthenticated. Never throws — returns null on any failure.
	 */
	public ScheduleResponse fetchSchedule()
	{
		if (apiUrl == null || apiUrl.isEmpty())
		{
			return null;
		}
		Request request = new Request.Builder()
			.url(apiUrl + "/api/plugin/schedule")
			.get()
			.build();
		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful())
			{
				return null;
			}
			String body = response.body().string();
			return GSON.fromJson(body, ScheduleResponse.class);
		}
		catch (IOException e)
		{
			log.debug("schedule fetch failed: {}", e.getMessage());
			return null;
		}
	}

	public static class ScheduleResponse
	{
		public java.util.List<ScheduledBingo> bingos;
		public java.util.List<ScheduledWeekly> weeklies;
	}

	public static class ScheduledBingo
	{
		public int id;
		public String title;
		public String startDate;
		public String endDate;
		public String status; // "active" | "upcoming"
		public Integer boardSize; // N for an N×N board
		public Integer tileCount; // count of tiles configured for this event
	}

	public static class EventDetail
	{
		public int id;
		public String name;
		public Integer boardSize;
		public String startDate;
		public String endDate;
		public String forceEndedAt;
		public java.util.List<EventTile> tiles;
	}

	public static class EventTile
	{
		public int id;
		public int position;
		public String label;
		public String icon;
		public String description;
		public String tileType;     // "standard" | "drop" | "stat" etc.
		public Integer requiredAmount;
		public String trackedStat;
		public String statType;
		public Integer statGoal;
		public String trackingMode;
		public Boolean optional;
	}

	/**
	 * GET /api/plugin/event/{id} — full event + tiles for the plugin schedule detail view.
	 */
	public EventDetail fetchEventDetail(int id)
	{
		if (apiUrl == null || apiUrl.isEmpty()) return null;
		Request request = new Request.Builder()
			.url(apiUrl + "/api/plugin/event/" + id)
			.get()
			.build();
		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful() || response.body() == null) return null;
			return GSON.fromJson(response.body().string(), EventDetail.class);
		}
		catch (IOException e)
		{
			return null;
		}
	}

	public static class ScheduledWeekly
	{
		public int id;
		public String title;
		public String type;
		public String metric;
		public String status;
		public String startDate;
		public String endDate;
	}

	public static class MyActivePlayer
	{
		public PlayerInfo player;
	}

	public static class PlayerInfo
	{
		public int playerId;
		public String playerToken;
		public String playerName;
		public int eventId;
		public String eventName;
		public Integer teamId;
		public String teamName;
		public String teamColor;
	}

	/**
	 * GET /api/plugin/my-active-player — uses the admin plugin token to discover the
	 * caller's player record (and per-event playerToken) for whatever event they're
	 * currently in. Lets an admin who's also a player skip the manual token-paste step.
	 * Returns null on any failure or when there's no match.
	 */
	public PlayerInfo fetchMyActivePlayer(String adminToken, String rsn)
	{
		if (apiUrl == null || apiUrl.isEmpty() || adminToken == null || adminToken.isEmpty()) return null;
		String url = apiUrl + "/api/plugin/my-active-player";
		if (rsn != null && !rsn.isEmpty()) url += "?rsn=" + java.net.URLEncoder.encode(rsn, java.nio.charset.StandardCharsets.UTF_8);
		Request request = new Request.Builder()
			.url(url)
			.header("Authorization", "Bearer " + adminToken)
			.get()
			.build();
		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful() || response.body() == null) return null;
			MyActivePlayer wrap = GSON.fromJson(response.body().string(), MyActivePlayer.class);
			return wrap == null ? null : wrap.player;
		}
		catch (IOException e)
		{
			return null;
		}
	}

	public static class EnrollResponse
	{
		public boolean enrolled;
		public Boolean alreadyEnrolled;
		public Integer compId;
		public String compTitle;
		public Long baselineValue;
		public String reason;
	}

	/**
	 * POST /api/plugin/hello — self-register as a guest clan member. Unauthenticated.
	 * Returns {knownMember, isGuest}. Never throws — failures become a null return + log.
	 */
	public HelloResponse hello(String rsn)
	{
		if (apiUrl == null || apiUrl.isEmpty())
		{
			return null;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("rsn", rsn);

		RequestBody body = RequestBody.create(JSON, payload.toString());
		Request request = new Request.Builder()
			.url(apiUrl + "/api/plugin/hello")
			.post(body)
			.build();

		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful())
			{
				log.debug("plugin/hello returned HTTP {}", response.code());
				return null;
			}
			String responseBody = response.body().string();
			return GSON.fromJson(responseBody, HelloResponse.class);
		}
		catch (IOException e)
		{
			log.debug("plugin/hello failed: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * POST /api/plugin/clan-sync — upload scraped clan roster. Authenticated with the admin token.
	 */
	public ClanSyncResponse syncClan(String adminToken, String clanName, java.util.List<ClanMember> members) throws IOException, ClanMismatchException, AdminUnauthorizedException
	{
		if (apiUrl == null || apiUrl.isEmpty())
		{
			throw new IOException("Site URL is not configured");
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("clanName", clanName);
		payload.add("members", GSON.toJsonTree(members));

		RequestBody body = RequestBody.create(JSON, payload.toString());
		Request request = new Request.Builder()
			.url(apiUrl + "/api/plugin/clan-sync")
			.header("Authorization", "Bearer " + adminToken)
			.post(body)
			.build();

		try (Response response = httpClient.newCall(request).execute())
		{
			String responseBody = response.body() != null ? response.body().string() : "";
			if (response.code() == 401)
			{
				throw new AdminUnauthorizedException("Admin token revoked");
			}
			if (response.code() == 409)
			{
				String serverClan = null;
				try
				{
					JsonObject err = new JsonParser().parse(responseBody).getAsJsonObject();
					if (err.has("serverClanName"))
					{
						serverClan = err.get("serverClanName").getAsString();
					}
				}
				catch (Exception ignored) {}
				throw new ClanMismatchException(serverClan);
			}
			if (!response.isSuccessful())
			{
				throw new IOException("HTTP " + response.code() + " — " + responseBody);
			}
			return GSON.fromJson(responseBody, ClanSyncResponse.class);
		}
	}

	public static class LinkResponse
	{
		public String token;
		public int userId;
		public String username;
		public String rsn;
	}

	public static class HelloResponse
	{
		public boolean knownMember;
		public boolean isGuest;
	}

	public static class ClanMember
	{
		public String rsn;
		public String rank;
		public Integer joinedDays;
	}

	public static class ClanSyncResponse
	{
		public int added;
		public int updated;
		public int markedLeft;
		public int renamed;
		public int returned;
		public java.util.List<ClanChange> changes;
	}

	public static class ClanChange
	{
		public String type;     // "joined" | "left" | "returned" | "renamed" | "rank_changed"
		public String rsn;
		public String oldRsn;   // populated only on rename
		public String oldRank;  // populated only on rank_changed
		public String newRank;  // populated only on rank_changed
	}

	public static class SyncStatus
	{
		public String lastSyncAt; // ISO 8601 or null
		public SyncStatusSummary summary;
	}

	public static class SyncStatusSummary
	{
		public int added;
		public int markedLeft;
		public int returned;
		public int renamed;
	}

	/**
	 * GET /api/plugin/clan-sync — returns the most recent sync timestamp + summary so the
	 * panel can show "Last sync: X ago" without performing a fresh sync. Returns null state
	 * silently on auth/network failure — this is best-effort UX restoration.
	 */
	public SyncStatus fetchSyncStatus(String adminToken)
	{
		if (apiUrl == null || apiUrl.isEmpty() || adminToken == null || adminToken.isEmpty())
		{
			return null;
		}
		Request request = new Request.Builder()
			.url(apiUrl + "/api/plugin/clan-sync")
			.header("Authorization", "Bearer " + adminToken)
			.get()
			.build();
		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful() || response.body() == null) return null;
			return GSON.fromJson(response.body().string(), SyncStatus.class);
		}
		catch (IOException e)
		{
			return null;
		}
	}

	public static class ClanMismatchException extends Exception
	{
		public final String serverClanName;
		public ClanMismatchException(String serverClanName)
		{
			super("Clan name mismatch");
			this.serverClanName = serverClanName;
		}
	}

	public static class AdminUnauthorizedException extends Exception
	{
		public AdminUnauthorizedException(String message) { super(message); }
	}

	/**
	 * POST /api/events/{eventId}/submissions — submits a drop with image proof.
	 */
	public void submitDrop(int eventId, int tileId, int teamId, int amount, String imageUrl, String note, int creditPlayerId, Integer itemId) throws IOException
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("tileId", tileId);
		payload.addProperty("teamId", teamId);
		payload.addProperty("amount", amount);
		payload.addProperty("imageUrl", imageUrl);
		payload.addProperty("note", note);
		payload.addProperty("creditPlayerId", creditPlayerId);
		if (itemId != null)
		{
			payload.addProperty("itemId", itemId);
		}

		RequestBody body = RequestBody.create(JSON, payload.toString());

		Request request = authedRequest(apiUrl + "/api/events/" + eventId + "/submissions")
			.post(body)
			.build();

		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful())
			{
				String responseBody = response.body() != null ? response.body().string() : "no body";
				throw new IOException("Submission failed: HTTP " + response.code() + " — " + responseBody);
			}
			log.info("Drop submitted successfully for tile {}", tileId);
		}
	}
}
