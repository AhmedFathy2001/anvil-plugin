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
 * The plugin-side view of {@code GET /api/plugin/federation/state} — the ONE federation read the plugin
 * makes on the <b>site-relay</b> (auto) path ({@code FEDERATION_WIRE.md} §10.2): the home site does broker
 * + inter-site work server-to-server and hands the plugin ready per-clan boards, so the plugin never dials a
 * broker or another clan's site ({@link #verificationUrl} = a self-host's Discord identity proof in the
 * broker's page, §10.3). Immutable/RuneLite-free/unit-testable; {@link #parse} never throws — a bad body yields a {@link #disabled()} sentinel.
 */
public final class FederationState
{
	public final boolean enabled;
	public final boolean connected;
	/** Established federation identity (broker device login done), even with zero remote clans; durable across
	 * reloads. Drives "Disconnect" (a signed-in member isn't re-offered "Connect"); implied by {@link #connected}. */
	public final boolean signedIn;
	public final boolean needsLogin;
	/** Broker device-login page for a self-host home (§10.3), or {@code null}. */
	public final String verificationUrl;
	/** One row per federated clan, ready to render. Never {@code null}. */
	public final List<ConnectionView> clans;

	public FederationState(boolean enabled, boolean connected, boolean signedIn, boolean needsLogin,
		String verificationUrl, List<ConnectionView> clans)
	{
		this.enabled = enabled;
		this.connected = connected;
		this.signedIn = signedIn || connected; // connected always implies signed in
		this.needsLogin = needsLogin;
		this.verificationUrl = verificationUrl == null || verificationUrl.isEmpty() ? null : verificationUrl;
		this.clans = clans == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(clans));
	}

	/** Inert sentinel (federation off, nothing connected, no clans), used when {@code /state} is absent/
	 * unreachable/unparseable or the plugin isn't set up: the sidebar falls back to today's single-home render. */
	public static FederationState disabled()
	{
		return new FederationState(false, false, false, false, null, Collections.emptyList());
	}

	/** "Connect" is offered when federation is on but not yet signed in. Keyed on {@link #signedIn} not
	 * {@link #connected} — a member federated into no other clan is still signed in (gets "Disconnect"). */
	public boolean needsConnect()
	{
		return enabled && !signedIn;
	}

	// ---- Defensive parser limits (§9 payload DoS + §2 length/shape caps) --------------------------
	// All federated input is untrusted (a "trusted" home may relay self-host data): bound size+nesting BEFORE
	// Gson (a JSON bomb exhausts the parser / blows the stack), clamp strings, cap arrays — a hostile /state can only hide federation.

	/** Hard ceiling on the {@code /state} text we will parse (matches the client's byte cap). */
	static final int MAX_JSON_CHARS = 512 * 1024;
	/** Reject JSON nested deeper than this. The real shape is ~6 levels; 32 is a generous JSON-bomb guard. */
	static final int MAX_JSON_DEPTH = 32;
	static final int MAX_STRING = 256;
	/** Array caps — a hostile home can't make the sidebar allocate/render an unbounded list. */
	static final int MAX_CLANS = 64;
	static final int MAX_NEAREST = 64;
	static final int MAX_ACTIVITY = 128;
	static final int MAX_ACTIVE = 64;
	static final int MAX_WORKERS = 32;

	/** Parse a {@code /api/plugin/federation/state} body. Never throws; a null/garbage/oversized/over-nested
	 * body (§9) or per-field surprise degrades to {@link #disabled()} (or as much as parsed) — bad responses
	 * hide federation, never crash the sidebar. */
	public static FederationState parse(Gson gson, String json)
	{
		if (json == null || json.trim().isEmpty())
		{
			return disabled();
		}
		// §9 parser DoS: bound total size and nesting up front — a JSON bomb must never reach Gson.
		if (json.length() > MAX_JSON_CHARS || !withinDepthLimit(json, MAX_JSON_DEPTH))
		{
			return disabled();
		}
		try
		{
			JsonObject o = new JsonParser().parse(json).getAsJsonObject();
			boolean enabled = boolAt(o, "enabled");
			boolean connected = boolAt(o, "connected");
			boolean signedIn = boolAt(o, "signedIn");
			boolean needsLogin = boolAt(o, "needsLogin");
			String verificationUrl = strAt(o, "verificationUrl");
			List<ConnectionView> clans = new ArrayList<>();
			JsonElement clansEl = o.get("clans");
			if (clansEl != null && clansEl.isJsonArray())
			{
				for (JsonElement el : clansEl.getAsJsonArray())
				{
					if (clans.size() >= MAX_CLANS)
					{
						break;
					}
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
			return new FederationState(enabled, connected, signedIn, needsLogin, verificationUrl, clans);
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
					if (nearest.size() >= MAX_NEAREST)
					{
						break;
					}
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
			error.isEmpty() ? null : error, tilesComplete, tilesTotal, nearest,
			AnvilActivityLog.aggregateForDisplay(activity), active);
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
			if (out.size() >= MAX_ACTIVITY)
			{
				break;
			}
			if (e == null || !e.isJsonObject())
			{
				continue;
			}
			JsonObject a = e.getAsJsonObject();
			// Build via constructor + Kind.fromWire; a direct Gson populate bypasses field normalization + enum casing.
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
			if (out.size() >= MAX_ACTIVE)
			{
				break;
			}
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
					if (workers.size() >= MAX_WORKERS)
					{
						break;
					}
					if (wn != null && wn.isJsonPrimitive())
					{
						String name = clamp(wn.getAsString());
						if (!name.isEmpty())
						{
							workers.add(name);
						}
					}
				}
			}
			boolean self = boolAt(a, "self");
			// Row only reads label/current/goal/tileId; Type is render-irrelevant, so STAT is a placeholder.
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
		return el != null && el.isJsonPrimitive() ? clamp(el.getAsString()) : "";
	}

	/** Clamp a federated string to {@link #MAX_STRING} chars (§2 length caps); null → "". */
	private static String clamp(String s)
	{
		if (s == null)
		{
			return "";
		}
		return s.length() <= MAX_STRING ? s : s.substring(0, MAX_STRING);
	}

	/** §9 JSON-bomb guard: one O(n) pass over the raw text, run <b>before</b> Gson, rejecting the moment
	 * nesting exceeds {@code maxDepth} so a deep body can't overflow the recursive parser. Skips string literals. */
	static boolean withinDepthLimit(String json, int maxDepth)
	{
		int depth = 0;
		boolean inString = false;
		boolean escaped = false;
		for (int i = 0; i < json.length(); i++)
		{
			char ch = json.charAt(i);
			if (inString)
			{
				if (escaped)
				{
					escaped = false;
				}
				else if (ch == '\\')
				{
					escaped = true;
				}
				else if (ch == '"')
				{
					inString = false;
				}
				continue;
			}
			if (ch == '"')
			{
				inString = true;
			}
			else if (ch == '{' || ch == '[')
			{
				if (++depth > maxDepth)
				{
					return false;
				}
			}
			else if (ch == '}' || ch == ']')
			{
				if (depth > 0)
				{
					depth--;
				}
			}
		}
		return true;
	}

	/** Like {@link #strAt} but returns {@code null} (not "") for an absent/blank value — for {@code player}. */
	private static String nullableStr(JsonObject o, String key)
	{
		String s = strAt(o, key);
		return s.isEmpty() ? null : s;
	}
}
