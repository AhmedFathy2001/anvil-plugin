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
 * <li>drop: items[{id,name,qty,value}] (several = one combined haul), source, sourceKind, kc,
 *     dropRate, guaranteed, troll, notable ("loot"|"unlock"), earned, spoon, note</li>
 * <li>pet: name, itemId, duplicate, source, skill, kc, dropRate</li>
 * <li>clogSlot: item, itemId, slots, slotsMax, source, kc</li>
 * <li>caTask: task, tier ("Easy".."Grandmaster"), totalPoints, nextThreshold</li>
 * <li>caTier: tier — diary: area, tier — quest: quest, difficulty ("Novice".."Grandmaster")</li>
 * <li>level: skill, level, at99, skills (level99 without level, to a site lacking level-rules) —
 *     totalLevel: total, milestone, maxed, current, max</li>
 * </ul>
 * Every kind also carries {@code rsn} when known.
 */
final class NotifyFacts
{
	final String kind;
	final JsonObject facts = new JsonObject();

	NotifyFacts(String kind, String rsn)
	{
		this.kind = kind;
		put("rsn", rsn);
	}

	NotifyFacts put(String key, String value)
	{
		if (value != null && !value.isEmpty())
		{
			facts.addProperty(key, value);
		}
		return this;
	}

	NotifyFacts put(String key, Number value)
	{
		if (value != null)
		{
			facts.addProperty(key, value);
		}
		return this;
	}

	NotifyFacts flag(String key, boolean value)
	{
		if (value)
		{
			facts.addProperty(key, true);
		}
		return this;
	}

	/** Adds one entry to the drop's {@code items}. */
	NotifyFacts item(int id, String name, int qty, long value)
	{
		JsonArray items = facts.getAsJsonArray("items");
		if (items == null)
		{
			items = new JsonArray();
			facts.add("items", items);
		}
		JsonObject it = new JsonObject();
		it.addProperty("id", id);
		it.addProperty("name", name);
		it.addProperty("qty", qty);
		it.addProperty("value", value);
		items.add(it);
		return this;
	}
}
