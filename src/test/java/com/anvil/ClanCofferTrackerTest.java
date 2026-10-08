package com.anvil;

import org.junit.Test;

import static org.junit.Assert.*;

public class ClanCofferTrackerTest
{
	@Test
	public void startsWithAZeroEffectSnapshotThenReportsMovement()
	{
		ClanCofferTracker t = new ClanCofferTracker();
		ClanCofferTracker.Observation first = t.observe(12_000_000, 1_000L);
		assertEquals(ClanCofferTracker.Kind.SNAPSHOT, first.kind);
		assertEquals(first.beforeBalance, first.afterBalance);
		assertNull(t.observe(12_000_000, 2_000L));

		ClanCofferTracker.Observation deposit = t.observe(17_000_000, 3_000L);
		assertEquals(ClanCofferTracker.Kind.DEPOSIT, deposit.kind);
		assertEquals(12_000_000, deposit.beforeBalance);
		assertEquals(17_000_000, deposit.afterBalance);
		assertFalse(deposit.actorConfirmed);
	}

	@Test
	public void matchingSelfChatAttributesOnlyTheNearbyMovement()
	{
		ClanCofferTracker t = new ClanCofferTracker();
		t.observe(10_000_000, 0L);
		t.onChatLine("You have deposited 5,000,000 coins into the Clan Coffer.", "Drenvox mdps", 1_000L);
		assertTrue(t.observe(15_000_000, 2_000L).actorConfirmed);
		assertFalse(t.observe(16_000_000, 3_000L).actorConfirmed);
	}

	@Test
	public void clanDonationWordAndPrefixedSenderAreRecognised()
	{
		ClanCofferTracker t = new ClanCofferTracker();
		t.observe(1, 0L);
		t.onChatLine("Drenvox mdps has donated to the clan coffer.", "Drenvox_mdps", 1_000L);
		assertTrue(t.observe(2, 2_000L).actorConfirmed);
	}

	@Test
	public void otherPlayersAndStaleOrWrongDirectionMessagesNeverAttribute()
	{
		ClanCofferTracker t = new ClanCofferTracker();
		t.observe(10_000_000, 0L);
		t.onChatLine("Other Person has withdrawn 1,000,000 coins from the Clan Coffer.", "Drenvox mdps", 1_000L);
		assertFalse(t.observe(9_000_000, 2_000L).actorConfirmed);

		t.onChatLine("Drenvox mdps has deposited coins into the Clan Coffer.", "Drenvox_mdps", 3_000L);
		assertFalse(t.observe(8_000_000, 4_000L).actorConfirmed);

		t.onChatLine("You have withdrawn coins from the Clan Coffer.", "Drenvox mdps", 5_000L);
		assertFalse(t.observe(7_000_000, 21_000L).actorConfirmed);
	}

	@Test
	public void resetMakesAReopenABaselineAgain()
	{
		ClanCofferTracker t = new ClanCofferTracker();
		t.observe(4, 0L);
		t.reset();
		assertEquals(ClanCofferTracker.Kind.SNAPSHOT, t.observe(9, 1L).kind);
	}
}
