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
		return tierLabel(e);
	}

	private static String tierLabel(Entry e)
	{
		return Boolean.TRUE.equals(e.s) ? "Anvil Patron" : e.g != null && e.g.size() >= 2 ? "Anvil Champion" : "Anvil Supporter";
	}

	/**
	 * Hover inside a line's text (an Anvil line naming someone: "Tile complete: … — by ★Drenvox!"):
	 * the tier when {@code x} (pixels from the text's left edge) falls on a Supporter's name that is
	 * painted in their colours, else null. {@code width} measures raw chat text the way the chat font
	 * draws it (tags and icons included), so only the name itself answers, not the whole line.
	 */
	String tierInLine(String raw, int x, java.util.function.ToIntFunction<String> width)
	{
		if (raw == null || raw.isEmpty() || byName.isEmpty() || x < 0)
		{
			return null;
		}
		// The visible characters, and where each one sits in the raw text.
		StringBuilder plain = new StringBuilder();
		List<Integer> at = new java.util.ArrayList<>();
		for (int i = 0; i < raw.length(); i++)
		{
			char ch = raw.charAt(i);
			if (ch == '<')
			{
				int close = raw.indexOf('>', i);
				if (close < 0)
				{
					break;
				}
				i = close;
				continue;
			}
			plain.append(ch == '\u00A0' ? ' ' : Character.toLowerCase(ch));
			at.add(i);
		}
		String text = plain.toString();
		for (Map.Entry<String, Entry> kv : byName.entrySet())
		{
			Entry e = kv.getValue();
			String name = kv.getKey();
			if (name == null || name.isEmpty())
			{
				continue;
			}
			for (int from = text.indexOf(name); from >= 0; from = text.indexOf(name, from + 1))
			{
				int to = from + name.length();
				boolean whole = (from == 0 || !Character.isLetterOrDigit(text.charAt(from - 1)))
					&& (to == text.length() || !Character.isLetterOrDigit(text.charAt(to)));
				int rawFrom = at.get(from);
				// Painted: a colour tag opens right before it (a plain mention in someone's message isn't flair).
				String before = raw.substring(0, rawFrom);
				if (!whole || !paintedJustBefore(before))
				{
					continue;
				}
				int x0 = width.applyAsInt(before);
				int x1 = width.applyAsInt(raw.substring(0, at.get(to - 1) + 1));
				if (x >= x0 && x < x1)
				{
					return tierLabel(e);
				}
			}
		}
		return null;
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

	/** The tags right before a point include a colour: what was there is painted, not plain text. */
	static boolean paintedJustBefore(String raw)
	{
		int end = raw.length();
		while (end > 0 && raw.charAt(end - 1) == '>')
		{
			int open = raw.lastIndexOf('<', end - 1);
			if (open < 0)
			{
				return false;
			}
			if (raw.startsWith("<col=", open))
			{
				return true;
			}
			end = open;
		}
		return false;
	}
}
