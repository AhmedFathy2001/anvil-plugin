package com.anvil;

import com.google.gson.*;
import java.util.Map;

/**
 * A team view from the live push ({@link PushSocket}) laid over the config this plugin holds. The
 * site builds it once for the whole team with no caller in it, so two things are made ours here:
 * per-member tiles take our own number from {@code members}, and our stat gain lands in
 * {@code yourGained}. (The panel's rows are the site's own view now, so they need nothing.)
 */
final class PushMerge
{
	private PushMerge()
	{
	}

	/**
	 * {@code held} with {@code team}'s fields over it (one level deep, so the event's and panel's
	 * caller-only fields survive), then our own numbers from {@code mine} ({@code c}: progress by
	 * tile, {@code y}: stat gain by tile; null when we have none).
	 */
	static JsonObject merge(JsonObject held, JsonObject team, JsonObject mine)
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
		return out;
	}
}
