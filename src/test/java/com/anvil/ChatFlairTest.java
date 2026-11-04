package com.anvil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;

public class ChatFlairTest
{
	/** A look, with the name it's for carried in a side table (the wire keeps names apart from looks). */
	private static final java.util.Map<ChatFlair.Entry, String> NAMES = new java.util.IdentityHashMap<>();

	private static ChatFlair.Entry entry(String n, String c, Boolean star, String... grad)
	{
		ChatFlair.Entry e = new ChatFlair.Entry();
		NAMES.put(e, n);
		e.c = c;
		e.s = star;
		e.g = grad.length == 0 ? null : Arrays.asList(grad);
		return e;
	}

	private static ChatFlair with(ChatFlair.Entry... entries)
	{
		ChatFlair f = new ChatFlair();
		f.adopt(wire(1, null, entries));
		return f;
	}

	static ChatFlair.Wire wire(int v, Integer from, ChatFlair.Entry... entries)
	{
		ChatFlair.Wire w = new ChatFlair.Wire();
		w.v = v;
		w.from = from;
		w.styles = new ArrayList<>(Arrays.asList(entries));
		w.names = new String[entries.length];
		w.ix = new int[entries.length];
		for (int i = 0; i < entries.length; i++)
		{
			w.names[i] = NAMES.get(entries[i]);
			w.ix[i] = i;
		}
		return w;
	}

	@Test
	public void solidColourKeepsTheIconsAndMatchesSpacesAndCase()
	{
		ChatFlair f = with(entry("gim nisbro", "#ffd35c", null));
		assertEquals("<img=3><col=ffd35c>GIM Nisbro</col>", f.decorate("<img=3>GIM Nisbro", "<img=99>"));
		assertNull(f.decorate("Someone Else", "<img=99>"));
	}

	@Test
	public void patronGetsTheStarAndTheGradient()
	{
		ChatFlair f = with(entry("ab", "#ff7ab8", true, "#ff0000", "#0000ff"));
		assertEquals("<img=99><col=ff0000>A</col><col=0000ff>b</col>", f.decorate("Ab", "<img=99>"));
		// No star icon registered yet: the name still gets its colours.
		assertEquals("<col=ff0000>A</col><col=0000ff>b</col>", f.decorate("Ab", null));
	}

	@Test
	public void leavesAlreadyDecoratedNamesAndJunkAlone()
	{
		ChatFlair f = with(entry("ab", "#ff7ab8", null), entry("cd", "red", null));
		assertNull(f.decorate("<col=ff0000>Ab</col>", null));
		assertNull(f.decorate("Cd", null));
	}

	/** Six pixels a visible character, an icon twelve; tags take no room. */
	private static int width(String raw)
	{
		String icons = raw.replaceAll("<img=\\d+>", "\u0001");
		int w = 0;
		for (char ch : icons.replaceAll("<[^>]*>", "").toCharArray())
		{
			w += ch == '\u0001' ? 12 : 6;
		}
		return w;
	}

	@Test
	public void hoverOnAPaintedNameInsideALineGivesTheTier()
	{
		ChatFlair f = with(entry("drenvox", "#ff7ab8", true, "#ffd27a", "#ff7ab8"));
		String line = "<col=7a5600>[Anvil]</col> <col=006a00>Tile complete: 1 lap of seers — by </col><img=5>"
			+ f.decorate("Drenvox", "") + "<col=006a00>!</col>";
		int nameStart = width(line.substring(0, line.indexOf("<img=5>"))) + 12;
		assertEquals("Anvil Patron", f.tierInLine(line, nameStart + 3, ChatFlairTest::width));
		assertEquals("Anvil Patron", f.tierInLine(line, nameStart + 7 * 6 - 1, ChatFlairTest::width));
		assertNull("before the name: the rest of the line", f.tierInLine(line, nameStart - 20, ChatFlairTest::width));
		assertNull("after it", f.tierInLine(line, nameStart + 7 * 6 + 1, ChatFlairTest::width));
		// Someone typing the name is not flair.
		assertNull(f.tierInLine("<col=0000ff>drenvox is so lucky</col>".replace("<col=0000ff>", ""), 3, ChatFlairTest::width));
	}

	@Test
	public void aDeltaOnTheHeldVersionSetsAndDropsInPlace()
	{
		ChatFlair f = with(entry("alpha", "#ffd35c", null), entry("charlie", "#ffd35c", null));
		ChatFlair.Wire d = wire(2, 1, entry("bravo", "#ff0000", true), entry("alpha", "#00ff00", null));
		d.del = new String[]{"charlie"};
		assertEquals(true, f.apply(d));
		assertEquals(2, f.version());
		assertEquals("#00ff00", f.entry("Alpha").c);
		assertEquals(Boolean.TRUE, f.entry("BRAVO").s);
		assertNull(f.entry("charlie"));
		// Not from the version held (missed one, or a "full" marker): refused, nothing changes.
		assertEquals(false, f.apply(wire(4, 3, entry("delta", "#ffffff", null))));
		ChatFlair.Wire full = wire(3, 2);
		full.full = true;
		assertEquals(false, f.apply(full));
		assertEquals(2, f.version());
		assertNull(f.entry("delta"));
	}

	/** A fake scheduler: what was asked for, run by hand. */
	private static final class Later implements ChatFlairSync.Scheduler
	{
		final java.util.List<Runnable> runs = new ArrayList<>();
		final java.util.List<Long> delays = new ArrayList<>();

