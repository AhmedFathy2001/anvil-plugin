package com.anvil;

import com.google.gson.*;
import java.util.Map;

/**
 * A team view from the live push ({@link PushSocket}) laid over the config this plugin holds. The
 * site builds it once for the whole team with no caller in it, so per-member tiles take our own
 * number from {@code members} here. (The panel's rows are the site's own view now.)
 */
final class PushMerge {
	PushMerge() {
	}

	/**
	 * {@code held} with {@code team}'s fields over it (one level deep, so the event's and panel's
	 * caller-only fields survive), then our own numbers from {@code mine} ({@code c}: progress by
	 * tile; null when we have none).
	 */
	static JsonObject merge(JsonObject held, JsonObject team, JsonObject mine) {
		var out = held.deepCopy();
		for (Map.Entry<String, JsonElement> e : team.deepCopy().entrySet()) {
			var cur = out.get(e.getKey());
			if (cur != null && cur.isJsonObject() && e.getValue().isJsonObject()) {
				for (Map.Entry<String, JsonElement> f : e.getValue().getAsJsonObject().entrySet()) cur.getAsJsonObject().add(f.getKey(), f.getValue());
			} else {
				// The push is the addressed board's: another board's rows (tagged) stay as they were.
				if (cur != null && cur.isJsonArray() && e.getValue().isJsonArray()) {
					for (JsonElement r : cur.getAsJsonArray()) if (r.isJsonObject() && r.getAsJsonObject().has("board")) e.getValue().getAsJsonArray().add(r);
				}
				out.add(e.getKey(), e.getValue());
			}
		}
		var c = mine != null && mine.has("c") ? mine.getAsJsonObject("c") : new JsonObject();
		for (Map.Entry<String, JsonElement> e : out.entrySet()) {
			// The tracked lists carry tileId/currentAmount, the count list ({@code tiles}) t/have.
			var counts = e.getKey().equals("tiles");
			if ((counts || e.getKey().startsWith("tracked")) && e.getValue().isJsonArray()) {
				for (JsonElement row : e.getValue().getAsJsonArray()) {
					var r = row.isJsonObject() ? row.getAsJsonObject() : null;
					var id = r == null ? null : r.get(counts ? "t" : "tileId");
					var tile = id == null ? null : id.getAsString();
					if (tile != null && c.has(tile)) r.add(counts ? "have" : "currentAmount", c.get(tile));
				}
			}
		}
		return out;
	}
}
