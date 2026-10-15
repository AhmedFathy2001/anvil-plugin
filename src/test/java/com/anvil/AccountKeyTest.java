package com.anvil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

/** RuneLite's ConfigManager throws on any key containing a colon, so a per-account key must never carry one. */
public class AccountKeyTest
{
	@Test
	public void perAccountKeysNeverContainAColon()
	{
		assertEquals("pbImported_drenvox", AnvilPlugin.accountKey("pbImported", "drenvox"));
		assertFalse(AnvilPlugin.accountKey("clogState", "odd:name").contains(":"));
		assertFalse(AnvilPlugin.accountKey("clogState", null).contains(":"));
	}
}
