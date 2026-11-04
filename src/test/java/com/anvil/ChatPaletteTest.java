package com.anvil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class ChatPaletteTest
{
	@Test
	public void anvilColoursByToneButInfoKeepsTheOwnTextColour()
	{
		assertEquals("16c60c", ChatPalette.ANVIL.text(ChatPalette.Tone.DONE, "ffffff", false));
		assertEquals("abcdef", ChatPalette.ANVIL.text(ChatPalette.Tone.INFO, "abcdef", false));
	}

	@Test
	public void plainUsesTheOwnColourForEverything()
	{
		ChatPalette p = ChatPalette.from("plain", null);
		assertEquals("abcdef", p.text(ChatPalette.Tone.WARN, "abcdef", false));
	}

	@Test
	public void customOverridesWhatItNamesAndIgnoresJunk()
	{
		Map<String, String> c = new HashMap<>();
		c.put("drop", "#00FF00");
		c.put("warn", "red");
		c.put("info", "112233");
		ChatPalette p = ChatPalette.from("custom", c);
		assertEquals("00ff00", p.text(ChatPalette.Tone.DROP, "ffffff", false));
		assertEquals("ff3b3b", p.text(ChatPalette.Tone.WARN, "ffffff", false));
		assertEquals("112233", p.text(ChatPalette.Tone.INFO, "ffffff", false));
		assertEquals(ChatPalette.Tone.INFO, ChatPalette.Tone.of("nonsense"));
		assertNull(ChatPalette.nameColor("<col=ff>"));
		assertEquals("ff7ab8", ChatPalette.nameColor("#FF7AB8"));
	}

	@Test
	public void gradientNameColoursEachLetterAcrossTheStops()
	{
		java.util.List<String> two = java.util.Arrays.asList("#ff0000", "#0000ff");
		assertEquals("<col=ff0000>A</col><col=800080>b</col><col=0000ff>c</col>", ChatPalette.gradientName("Abc", two));
		// Spaces take no colour of their own; the ends still land on the first and last stops.
		assertEquals("<col=ff0000>A </col><col=0000ff>c</col>", ChatPalette.gradientName("A c", two));
		assertNull(ChatPalette.gradientName("Abc", java.util.Arrays.asList("#ff0000", "red")));
		assertNull(ChatPalette.gradientName("", two));
	}

	@Test
	public void theOpaqueChatboxGetsDarkInksButACustomPaletteStaysTheirs()
	{
		assertEquals("006a00", ChatPalette.ANVIL.text(ChatPalette.Tone.DONE, "000000", true));
		assertEquals("a03c00", ChatPalette.ANVIL.text(ChatPalette.Tone.DROP, "000000", true));
		assertEquals("info keeps the player's own text colour", "000000", ChatPalette.ANVIL.text(ChatPalette.Tone.INFO, "000000", true));
		java.util.Map<String, String> mine = new java.util.HashMap<>();
		mine.put("done", "#00ff00");
		ChatPalette custom = ChatPalette.from("custom", mine);
		assertEquals("00ff00", custom.text(ChatPalette.Tone.DONE, "000000", true));
	}

	@Test
	public void everyColourInALineIsMadeReadableOnItsChatbox()
	{
		// Already readable: untouched.
		assertEquals("006a00", ChatPalette.readableHex("006a00", true));
		assertEquals("ffd700", ChatPalette.readableHex("ffd700", false));
		// Pale on parchment gets darker; dark on the transparent box gets lighter — only as far as needed.
		for (String pale : new String[]{"ffd27a", "7ae0ff", "ffffff", "ff7ab8"})
		{
			String fixed = ChatPalette.readableHex(pale, true);
			assertTrue(pale + " -> " + fixed, ChatPalette.contrast(Integer.parseInt(fixed, 16), 0xC4B48C) >= ChatPalette.MIN_CONTRAST);
		}
		String dark = ChatPalette.readableHex("1a1a6e", false);
		assertTrue(ChatPalette.contrast(Integer.parseInt(dark, 16), 0x262626) >= ChatPalette.MIN_CONTRAST);
		// The whole line: every <col> rewritten, the text left alone.
		String line = ChatPalette.readable("<col=ffd27a>[Anvil]</col> <col=ff7ab8>W</col><col=7ae0ff>y</col> got a <col=006a00>pet</col>", true);
		assertTrue(line.contains("<col=006a00>pet</col>"));
		assertTrue(line.contains("[Anvil]") && line.contains(">W<") && line.contains(">y<"));
		assertFalse(line.contains("ffd27a"));
	}

	@Test
	public void aSupportersSenderColumnNamesTheirTier()
	{
		ChatFlair f = new ChatFlair();
		ChatFlair.Response r = new ChatFlair.Response();
		ChatFlair.Entry patron = new ChatFlair.Entry();
		patron.n = "drenvox";
		patron.c = "#ff7ab8";
		patron.g = java.util.Arrays.asList("#ffd27a", "#ff7ab8");
		patron.s = true;
		ChatFlair.Entry supp = new ChatFlair.Entry();
		supp.n = "rikkert";
		supp.c = "#ffd35c";
		r.names = java.util.Arrays.asList(patron, supp);
		f.adopt(r);
		assertEquals("Anvil Patron", f.tierOfSender("[02:25] <img=12><col=ffd27a>D</col><col=ff7ab8>renvox</col>:"));
		assertEquals("Anvil Supporter", f.tierOfSender("Rikkert:"));
		assertNull("a message, not a sender", f.tierOfSender("Drenvox is great"));
		assertNull(f.tierOfSender("Somebody:"));
	}
}
