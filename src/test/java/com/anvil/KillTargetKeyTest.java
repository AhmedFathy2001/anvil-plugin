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


}
