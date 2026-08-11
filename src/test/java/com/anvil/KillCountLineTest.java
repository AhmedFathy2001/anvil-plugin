package com.anvil;

import java.util.regex.Matcher;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Verifies the Jagex kill-count chat-line parsing that drives real-time boss-KC pushes, KC-line
 * kill crediting, and the KC shown on rare-drop posts. The captured boss name must exactly match
 * the server's trackedKcNames watch-list (after shared normalization), so counter words that vary
 * by activity ("completion", "chest", "success", …) and the "subdued"/"completed" prefixes must
 * never leak into it — the Gauntlet's "completion count" wording did exactly that and silently
 * killed its real-time tracking.
 */
public class KillCountLineTest
{
	/** Runs a line through the plugin's KC parse: [bossName, count], or null when it doesn't match. */
	private static String[] parse(String line)
	{
		Matcher m = AnvilPlugin.KILL_COUNT_PATTERN.matcher(line);
		if (!m.find())
		{
			return null;
		}
		return new String[]{m.group(1).trim(), m.group(2)};
	}

	@Test
	public void parsesPlainKillCount()
	{
		String[] r = parse("Your Leviathan kill count is: 42.");
		assertEquals("Leviathan", r[0]);
		assertEquals("42", r[1]);
	}

	@Test
	public void parsesRaidCompletedVariant()
	{
		String[] r = parse("Your completed Chambers of Xeric count is: 125.");
		assertEquals("Chambers of Xeric", r[0]);
		assertEquals("125", r[1]);
	}

	@Test
	public void parsesRaidModeVariantWithColon()
	{
		String[] r = parse("Your completed Tombs of Amascut: Expert Mode count is: 8.");
		assertEquals("Tombs of Amascut: Expert Mode", r[0]);
		assertEquals("8", r[1]);
	}

	@Test
	public void completionCounterWordStaysOutOfName()
	{
		String[] r = parse("Your Gauntlet completion count is: 15.");
		assertEquals("Gauntlet", r[0]);
		assertEquals("15", r[1]);
	}

	@Test
	public void corruptedGauntletCompletionVariant()
	{
		String[] r = parse("Your Corrupted Gauntlet completion count is: 3.");
		assertEquals("Corrupted Gauntlet", r[0]);
		assertEquals("3", r[1]);
	}

	@Test
	public void subduedPrefixStaysOutOfName()
	{
		String[] r = parse("Your subdued Wintertodt count is: 200.");
		assertEquals("Wintertodt", r[0]);
		assertEquals("200", r[1]);
	}

	@Test
	public void chestCounterWordStaysOutOfName()
	{
		String[] r = parse("Your Barrows chest count is: 310.");
		assertEquals("Barrows", r[0]);
		assertEquals("310", r[1]);
	}

	@Test
	public void successCounterWordStaysOutOfName()
	{
		String[] r = parse("Your Zalcano success count is: 61.");
		assertEquals("Zalcano", r[0]);
		assertEquals("61", r[1]);
	}

	@Test
	public void parsesThousandsSeparators()
	{
		String[] r = parse("Your Zulrah kill count is: 1,250.");
		assertEquals("Zulrah", r[0]);
		assertEquals("1,250", r[1]);
	}

	@Test
	public void ignoresUnrelatedChat()
	{
		assertNull(parse("Your heart is racing."));
	}
}
