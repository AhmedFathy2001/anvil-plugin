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

	// ---- Display-time widget painting (sender columns, PM lines, broadcasts)

	@Test
	public void senderColumnsArePaintedOnTheWidgetText()
	{
		ChatFlair f = with(entry("gim nisbro", "#ffd35c", null), entry("drenvox", "#00ff00", true));
		assertEquals("<img=3><col=ffd35c>GIM\u00A0Nisbro</col>:", f.paintSender("<img=3>GIM\u00A0Nisbro:", "<img=99>", false));
		assertEquals("<col=0000ff>[The AFK Spot]</col> <img=99><col=00ff00>Drenvox</col>:",
			f.paintSender("<col=0000ff>[The AFK Spot]</col> Drenvox:", "<img=99>", false));
		assertEquals("[12:34] <col=00ff00>Drenvox</col>:", f.paintSender("[12:34] Drenvox:", null, false));
	}

	@Test
	public void privateMessagesInAndOutArePainted()
	{
		ChatFlair f = with(entry("drenvox", "#00ff00", null));
		assertEquals("From <col=00ff00>Drenvox</col>:", f.paintSender("From Drenvox:", null, false));
		assertEquals("To <col=00ff00>Drenvox</col>:", f.paintSender("To Drenvox:", null, false));
		// Split private chat: the whole line is one widget, coloured by the game.
		assertEquals("<col=00ffff>To <col=00ff00>Drenvox</col><col=00ffff>: hey there</col>",
			f.paintPmLine("<col=00ffff>To Drenvox: hey there</col>", null, false));
		// The chatbox never takes a whole line as a sender; a PM line needs From / To.
		assertNull(f.paintSender("<col=00ffff>To Drenvox: hey there</col>", null, false));
		assertNull(f.paintPmLine("Drenvox: hey", null, false));
		assertNull(f.paintPmLine("[Clan] Drenvox: hey", null, false));
		// A typed message that merely starts "Name:" is not a sender.
		assertNull(f.paintSender("Drenvox: hey", null, false));
	}

	@Test
	public void paintingIsIdempotent()
	{
		ChatFlair f = with(entry("drenvox", "#00ff00", true), entry("ab", "#ffd35c", null, "#ff0000", "#00ff00"));
		String once = f.paintSender("<img=2>Drenvox:", "<img=99>", false);
		assertNull(f.paintSender(once, "<img=99>", false));
		String grad = f.paintPmLine("From Ab: hi", null, false);
		assertNull(f.paintPmLine(grad, null, false));
		String b = f.paintLead("<col=ef1020>Ab received a drop: Bones</col>", null, false);
		assertNull(f.paintLead(b, null, false));
	}

	@Test
	public void nonSupportersAndPartialNamesAreUntouched()
	{
		ChatFlair f = with(entry("bob", "#00ff00", null));
		assertNull(f.paintSender("Bobby:", null, false));
		assertNull(f.paintSender("From Bobby: hi", null, false));
		assertNull(f.paintLead("Bobby has completed a quest: Gertrude's Cat", null, false));
		assertNull(f.leadKey("Bobby has completed a quest: Gertrude's Cat"));
		assertNull(f.paintLead("Someone received a drop: Bob's hat", null, false));
		assertNull(f.paintLead("Bob_2 has reached a total level of 100.", null, false));
		assertNull(f.paintLead("Bob-x has reached a total level of 100.", null, false));
	}

	@Test
	public void broadcastLeadingNameIsPaintedAfterIconsKeepingTheColour()
	{
		ChatFlair f = with(entry("nisbro hcm", "#ffd35c", null), entry("nisbro", "#00ff00", null), entry("bob", "#00ff00", true));
		// Longest name wins, nbsp counts as a space, the icon stays, and the line's colour re-opens after.
		String line = "<img=10><col=ef1020>Nisbro\u00A0HCM has completed a quest: <col=0c5f00>Gertrude's Cat</col></col>";
		assertEquals("<img=10><col=ef1020><col=ffd35c>Nisbro\u00A0HCM</col><col=ef1020> has completed a quest: <col=0c5f00>Gertrude's Cat</col></col>",
			f.paintLead(line, null, false));
		assertEquals("Nisbro HCM has completed a quest: Gertrude's Cat", f.leadKey(line));
		assertEquals(f.leadKey(line), ChatFlair.key(line));
		// Shorter names still match on a word boundary; the Patron's star goes before the name.
		assertEquals("<col=00ff00>Nisbro</col> received a drop: Bones", f.paintLead("Nisbro received a drop: Bones", "<img=99>", false));
		assertEquals("<img=99><col=00ff00>Bob</col> has reached a total level of 100.",
			f.paintLead("Bob has reached a total level of 100.", "<img=99>", false));
		// A "[channel]" lead in the same widget is skipped, and the key ignores it.
		assertEquals("[The AFK Spot] <col=00ff00>Bob</col> has reached a total level of 100.",
			f.paintLead("[The AFK Spot] Bob has reached a total level of 100.", null, false));
		assertEquals("Bob has reached a total level of 100.", ChatFlair.key("<col=0000ff>[The AFK Spot]</col> <img=1>Bob has reached a total level of 100."));
	}

	@Test
	public void hoverStillFindsAPaintedPrivateMessageSender()
	{
		ChatFlair f = with(entry("drenvox", "#00ff00", true));
		assertEquals("Anvil Patron", f.tierOfSender(f.paintSender("From Drenvox:", "<img=99>", false)));
		assertEquals("Anvil Patron", f.tierOfSender(f.paintSender("To Drenvox:", "<img=99>", false)));
	}

	// ---- Sender columns as the chatbox really writes them

	private static ChatFlair drenvox()
	{
		return with(entry("gim drenvox", "#00ff00", true), entry("bob", "#ffd35c", null));
	}

	@Test
	public void clanSenderWithChannelColoursAndIconsIsPainted()
	{
		ChatFlair f = drenvox();
		String star = "<img=99>";
		String name = "<col=00ff00>GIM\u00A0Drenvox</col>";
		// Clan chat: channel in its own colour, rank key + HCGIM icons, then the name.
		assertEquals("<col=0000ff>[The AFK Spot]</col> <img=48><img=10>" + star + name + ":",
			f.paintSender("<col=0000ff>[The AFK Spot]</col> <img=48><img=10>GIM\u00A0Drenvox:", star, false));
		// Brackets in the default colour, the channel name coloured inside them.
		assertEquals("[<col=0000ff>The AFK Spot</col>] <img=10>" + star + name + ":",
			f.paintSender("[<col=0000ff>The AFK Spot</col>] <img=10>GIM\u00A0Drenvox:", star, false));
		// Guest clan / GIM group chat / friends chat: same shape, other channels.
		assertEquals("<col=7f0000>[Guests]</col> <img=10>" + star + name + ":",
			f.paintSender("<col=7f0000>[Guests]</col> <img=10>GIM\u00A0Drenvox:", star, false));
		assertEquals("<col=7f0000>[Iron Pals]</col> <img=10>" + star + name + ":",
			f.paintSender("<col=7f0000>[Iron Pals]</col> <img=10>GIM\u00A0Drenvox:", star, false));
		assertEquals("[<col=0000ff>Fc Name</col>] <img=2><col=ffd35c>Bob</col>:", f.paintSender("[<col=0000ff>Fc Name</col>] <img=2>Bob:", star, false));
		// A username colour from RuneLite's chat colours: it re-opens after ours.
		assertEquals("[Clan] <col=ff00ff><img=10>" + star + name + "<col=ff00ff>:</col>",
			f.paintSender("[Clan] <col=ff00ff><img=10>GIM\u00A0Drenvox:</col>", star, false));
	}

	@Test
	public void timestampsOnOrOffAndPublicWithIcons()
	{
		ChatFlair f = drenvox();
		assertEquals("<img=2><col=ffd35c>Bob</col>:", f.paintSender("<img=2>Bob:", null, false));
		assertEquals("[12:34] <img=2><col=ffd35c>Bob</col>:", f.paintSender("[12:34] <img=2>Bob:", null, false));
		assertEquals("<col=000000>[12:34]</col> <col=0000ff>[The AFK Spot]</col> <img=10><col=00ff00>GIM Drenvox</col>:",
			f.paintSender("<col=000000>[12:34]</col> <col=0000ff>[The AFK Spot]</col> <img=10>GIM Drenvox:", null, false));
		// Trailing space after the colon still a column.
		assertEquals("<col=ffd35c>Bob</col>: ", f.paintSender("Bob: ", null, false));
		assertEquals(true, ChatFlair.column("<img=2><col=ffd35c>Bob</col>:"));
	}

	@Test
	public void typedTextIsNeverASender()
	{
		ChatFlair f = drenvox();
		// Anyone can type a channel prefix: "[Clan] Drenvox: …" in a message widget is not painted.
		assertNull(f.paintSender("[Clan] GIM Drenvox: hi", null, false));
		assertNull(f.paintSender("<col=0000ff>[The AFK Spot]</col> <img=10>GIM Drenvox: <col=0000ff>Test</col>", null, false));
		assertEquals(false, ChatFlair.column("[The AFK Spot] GIM Drenvox: Test"));
		assertNull(f.paintSender("Bob: hi there", null, false));
		assertNull("not a name: too long", f.paintSender("[Clan] Somebody With A Long Title:", null, false));
		assertNull("Bobby is not Bob", f.paintSender("[Clan] <img=10>Bobby:", null, false));
	}

	/** One chatbox row as widgets: x, y and text per index (null text = hidden). */
	private static boolean column(int i, int[] x, int[] y, String[] t)
	{
		return ChatFlair.senderColumn(i, t.length, j -> x[j], j -> y[j], j -> t[j]);
	}

	private static boolean typed(int i, int[] x, int[] y, String[] t)
	{
		return ChatFlair.typedBySomeone(i, j -> x[j], j -> y[j], j -> t[j]);
	}

	@Test
	public void theLayoutNotTheTextSaysWhichWidgetIsTheSender()
	{
		// Row 0: sender "Mallory:" then her typed "[Clan] Bob:"; row 14: a real clan sender and its message.
		int[] x = {0, 48, 0, 120};
		int[] y = {0, 0, 14, 14};
		String[] t = {"Mallory:", "[Clan] Bob:", "[The AFK Spot] <img=10>Bob:", "hi"};
		assertEquals(true, column(0, x, y, t));
		assertEquals("her typed text is the row's last widget", false, column(1, x, y, t));
		assertEquals(true, column(2, x, y, t));
		assertEquals(false, column(3, x, y, t));
		// A hidden widget beside it doesn't make a sender.
		assertEquals(false, column(0, x, y, new String[]{"Mallory:", null, "x", "y"}));
	}

	@Test
	public void aTypedBroadcastLookalikeIsNotPainted()
	{
		// Mallory types "Bob has completed a quest: …" in public chat: a player's sender column is on its left.
		int[] x = {0, 48, 0, 90};
		int[] y = {0, 0, 14, 14};
		String[] t = {"Mallory:", "Bob has completed a quest: Gertrude's Cat", "[The AFK Spot]", "Bob has completed a quest: Gertrude's Cat"};
		assertEquals(true, typed(1, x, y, t));
		// The real broadcast: only the channel to its left.
		assertEquals(false, typed(3, x, y, t));
		// And with no channel widget at all.
		assertEquals(false, typed(0, new int[]{0}, new int[]{0}, new String[]{"Bob has completed a quest: Gertrude's Cat"}));
		// A text that was never a real broadcast has no key in the set; typed text isn't added by anyone.
		assertNull(drenvox().leadKey("Mallory has completed a quest: Gertrude's Cat"));
	}

	@Test
	public void widthCountsIconsAndTheStarButNotColours()
	{
		java.util.function.ToIntFunction<String> six = t -> 6 * t.length();
		java.util.function.IntUnaryOperator icons = n -> n == 99 ? 11 : 13;
		assertEquals(6 * 4, ChatFlair.measure("<col=ff0000>Bob:</col>", six, icons));
		assertEquals(13 + 13 + 11 + 6 * 4, ChatFlair.measure("<img=48><img=10><img=99><col=00ff00>Bob</col>:", six, icons));
		assertEquals(0, ChatFlair.measure("<col=ff0000></col>", six, icons));
		// The room a Patron's star needs in a sender column: just the star.
		ChatFlair f = drenvox();
		java.util.function.ToIntFunction<String> none = t -> 0;
		String plain = "[Clan] <img=10>GIM Drenvox:";
		assertEquals(11, ChatFlair.measure(f.paintSender(plain, "<img=99>", false), none, icons) - ChatFlair.measure(plain, none, icons));
	}

	@Test
	public void hoverZoneStartsAtTheNameAfterIconsAndStar()
	{
		ChatFlair f = drenvox();
		java.util.function.IntUnaryOperator icons = n -> n == 99 ? 11 : 13;
		java.util.function.ToIntFunction<String> w = raw -> ChatFlair.measure(raw, t -> 6 * t.length(), icons);
		String line = f.paintLead("<img=10><col=ef1020>GIM Drenvox has reached a total level of 100.</col>", "<img=99>", false);
		int nameAt = 13 + 11; // HCGIM icon, then the star
		assertEquals("Anvil Patron", f.tierInLine(line, nameAt + 1, w));
		assertEquals("Anvil Patron", f.tierInLine(line, nameAt + 11 * 6 - 1, w));
		assertNull("over the star", f.tierInLine(line, nameAt - 2, w));
		assertNull("after the name", f.tierInLine(line, nameAt + 11 * 6 + 1, w));
	}

	// ---- Split private chat: sender and message are separate widgets laid out for the plain name

	@Test
	public void theStarGetsRoomOnTheSplitPmLineOnce()
	{
		final int min = Integer.MIN_VALUE;
		// Row y=0: "From <helm>GIM Drenvox:" at x 0, w 100; "Test" at x 100. Row y=14: another line.
		int[] ax = {0, 100, 0, 60};
		int[] ay = {0, 0, 14, 14};
		int[] x = {0, 100, 0, 60};
		int[] w = {100, 300, 60, 340};
		boolean[] shown = {true, true, true, true};
		int[] recX = {min, min, min, min};
		int[] recW = {min, min, min, min};
		assertEquals(true, ChatFlair.makeRoom(0, 11, 4, ax, ay, x, w, shown, recX, recW));
		assertEquals(111, w[0]);
		assertEquals("the message moves over by the star", 111, x[1]);
		assertEquals(289, w[1]);
		assertEquals("the next line is left alone", 60, x[3]);
		// Next frame, nothing laid out again: not moved twice.
		assertEquals(false, ChatFlair.makeRoom(0, 11, 4, ax, ay, x, w, shown, recX, recW));
		assertEquals(111, x[1]);
		// The game lays the line out afresh (plain positions back): room made again.
		x[1] = 100;
		w[0] = 100;
		w[1] = 300;
		assertEquals(true, ChatFlair.makeRoom(0, 11, 4, ax, ay, x, w, shown, recX, recW));
		assertEquals(111, x[1]);
		assertEquals(111, w[0]);
		// No star (gradient / colour only): nothing to make room for.
		assertEquals(false, ChatFlair.makeRoom(2, 0, 4, ax, ay, x, w, shown, recX, recW));
	}

	@Test
	public void splitPmSenderIsFoundByLayout()
	{
		int[] ax = {0, 100, 0};
		int[] ay = {0, 0, 14};
		boolean[] shown = {true, true, true};
		assertEquals("a sender column has its message to the right", true, ChatFlair.beside(0, 3, ax, ay, shown, false));
		assertEquals("a typed \"From Bob:\" is the message: nothing to its right", false, ChatFlair.beside(1, 3, ax, ay, shown, false));
		assertEquals("…and a sender to its left", true, ChatFlair.beside(1, 3, ax, ay, shown, true));
		assertEquals("a whole line alone on its row", false, ChatFlair.beside(2, 3, ax, ay, shown, true));
		assertEquals(false, ChatFlair.beside(0, 3, ax, ay, new boolean[]{true, false, true}, false));
		// The star's room, from the widths of the icons only.
		ChatFlair f = drenvox();
		java.util.function.IntUnaryOperator icons = k -> k == 99 ? 11 : 13;
		String plain = "From <img=10>GIM Drenvox:";
		assertEquals(11, ChatFlair.measure(f.paintPmLine(plain, "<img=99>", false), t -> 0, icons) - ChatFlair.measure(plain, t -> 0, icons));
	}

	// ---- Group ironman side panel members

	@Test
	public void gimMemberNamesArePaintedKeepingIconsAndTheGroupStar()
	{
		ChatFlair f = drenvox();
		assertEquals("<img=48><col=00ff00>GIM\u00A0Drenvox</col><img=60>", f.paintListName("<img=48>GIM\u00A0Drenvox<img=60>"));
		assertEquals("no Patron star in a list", "<col=ffd35c>Bob</col>", f.paintListName("Bob"));
		assertNull(f.paintListName(f.paintListName("Bob")));
		assertNull("not a supporter", f.paintListName("GIM Minj"));
		assertNull("Bobby is not Bob", f.paintListName("Bobby"));
	}

	@Test
	public void gimMemberIsPaintedOnlyWhenTheRowShowsAWorld()
	{
		// Row 0: name, world "W308"; row 20: name, "---"; the world widgets have nothing to their right.
		int[] x = {20, 140, 20, 140};
		int[] y = {0, 1, 20, 20};
		String[] t = {"GIM Drenvox", "<col=ffff00>W308</col>", "Bob", "---"};
		assertEquals(true, ChatFlair.onWorldBeside(0, 4, j -> x[j], j -> y[j], j -> t[j]));
		assertEquals("offline", false, ChatFlair.onWorldBeside(2, 4, j -> x[j], j -> y[j], j -> t[j]));
		assertEquals("the world column is not a name", false, ChatFlair.onWorldBeside(1, 4, j -> x[j], j -> y[j], j -> t[j]));
		assertEquals(true, ChatFlair.onWorldBeside(0, 2, j -> x[j], j -> y[j], j -> new String[]{"Bob", "World 302"}[j]));
	}

	// ---- Right-click names after Player Indicators

	@Test
	public void menuTargetGetsOurGradientBackAfterAFriendGreenPrefix()
	{
		ChatFlair f = with(entry("denoverse", "#ff7ab8", true, "#ffd27a", "#ff7ab8", "#7ae0ff"));
		String ours = f.decorate("Denoverse", null);
		String game = "<col=ffffff>Denoverse<col=ff00ff>  (level-71)";
		String painted = f.paintMenuTarget(game, "Denoverse", false);
		assertEquals(ours + "<col=ff00ff>  (level-71)", painted);
		assertNull("ours already: nothing to do", f.paintMenuTarget(painted, "Denoverse", true));
		// Player Indicators: drop the first tag, put friend green (and a rank icon) in front.
		String pi = "<img=5><col=00ff00>" + painted.substring(painted.indexOf('>') + 1);
		assertEquals("<img=5>" + ours + "<col=ff00ff>  (level-71)", f.paintMenuTarget(pi, "Denoverse", true));
		assertNull("override off: their colour stays", f.paintMenuTarget(pi, "Denoverse", false));
		// Their colour on the plain name: same rule.
		assertNull(f.paintMenuTarget("<col=00ff00>Denoverse<col=ff00ff>  (level-71)", "Denoverse", false));
		assertEquals(ours + "<col=ff00ff>  (level-71)", f.paintMenuTarget("<col=00ff00>Denoverse<col=ff00ff>  (level-71)", "Denoverse", true));
		// Tick after tick (they strip, we repaint) the target stays the same length.
		String again = f.paintMenuTarget(pi, "Denoverse", true);
		for (int n = 0; n < 5; n++)
		{
			String next = f.paintMenuTarget("<img=5><col=00ff00>" + again.substring(again.indexOf("<col=") + again.substring(again.indexOf("<col=")).indexOf('>') + 1), "Denoverse", true);
			assertEquals(again, next);
		}
		assertNull("not a supporter", f.paintMenuTarget("<col=ffffff>Someone<col=ff00ff>  (level-3)", "Someone", true));
	}
}
