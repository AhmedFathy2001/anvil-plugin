package com.anvil.api;

import java.util.Collections;
import com.anvil.detect.AccountProgress;
import com.google.gson.Gson;
import java.util.List;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * The account as numbers: kill counts, XP, varbit counters, quest points, recap totals.
 *
 * <p>All of it is ABSOLUTE rather than a delta, which is the property that makes the whole scheme
 * safe: a push that is lost, duplicated or delivered out of order cannot corrupt a total. The server
 * keeps {@code max(hiscores, pushed)} and reconciles against the next sweep, so the plugin is a
 * shortcut to a number the hiscores would eventually report anyway — never the source of truth.</p>
 */
@Slf4j
@Singleton
public class StatSubmissions
{
	private final BingoApiClient api;
	private final Gson gson;

	@Inject
	StatSubmissions(BingoApiClient api, Gson gson)
	{
		this.api = api;
		this.gson = gson;
	}

	public void submitStatKc(Map<String, Integer> counts) throws IOException
	{
		submitStats(counts, "stats", "name", "kc", "KC push", "Real-time KC pushed for {} boss(es)");
	}

	/**
	 * The three stat pushes, which are one request in three spellings.
	 *
	 * <p>They all POST to {@code /api/plugin/stats} with ABSOLUTE values and differ only in what the
	 * array is called, what the name field is called, and what the value field is called. They were
	 * three thirty-five-line methods that agreed on everything else, down to the log wording.</p>
	 *
	 * <p>Entries with a null key or a null value are dropped rather than sent — a half-read counter
	 * would be stored by the server as a real one.</p>
	 */
	private void submitStats(Map<String, Integer> values, String arrayKey, String nameKey,
		String valueKey, String failureLabel, String successLog) throws IOException
	{
		if (values == null || values.isEmpty())
		{
			return;
		}
		RequestBody body = RequestBody.create(BingoApiClient.JSON, statsPayload(values, arrayKey, nameKey, valueKey).toString());
		Request request = api.authedRequest(api.clanUrl("/api/plugin/stats"))
			.post(body)
			.build();

		api.postExpectingOk(request, failureLabel, false);
		log.info(successLog, values.size());
	}

	/** The request body. Package-private so a test can hold the wire format to the byte. */
	public static JsonObject statsPayload(Map<String, Integer> values, String arrayKey, String nameKey, String valueKey)
	{
		JsonArray entries = new JsonArray();
		for (Map.Entry<String, Integer> e : values.entrySet())
		{
			if (e.getKey() == null || e.getValue() == null)
			{
				continue;
			}
			JsonObject entry = new JsonObject();
			entry.addProperty(nameKey, e.getKey());
			entry.addProperty(valueKey, e.getValue());
			entries.add(entry);
		}
		JsonObject payload = new JsonObject();
		payload.add(arrayKey, entries);
		return payload;
	}

	/**
	 * POST /api/plugin/stats — real-time skill-XP push (no screenshot). Body is
	 * {@code {"skills":[{"name":"<skill>","xp":<absolute xp>}]}}. Same contract as {@link #submitStatKc}:
	 * event/team/player resolved from the token, ABSOLUTE (idempotent) values, server keeps
	 * max(hiscores, pushed) per skill and the hourly cron reconciles. Completes skill-XP tiles instantly
	 * instead of waiting on the ~1h hiscores lag.
	 */
	public void submitStatXp(Map<String, Integer> xp) throws IOException
	{
		submitStats(xp, "skills", "name", "xp", "Skill XP push", "Real-time XP pushed for {} skill(s)");
	}

	/**
	 * POST /api/plugin/stats — real-time activity push (no screenshot). Body is
	 * {@code {"activities":[{"key":"<site stat key>","value":<absolute count>}]}} for the hiscores
	 * counters that are neither a boss nor a skill: clue completions per tier, Colosseum glory,
	 * collection-log slots.
	 *
	 * <p>Unlike {@link #submitStatKc} and {@link #submitStatXp}, which send the name the game printed
	 * and let the server map it, these go by the site's own key — they're read from named varbits, so
	 * the plugin already knows which counter it holds. Same contract otherwise: ABSOLUTE values, the
	 * server keeps max(hiscores, pushed), and unknown keys are dropped rather than stored.
	 */
	public void submitStatActivities(Map<String, Integer> values) throws IOException
	{
		submitStats(values, "activities", "key", "value", "Activity push",
			"Real-time activity counts pushed for {} key(s)");
	}

	/**
	 * POST /api/plugin/counters — the fun end-of-event recap counters (total deaths, total loot GP and
	 * PvP kills for the active event). Body is {@code {"deaths":<n>,"lootGp":<gp>,"pvpKills":<n>}} with
	 * ABSOLUTE per-event totals.
	 * Idempotent like {@link #submitStatKc}: event/team/player resolved from the token, the server keeps
	 * max(stored, pushed) per counter, so a retry or client restart never double-counts. No screenshot.
	 * Purely cosmetic (superlatives only — never scoring).
	 */
	public void submitEventCounters(int deaths, long lootGp, int pvpKills, int biggestHit, int minutesPlayed, int caTasks) throws IOException
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("deaths", deaths);
		payload.addProperty("lootGp", lootGp);
		payload.addProperty("pvpKills", pvpKills);
		// Newer counters. A site that predates them ignores unknown BingoApiClient.JSON keys, so an updated plugin
		// keeps working against an older instance — the extra awards simply don't appear there.
		payload.addProperty("biggestHit", biggestHit);
		payload.addProperty("minutesPlayed", minutesPlayed);
		payload.addProperty("caTasks", caTasks);

