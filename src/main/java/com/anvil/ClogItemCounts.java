package com.anvil;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.widgets.Widget;
import net.runelite.client.RuneLite;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/**
 * How many of each item the player's collection log holds — the number behind "that's my 3rd pet"
 * and "which drop number was that".
 *
 * <p>OSRS gives no API for this. The client only ever holds the quantities for the log page you are
 * currently LOOKING at: open the Chambers of Xeric page and the client learns those items, and
 * nothing else. So this harvests opportunistically — every time a native log page draws, whatever it
 * shows is recorded — and persists per account so a session doesn't start blind.
 *
 * <p>That makes the data honestly partial: an item on a page the player has never opened is simply
 * unknown, and callers omit the field rather than print a zero. It's also a PRE-drop snapshot, since
 * the log isn't open at the moment something drops — {@link #countAfterDrop} is what callers want,
 * and it explains the +1.
 *
 * <p>And it can LAG: someone who got three more since they last opened that page will be reported
 * one low by three. It re-syncs the moment they browse it again. A first-ever drop is always exact
 * (an un-obtained slot harvests as 0, so 0 + 1 = their 1st), which is the case that matters most.
 *
 * <p>One file, keyed by account hash, so an alt's log never reports as the main's.
 */
@Slf4j
@Singleton
public class ClogItemCounts
{
	private static final File STORE = new File(RuneLite.RUNELITE_DIR, "anvil-clog-counts.json");
	private static final Type STORE_TYPE = new TypeToken<Map<String, Map<String, Integer>>>() {}.getType();

	private final Gson gson;

	/** accountHash → (itemId → quantity held). Loaded once, written back after a harvest changes it. */
	private Map<String, Map<String, Integer>> byAccount = new HashMap<>();
	private boolean loaded;

	@Inject
	public ClogItemCounts(Gson gson)
	{
		this.gson = gson;
	}

	private synchronized void load()
	{
		if (loaded)
		{
			return;
		}
		loaded = true;
		if (!STORE.exists())
		{
			return;
		}
		try (Reader r = new FileReader(STORE))
		{
			Map<String, Map<String, Integer>> parsed = gson.fromJson(r, STORE_TYPE);
			if (parsed != null)
			{
				byAccount = parsed;
			}
		}
		catch (Exception e)
		{
			// A corrupt cache is not worth a stack trace or a lost session — start empty and
			// re-harvest as the player browses.
			log.debug("Could not read clog counts: {}", e.getMessage());
		}
	}

	private synchronized void save()
	{
		try (Writer w = new FileWriter(STORE))
		{
			gson.toJson(byAccount, STORE_TYPE, w);
		}
		catch (IOException e)
		{
			log.debug("Could not write clog counts: {}", e.getMessage());
		}
	}

	/**
	 * Record every item quantity on the log page that just drew. {@code items} is the log's
	 * ENTRY_ITEMS container; its dynamic children each carry an item id and the quantity held.
	 * A no-op without an account hash (logged out) or an empty page.
	 */
	public synchronized void harvest(String accountHash, Widget items)
	{
		if (accountHash == null || accountHash.isEmpty() || items == null)
		{
			return;
		}
		Widget[] children = items.getDynamicChildren();
		if (children == null || children.length == 0)
		{
			return;
		}
		load();
		Map<String, Integer> mine = byAccount.computeIfAbsent(accountHash, k -> new HashMap<>());
		boolean changed = false;
		for (Widget child : children)
		{
			if (child == null)
			{
				continue;
			}
			int itemId = child.getItemId();
			if (itemId <= 0)
			{
				continue;
			}
			// A slot the player hasn't obtained still renders (greyed out) with quantity 0 — that's a
			// real answer, not a missing one, so it's recorded as 0 rather than skipped.
			int qty = Math.max(0, child.getItemQuantity());
			Integer prev = mine.put(String.valueOf(itemId), qty);
			if (prev == null || prev != qty)
			{
				changed = true;
			}
		}
		if (changed)
		{
			save();
		}
	}

	/** Quantity the log last showed for this item, or null when that page has never been opened. */
	public synchronized Integer known(String accountHash, int itemId)
	{
		if (accountHash == null || accountHash.isEmpty() || itemId <= 0)
		{
			return null;
		}
		load();
		Map<String, Integer> mine = byAccount.get(accountHash);
		return mine == null ? null : mine.get(String.valueOf(itemId));
	}

    /**
     * What the log WILL read for this item once the drop that just happened is counted — the last
     * harvested quantity plus one. Null when this item's page has never been browsed, in which case
     * the caller says nothing rather than guessing.
     *
     * <p>The +1 is why this exists rather than callers using {@link #known}: the log is never open at
     * the moment of a drop, so every harvest predates it.
     */
	public synchronized Integer countAfterDrop(String accountHash, int itemId)
	{
		Integer prior = known(accountHash, itemId);
		return prior == null ? null : prior + 1;
	}

	/** "1st" / "2nd" / "3rd" / "11th" — for reading a count as a position in a sentence. */
	public static String ordinal(int n)
	{
		if (n <= 0)
		{
			return String.valueOf(n);
		}
		int mod100 = n % 100;
		if (mod100 >= 11 && mod100 <= 13)
		{
			return n + "th";
		}
		switch (n % 10)
		{
			case 1:
				return n + "st";
			case 2:
				return n + "nd";
			case 3:
				return n + "rd";
			default:
				return n + "th";
		}
	}
}
