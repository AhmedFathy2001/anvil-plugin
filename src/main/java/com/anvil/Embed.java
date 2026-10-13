package com.anvil;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * A Discord embed, written in call order.
 *
 * <p>Each call appends its key to the JSON object, so the payload's key order is the order the
 * calls are made in — callers that care about the bytes (every one of them: the site forwards the
 * embed as-is) build in the order the post has always had. The fields array is opened by the first
 * field, or explicitly with {@link #fields()} where an empty array still has to be sent.
 */
final class Embed
{
	static final String WIKI = "https://oldschool.runescape.wiki/w/";

	final JsonObject json = new JsonObject();
	private JsonArray fields;

	/** The poster's name across the top; omitted when there isn't one. */
	Embed author(String name)
	{
		if (name != null && !name.isEmpty())
		{
			json.add("author", obj("name", name));
		}
		return this;
	}

	Embed title(String title)
	{
		json.addProperty("title", title);
		return this;
	}

	Embed desc(String description)
	{
		json.addProperty("description", description);
		return this;
	}

	Embed color(int color)
	{
		json.addProperty("color", color);
		return this;
	}

	Embed url(String url)
	{
		json.addProperty("url", url);
		return this;
	}

	/** Link to a wiki page (the OSRS wiki uses underscores for spaces). */
	Embed wiki(String page)
	{
		return url(WIKI + page.replace(' ', '_'));
	}

	Embed thumb(String url)
	{
		json.add("thumbnail", obj("url", url));
		return this;
	}

	/**
	 * RuneLite's static export of the game cache — the exact sprite the client renders, on a public
	 * CDN Discord can fetch. Mirrors the site's lib/tileIcons.itemIconUrl.
	 */
	static String iconUrl(int itemId)
	{
		return "https://static.runelite.net/cache/item/icon/" + itemId + ".png";
	}

	/** The item's own sprite as the thumbnail; nothing for an unknown id. */
	Embed icon(Integer itemId)
	{
		return itemId != null && itemId > 0 ? thumb(iconUrl(itemId)) : this;
	}

	/** The screenshot attached under {@code shotName}. */
	Embed image(String shotName)
	{
		json.add("image", obj("url", "attachment://" + shotName));
		return this;
	}

	/** Open the fields array here, empty or not. */
	Embed fields()
	{
		fields = new JsonArray();
		json.add("fields", fields);
		return this;
	}

	Embed field(String name, String value, boolean inline)
	{
		if (fields == null)
		{
			fields();
		}
		JsonObject f = obj("name", name);
		f.addProperty("value", value);
		f.addProperty("inline", inline);
		fields.add(f);
		return this;
	}

	/** An inline field whose value is a number or short token — boxed with backticks. */
	Embed stat(String name, String value)
	{
		return field(name, "`" + value.replace("`", "") + "`", true);
	}

	private static JsonObject obj(String key, String value)
	{
		JsonObject o = new JsonObject();
		o.addProperty(key, value);
		return o;
	}
}
