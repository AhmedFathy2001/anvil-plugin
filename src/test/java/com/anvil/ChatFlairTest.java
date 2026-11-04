package com.anvil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;

public class ChatFlairTest
{
	private static ChatFlair.Entry entry(String n, String c, Boolean star, String... grad)
	{
		ChatFlair.Entry e = new ChatFlair.Entry();
		e.n = n;
		e.c = c;
		e.s = star;
		e.g = grad.length == 0 ? null : Arrays.asList(grad);
		return e;
	}

	private static ChatFlair with(ChatFlair.Entry... entries)
	{
		ChatFlair f = new ChatFlair();
		ChatFlair.Response r = new ChatFlair.Response();
		r.names = new ArrayList<>(Arrays.asList(entries));
		f.adopt(r);
		return f;
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
}