		RequestBody body = RequestBody.create(BingoApiClient.JSON, payload.toString());
		Request request = api.authedRequest(api.clanUrl("/api/plugin/counters"))
			.post(body)
			.build();

		api.postExpectingOk(request, "Counter push", false);
		log.info("Recap counters pushed (deaths={}, lootGp={}, pvpKills={}, biggestHit={}, minutes={}, caTasks={})",
			deaths, lootGp, pvpKills, biggestHit, minutesPlayed, caTasks);
	}

	/**
	 * POST /api/plugin/progress — quest points, combat-achievement points/tier, diary counts.
	 *
	 * <p>Account state the hiscores never publish (site: lib/memberProgress). Only keys whose value
	 * actually moved since the last successful push are sent, so the steady state is no request at
	 * all; the server max-merges what does arrive, which makes a retry free and stops a client that
	 * read a varbit before the game populated it from walking somebody's account backwards.
	 *
	 * <p>Never scoring: nothing here completes a tile or moves a standing.
	 */
	public void submitProgress(Map<String, Integer> progress) throws IOException
	{
		submitProgress(progress, null, null);
	}

	/**
	 * The same push, carrying an item list — every quest with its state, so the site can show which
	 * are left rather than only how many are done. Sent whole and only when it changed, since half a
	 * list is worse than none.
	 */
	public void submitProgress(Map<String, Integer> progress, String itemCategory,
		List<AccountProgress.Item> items) throws IOException
	{
		submitProgress(progress, itemCategory, items, null, 0);
	}

	/**
	 * The same push, carrying the raw combat-achievement varps.
	 *
	 * <p>We send the numbers and the game's own point total; the site decodes which task each bit is
	 * and refuses the lot if the points don't reconcile. Nothing here knows what a combat task is,
	 * which is the point: the catalogue lives where it can be updated without a release.
	 */
	public void submitProgress(Map<String, Integer> progress, String itemCategory,
		List<AccountProgress.Item> items, Map<Integer, Integer> caVarps,
		int caPoints) throws IOException
	{
		boolean hasItems = itemCategory != null && items != null && !items.isEmpty();
		boolean hasVarps = caVarps != null && !caVarps.isEmpty() && caPoints > 0;
		if ((progress == null || progress.isEmpty()) && !hasItems && !hasVarps)
		{
			return;
		}
		if (progress == null)
		{
			progress = Collections.emptyMap();
		}
		JsonArray rows = new JsonArray();
		for (Map.Entry<String, Integer> e : progress.entrySet())
		{
			if (e.getKey() == null || e.getValue() == null)
			{
				continue;
			}
			JsonObject row = new JsonObject();
			row.addProperty("key", e.getKey());
			row.addProperty("value", e.getValue());
			rows.add(row);
		}
		if (rows.size() == 0 && !hasItems && !hasVarps)
		{
			return;
		}

		JsonObject payload = new JsonObject();
		payload.add("progress", rows);
		if (hasItems)
		{
			JsonArray itemRows = new JsonArray();
			for (AccountProgress.Item item : items)
			{
				if (item == null || item.name == null || item.name.isEmpty())
				{
					continue;
				}
				JsonObject row = new JsonObject();
				row.addProperty("id", item.id);
				row.addProperty("name", item.name);
				row.addProperty("state", item.state);
				itemRows.add(row);
			}
			JsonObject set = new JsonObject();
			set.addProperty("category", itemCategory);
			set.add("items", itemRows);
			JsonArray sets = new JsonArray();
			sets.add(set);
			payload.add("items", sets);
		}
		if (hasVarps)
		{
			JsonObject varpObj = new JsonObject();
			for (Map.Entry<Integer, Integer> e : caVarps.entrySet())
			{
				varpObj.addProperty(String.valueOf(e.getKey()), e.getValue());
			}
			payload.add("caVarps", varpObj);
			payload.addProperty("caPoints", caPoints);
		}

		RequestBody body = RequestBody.create(BingoApiClient.JSON, payload.toString());
		Request request = api.authedRequest(api.clanUrl("/api/plugin/progress"))
			.post(body)
			.build();

		// The reply says what the server made of it — including, for combat achievements, whether
		// the bits reconciled against the point total and how many tasks it couldn't name. Logged
		// at INFO because when this feature is quiet the only alternative is guessing which half
		// went wrong, which has cost a day already.
		String responseBody = api.postExpectingOk(request, "Progress push", false);
		log.info("Anvil progress pushed ({} key(s), {} varps): {}", rows.size(),
			hasVarps ? caVarps.size() : 0, responseBody);
	}
}
