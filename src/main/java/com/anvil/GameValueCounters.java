package com.anvil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Counters read off game values (varbits/varps) rather than a chat line: Tithe Farm points, Giants'
 * Foundry reputation, Doom delve completions, slayer task streaks. WHICH values, and the target name
 * each credits, come from the site (lib/gameCounters, capability 'game-counters'), so adding one is a
 * catalog line there and never a release here.
 *
 * <p>Generic delta rule: per source, a change in the entry's direction (default: increase) after the
 * baseline is credited with amount = the change; a change the other way (spending, a streak reset)
 * never subtracts and just becomes the new baseline. An entry with several sources sums them — or,
 * with {@code changes}, counts 1 per tick on which any source moved (one slayer task may bump both
 * the main streak and Mortimer's own). {@code unless} vetoes a tick on which another var moved.
 *
 * <p>The baseline is taken only once logged in and settled ({@link #SETTLE_TICKS} ticks): right at
 * login the client's vars are still arriving, and a 0 → real-balance jump read then would credit a
 * whole bank of points. A single source jump bigger than {@code maxDelta} is re-baselined instead of
 * credited — the honest sources move by tens or hundreds per action, so a bigger jump is a resync.
 *
 * <p>Pure (no client): {@link #tick} is handed a {@link Reader}, so the rules are unit-tested.
 */
final class GameValueCounters
{
	/** Ticks after a session starts (or after a loading zone) before values count. */
	static final int SETTLE_TICKS = 5;
	/** Fallback per-source jump cap when the site sends none. */
	static final int DEFAULT_MAX_DELTA = 1000;

	static final class Source
	{
		/** "varbit" or "varp". */
		String type;
		int id;

		Source()
		{
		}

		Source(String type, int id)
		{
			this.type = type;
			this.id = id;
		}

		boolean isVarp()
		{
			return "varp".equalsIgnoreCase(type);
		}

		boolean valid()
		{
			return id > 0 && ("varp".equalsIgnoreCase(type) || "varbit".equalsIgnoreCase(type));
		}

		String key()
		{
			return (isVarp() ? "p" : "b") + id;
		}
	}

	/** One catalog entry as the site ships it. */
	static final class Entry
	{
		String name;
		List<Source> sources;
		/** "increase" (default) or "decrease". */
		String direction;
		/** False until checked in game: credits carry the 'unverified_counter' flag. */
		boolean confirmed;
		/** Largest single per-source jump that is credited; 0 → {@link #DEFAULT_MAX_DELTA}. */
		int maxDelta;
		/** Count 1 per tick on which any source moved, instead of summing the deltas. */
		boolean changes;
		/**
		 * Skip a tick on which any of these moved the same way: "other masters" is the main slayer
		 * streak rising when neither Mortimer's nor Krystilia's own did.
		 */
		List<Source> unless;

		Entry()
		{
		}

		Entry(String name, boolean confirmed, int maxDelta, Source... sources)
		{
			this.name = name;
			this.confirmed = confirmed;
			this.maxDelta = maxDelta;
			this.sources = java.util.Arrays.asList(sources);
		}

		boolean decreasing()
		{
			return "decrease".equalsIgnoreCase(direction);
		}

		int cap()
		{
			return maxDelta > 0 ? maxDelta : DEFAULT_MAX_DELTA;
		}

		boolean valid()
		{
			if (name == null || name.trim().isEmpty() || sources == null || sources.isEmpty())
			{
				return false;
			}
			for (Source s : sources)
			{
				if (s == null || !s.valid())
				{
					return false;
				}
			}
			if (unless != null)
			{
				for (Source s : unless)
				{
					if (s == null || !s.valid())
					{
						return false;
					}
				}
			}
			return true;
		}
	}

	/** A change to credit under {@code entry.name}. */
	static final class Credit
	{
		final Entry entry;
		final int amount;

		Credit(Entry entry, int amount)
		{
			this.entry = entry;
			this.amount = amount;
		}
	}

	interface Reader
	{
		int read(boolean varp, int id);
	}

	// Baselines, keyed per entry name AND var, so two entries on one var each keep their own.
	private final Map<String, Integer> last = new HashMap<>();
	private List<Entry> entries = Collections.emptyList();
	private List<Entry> source;
	private boolean inGame;
	private int settleTicks;

	/** Replace the catalog (a no-op for the same list as last time). Invalid entries are dropped. */
	void configure(List<Entry> next)
	{
		if (next == source && source != null)
		{
			return;
		}
		source = next;
		List<Entry> clean = new ArrayList<>();
		if (next != null)
		{
			for (Entry e : next)
			{
				if (e != null && e.valid())
				{
					clean.add(e);
				}
			}
		}
		entries = clean;
	}

	List<Entry> entries()
	{
		return entries;
	}

	/**
	 * Called every logged-in tick (idempotent): the first one after a session ended — or after the
	 * plugin started mid-session — starts a fresh session whose values settle before counting.
	 */
	void onLoggedIn()
	{
		if (!inGame)
		{
			last.clear();
			inGame = true;
			settleTicks = 0;
		}
	}

	/** LOADING: same account, values persist — pause reads and resume after settling, keep baselines. */
	void onLoading()
	{
		settleTicks = 0;
	}

	/** Login screen / hop / connection lost: the next account's values are not this one's. */
	void onSessionEnd()
	{
		last.clear();
		inGame = false;
		settleTicks = 0;
	}

	/**
	 * One game tick. {@code wanted} names the entries worth reading (a tile targets one of their
	 * names); others are forgotten so a tile added later baselines fresh rather than crediting what
	 * moved meanwhile.
	 */
	List<Credit> tick(Reader reader, Predicate<Entry> wanted)
	{
		if (!inGame)
		{
			return Collections.emptyList();
		}
		if (settleTicks < SETTLE_TICKS)
		{
			settleTicks++;
			return Collections.emptyList();
		}
		List<Credit> out = null;
		for (Entry e : entries)
		{
			String prefix = e.name.toLowerCase(Locale.ROOT) + "|";
			if (!wanted.test(e))
			{
				last.keySet().removeIf(k -> k.startsWith(prefix));
				continue;
			}
			int total = 0;
			boolean moved = false;
			for (Source s : e.sources)
			{
				int delta = delta(reader, e, prefix, s);
				if (delta > 0)
				{
					total += delta;
					moved = true;
				}
			}
			boolean vetoed = false;
			if (e.unless != null)
			{
				for (Source s : e.unless)
				{
					// Every veto source is read (not short-circuited) so each keeps its baseline current.
					vetoed |= delta(reader, e, prefix + "!", s) > 0;
				}
			}
			if (!moved || vetoed)
			{
				continue;
			}
			if (out == null)
			{
				out = new ArrayList<>();
			}
			out.add(new Credit(e, e.changes ? 1 : total));
		}
		return out == null ? Collections.emptyList() : out;
	}

	/**
	 * This source's change since last tick in the entry's direction, re-baselining as it goes; 0 for
	 * a first sighting, a change the other way (spend / reset) or a jump past the cap (a resync).
	 */
	private int delta(Reader reader, Entry e, String prefix, Source s)
	{
		int now = reader.read(s.isVarp(), s.id);
		Integer before = last.put(prefix + s.key(), now);
		if (before == null)
		{
			return 0;
		}
		int delta = e.decreasing() ? before - now : now - before;
		return delta <= 0 || delta > e.cap() ? 0 : delta;
	}
}
