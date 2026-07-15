package com.anvil;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The plugin-side view of {@code GET /api/plugin/federation/state} — the ONE federation read the
 * plugin makes on the <b>site-relay</b> (auto) path (see {@code FEDERATION_WIRE.md} §10.2). The home
 * site does all broker + inter-site work server-to-server and hands the plugin a ready, per-clan board
 * summary; the plugin never connects to a broker or to another clan's site on this path.
 *
 * <p>Shape:</p>
 * <pre>
 * { "enabled": true, "connected": true, "needsLogin": false, "verificationUrl": null,
 *   "clans": [ { "id": "&lt;uuid&gt;", "name": "The Anvil Clan", "eventName": "Summer Bingo",
 *               "board": { "tilesComplete": 7, "tilesTotal": 25,
 *                          "nearest": [ { "name": "…", "current": 4, "target": 5, "complete": false } ] },
 *               "activity": [ { "id":"s1","ts":"…","player":"You","tileId":102,"tileLabel":"…",
 *                               "kind":"progress","amount":2,"self":true } ],
 *               "active":   [ { "tileId":102,"label":"500 Zulrah KC","current":420,"goal":500,
 *                               "workers":["You"],"self":true } ] } ] }
 * </pre>
 *
 * <ul>
 *   <li>{@link #enabled} — the clan admin turned federation on for this instance.</li>
 *   <li>{@link #connected} — the home site has the member's federation session in hand (hosted =
 *       zero-click, so this is normally already {@code true}).</li>
 *   <li>{@link #needsLogin}/{@link #verificationUrl} — a self-host home that needs the member to prove
 *       identity via Discord in the broker's own browser page (§10.3); the plugin opens the URL and
 *       polls this endpoint back to {@code connected}.</li>
 *   <li>{@link #clans} — one {@link ConnectionView} per federated clan, already shaped for the sidebar
 *       (same board-summary / working-on / activity / nearest sections as the single-home render).</li>
 * </ul>
 *
 * <p>Deliberately RuneLite-free and immutable, in the value-object style of {@link ConnectionView} /
 * {@link FederationHome}, so it and its parser are fully unit-testable. {@link #parse} never throws — a
 * malformed body yields a {@link #disabled()} sentinel so a bad response can never break the sidebar.</p>
 */
public final class FederationState
{
	public final boolean enabled;
	public final boolean connected;
	public final boolean needsLogin;
	/** Broker device-login page for a self-host home (§10.3), or {@code null}. */
	public final String verificationUrl;
	/** One row per federated clan, ready to render. Never {@code null}. */
	public final List<ConnectionView> clans;

	public FederationState(boolean enabled, boolean connected, boolean needsLogin, String verificationUrl,
		List<ConnectionView> clans)
	{
		this.enabled = enabled;
		this.connected = connected;
		this.needsLogin = needsLogin;
		this.verificationUrl = verificationUrl == null || verificationUrl.isEmpty() ? null : verificationUrl;
		this.clans = clans == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(clans));
	}

	/**
	 * The inert sentinel — federation off, nothing connected, no clans. Used whenever {@code /state} is
	 * absent (older server), unreachable, unparseable, or the plugin isn't set up: the sidebar then falls
	 * back to its single-home render, so the default path is byte-for-byte today's behaviour.
	 */
	public static FederationState disabled()
	{
		return new FederationState(false, false, false, null, Collections.emptyList());
	}

	/** True when a "Connect" affordance should be offered (federation on, but the home isn't connected yet). */
	public boolean needsConnect()
	{
		return enabled && !connected;
	}

	/**
	 * Parse a {@code /api/plugin/federation/state} body. Never throws; a null/blank/garbage body or any
	 * per-field surprise degrades to {@link #disabled()} (or as much as parsed cleanly), so a bad response
	 * can only ever hide federation, never crash the sidebar.
	 */
	public static FederationState parse(Gson gson, String json)
	{
		if (json == null || json.trim().isEmpty())
		{
			return disabled();
		}
		try
		{
			JsonObject o = new JsonParser().parse(json).getAsJsonObject();
			boolean enabled = boolAt(o, "enabled");
			boolean connected = boolAt(o, "connected");
			boolean needsLogin = boolAt(o, "needsLogin");
			String verificationUrl = strAt(o, "verificationUrl");
			List<ConnectionView> clans = new ArrayList<>();
			JsonElement clansEl = o.get("clans");
			if (clansEl != null && clansEl.isJsonArray())
			{
				for (JsonElement el : clansEl.getAsJsonArray())
				{
					if (el != null && el.isJsonObject())
					{
						ConnectionView view = parseClan(el.getAsJsonObject());
						if (view != null)
						{
							clans.add(view);
						}
					}
				}
			}
			return new FederationState(enabled, connected, needsLogin, verificationUrl, clans);
		}
		catch (RuntimeException e)
		{
			return disabled();
		}
	}

	// ---- Per-clan mapping (JSON → the sidebar's ConnectionView) -----------------------------------

	private static ConnectionView parseClan(JsonObject c)
	{
		String id = strAt(c, "id");
		String name = strAt(c, "name");
		if (id.isEmpty() && name.isEmpty())
		{
			return null; // a row with no identity at all — skip it rather than render "(unnamed clan)"
		}
		String eventName = strAt(c, "eventName");
		String error = strAt(c, "error");

		int tilesComplete = 0;
		int tilesTotal = 0;
		List<ConnectionView.TileProgressView> nearest = new ArrayList<>();
		JsonElement boardEl = c.get("board");
		if (boardEl != null && boardEl.isJsonObject())
		{
			JsonObject board = boardEl.getAsJsonObject();
			tilesComplete = intAt(board, "tilesComplete");
			tilesTotal = intAt(board, "tilesTotal");
			JsonElement nearestEl = board.get("nearest");
			if (nearestEl != null && nearestEl.isJsonArray())
			{
				for (JsonElement el : nearestEl.getAsJsonArray())
				{
					if (el != null && el.isJsonObject())
					{
						JsonObject t = el.getAsJsonObject();
						nearest.add(new ConnectionView.TileProgressView(
							strAt(t, "name"), intAt(t, "current"), intAt(t, "target"), boolAt(t, "complete")));
					}
				}
			}
		}

		List<ActivityEntry> activity = parseActivity(c.get("activity"));
		List<ConnectionView.ActiveTask> active = parseActive(c.get("active"));

		return new ConnectionView(id, name, eventName.isEmpty() ? null : eventName,
			error.isEmpty() ? null : error, tilesComplete, tilesTotal, nearest, activity, active);
	}

	private static List<ActivityEntry> parseActivity(JsonElement el)
	{
		List<ActivityEntry> out = new ArrayList<>();
		if (el == null || !el.isJsonArray())
		{
			return out;
		}
		for (JsonElement e : el.getAsJsonArray())
		{
			if (e == null || !e.isJsonObject())
			{
				continue;
			}
			JsonObject a = e.getAsJsonObject();
			// Build through the constructor + Kind.fromWire — never let Gson populate ActivityEntry
			// directly (it bypasses the field normalization and enum casing; see ActivityEntry docs).
			out.add(new ActivityEntry(strAt(a, "id"), strAt(a, "ts"), nullableStr(a, "player"),
				intAt(a, "tileId"), strAt(a, "tileLabel"), ActivityEntry.Kind.fromWire(strAt(a, "kind")),
				intAt(a, "amount"), boolAt(a, "self")));
		}
		return out;
	}

	private static List<ConnectionView.ActiveTask> parseActive(JsonElement el)
	{
		List<ConnectionView.ActiveTask> out = new ArrayList<>();
		if (el == null || !el.isJsonArray())
		{
			return out;
		}
		for (JsonElement e : el.getAsJsonArray())
		{
			if (e == null || !e.isJsonObject())
			{
				continue;
			}
			JsonObject a = e.getAsJsonObject();
			List<String> workers = new ArrayList<>();
			JsonElement w = a.get("workers");
			if (w != null && w.isJsonArray())
			{
				for (JsonElement wn : w.getAsJsonArray())
				{
					if (wn != null && wn.isJsonPrimitive())
					{
						String name = wn.getAsString();
						if (name != null && !name.isEmpty())
						{
							workers.add(name);
						}
					}
				}
			}
			boolean self = boolAt(a, "self");
			// The sidebar row only reads label / current / goal / tileId; Type is irrelevant to the render,
			// so STAT is a fine placeholder (the server already decided who's "active" — no matching here).
			ClogTaskModel.TaskRow tile = new ClogTaskModel.TaskRow(
				intAt(a, "tileId"), strAt(a, "label"), ClogTaskModel.Type.STAT,
				intAt(a, "current"), intAt(a, "goal"), -1);
			out.add(new ConnectionView.ActiveTask(tile, workers, self));
		}
		return out;
	}

	// ---- JSON helpers (tolerant; a missing/typed-wrong field is its zero value) -------------------

	private static boolean boolAt(JsonObject o, String key)
	{
		try
		{
			JsonElement el = o.get(key);
			return el != null && el.isJsonPrimitive() && el.getAsBoolean();
		}
		catch (RuntimeException e)
		{
			return false;
		}
	}

	private static int intAt(JsonObject o, String key)
	{
		try
		{
			JsonElement el = o.get(key);
			return el != null && el.isJsonPrimitive() ? el.getAsInt() : 0;
		}
		catch (RuntimeException e)
		{
			return 0;
		}
	}

	private static String strAt(JsonObject o, String key)
	{
		JsonElement el = o.get(key);
		return el != null && el.isJsonPrimitive() ? el.getAsString() : "";
	}

	/** Like {@link #strAt} but returns {@code null} (not "") for an absent/blank value — for {@code player}. */
	private static String nullableStr(JsonObject o, String key)
	{
		String s = strAt(o, key);
		return s.isEmpty() ? null : s;
	}
}
