package com.anvil.api;

import com.anvil.api.dto.CoopFingerprint;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * A tile credited by something that happened, with a screenshot behind it.
 *
 * <p>Drops, timed clears, and the starting shot — the three things a person files rather than a
 * counter reports. Each carries an uploaded image, which is what makes them checkable by a clan
 * admin later, and each can be refused with {@code start_proof_required} until the starting shot
 * itself is in (see {@link ApiErrors}).</p>
 */
@Slf4j
@Singleton
public class TileSubmissions
{
	private final BingoApiClient api;
	private final Gson gson;

	@Inject
	TileSubmissions(BingoApiClient api, Gson gson)
	{
		this.api = api;
		this.gson = gson;
	}

	/**
	 * POST /api/events/:id/start-proof — file this account's STARTING SHOT (site lib/startProof).
	 *
	 * The image has already been through {@link #uploadImage}; this hands over its URL plus the
	 * keyword we baked into the banner, which the server recomputes. A capture from an authenticated
	 * plugin whose keyword matches is accepted outright; anything else waits for staff.
	 *
	 * <p>The world position and session start ride along so the server can record what the two
	 * client-side checks (StartProofRules) saw — it re-measures both rather than trusting our
	 * verdict, and a shot that fails one lands `pending` instead of accepted. Both are optional:
	 * pass null when we can't answer, and the server simply doesn't run that check.
	 */
	public void submitStartProof(int eventId, String imageUrl, String keyword, String capturedAt,
		Integer x, Integer y, String loginAt) throws IOException
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("imageUrl", imageUrl);
		if (keyword != null)
		{
			payload.addProperty("keyword", keyword);
		}
		if (capturedAt != null)
		{
			payload.addProperty("capturedAt", capturedAt);
		}
		if (x != null && y != null)
		{
			payload.addProperty("x", x);
			payload.addProperty("y", y);
		}
		if (loginAt != null)
		{
			payload.addProperty("loginAt", loginAt);
		}

		RequestBody body = RequestBody.create(BingoApiClient.JSON, payload.toString());
		Request request = api.authedRequest(api.clanUrl("/api/events/" + eventId + "/start-proof"))
			.post(body)
			.build();

		api.postExpectingOk(request, "Starting shot failed", true);
		log.info("Starting shot filed for event {}", eventId);
	}

	public void submitDrop(int eventId, int tileId, int teamId, int amount, String imageUrl, String note, int creditPlayerId, Integer itemId) throws IOException
	{
		submitDrop(eventId, tileId, teamId, amount, imageUrl, note, creditPlayerId, itemId, null);
	}

	/**
	 * As above, plus the shared-kill fingerprint: what this client could see of its company when the
	 * kill happened. The server correlates reports of the SAME kill from it (lib/coopRuns) — it never
	 * decides anything locally, because two clients that can't see each other would both stay quiet
	 * or both submit. Null on everything that isn't a shared-kill tile.
	 */
	public void submitDrop(int eventId, int tileId, int teamId, int amount, String imageUrl, String note,
		int creditPlayerId, Integer itemId, CoopFingerprint coop) throws IOException
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
		if (coop != null)
		{
			if (coop.teammates != null && !coop.teammates.isEmpty())
			{
				JsonArray names = new JsonArray();
				for (String n : coop.teammates)
				{
					names.add(n);
				}
				payload.add("coopGroup", names);
			}
			if (coop.partySize > 1)
			{
				payload.addProperty("coopPartySize", coop.partySize);
			}
		}

		RequestBody body = RequestBody.create(BingoApiClient.JSON, payload.toString());

		Request request = api.authedRequest(api.clanUrl("/api/events/" + eventId + "/submissions"))
			.post(body)
			.build();

		api.postExpectingOk(request, "Submission failed", true);
		log.info("Drop submitted successfully for tile {}", tileId);
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

		RequestBody body = RequestBody.create(BingoApiClient.JSON, payload.toString());

		Request request = api.authedRequest(api.clanUrl("/api/events/" + eventId + "/submissions"))
			.post(body)
			.build();

		api.postExpectingOk(request, "Timed submission failed", true);
		log.info("Timed clear submitted successfully for tile {}", tileId);
	}
}
