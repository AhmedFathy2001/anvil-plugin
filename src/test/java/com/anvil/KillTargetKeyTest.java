package com.anvil;

import java.util.regex.Matcher;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Kill tiles are matched by the key DropSource.killTargetKey makes of each target, against the name
 * the kill signal carries (KC line name, loot-event NPC name), lowercased.
 */
public class KillTargetKeyTest
{
	private static String kcName(String line)
	{
		Matcher m = TestPatterns.get("kc.count").matcher(line);
		assertTrue(line, m.find());
		return m.group("name").trim().toLowerCase();
	}

	@Test
	public void awakenedTileMatchesOnlyItsOwnKillCountLine()
	{
		String awakened = DropSource.killTargetKey("Vardorvis (awakened)");
		String normal = DropSource.killTargetKey("Vardorvis");
		// The awakened fight's own KC line (DropTracker pb_lines test sample; colour tags stripped).
		assertEquals(awakened, kcName("Your Vardorvis (awakened) kill count is: 3."));
		assertEquals(DropSource.killTargetKey("Vardorvis (Awakened)"), kcName("Your Vardorvis (Awakened) kill count is: 2."));
		// A normal kill (KC line "Vardorvis", loot event NPC "Vardorvis") no longer reaches it.
		assertEquals(normal, kcName("Your Vardorvis kill count is: 40."));
		assertNotEquals(awakened, normal);
		assertEquals("duke sucellus (awakened)", DropSource.killTargetKey("Duke Sucellus (awakened)"));
	}

	@Test
	public void wikiQualifiersStillDrop()
	{
		assertEquals("armoured zombie", DropSource.killTargetKey("Armoured zombie (Zemouregal's Fort)"));
		assertEquals("cow", DropSource.killTargetKey("  Cow "));
		assertNull(DropSource.killTargetKey(""));
		assertNull(DropSource.killTargetKey(null));
		// Drop-tile source matching is untouched: an awakened SOURCE still matches the plain loot name.
		assertTrue(DropSource.sourceMatches(java.util.Collections.singletonList("Vardorvis (awakened)"), "Vardorvis"));
	}
}
