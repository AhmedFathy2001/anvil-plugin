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
	public void aGradientNameIsColouredLetterByLetter()
	{
		org.junit.Assert.assertEquals("<col=ff0000>A<col=800080>b<col=0000ff>c</col>",
			ChatPalette.gradientName("Abc", java.util.Arrays.asList("#ff0000", "#0000ff")));
		org.junit.Assert.assertEquals("<col=ff0000>A <col=0000ff>b</col>",
			ChatPalette.gradientName("A b", java.util.Arrays.asList("ff0000", "0000ff")));
		org.junit.Assert.assertNull("one usable stop: the solid colour instead",
			ChatPalette.gradientName("Abc", java.util.Arrays.asList("#ff0000", "nope")));
		org.junit.Assert.assertNull(ChatPalette.gradientName("Abc", null));
	}
}
