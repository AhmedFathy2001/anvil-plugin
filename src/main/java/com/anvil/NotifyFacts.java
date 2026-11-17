package com.anvil;

import com.google.gson.*;

/**
 * What a clan notification says happened, for the site to turn into a Discord post.
 *
 * <p>The plugin decides WHETHER to post (toggles, channel flags, tier floors, dedup, rarity) and
 * reports what only the client knows; the site's lib/pluginEmbeds.ts owns how it reads (titles,
 * colours, links, wording, number formatting). Sent as {@code {channel, kind, facts}} to
 * /api/plugin/notify. The site has to advertise {@code site-embeds} BEFORE a plugin sending these
 * ships — there is no local fallback. Unset values are left out. Kinds and their facts:
 *
 * <ul>
 * <li>death: message, source, sourceKind (the site validates a {@code fought} source as a boss)</li>
 * <li>drop: items[{id,name,qty,value}] (several = one combined haul), source, sourceKind, kc,
 *     dropRate, guaranteed, troll, notable ("loot"|"unlock"), earned, spoon, note</li>
 * <li>pet: name, itemId, duplicate, source, skill, kc, dropRate</li>
 * <li>clogSlot: item, itemId, slots, slotsMax, source, kc</li>
 * <li>caTask: task, tier ("Easy".."Grandmaster"), totalPoints, nextThreshold</li>
 * <li>caTier: tier — diary: area, tier — quest: quest, difficulty ("Novice".."Grandmaster")</li>
 * <li>level: skill, level, at99, skills (level99 without level, to a site lacking level-rules) —
 *     totalLevel: total, milestone, maxed, current, max</li>
 * <li>line: k (the site's watch kind), line — ge: item, itemId, qty, value, sold — trade / storage:
 *     items (qty below 0 = gave or deposited) — ext: source, text</li>
 * </ul>
 * Every kind also carries {@code rsn} when known.
 */
final class NotifyFacts {
	final String kind;
	final JsonObject facts = new JsonObject();

	NotifyFacts(String kind, String rsn) {
		this.kind = kind;
		put("rsn", rsn);
	}

	NotifyFacts put(String key, String value) {
		if (value != null && !value.isEmpty()) facts.addProperty(key, value);
		return this;
	}

	NotifyFacts put(String key, Number value) {
		if (value != null) facts.addProperty(key, value);
		return this;
	}

	NotifyFacts flag(String key, boolean value) {
		if (value) facts.addProperty(key, true);
		return this;
	}

	/** Adds one entry to the drop's {@code items}. */
	NotifyFacts item(int id, String name, int qty, long value) {
		var items = facts.getAsJsonArray("items");
		if (items == null) {
			items = new JsonArray();
			facts.add("items", items);
		}
		var it = new JsonObject();
		it.addProperty("id", id);
		it.addProperty("name", name);
		it.addProperty("qty", qty);
		it.addProperty("value", value);
		items.add(it);
		return this;
	}

	/**
	 * Matches {@code s} against a site watch, ignoring case: {@code *} is any text, {@code #} a number
	 * (digits and commas). Returns the last number matched (0 if none), or -1. One backtrack point, so
	 * the cost is at most pattern x line.
	 */
	static long glob(String p, String s) {
		int i = 0, j = 0, si = -1, sj = 0;
		long n = 0;
		while (j < s.length()) {
			char c = i < p.length() ? p.charAt(i) : 0;
			if (c == '#' && Character.isDigit(s.charAt(j))) {
				long v = 0;
				for (; j < s.length() && (Character.isDigit(s.charAt(j)) || s.charAt(j) == ','); j++) {
					if (s.charAt(j) != ',') v = Math.min(v * 10 + s.charAt(j) - '0', 1L << 40);
				}
				n = v;
				i++;
			} else if (c == '*') {
				si = ++i;
				sj = j;
			} else if (c != 0 && c != '#' && Character.toLowerCase(c) == Character.toLowerCase(s.charAt(j))) {
				i++;
				j++;
			} else if (si >= 0) {
				i = si;
				j = ++sj;
			} else return -1;
		}
		while (i < p.length() && p.charAt(i) == '*') i++;
		return i == p.length() ? n : -1;
	}
}
