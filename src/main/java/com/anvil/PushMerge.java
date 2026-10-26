package com.anvil;

import com.google.gson.*;
import java.util.Map;

/**
 * A team view from the live push ({@link PushSocket}) laid over the config this plugin holds. The
 * site builds it once for the whole team with no caller in it, so three things are made ours here:
 * per-member tiles take our own number from {@code members}, our stat gain lands in
 * {@code yourGained}, and our name in "Active now" becomes "You", first, as the config route does.
 */
final class PushMerge
{
	static final String YOU = "You";

	private PushMerge()
	{
	}

	/**
	 * {@code held} with {@code team}'s fields over it (one level deep, so the event's and panel's
	 * caller-only fields survive), then our own numbers from {@code mine} ({@code c}: progress by
	 * tile, {@code y}: stat gain by tile; null when we have none).
	 */
	static JsonObject merge(JsonObject held, JsonObject team, JsonObject mine, String myName)
	{
		JsonObject out = held.deepCopy();
		for (Map.Entry<String, JsonElement> e : team.deepCopy().entrySet())
		{
			JsonElement cur = out.get(e.getKey());
			if (cur != null && cur.isJsonObject() && e.getValue().isJsonObject())
			{
				for (Map.Entry<String, JsonElement> f : e.getValue().getAsJsonObject().entrySet())
				{
					cur.getAsJsonObject().add(f.getKey(), f.getValue());
				}
			}
			else
			{
				out.add(e.getKey(), e.getValue());
			}
		}
		JsonObject c = mine != null && mine.has("c") ? mine.getAsJsonObject("c") : new JsonObject();
		JsonObject y = mine != null && mine.has("y") ? mine.getAsJsonObject("y") : new JsonObject();
		for (Map.Entry<String, JsonElement> e : out.entrySet())
		{
			if (e.getKey().startsWith("tracked") && e.getValue().isJsonArray())
			{
				for (JsonElement row : e.getValue().getAsJsonArray())
				{
					JsonObject r = row.isJsonObject() ? row.getAsJsonObject() : null;
					String tile = r != null && r.has("tileId") ? r.get("tileId").getAsString() : null;
					if (tile != null && c.has(tile))
					{
						r.add("currentAmount", c.get(tile));
					}
					if (tile != null && y.has(tile))
					{
						r.add("yourGained", y.get(tile));
					}
				}
			}
		}
		JsonObject panel = out.has("panel") && out.get("panel").isJsonObject() ? out.getAsJsonObject("panel") : null;
		if (panel != null && panel.has("tiles") && panel.get("tiles").isJsonArray())
		{
			for (JsonElement row : panel.getAsJsonArray("tiles"))
			{
				JsonObject r = row.getAsJsonObject();
				String tile = r.get("tileId").getAsString();
				if (c.has(tile))
				{
					long current = c.get(tile).getAsLong();
					r.addProperty("current", current);
					r.addProperty("complete", current >= r.get("goal").getAsLong());
				}
			}
		}
		if (panel != null && panel.has("activeNow") && panel.get("activeNow").isJsonArray() && myName != null)
		{
			for (JsonElement row : panel.getAsJsonArray("activeNow"))
			{
				markYou(row.getAsJsonObject(), myName);
			}
		}
		return out;
	}

	/** Our own name in an "Active now" row, as the config route words it for the caller. */
	private static void markYou(JsonObject row, String myName)
	{
		JsonArray workers = row.has("workers") ? row.getAsJsonArray("workers") : null;
		if (workers == null)
		{
			return;
		}
		JsonArray named = new JsonArray();
		String found = null;
		for (JsonElement w : workers)
		{
			if (found == null && Rsn.same(w.getAsString(), myName))
			{
				found = w.getAsString();
			}
			else
			{
				named.add(w);
			}
		}
		if (found == null)
		{
			return;
		}
		JsonArray ordered = new JsonArray();
		ordered.add(YOU);
		ordered.addAll(named);
		row.add("workers", ordered);
		row.addProperty("self", true);
		JsonObject gains = row.has("gains") ? row.getAsJsonObject("gains") : null;
		if (gains != null && gains.has(found))
		{
			gains.add(YOU, gains.remove(found));
		}
	}
}