		@Override
		public void schedule(Runnable r, long ms)
		{
			runs.add(r);
			delays.add(ms);
		}
	}

	private static final class Site implements ChatFlairSync.Source
	{
		ChatFlair.Since since;
		ChatFlair.Wire full;
		final java.util.List<String> asked = new ArrayList<>();

		@Override
		public ChatFlair.Since since(int v)
		{
			asked.add("since " + v);
			return since;
		}

		@Override
		public ChatFlair.Wire full(int v)
		{
			asked.add("full " + v);
			return full;
		}
	}

	@Test
	public void aPushThatFitsIsAppliedWithNoRequestAndOneThatDoesntCatchesUpAfterJitter()
	{
		ChatFlair f = with(entry("alpha", "#ffd35c", null));
		Site site = new Site();
		Later later = new Later();
		int[] changed = {0};
		ChatFlairSync sync = new ChatFlairSync(f, site, later, () -> 12_345L, () -> changed[0]++);

		sync.pushed(wire(2, 1, entry("bravo", "#ff0000", null)));
		assertEquals(1, changed[0]);
		assertEquals(0, later.runs.size());
		assertEquals(0, site.asked.size());

		// Missed version 3: this one (from 3) doesn't fit. Two such pushes are one jittered catch-up.
		sync.pushed(wire(4, 3, entry("delta", "#ffffff", null)));
		sync.pushed(wire(5, 4, entry("echo", "#ffffff", null)));
		assertEquals(1, later.runs.size());
		assertEquals(Long.valueOf(12_345L), later.delays.get(0));
		// The site has the missing deltas: applied in order, no whole download.
		ChatFlair.Since since = new ChatFlair.Since();
		since.v = 4;
		since.deltas = Arrays.asList(wire(3, 2, entry("charlie", "#ffffff", null)), wire(4, 3, entry("delta", "#ffffff", null)));
		site.since = since;
		later.runs.get(0).run();
		assertEquals(Arrays.asList("since 2"), site.asked);
		assertEquals(4, f.version());
		assertEquals("#ffffff", f.entry("delta").c);
		assertEquals(2, changed[0]);

		// Too far behind: the whole versioned list.
		since = new ChatFlair.Since();
		since.v = 9;
		site.since = since;
		site.full = wire(9, null, entry("zulu", "#123456", null));
		sync.pushed(wire(9, 8));
		later.runs.get(1).run();
		assertEquals(Arrays.asList("since 2", "since 4", "full 9"), site.asked);
		assertEquals(9, f.version());
		assertNull(f.entry("alpha"));
		assertEquals("#123456", f.entry("zulu").c);
	}

	@Test
	public void theListIsWantedForALoginInNoClanWhoseConfigIsA404()
	{
		// No config at all (404): the site's /api/version capabilities decide.
		assertEquals(true, ChatFlairSync.wanted(true, true, null, "chat-flair-v"::equals));
		assertEquals(false, ChatFlairSync.wanted(true, true, null, c -> false));
		assertEquals(false, ChatFlairSync.wanted(true, false, null, "chat-flair-v"::equals));
		assertEquals(false, ChatFlairSync.wanted(false, true, null, "chat-flair-v"::equals));
	}

	@Test
	public void twoHundredThousandNamesStayCompactAndFast()
	{
		String[] names = new String[200_000];
		int[] ix = new int[names.length];
		java.util.Random r = new java.util.Random(7);
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (int i = 0; i < names.length; )
		{
			String n = (Long.toString(Math.abs(r.nextLong()), 36) + "000000000000").substring(0, 4 + r.nextInt(8));
			if (seen.add(n))
			{
				names[i] = n;
				ix[i++] = r.nextInt(10) == 0 ? 3 + r.nextInt(500) : r.nextInt(3);
			}
		}
		ChatFlair.Wire w = new ChatFlair.Wire();
		w.v = 1;
		w.names = names;
		w.ix = ix;
		w.styles = new ArrayList<>();
		for (int i = 0; i < 503; i++)
		{
			w.styles.add(entry("s" + i, String.format("#%06x", r.nextInt(0xffffff)), i == 2 ? true : null));
		}
		Runtime rt = Runtime.getRuntime();
		System.gc();
		long mem0 = rt.totalMemory() - rt.freeMemory();
		long t0 = System.nanoTime();
		ChatFlair f = new ChatFlair();
		f.adopt(w);
		long t1 = System.nanoTime();
		w = null;
		System.gc();
		long mem1 = rt.totalMemory() - rt.freeMemory();
		int hits = 0;
		long t2 = System.nanoTime();
		for (int i = 0; i < 100_000; i++)
		{
			hits += f.entry(names[i]) != null ? 1 : 0;
		}
		long t3 = System.nanoTime();
		ChatFlair.Wire d = wire(2, 1, entry("aaaa-new", "#ffffff", null));
		d.del = new String[]{names[5]};
		long t4 = System.nanoTime();
		f.apply(d);
		long t5 = System.nanoTime();
		System.out.printf("[load] 200k flair: adopt %d ms, held ~%.1f MB, lookup %.0f ns, delta %d ms%n",
			(t1 - t0) / 1_000_000, (mem1 - mem0) / 1e6, (t3 - t2) / 100_000.0, (t5 - t4) / 1_000_000);
		assertEquals(100_000, hits);
		assertNull(f.entry(names[5]));
	}
}
