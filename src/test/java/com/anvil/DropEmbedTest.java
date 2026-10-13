package com.anvil;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * The drop embed is forwarded to Discord as-is, so moving it onto {@link Embed} had to leave the
 * payload byte-for-byte alone — key order included. The reference below is the builder as it was
 * written before (hand-rolled JsonObjects), kept here so any drift shows up as a string diff.
 */
public class DropEmbedTest
{
	private static final int RARE = 0xD4A017;

	@Test
	public void matchesTheHandBuiltEmbed()
	{
		check("Zezima", "💰 Rare drop!", "Zezima received a valuable drop from Vorkath.", "Dragonbone necklace",
			22111, 1, 12_345_678L, 1.0 / 1000, 54, "KC", false);
		// Way under rate, so the luck field is present.
		check("Zezima", "💰 Rare drop!", "d", "Vorkath's head", 21907, 1, 0, 1.0 / 50, 1, "KC", false);
		check(null, "💎 Notable drop!", "A clan member unlocked Fire cape!", "Fire cape", -1, 1, 0, null, null,
			"KC", true);
		check("Lynx Titan", "🎣 Troll drop!", "x", "Dragon spear", 1249, 3, 99_000L, 1.0 / 128, 1_204, "Opened", false);
		check("", "t", "d", "Item", 4151, 2, 5, 1.0 / 3, 0, "", false);
	}

	@Test
	public void emptyFieldsArrayIsStillSent()
	{
		JsonObject o = new Embed().author("a").title("t").desc("d").color(1).wiki("Abyssal whip").fields()
			.icon(4151).image("x.png").json;
		assertEquals("{\"author\":{\"name\":\"a\"},\"title\":\"t\",\"description\":\"d\",\"color\":1,"
			+ "\"url\":\"https://oldschool.runescape.wiki/w/Abyssal_whip\",\"fields\":[],"
			+ "\"thumbnail\":{\"url\":\"https://static.runelite.net/cache/item/icon/4151.png\"},"
			+ "\"image\":{\"url\":\"attachment://x.png\"}}", o.toString());
	}

	private static void check(String rsn, String title, String desc, String itemName, int itemId, int qty,
		long value, Double dropRate, Integer kc, String countLabel, boolean guaranteed)
	{
		assertEquals(
			legacy(rsn, title, desc, itemName, itemId, qty, value, dropRate, kc, "anvil-drop.png", countLabel, guaranteed).toString(),
			AnvilPlugin.dropEmbed(rsn, title, desc, itemName, itemId, qty, value, dropRate, kc, countLabel, guaranteed).toString());
	}

	/** The pre-Embed buildDropEmbed, verbatim apart from taking the RSN as a parameter. */
	private static JsonObject legacy(String rsn, String title, String description,
		String itemName, int itemId, int qty, long value, Double dropRate, Integer killCount, String shotName,
		String countLabel, boolean guaranteed)
	{
		JsonObject embed = new JsonObject();
		if (rsn != null && !rsn.isEmpty())
		{
			JsonObject author = new JsonObject();
			author.addProperty("name", rsn);
			embed.add("author", author);
		}
		embed.addProperty("title", title);
		embed.addProperty("description", description);
		embed.addProperty("color", RARE);

		JsonArray fields = new JsonArray();
		fields.add(statField("Item", qty > 1 ? itemName + " ×" + qty : itemName));
		if (value > 0)
		{
			fields.add(statField("Value", String.format("%,d gp", value)));
		}
		if (guaranteed)
		{
			fields.add(statField("Drop rate", "Guaranteed"));
		}
		else if (dropRate != null && dropRate > 0)
		{
			long oneIn = Math.round(1.0 / dropRate);
			fields.add(statField("Drop rate", "1/" + String.format("%,d", oneIn)));
		}
		if (killCount != null && killCount > 0)
		{
			fields.add(statField(countLabel == null || countLabel.isEmpty() ? "KC" : countLabel,
				String.format("%,d", killCount)));
		}
		String luck = guaranteed ? null : DropLuck.luckLabel(dropRate, killCount);
		if (luck != null)
		{
			fields.add(statField("Luck", luck));
		}
		embed.add("fields", fields);
		embed.addProperty("url", "https://oldschool.runescape.wiki/w/" + itemName.replace(' ', '_'));
		if (itemId > 0)
		{
			JsonObject thumb = new JsonObject();
			thumb.addProperty("url", "https://static.runelite.net/cache/item/icon/" + itemId + ".png");
			embed.add("thumbnail", thumb);
		}
		JsonObject image = new JsonObject();
		image.addProperty("url", "attachment://" + shotName);
		embed.add("image", image);
		return embed;
	}

	private static JsonObject statField(String name, String value)
	{
		JsonObject f = new JsonObject();
		f.addProperty("name", name);
		f.addProperty("value", "`" + value.replace("`", "") + "`");
		f.addProperty("inline", true);
		return f;
	}
}
