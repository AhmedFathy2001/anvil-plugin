package com.anvil;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class InternalStateKeyTest
{
	@Test
	public void bookkeepingWritesDoNotTriggerARefresh()
	{
		assertTrue(AnvilPlugin.isInternalStateKey("recapCounterMinutes"));
		assertTrue(AnvilPlugin.isInternalStateKey("recapCounterBiggestHit"));
		assertTrue(AnvilPlugin.isInternalStateKey("vestigeRolls:zezima"));
		assertTrue(AnvilPlugin.isInternalStateKey("clogSyncState:zezima"));
	}

	@Test
	public void memberSettingsStillDo()
	{
		assertFalse(AnvilPlugin.isInternalStateKey("playerToken"));
		assertFalse(AnvilPlugin.isInternalStateKey("activeClan"));
		assertFalse(AnvilPlugin.isInternalStateKey("syncClanCoffer"));
		assertFalse(AnvilPlugin.isInternalStateKey(null));
	}
}
