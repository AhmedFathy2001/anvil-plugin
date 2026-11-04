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
	private static final Pattern SENDER_COLUMN = Pattern.compile("^(?:\\[[^\\]]*\\]\\s*)*(?:(?:From|To)\\s+)?(.+?):$");

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

	// ---- Display-time painting of chat widgets. The game's message data (MessageNode name / value) is
	// never touched: the chatbox builds its right-click options ("Message", "Add friend", "Remove
	// friend", reply, report) from that data, and a tagged name breaks the friend check and the action.

	/** Raw chat text's visible characters (tags out, nbsp as a space), and where each sits in the raw. */
	static final class Shown
	{
		final String text;
		final int[] at;

		Shown(String text, int[] at)
		{
			this.text = text;
			this.at = at;
		}
	}

	static Shown shown(String raw)
	{
		StringBuilder plain = new StringBuilder(raw.length());
		int[] at = new int[raw.length()];
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
			at[plain.length()] = i;
			plain.append(ch == '\u00A0' ? ' ' : ch);
		}
		return new Shown(plain.toString(), at);
	}

	/**
	 * A sender as the chatbox shows it: optional "[channel]" / "[timestamp]" groups, then "From " / "To "
	 * on a private message, then the name and a colon. Group 2 is From/To, group 3 the name, group 4
	 * whatever follows the colon (empty in the chatbox's sender column; the message on a split-PM line).
	 */
	private static final Pattern SENDER_SHOWN = Pattern.compile("^\\s*((?:\\[[^\\]]*\\]\\s*)*)(?:(From|To)\\s+)?([^:\\[\\]]{1,24}):(.*)$", Pattern.DOTALL);
	/** Leading "[channel]" / "[timestamp]" groups and spaces before a broadcast's text. */
	private static final Pattern LEAD = Pattern.compile("^(?:\\s*\\[[^\\]]*\\])*\\s*");

	/**
	 * A chatbox SENDER COLUMN's text ("Name:", "[Clan] <icons>Name:", "From Name:", "To Name:") with the
	 * name painted; null when it isn't one (anything after the colon), the name has no flair, or it is
	 * painted already. Text alone proves nothing — anyone can type "[Clan] Bob:" — so the caller passes
	 * only the widget the layout says is a sender column (senderColumn).
	 */
	String paintSender(String raw, String star, boolean opaque)
	{
		return paintSender(raw, star, opaque, false);
	}

	/**
	 * A split-private-chat line ("From Name: hi" / "To Name: hi"), which the game writes as one widget:
	 * only for widgets of that container (never the chatbox), and only with From / To before the name.
	 */
	String paintPmLine(String raw, String star, boolean opaque)
	{
		return paintSender(raw, star, opaque, true);
	}

	private String paintSender(String raw, String star, boolean opaque, boolean pmLine)
	{
		if (raw == null || raw.isEmpty() || isEmpty() || raw.indexOf(':') < 0)
		{
			return null;
		}
		Shown s = shown(raw);
		Matcher m = SENDER_SHOWN.matcher(s.text);
		// A sender column ends at the colon; a split-PM line is "From/To Name: …". A typed "[Clan] Bob: hi"
		// in a message widget is neither.
		if (!m.matches() || (pmLine ? m.group(2) == null || !m.group(1).isEmpty() : !m.group(4).trim().isEmpty()))
		{
			return null;
		}
		int from = m.start(3);
		int to = m.end(3);
		while (from < to && s.text.charAt(from) == ' ')
		{
			from++;
		}
		while (to > from && s.text.charAt(to - 1) == ' ')
		{
			to--;
		}
		return from < to && to - from <= 12 ? paintAt(raw, s, from, to, star, opaque) : null;
	}

	/**
	 * Chatbox layout, not text, says which widget is a sender column: one with a line's text beside it
	 * (same row, to its right, within the line's few widgets). A typed "[Clan] Bob:" is the message
	 * widget, the last on its row. {@code text(j)} is null for a hidden or missing widget.
	 */
	static boolean senderColumn(int i, int n, java.util.function.IntUnaryOperator x, java.util.function.IntUnaryOperator y,
		java.util.function.IntFunction<String> text)
	{
		for (int j = i + 1; j < n && j <= i + 3; j++)
		{
			String t = text.apply(j);
			if (t != null && !t.isEmpty() && y.applyAsInt(j) == y.applyAsInt(i) && x.applyAsInt(j) > x.applyAsInt(i))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * A line text with a player's sender column to its left ("Name:" — someone typed it): never a
	 * broadcast, whatever it says. A broadcast's row has only the channel ("[The AFK Spot]") or nothing.
	 */
	static boolean typedBySomeone(int i, java.util.function.IntUnaryOperator x, java.util.function.IntUnaryOperator y,
		java.util.function.IntFunction<String> text)
	{
		for (int j = i - 1; j >= 0 && j >= i - 3; j--)
		{
			String t = text.apply(j);
			if (t != null && y.applyAsInt(j) == y.applyAsInt(i) && x.applyAsInt(j) < x.applyAsInt(i) && column(t))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Room in one row of chat widgets for a sender column that grew by {@code d} px (the Patron star):
	 * widen it, and move each shown widget on its row that starts right of it over by d. {@code ax/ay}
	 * place the widgets on screen (for "same row", "right of"); {@code x/w} are the values to change.
	 * {@code recX/recW} hold what was last set (MIN_VALUE: nothing), updated here: a widget still where
	 * it was put is not moved again, one the game laid out afresh is. True when anything changed.
	 */
	static boolean makeRoom(int at, int d, int n, int[] ax, int[] ay, int[] x, int[] w, boolean[] shown, int[] recX, int[] recW)
	{
		if (d <= 0)
		{
			return false;
		}
		boolean moved = false;
		if (w[at] != recW[at])
		{
			w[at] += d;
			recW[at] = w[at];
			moved = true;
		}
		for (int j = 0; j < n; j++)
		{
			if (j == at || !shown[j] || ay[j] != ay[at] || ax[j] <= ax[at] || x[j] == recX[j])
			{
				continue;
			}
			x[j] += d;
			w[j] = Math.max(1, w[j] - d);
			recX[j] = x[j];
			moved = true;
		}
		return moved;
	}

	/** Another shown widget on row {@code at}'s line, to its left (left = true) or right. */
	static boolean beside(int at, int n, int[] ax, int[] ay, boolean[] shown, boolean left)
	{
		for (int j = 0; j < n; j++)
		{
			if (j != at && shown[j] && ay[j] == ay[at] && (left ? ax[j] < ax[at] : ax[j] > ax[at]))
			{
				return true;
			}
		}
		return false;
	}

	private static final Pattern ON_WORLD = Pattern.compile("^(?:W|World\\s*)\\d+$");

	/**
	 * A player's right-click target ("<col=ffffff>Name<col=40ff00>  (level-71)") with their name in their
	 * colours, re-done after another plugin recoloured it: Player Indicators, on every client tick, drops
	 * the target's FIRST tag and puts its own colour in front, which turned the start of a gradient into
	 * its friend green. Null when it's ours already, the name isn't there or has no flair, or (override
	 * off) another plugin's colour is on it — not the game's white.
	 */
	String paintMenuTarget(String target, String name, boolean override)
	{
		if (target == null || name == null || isEmpty())
		{
			return null;
		}
		String d = decorate(name, null);
		if (d == null)
		{
			return null;
		}
		d = ChatPalette.readable(d, false);
		Shown s = shown(target);
		int from = s.text.indexOf(name.replace('\u00A0', ' '));
		if (from < 0)
		{
			return null;
		}
		int rs = s.at[from];
		int re = s.at[from + name.length() - 1] + 1;
		int k = shown(d).at[0];
		if (rs >= k && target.startsWith(d, rs - k))
		{
			return null;
		}
		// The colour tags right before the name are whoever coloured it last: replaced with ours.
		int start = rs;
		String col = null;
		while (start > 0 && target.charAt(start - 1) == '>')
		{
			int open = target.lastIndexOf('<', start - 1);
			if (open < 0 || !(target.startsWith("<col=", open) || target.startsWith("</col>", open)))
			{
				break;
			}
			if (col == null && target.startsWith("<col=", open))
			{
				col = target.substring(open + 5, start - 1);
			}
			start = open;
		}
		boolean theirs = !override && col != null && !col.equalsIgnoreCase("ffffff");
		String plain = s.text.substring(from, from + name.length());
		if (theirs && target.substring(rs, re).equals(plain))
		{
			return null;
		}
		// Our paint ends with its own close: an old one after the name goes, or a repaint every tick would pile them up.
		while (target.startsWith("</col>", re))
		{
			re += 6;
		}
		// Override off and their colour in front: they took only our first tag, so the rest of our gradient
		// is still inside the name. Strip it, and the whole name is in their colour.
		return theirs
			? target.substring(0, rs) + plain + target.substring(re)
			: target.substring(0, start) + d + target.substring(re);
	}

	/** A sender widget that is just the column ("…Name:"), not a whole line: the line's text sits beside it. */
	static boolean column(String raw)
	{
		String t = shown(raw).text.trim();
		return t.endsWith(":");
	}

	/**
	 * Raw chat text's drawn width: the visible characters measured by the font ({@code textWidth}, given
	 * no tags), plus each {@code <img=n>} at {@code iconWidth(n)}. Colour tags take no room. The hover maths
	 * and the room a star needs both go through this, so an icon or the star never shifts them.
	 */
	static int measure(String raw, java.util.function.ToIntFunction<String> textWidth, java.util.function.IntUnaryOperator iconWidth)
	{
		StringBuilder text = new StringBuilder(raw.length());
		int icons = 0;
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
				if (raw.startsWith("img=", i + 1))
				{
					try
					{
						icons += iconWidth.applyAsInt(Integer.parseInt(raw.substring(i + 5, close)));
					}
					catch (NumberFormatException ignored)
					{
						// not an icon after all: no room
					}
				}
				i = close;
				continue;
			}
			text.append(ch);
		}
		return (text.length() == 0 ? 0 : textWidth.applyAsInt(text.toString())) + icons;
	}

	/**
	 * A broadcast's key ("Nisbro HCM has completed a quest: …" with tags and leading "[channel]" out) when
	 * it starts with a Supporter's name, else null. Kept at onChatMessage so the chatbox walk knows which
	 * widgets are those broadcasts without a name lookup per widget.
	 */
	String leadKey(String raw)
	{
		if (raw == null || raw.isEmpty() || isEmpty())
		{
			return null;
		}
		Shown s = shown(raw);
		return leadName(s) < 0 ? null : key(s);
	}

	/** The same key for any text (no lookup): what the chatbox walk compares against the kept ones. */
	static String key(String raw)
	{
		return raw == null ? null : key(shown(raw));
	}

	private static String key(Shown s)
	{
		Matcher m = LEAD.matcher(s.text);
		return m.lookingAt() ? s.text.substring(m.end()) : s.text;
	}

	/** A broadcast widget with the leading name painted (icons and colours kept); null when none / already. */
	String paintLead(String raw, String star, boolean opaque)
	{
		if (raw == null || raw.isEmpty() || isEmpty())
		{
			return null;
		}
		Shown s = shown(raw);
		int end = leadName(s);
		if (end < 0)
		{
			return null;
		}
		Matcher m = LEAD.matcher(s.text);
		int from = m.lookingAt() ? m.end() : 0;
		return paintAt(raw, s, from, end, star, opaque);
	}

	/**
	 * Where the Supporter's name the text starts with ends (exclusive), or -1: the longest prefix of at
	 * most 12 characters that ends on a word ("Bob" is not the start of "Bobby has…") and is on the list.
	 */
	private int leadName(Shown s)
	{
		String t = s.text;
		Matcher m = LEAD.matcher(t);
		int from = m.lookingAt() ? m.end() : 0;
		for (int to = Math.min(t.length(), from + 12); to > from; to--)
		{
			if (!Character.isLetterOrDigit(t.charAt(to - 1)) || to < t.length() && nameChar(t.charAt(to)))
			{
				continue;
			}
			if (entry(t.substring(from, to)) != null)
			{
				return to;
			}
		}
		return -1;
	}

	private static boolean nameChar(char c)
	{
		return Character.isLetterOrDigit(c) || c == '_' || c == '-';
	}

	/**
	 * Replace the visible characters [from, to) — a plain name in the raw text — with the painted name,
	 * re-opening the colour the text had there so the rest of the line keeps it. Null when the name
	 * carries tags inside (gradient-painted already) or our paint is right before it (painted already).
	 */
	private String paintAt(String raw, Shown s, int from, int to, String star, boolean opaque)
	{
		int rs = s.at[from];
		int re = s.at[to - 1] + 1;
		String name = raw.substring(rs, re);
		if (name.indexOf('<') >= 0)
		{
			return null;
		}
		String d = decorate(name, star);
		if (d == null)
		{
			return null;
		}
		d = ChatPalette.readable(d, opaque);
		int k = shown(d).at[0];
		if (rs >= k && raw.startsWith(d.substring(0, k), rs - k))
		{
			return null;
		}
		String before = raw.substring(0, rs);
		int open = before.lastIndexOf("<col=");
		String reopen = open >= 0 && open > before.lastIndexOf("</col>") && before.indexOf('>', open) > 0
			? before.substring(open, before.indexOf('>', open) + 1) : "";
		return before + d + reopen + raw.substring(re);
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
