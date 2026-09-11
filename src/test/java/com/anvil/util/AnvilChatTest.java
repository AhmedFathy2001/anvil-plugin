package com.anvil.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The chatbox line, held to the byte.
 *
 * <p>These expectations are not derived from {@link AnvilChat} — they are the literal output of the
 * hand-built string it replaced:</p>
 *
 * <pre>
 * String safe = message.replace('|', '¦');
 * "&lt;col=" + CHAT_PREFIX_COLOR + "&gt;[Anvil]&lt;/col&gt; &lt;col=" + CHAT_BODY_COLOR + "&gt;" + safe + "&lt;/col&gt;"
 * </pre>
 *
 * <p>with {@code CHAT_PREFIX_COLOR = "ffd700"} and {@code CHAT_BODY_COLOR = "ffffff"}. If a future
 * change to how the line is built moves so much as a space, this fails — which is the point. Anvil's
 * chat lines are the plugin's most visible output and a member reads them every session.</p>
 */
public class AnvilChatTest
{
	private static final String PREFIX = "<col=ffd700>[Anvil]</col> ";

	@Test
	public void anOrdinaryLineIsTaggedGoldThenWrittenWhite()
	{
		assertEquals(PREFIX + "<col=ffffff>Bingo running: July Madness.</col>",
			AnvilChat.line("Bingo running: July Madness."));
	}

	@Test
	public void aPipeBecomesABrokenBarSoTheChatPipelineStopsEatingTheLine()
	{
		// An event called "The AFK Spot | July Bingo" printed as a bare "July Bingo." before this.
		assertEquals(PREFIX + "<col=ffffff>Bingo running: The AFK Spot ¦ July Bingo.</col>",
			AnvilChat.line("Bingo running: The AFK Spot | July Bingo."));
	}

	@Test
	public void anEmptyMessageStillCarriesTheTag()
	{
		assertEquals(PREFIX + "<col=ffffff></col>", AnvilChat.line(""));
	}

	/**
	 * The body goes through the colour overload, which does NOT Jagex-escape. The plain
	 * {@code append(String)} overload does, and would turn these into brackets — so this test is
	 * really asserting that nobody swaps the overloads while tidying up.
	 */
	@Test
	public void angleBracketsInAnAdminAuthoredNameSurviveUnescaped()
	{
		assertEquals(PREFIX + "<col=ffffff>Tracked as a guest in <Clan></col>",
			AnvilChat.line("Tracked as a guest in <Clan>"));
	}
}
