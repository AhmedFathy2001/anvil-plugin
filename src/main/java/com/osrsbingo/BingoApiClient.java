package com.osrsbingo;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.concurrent.TimeUnit;

@Slf4j
@Singleton
public class BingoApiClient
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final MediaType PNG = MediaType.parse("image/png");

	private final Gson gson;
	private final OkHttpClient httpClient;
	private String apiUrl;
	private String playerToken;
	// In-game RSN of the locally logged-in account. Sent as `X-RSN` on every player-token
	// request so the server can scope the per-user plugin token to the correct clan_member
	// (and reject drops on accounts that aren't signed up for the active event).
	private volatile String currentRsn;

	@Inject
	public BingoApiClient(Gson gson, OkHttpClient client)
	{
		this.gson = gson;
		// Read timeout is generous because clan-sync reconciliation against a 100+ member
		// roster does a lot of round-trips to Turso and can comfortably take 30+ seconds.
		// Tighter timeouts here will trip even on healthy servers.
		this.httpClient = client.newBuilder()
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

	/** The RSN currently stamped on requests (the logged-in character), or null. Thread-safe read. */
	public String getCurrentRsn()
	{
		return currentRsn;
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
	 * Fire-and-forget: POST a clan notification to our own site, which forwards it to the Discord
	 * channel configured server-side. {@code channel} is one of "deaths", "pvpKills", "rareDrops",
	 * "combatAchievements". Either {@code content} or {@code embed} may be null; {@code png} may be
	 * null (text/embed-only). The plugin never holds or calls the Discord webhook URL itself — the
	 * server owns it — which keeps every plugin request pointed at the one configured base URL
	 * (RuneLite plugin-hub rule). Never blocks the caller: uses OkHttp's async dispatcher.
	 */
	public void postNotification(String channel, String content, JsonObject embed, byte[] png, String filename)
	{
		if (!isConfigured() || channel == null || channel.isEmpty())
		{
			return;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("channel", channel);
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
					RequestBody.create(PNG, png))
				.build();
		}
		else
		{
			body = RequestBody.create(JSON, payload.toString());
		}

		Request request = authedRequest(apiUrl + "/api/plugin/notify").post(body).build();
		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("notify post failed: {}", e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful())
					{
						log.debug("notify returned HTTP {}", r.code());
					}
				}
			}
		});
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
			return gson.fromJson(body, PluginConfigResponse.class);
		}
	}

	/**
	 * GET /api/plugin/board — full board for the caller's active event (every tile + grid slot +
	 * all-team completion state). Player-token authed. Never throws — returns null on any failure
	 * so the clog view can show a graceful "couldn't load" message.
	 */
	public BoardResponse fetchBoard()
	{
		if (!isConfigured())
		{
			return null;
		}
		Request request = authedRequest(apiUrl + "/api/plugin/board")
			.get()
			.build();
		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful() || response.body() == null)
			{
				return null;
			}
			return gson.fromJson(response.body().string(), BoardResponse.class);
		}
		catch (IOException e)
		{
			log.debug("board fetch failed: {}", e.getMessage());
			return null;
		}
	}

	public static class BoardResponse
	{
		public int eventId;        // event this board belongs to (guards against stale-cache leak)
		public String name;        // event name (for the preview header — may differ from your active event)
		public boolean readOnly;   // true = preview of an event you're not actively competing in
		public String format;      // "bingo" | "tilerace"
		public String scoringMode; // "tiles" | "points"
		public int boardSize;      // N for an N×N grid
		public int yourTeamId;     // the calling player's team (-1 in a read-only preview)
		public java.util.List<BoardTile> tiles;
		public java.util.List<BoardTeam> teams;
		public java.util.List<PluginConfigResponse.TierBand> tiers; // difficulty bands for the Tier filter
	}

	/**
	 * GET /api/plugin/board?eventId=N — read-only preview of any event (upcoming, or a live event
	 * you're not competing in). Anonymous. Never throws — returns null on any failure.
	 */
	public BoardResponse fetchBoardPreview(int eventId)
	{
		if (apiUrl == null || apiUrl.isEmpty())
		{
			return null;
		}
		Request request = new Request.Builder()
			.url(apiUrl + "/api/plugin/board?eventId=" + eventId)
			.get()
			.build();
		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful() || response.body() == null)
			{
				return null;
			}
			return gson.fromJson(response.body().string(), BoardResponse.class);
		}
		catch (IOException e)
		{
			log.debug("board preview fetch failed: {}", e.getMessage());
			return null;
		}
	}

	public static class BoardTile
	{
		public int tileId;
		public int position;     // raw board slot
		public int index;        // 0..N-1 in board order (tile-race sequence)
		public int row;
		public int col;
		public String label;
		public String description;
		public int points;
		public int itemId;       // representative OSRS item icon, or -1 (no item — use a sprite)
		public java.util.List<Integer> itemIds; // every item on the tile (compound sets have several)
		public int requiredAmount;
		public String requirement; // human task text for stat tiles ("Gain 1,000,000 Mining XP"); null otherwise
		public int optional;     // 1 = optional tile
		public String category;  // free-text grouping (e.g. "GWD", "Slayer") for the plugin's Category filter; null = none
		public String tileType;  // "drop" | "kill" | "timed" | "standard" (null on older servers) — for kind classification
		public String statType;  // "skill" | "boss" | "kc" for stat tiles; null otherwise
		public boolean complete; // YOUR team has completed this tile
		// Per-item breakdown for compound tiles (e.g. a full-moon set), with your team's progress.
		// Null/absent for simple single-item or manual tiles.
		public java.util.List<PluginConfigResponse.ItemRequirement> itemRequirements;
	}

	public static class BoardTeam
	{
		public int teamId;
		public String name;
		public String color;     // hex like "#ff0000"
		public java.util.List<Integer> completedTileIds;
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
			return gson.fromJson(responseBody, LinkResponse.class);
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
			return gson.fromJson(body, ActiveWeekly.class);
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
			return gson.fromJson(responseBody, EnrollResponse.class);
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
			return gson.fromJson(body, ScheduleResponse.class);
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
		public String format;      // "bingo" | "tilerace" — picks the in-game view
		public String scoringMode; // "tiles" | "points"
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

	/**
	 * GET /api/plugin/weekly-leaderboard[?id=] — ranked standings for a weekly competition (the
	 * active one when id is null). Unauthenticated. Never throws — returns null on any failure.
	 */
	public WeeklyLeaderboard fetchWeeklyLeaderboard(Integer competitionId)
	{
		if (apiUrl == null || apiUrl.isEmpty())
		{
			return null;
		}
		String url = apiUrl + "/api/plugin/weekly-leaderboard"
			+ (competitionId != null ? "?id=" + competitionId : "");
		Request request = new Request.Builder().url(url).get().build();
		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful())
			{
				return null;
			}
			return gson.fromJson(response.body().string(), WeeklyLeaderboard.class);
		}
		catch (IOException e)
		{
			log.debug("weekly-leaderboard fetch failed: {}", e.getMessage());
			return null;
		}
	}

	public static class WeeklyLeaderboard
	{
		public WeeklyComp competition;
		public int total;
		public java.util.List<LeaderboardEntry> entries;
	}

	public static class WeeklyComp
	{
		public int id;
		public String title;
		public String type;   // "skill" | "boss"
		public String metric;
		public String startDate;
		public String endDate;
	}

	public static class LeaderboardEntry
	{
		public int rank;
		public String rsn;
		public long gained;
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
			MyActivePlayer wrap = gson.fromJson(response.body().string(), MyActivePlayer.class);
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
			return gson.fromJson(responseBody, HelloResponse.class);
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
		payload.add("members", gson.toJsonTree(members));

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
			return gson.fromJson(responseBody, ClanSyncResponse.class);
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
			return gson.fromJson(response.body().string(), SyncStatus.class);
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

	/**
	 * POST /api/events/{eventId}/submissions — submits a timed-clear with image proof.
	 * amount is fixed at 1; the clear time (seconds) rides in durationSeconds. The server
	 * completes the tile when durationSeconds ≤ the tile's threshold.
	 */
	public void submitTimed(int eventId, int tileId, int teamId, int durationSeconds, String imageUrl, String note, int creditPlayerId) throws IOException
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("tileId", tileId);
		payload.addProperty("teamId", teamId);
		payload.addProperty("amount", 1);
		payload.addProperty("durationSeconds", durationSeconds);
		payload.addProperty("imageUrl", imageUrl);
		payload.addProperty("note", note);
		payload.addProperty("creditPlayerId", creditPlayerId);

		RequestBody body = RequestBody.create(JSON, payload.toString());

		Request request = authedRequest(apiUrl + "/api/events/" + eventId + "/submissions")
			.post(body)
			.build();

		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful())
			{
				String responseBody = response.body() != null ? response.body().string() : "no body";
				throw new IOException("Timed submission failed: HTTP " + response.code() + " — " + responseBody);
			}
			log.info("Timed clear submitted successfully for tile {}", tileId);
		}
	}
}
