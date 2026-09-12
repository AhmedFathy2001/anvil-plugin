package com.anvil.api;

import java.time.Instant;
import com.google.gson.JsonParser;
import com.anvil.api.dto.RateLimitedException;
import com.anvil.api.dto.ClogPushResult;
import com.anvil.clog.ClogPage;
import com.anvil.ui.AnvilMoments;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * The account's own record: its collection log, its personal bests, and the moments worth showing.
 *
 * <p>All of it is the player's OWN data going to the player's OWN clan site — the pattern the plugin
 * hub accepts, since nothing here reads or reports anybody else. Every part is opt-out in config,
 * and nothing is read at all while the toggles are off.</p>
 */
@Slf4j
@Singleton
public class ProfileSubmissions
{
	private final BingoApiClient api;
	private final Gson gson;

	@Inject
	ProfileSubmissions(BingoApiClient api, Gson gson)
	{
		this.api = api;
		this.gson = gson;
	}

	public ClogPushResult submitClogItems(Map<Integer, Integer> items) throws IOException
	{
		if (items == null || items.isEmpty())
		{
			return new ClogPushResult();
		}
		JsonArray arr = new JsonArray();
		for (Map.Entry<Integer, Integer> e : items.entrySet())
		{
			JsonObject item = new JsonObject();
			item.addProperty("id", e.getKey());
			item.addProperty("q", e.getValue());
			arr.add(item);
		}
		JsonObject payload = new JsonObject();
		payload.add("items", arr);

		RequestBody body = RequestBody.create(BingoApiClient.JSON, payload.toString());
		Request request = api.authedRequest(api.clanUrl("/api/plugin/clog"))
			.post(body)
			.build();

		try (Response response = api.newCall(request).execute())
		{
			if (!response.isSuccessful())
			{
				String responseBody = response.body() != null ? response.body().string() : "";
				// The site's own sentence, not its BingoApiClient.JSON. A player reading chat should be told what to
				// do, and "{"error":...,"retryAfterMs":49445}" tells them to file a bug.
				if (response.code() == 429)
				{
					throw new RateLimitedException(ApiErrors.friendlyError(429, responseBody), ApiErrors.retryAfterFrom(responseBody));
				}
				log.debug("Collection log push refused: HTTP {} — {}", response.code(), responseBody);
				throw ApiErrors.friendlyFailure(response.code(), responseBody);
			}
			ClogPushResult result = new ClogPushResult();
			try
			{
				JsonObject json = new JsonParser().parse(response.body().string()).getAsJsonObject();
				result.added = json.has("added") ? json.get("added").getAsInt() : 0;
				result.removed = json.has("removed") ? json.get("removed").getAsInt() : 0;
				result.updated = json.has("updated") ? json.get("updated").getAsInt() : 0;
			}
			catch (Exception ignored)
			{
				// An older site doesn't report counts. The push still worked; we just can't say what
				// it did, so an automatic sync stays quiet rather than guessing.
			}
			return result;
		}
	}
	/**
	 * POST /api/plugin/clog — collection-log pages the player has actually opened.
	 *
	 * <p>Sends ONLY obtained items, and only pages whose contents changed since the last successful
	 * push ({@link ClogSync} owns that decision). The site already ships the full item catalogue, so
	 * the missing half is derivable — transmitting it would double every payload to say "still
	 * nothing here".
	 *
	 * <p>Idempotent: the server keys on (member, page) and replaces, so a retry or a client restart
	 * mid-sync costs nothing. Profile data only — never scoring.
	 */
	public void submitClogPages(List<ClogPage> pages, int syncedPages) throws IOException
	{
		if (pages == null || pages.isEmpty())
		{
			return;
		}
		JsonArray out = new JsonArray();
		for (ClogPage page : pages)
		{
			if (page == null || page.name == null || page.name.isEmpty())
			{
				continue;
			}
			JsonArray items = new JsonArray();
			for (int i = 0; i < page.itemIds.length; i++)
			{
				JsonObject item = new JsonObject();
				item.addProperty("id", page.itemIds[i]);
				item.addProperty("q", page.quantities[i]);
				items.add(item);
			}
			JsonObject p = new JsonObject();
			p.addProperty("name", page.name);
			p.addProperty("obtained", page.obtained);
			p.addProperty("total", page.total);
			p.add("items", items);
			if (!page.counts.isEmpty())
			{
				JsonObject counts = new JsonObject();
				for (Map.Entry<String, Integer> e : page.counts.entrySet())
				{
					counts.addProperty(e.getKey(), e.getValue());
				}
				p.add("counts", counts);
			}
			out.add(p);
		}
		if (out.size() == 0)
		{
			return;
		}

		JsonObject payload = new JsonObject();
		payload.add("pages", out);
		// How much of the log this account has opened at all — drives the site's "68% synced" note.
		payload.addProperty("syncedPages", syncedPages);

		RequestBody body = RequestBody.create(BingoApiClient.JSON, payload.toString());
		Request request = api.authedRequest(api.clanUrl("/api/plugin/clog"))
			.post(body)
			.build();

		api.postExpectingOk(request, "Collection log push failed", true);
		log.debug("Collection log pushed: {} page(s), {} synced", out.size(), syncedPages);
	}

