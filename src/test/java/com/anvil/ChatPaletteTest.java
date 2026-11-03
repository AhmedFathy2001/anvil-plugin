package com.anvil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class ChatPaletteTest
{
	@Test
	public void anvilColoursByToneButInfoKeepsTheOwnTextColour()
	{
		assertEquals("16c60c", ChatPalette.ANVIL.text(ChatPalette.Tone.DONE, "ffffff"));
		assertEquals("abcdef", ChatPalette.ANVIL.text(ChatPalette.Tone.INFO, "abcdef"));
	}

	@Test
	public void plainUsesTheOwnColourForEverything()
	{
		ChatPalette p = ChatPalette.from("plain", null);
		assertEquals("abcdef", p.text(ChatPalette.Tone.WARN, "abcdef"));
	}

	@Test
	public void customOverridesWhatItNamesAndIgnoresJunk()
	{
		Map<String, String> c = new HashMap<>();
		c.put("drop", "#00FF00");
		c.put("warn", "red");
		c.put("info", "112233");
		ChatPalette p = ChatPalette.from("custom", c);
		assertEquals("00ff00", p.text(ChatPalette.Tone.DROP, "ffffff"));
		assertEquals("ff3b3b", p.text(ChatPalette.Tone.WARN, "ffffff"));
		assertEquals("112233", p.text(ChatPalette.Tone.INFO, "ffffff"));
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
}
