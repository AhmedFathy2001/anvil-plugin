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
}
