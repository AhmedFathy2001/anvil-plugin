package com.anvil;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.Client;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;

/**
 * Reads the collection-log page the game is currently showing.
 *
 * <p>The client only ever holds ONE page — the log is drawn on demand, so an unopened page has no
 * items anywhere in memory. That's why a full sync means the player paging through their log once,
 * and why this is a read-what's-on-screen routine rather than a dump.
 *
 * <p><b>Self-verifying.</b> The header prints "Obtained: 5/9"; we count the items we believe are
 * obtained and only return a page when the two agree. A game update that changes how unobtained
 * items are drawn therefore makes this return null — the sync stalls and says so — instead of
 * quietly reporting that everybody lost half their collection log.
 *
 * <p>Runs on the client thread (its only caller is the COLLECTION_DRAW_LIST script hook), so it
 * allocates nothing beyond the page it returns and never blocks.
 */
final class ClogPageReader
{
	private ClogPageReader() {}

	/** "Obtained: 5/9" — the log's own progress line, and our correctness check. */
	private static final Pattern OBTAINED = Pattern.compile("obtained:\\s*(\\d+)\\s*/\\s*(\\d+)");
	/** "Abyssal Sire kills: 1,204" / "Chest openings: 87" — label and value, commas stripped. */
	private static final Pattern COUNT_LINE = Pattern.compile("^(.+?):\\s*([\\d,]+)$");
	/** Widget tags: the log wraps names in colour tags we don't want in the payload. */
	private static final Pattern TAGS = Pattern.compile("<[^>]*>");

	/**
	 * The page on screen, or null when it isn't readable yet (mid-draw, empty, or the obtained
	 * count disagrees with what we read — see the class note).
	 */
	static ClogPage read(Client client)
	{
		Widget header = client.getWidget(ComponentID.COLLECTION_LOG_ENTRY_HEADER);
		Widget items = client.getWidget(ComponentID.COLLECTION_LOG_ENTRY_ITEMS);
		if (header == null || items == null)
		{
			return null;
		}

		String name = null;
		int obtained = -1;
		int total = -1;
		// Allocated only if the page actually prints counters — most don't, and this runs on every
		// redraw of every page the player scrolls past.
		Map<String, Integer> counts = null;

		Widget[] lines = header.getStaticChildren();
		if (lines == null)
		{
			return null;
		}
		for (Widget line : lines)
		{
			if (line == null)
			{
				continue;
			}
			String text = strip(line.getText());
			if (text.isEmpty())
			{
				continue;
			}
			// First non-empty line is the entry title; the rest are progress and counters.
			if (name == null)
			{
				name = text;
				continue;
			}
			Matcher progress = OBTAINED.matcher(text.toLowerCase());
			if (progress.find())
			{
				obtained = parseInt(progress.group(1));
				total = parseInt(progress.group(2));
				continue;
			}
			Matcher count = COUNT_LINE.matcher(text);
			if (count.matches())
			{
				int value = parseInt(count.group(2).replace(",", ""));
				if (value >= 0)
				{
					if (counts == null)
					{
						counts = new LinkedHashMap<>(4);
					}
					counts.put(count.group(1).trim(), value);
				}
			}
		}

		if (name == null || name.isEmpty() || obtained < 0 || total <= 0)
		{
			return null;
		}

		Widget[] cells = items.getDynamicChildren();
		if (cells == null)
		{
			return null;
		}

		// Two passes so the arrays are exactly sized — a page is at most a few dozen items, and this
		// avoids both an ArrayList's boxing and a grow-and-copy on every redraw.
		int found = 0;
		for (Widget cell : cells)
		{
			if (isObtained(cell))
			{
				found++;
			}
		}
		// The log's own count is the authority. Disagreeing means we misread the page (or Jagex
		// changed the drawing); either way, committing it would corrupt a synced log.
		if (found != obtained)
		{
			return null;
		}

		int[] ids = new int[found];
		int[] quantities = new int[found];
		int i = 0;
		for (Widget cell : cells)
		{
			if (!isObtained(cell))
			{
				continue;
			}
			ids[i] = cell.getItemId();
			// A quantity of 0 on an obtained item means "one, uncounted" for the handful of log
			// entries that predate stack tracking; storing 0 would read as "you don't have it".
			quantities[i] = Math.max(1, cell.getItemQuantity());
			i++;
		}

		return new ClogPage(name, ids, quantities, obtained, total, counts);
	}

	/**
	 * Whether a cell is an item the account has.
	 *
	 * <p>The log draws unobtained items dimmed (non-zero opacity) with no quantity; obtained ones are
	 * fully opaque and carry their count. Either signal alone has an edge case, so both are accepted
	 * and the header's "Obtained: N" arbitrates — see {@link #read}.
	 */
	private static boolean isObtained(Widget cell)
	{
		if (cell == null || cell.getItemId() <= 0)
		{
			return false;
		}
		return cell.getOpacity() == 0 || cell.getItemQuantity() > 0;
	}

	private static String strip(String raw)
	{
		if (raw == null)
		{
			return "";
		}
		// The regex is the expensive part and most lines carry no tags at all — one scan for '<'
		// saves building a Matcher on every header line of every redraw.
		if (raw.indexOf('<') < 0)
		{
			return raw.trim();
		}
		return TAGS.matcher(raw).replaceAll("").trim();
	}

	private static int parseInt(String raw)
	{
		try
		{
			return Integer.parseInt(raw);
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}
}
