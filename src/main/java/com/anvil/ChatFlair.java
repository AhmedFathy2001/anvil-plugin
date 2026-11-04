package com.anvil;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Anvil Supporters' colours on their names in normal game chat (site capability "chat-flair-v"). The
 * site hands the whole list over and names are matched here, locally: the plugin never tells the site
 * whose messages it saw. The list is versioned: socket deltas (ChatFlairSync) move it one version at a
 * time, and a whole download is only for a version it can't reach that way.
 *
 * <p>Held compact for a list of hundreds of thousands: sorted names, each with a char index into a
 * small palette of looks, looked up by binary search. Replaced whole on every change (copy-on-write),
 * so readers on any thread see one consistent list.
 */
final class ChatFlair
{
	/** A look: solid colour, optional gradient stops, Patron star. */
	static final class Entry
	{
		String c;
		List<String> g;
		Boolean s;
	}

	/**
	 * GET chat-flair/v/&lt;v&gt; ({v, styles, names, ix}), and a push delta ({t:"flair", v, from, styles,
	 * names, ix, del} — or full:true, "fetch it").
	 */
	static final class Wire
	{
		int v;
		Integer from;
		Boolean full;
		List<Entry> styles;
		String[] names;
		int[] ix;
		String[] del;
	}

	/** GET chat-flair/v?since=: the current version, and the deltas to it when few. */
	static final class Since
	{
		int v;
		List<Wire> deltas;
	}

	/** One version of the list. */
	private static final class Held
	{
		final int v;
		final String[] names;
		final char[] ix;
		final Entry[] styles;

		Held(int v, String[] names, char[] ix, Entry[] styles)
		{
			this.v = v;
			this.names = names;
			this.ix = ix;
			this.styles = styles;
		}
	}

	/** A sender as the game writes it: rank/account-type icons, then the plain name. */
	private static final Pattern SENDER = Pattern.compile("^((?:<img=\\d+>)*)([^<>]+)$");

	private volatile Held held = new Held(0, new String[0], new char[0], new Entry[0]);

	int version()
	{
		return held.v;
	}

	/** Take a whole list. False when it is unusable (then nothing changes). */
	boolean adopt(Wire w)
	{
		if (w == null || w.v <= 0 || w.names == null || w.ix == null || w.styles == null || w.ix.length != w.names.length)
		{
			return false;
		}
		TreeMap<String, Entry> all = new TreeMap<>();
		put(all, w);
		held = build(w.v, all);
		return true;
	}

	/** Apply a delta onto the version it was made from. False when it doesn't fit (fetch instead). */
	boolean apply(Wire d)
	{
		Held h = held;
		if (d == null || d.from == null || d.from != h.v || h.v <= 0 || Boolean.TRUE.equals(d.full))
		{
			return false;
		}
		TreeMap<String, Entry> set = new TreeMap<>();
		put(set, d);
		Map<String, Boolean> gone = new HashMap<>();
		for (String n : d.del == null ? new String[0] : d.del)
		{
			gone.put(normalize(n), true);
		}
		// Merge the sorted held names with the sorted changes: one pass, no map of everything.
		held = merge(d.v, h, set, gone);
		return true;
	}

	private static void put(Map<String, Entry> into, Wire w)
	{
		if (w.names == null || w.ix == null || w.styles == null)
		{
			return;
		}
		for (int n = 0; n < w.names.length && n < w.ix.length; n++)
		{
			int i = w.ix[n];
			Entry e = i >= 0 && i < w.styles.size() ? w.styles.get(i) : null;
			if (w.names[n] != null && e != null && ChatPalette.nameColor(e.c) != null)
			{
				into.put(normalize(w.names[n]), e);
			}
		}
	}

	private static Held build(int v, TreeMap<String, Entry> all)
	{
		Map<String, Integer> palette = new HashMap<>();
		List<Entry> styles = new java.util.ArrayList<>();
		String[] names = new String[all.size()];
		char[] ix = new char[all.size()];
		int n = 0;
		for (Map.Entry<String, Entry> kv : all.entrySet())
		{
			names[n] = kv.getKey();
			ix[n++] = (char) intern(palette, styles, kv.getValue());
		}
		return new Held(v, names, ix, styles.toArray(new Entry[0]));
	}

