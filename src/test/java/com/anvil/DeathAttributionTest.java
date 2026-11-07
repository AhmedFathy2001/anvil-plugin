package com.anvil;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What killed us, as opposed to what we were killing.
 *
 * <p>The feed used to name the last thing we landed a hit on, which produced "died to Abyssal
 * portal", "died to Volatile earth" and "died to Jug" — three things a player attacked and none
 * that killed anybody. A hitsplat carries no attacker, so the inference runs off who had us
 * TARGETED when we last took damage.
 */
public class DeathAttributionTest
{
	private static final long T = 1_000_000L;

	@Test
	public void namesTheThingAttackingUsRatherThanTheThingWeAttacked()
	{
		DeathAttribution d = new DeathAttribution();
		d.targetedUs("The Whisperer", false, T);
		d.tookDamage(T + 100);
		// We spent the fight hitting a mechanic. It did not kill us.
		assertEquals("The Whisperer", d.killer("Volatile earth", T + 200));
	}

	@Test
	public void aMutualFightWinsOverAnythingElseOnUs()
	{
		DeathAttribution d = new DeathAttribution();
		d.targetedUs("Tekton", false, T);
		d.targetedUs("Abyssal portal", false, T + 50); // more recent, but not who we were fighting
		d.tookDamage(T + 100);
		assertEquals("Tekton", d.killer("Tekton", T + 200));
	}

	@Test
	public void otherwiseTheThingThatJustTurnedOnUs()
	{
		DeathAttribution d = new DeathAttribution();
		d.targetedUs("Guardian", false, T);
		d.targetedUs("Vasa Nistirio", false, T + 500);
		d.tookDamage(T + 600);
		// We were hitting neither; the newest acquisition is the one that finished it.
		assertEquals("Vasa Nistirio", d.killer("Muttadile", T + 700));
	}

	@Test
	public void reAcquiringUsRefreshesTheClaim()
	{
		DeathAttribution d = new DeathAttribution();
		d.targetedUs("Nylocas Ischyros", false, T);
		d.targetedUs("Nylocas Toxobolos", false, T + 100);
		d.targetedUs("Nylocas Ischyros", false, T + 900); // switched back onto us
		d.tookDamage(T + 950);
		assertEquals("Nylocas Ischyros", d.killer(null, T + 1_000));
	}

	@Test
	public void somethingThatStoppedAttackingUsIsNotASuspect()
	{
		DeathAttribution d = new DeathAttribution();
		d.targetedUs("Zulrah", false, T);
		d.tookDamage(T + 100);
		d.stoppedTargetingUs("Zulrah");
		assertNull(d.killer("Zulrah", T + 200));
	}

	@Test
	public void nothingOnUsIsAnsweredWithNothing()
	{
		// A mechanic, a fall, poison after the fight. Naming what we were hitting is exactly the bug.
		DeathAttribution d = new DeathAttribution();
		d.tookDamage(T);
		assertNull(d.killer("Jug", T + 100));
	}

	@Test
	public void damageTooOldToBeThisDeathNamesNobody()
	{
		DeathAttribution d = new DeathAttribution();
		d.targetedUs("Vorkath", false, T);
		d.tookDamage(T);
		assertNull(d.killer("Vorkath", T + 60_000));
	}

	@Test
	public void neverHavingTakenDamageNamesNobody()
	{
		DeathAttribution d = new DeathAttribution();
		d.targetedUs("Vorkath", false, T);
		assertNull(d.killer("Vorkath", T + 100));
	}

	@Test
	public void clearingEndsEveryClaim()
	{
		DeathAttribution d = new DeathAttribution();
		d.targetedUs("Vorkath", false, T);
		d.tookDamage(T);
		d.clear();
		assertEquals(0, d.attackerCount());
		assertNull(d.killer("Vorkath", T + 100));
	}

	@Test
	public void theAttackerListCannotGrowWithoutBound()
	{
		DeathAttribution d = new DeathAttribution();
		for (int i = 0; i < 200; i++)
		{
			d.targetedUs("Attacker " + i, false, T + i);
		}
		// Capped, and it is the OLDEST that get dropped — the newest arrivals are the suspects.
		assertEquals(24, d.attackerCount());
		d.tookDamage(T + 500);
		assertEquals("Attacker 199", d.killer(null, T + 600));
	}

	@Test
	public void blankNamesAreIgnoredRatherThanStored()
	{
		DeathAttribution d = new DeathAttribution();
		d.targetedUs(null, false, T);
		d.targetedUs("", false, T);
		d.tookDamage(T);
		assertEquals(0, d.attackerCount());
		assertNull(d.killer(null, T + 100));
	}

	@Test
	public void aPlayerKillerIsMarkedAsAPlayer()
	{
		DeathAttribution d = new DeathAttribution();
		d.targetedUs("Zezima", true, T);
		d.targetedUs("Revenant knight", false, T + 10);
		d.tookDamage(T + 20);
		assertEquals("Zezima", d.killer("Zezima", T + 30));
		assertTrue(d.isPlayer("Zezima"));
		assertFalse(d.isPlayer("Revenant knight"));
		d.stoppedTargetingUs("Zezima");
		assertFalse(d.isPlayer("Zezima"));
		d.targetedUs("Zezima", true, T + 40);
		d.clear();
		assertFalse(d.isPlayer("Zezima"));
		assertFalse(d.isPlayer(null));
	}
}
