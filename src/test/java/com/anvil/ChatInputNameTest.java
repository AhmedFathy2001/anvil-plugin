package com.anvil;

import java.util.regex.Matcher;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The chat input's own name is found in every channel the game can be typing to. */
public class ChatInputNameTest
{
	private static Matcher match(String text)
	{
		Matcher m = AnvilPlugin.inputNamePattern("dead person").matcher(text);
		assertTrue(text, m.find());
		return m;
	}

	@Test
	public void publicChat()
	{
		Matcher m = match("<img=2>dead person: Press Enter to Chat...");
		assertEquals("<img=2>", m.group(1));
		assertEquals("", m.group(2));
	}

	@Test
	public void channelSuffixesAreKept()
	{
		assertEquals(" (clan)", match("dead person (clan): <col=0000ff>*</col>").group(2));
		assertEquals(" (channel)", match("dead person (channel): Press Enter to Chat...").group(2));
		assertEquals(" <col=7f0000>(guest clan)</col>", match("dead person <col=7f0000>(guest clan)</col>: hi").group(2));
	}

	@Test
	public void anotherNameIsNotOurs()
	{
		assertFalse(AnvilPlugin.inputNamePattern("dead person").matcher("dead personal: hi").find());
		assertFalse(AnvilPlugin.inputNamePattern("dead person").matcher("dead person2 (clan): hi").find());
	}
}