	/**
	 * POST /api/plugin/pb — the account's best times, in centiseconds.
	 *
	 * <p>Centiseconds because the game separates runs by hundredths; whole seconds would tie times
	 * the game itself doesn't. The server keeps the FASTEST of stored and pushed, so a retry, a
	 * stale client or an out-of-order request can never raise somebody's record.
	 */
	public void submitPersonalBests(Map<String, Integer> bests) throws IOException
	{
		if (bests == null || bests.isEmpty())
		{
			return;
		}
		JsonArray out = new JsonArray();
		for (Map.Entry<String, Integer> e : bests.entrySet())
		{
			if (e.getKey() == null || e.getKey().isEmpty() || e.getValue() == null || e.getValue() <= 0)
			{
				continue;
			}
			JsonObject b = new JsonObject();
			b.addProperty("activity", e.getKey());
			b.addProperty("centis", e.getValue());
			out.add(b);
		}
		if (out.size() == 0)
		{
			return;
		}

		JsonObject payload = new JsonObject();
		payload.add("bests", out);

		RequestBody body = RequestBody.create(BingoApiClient.JSON, payload.toString());
		Request request = api.authedRequest(api.clanUrl("/api/plugin/pb"))
			.post(body)
			.build();

		api.postExpectingOk(request, "Personal best push failed", true);
		log.debug("Personal bests pushed: {}", out.size());
	}

	/**
	 * POST /api/plugin/moments — the clan's highlight feed: pets, uniques, big hauls and deaths.
	 *
	 * <p>Reports what the client SAW; the site decides what it meant. It knows which competition
	 * week or board is running, what counts as a unique, and which pets belong to which skill — so
	 * most of what goes up here is discarded there, on purpose, and a clan changing its mind about
	 * any of it costs no plugin release.
	 *
	 * <p>Idempotent: every entry carries a key derived from what happened, so the two loot events
	 * and three chat lines one occurrence fires — and a retry after a timeout — collapse into one
	 * row. A failure therefore just retries with the queue intact.
	 *
	 * <p>Never scoring: nothing here completes a tile or moves a standing.
	 */
	public void submitMoments(List<AnvilMoments.Moment> batch) throws IOException
	{
		if (batch == null || batch.isEmpty())
		{
			return;
		}
		JsonArray out = new JsonArray();
		for (AnvilMoments.Moment m : batch)
		{
			if (m == null || m.kind == null || m.key == null)
			{
				continue;
			}
			JsonObject o = new JsonObject();
			o.addProperty("kind", m.kind);
			o.addProperty("key", m.key);
			o.addProperty("at", Instant.ofEpochMilli(m.at).toString());
			o.addProperty("quantity", Math.max(1, m.quantity));
			// Everything below is best-effort — a skilling pet has no source, no KC and no price, and
			// inventing any of them would be worse than a shorter line on the feed.
			if (m.itemId != null)
			{
				o.addProperty("itemId", m.itemId);
			}
			if (m.itemName != null && !m.itemName.isEmpty())
			{
				o.addProperty("itemName", m.itemName);
			}
			if (m.valueGp != null && m.valueGp > 0)
			{
				o.addProperty("valueGp", m.valueGp);
			}
			if (m.source != null && !m.source.isEmpty())
			{
				o.addProperty("source", m.source);
			}
			if (m.taskName != null && !m.taskName.isEmpty())
			{
				o.addProperty("taskName", m.taskName);
			}
			if (m.tier != null && !m.tier.isEmpty())
			{
				o.addProperty("tier", m.tier);
			}
			if (m.sourceKind != null && !m.sourceKind.isEmpty())
			{
				o.addProperty("sourceKind", m.sourceKind);
			}
			if (m.kc != null && m.kc > 0)
			{
				o.addProperty("kc", m.kc);
			}
			out.add(o);
		}
		if (out.size() == 0)
		{
			return;
		}

		JsonObject payload = new JsonObject();
		payload.add("moments", out);

		RequestBody body = RequestBody.create(BingoApiClient.JSON, payload.toString());
		Request request = api.authedRequest(api.clanUrl("/api/plugin/moments"))
			.post(body)
			.build();

		api.postExpectingOk(request, "Moment push", false);
		log.debug("Moments pushed: {}", out.size());
	}
}