	private static Held merge(int v, Held h, TreeMap<String, Entry> set, Map<String, Boolean> gone)
	{
		Map<String, Integer> palette = new HashMap<>();
		List<Entry> styles = new java.util.ArrayList<>();
		for (Entry e : h.styles)
		{
			intern(palette, styles, e);
		}
		String[] names = new String[h.names.length + set.size()];
		char[] ix = new char[names.length];
		int n = 0;
		int i = 0;
		java.util.Iterator<Map.Entry<String, Entry>> it = set.entrySet().iterator();
		Map.Entry<String, Entry> next = it.hasNext() ? it.next() : null;
		while (i < h.names.length || next != null)
		{
			int cmp = next == null ? -1 : i >= h.names.length ? 1 : h.names[i].compareTo(next.getKey());
			if (cmp < 0)
			{
				if (!gone.containsKey(h.names[i]))
				{
					names[n] = h.names[i];
					ix[n++] = h.ix[i];
				}
				i++;
				continue;
			}
			if (cmp == 0)
			{
				i++; // replaced by the delta's look
			}
			if (!gone.containsKey(next.getKey()))
			{
				names[n] = next.getKey();
				ix[n++] = (char) intern(palette, styles, next.getValue());
			}
			next = it.hasNext() ? it.next() : null;
		}
		return new Held(v, Arrays.copyOf(names, n), Arrays.copyOf(ix, n), styles.toArray(new Entry[0]));
	}

	private static int intern(Map<String, Integer> palette, List<Entry> styles, Entry e)
	{
		String key = e.c + "|" + e.g + "|" + Boolean.TRUE.equals(e.s);
		Integer i = palette.get(key);
		if (i == null)
		{
			palette.put(key, i = styles.size());
			styles.add(e);
		}
		return i;
	}

	/** The look a (normalized) name wears, or null. */
	Entry entry(String name)
	{
		if (name == null)
		{
			return null;
		}
		Held h = held;
		int at = Arrays.binarySearch(h.names, normalize(name));
		return at >= 0 ? h.styles[h.ix[at]] : null;
	}

	/** The list as a whole download (for the disk copy). */
	Wire toWire()
	{
		Held h = held;
		Wire w = new Wire();
		w.v = h.v;
		w.names = h.names;
		w.ix = new int[h.ix.length];
		for (int n = 0; n < h.ix.length; n++)
		{
			w.ix[n] = h.ix[n];
		}
		w.styles = Arrays.asList(h.styles);
		return w;
	}

	boolean isEmpty()
	{
		return held.names.length == 0;
	}

	/** The site's normalizeRsn: lower case, any run of spaces / underscores (and the game's nbsp) as one space. */
	static String normalize(String name)
	{
		return SPACES.matcher(name.replace(' ', ' ').trim().toLowerCase(Locale.ROOT)).replaceAll(" ");
	}

	// Compiled once: normalize runs for every chat sender and every friends / channel list row.
	private static final Pattern SPACES = Pattern.compile("[\\s_]+");
	private static final Pattern TAGS = Pattern.compile("<[^>]*>");

	/** A sender column's text ("[02:25] <img=1>Name:" with any colouring): the name inside, or null. */
	private static final Pattern SENDER_COLUMN = Pattern.compile("^(?:\\[[^\\]]*\\]\\s*)?(.+?):$");

	/**
	 * What to say on hover over a chat line's SENDER: "Anvil Patron" / "Anvil Champion" / "Anvil
	 * Supporter", or null (not a sender column, or no flair). The star means Patron, a gradient
	 * Champion, a solid colour Supporter.
	 */
	String tierOfSender(String widgetText)
	{
		if (widgetText == null || isEmpty())
		{
			return null;
		}
		Matcher m = SENDER_COLUMN.matcher(TAGS.matcher(widgetText).replaceAll("").trim());
		if (!m.matches())
		{
			return null;
		}
		Entry e = entry(m.group(1));
		return e == null ? null : tierLabel(e);
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
		if (raw == null || raw.isEmpty() || isEmpty() || x < 0)
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
		// Every place a painted word starts, and the names (12 letters at most) that could start there:
		// a handful of lookups per line, however long the list.
		for (int from = 0; from < text.length(); from++)
		{
			if (from > 0 && Character.isLetterOrDigit(text.charAt(from - 1)) || !Character.isLetterOrDigit(text.charAt(from)))
			{
				continue;
			}
			String before = raw.substring(0, at.get(from));
			if (!paintedJustBefore(before))
			{
				continue;
			}
			for (int to = Math.min(text.length(), from + 12); to > from; to--)
			{
				if (to < text.length() && Character.isLetterOrDigit(text.charAt(to)))
				{
					continue;
				}
				Entry e = entry(text.substring(from, to));
				if (e == null)
				{
					continue;
				}
				int x0 = width.applyAsInt(before);
				int x1 = width.applyAsInt(raw.substring(0, at.get(to - 1) + 1));
				if (x >= x0 && x < x1)
				{
					return tierLabel(e);
				}
				break;
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
		if (sender == null || isEmpty())
		{
			return null;
		}
		Matcher m = SENDER.matcher(sender);
		if (!m.matches())
		{
			return null;
		}
		String name = m.group(2);
		Entry e = entry(name);
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
