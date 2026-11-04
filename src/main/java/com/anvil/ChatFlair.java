package com.anvil;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Anvil Supporters' colours on their names in normal game chat (site capability "chat-flair"). The
 * site hands the whole list over (GET /api/plugin/chat-flair) and names are matched here, locally:
 * the plugin never tells the site whose messages it saw.
 */
final class ChatFlair
{
	/** GET /api/plugin/chat-flair. */
	static final class Response
	{
		List<Entry> names;
	}

	/** One character: normalized name, solid colour, optional gradient stops, Patron star. */
	static final class Entry
	{
		String n;
		String c;
		List<String> g;
		Boolean s;
	}

	/** A chat sender as the game writes it: rank/account-type icons, then the plain name. */
	private static final Pattern SENDER = Pattern.compile("^((?:<img=\\d+>)*)([^<>]+)$");

	private volatile Map<String, Entry> byName = Collections.emptyMap();

	/** Replace the list (a 304 hands back the same object, which is already in force). */
	void adopt(Response res)
	{
		if (res == null || res.names == null)
		{
			return;
		}
		Map<String, Entry> next = new HashMap<>();
		for (Entry e : res.names)
		{
			if (e != null && e.n != null && ChatPalette.nameColor(e.c) != null)
			{
				next.put(normalize(e.n), e);
			}
		}
		byName = next;
	}

	boolean isEmpty()
	{
		return byName.isEmpty();
	}

	/** The site's normalizeRsn: lower case, any run of spaces / underscores (and the game's nbsp) as one space. */
	static String normalize(String name)
	{
		return name.replace(' ', ' ').trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_]+", " ");
	}

	/** A sender column's text ("[02:25] <img=1>Name:" with any colouring): the name inside, or null. */
	private static final Pattern SENDER_COLUMN = Pattern.compile("^(?:\\[[^\\]]*\\]\\s*)?(.+?):$");

	/**
	 * What to say on hover over a chat line's SENDER: "Anvil Patron" / "Anvil Champion" / "Anvil
	 * Supporter", or null (not a sender column, or no flair). The star means Patron, a gradient
	 * Champion, a solid colour Supporter.
	 */
	String tierOfSender(String widgetText)
	{
		if (widgetText == null || byName.isEmpty())
		{
			return null;
		}
		Matcher m = SENDER_COLUMN.matcher(widgetText.replaceAll("<[^>]*>", "").trim());
		if (!m.matches())
		{
			return null;
		}
		Entry e = byName.get(normalize(m.group(1)));
		if (e == null)
		{
			return null;
		}
		return Boolean.TRUE.equals(e.s) ? "Anvil Patron" : e.g != null && e.g.size() >= 2 ? "Anvil Champion" : "Anvil Supporter";
	}

	/**
	 * The sender with their flair: icons kept, then the star (when {@code star} is given and they are
	 * a Patron), then the name in their gradient or colour. Null when they have none or the sender
	 * isn't a plain name (already decorated by something else).
	 */
	String decorate(String sender, String star)
	{
		if (sender == null || byName.isEmpty())
		{
			return null;
		}
		Matcher m = SENDER.matcher(sender);
		if (!m.matches())
		{
			return null;
		}
		String name = m.group(2);
		Entry e = byName.get(normalize(name));
		if (e == null)
		{
			return null;
		}
		String painted = e.g != null && e.g.size() >= 2 ? ChatPalette.gradientName(name, e.g) : null;
		if (painted == null)
		{
			painted = "<col=" + ChatPalette.nameColor(e.c) + ">" + name + "</col>";
		}
		boolean starred = Boolean.TRUE.equals(e.s) && star != null;
		return m.group(1) + (starred ? star : "") + painted;
	}
}
