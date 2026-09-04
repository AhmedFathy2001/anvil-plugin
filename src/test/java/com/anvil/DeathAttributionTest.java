package com.anvil;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
		d.targetedUs("The Whisperer", T);
		d.tookDamage(T + 100);
		// We spent the fight hitting a mechanic. It did not kill us.
		assertEquals("The Whisperer", d.killer("Volatile earth", T + 200));
	}

	@Test
	public void aMutualFightWinsOverAnythingElseOnUs()
	{
		DeathAttribution d = new DeathAttribution();
		d.targetedUs("Tekton", T);
		d.targetedUs("Abyssal portal", T + 50); // more recent, but not who we were fighting
		d.tookDamage(T + 100);
		assertEquals("Tekton", d.killer("Tekton", T + 200));
	}

	@Test
	public void otherwiseTheThingThatJustTurnedOnUs()
	{
		DeathAttribution d = new DeathAttribution();
		d.targetedUs("Guardian", T);
		d.targetedUs("Vasa Nistirio", T + 500);
		d.tookDamage(T + 600);
		// We were hitting neither; the newest acquisition is the one that finished it.
		assertEquals("Vasa Nistirio", d.killer("Muttadile", T + 700));
	}

	@Test
	public void reAcquiringUsRefreshesTheClaim()
	{
		DeathAttribution d = new DeathAttribution();
		d.targetedUs("Nylocas Ischyros", T);
		d.targetedUs("Nylocas Toxobolos", T + 100);
		d.targetedUs("Nylocas Ischyros", T + 900); // switched back onto us
		d.tookDamage(T + 950);
		assertEquals("Nylocas Ischyros", d.killer(null, T + 1_000));
	}

	@Test
	public void somethingThatStoppedAttackingUsIsNotASuspect()
	{
		DeathAttribution d = new DeathAttribution();
		d.targetedUs("Zulrah", T);
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
		d.targetedUs("Vorkath", T);
		d.tookDamage(T);
		assertNull(d.killer("Vorkath", T + 60_000));
	}

	@Test
	public void neverHavingTakenDamageNamesNobody()
	{
		DeathAttribution d = new DeathAttribution();
		d.targetedUs("Vorkath", T);
		assertNull(d.killer("Vorkath", T + 100));
	}

	@Test
	public void clearingEndsEveryClaim()
	{
		DeathAttribution d = new DeathAttribution();
		d.targetedUs("Vorkath", T);
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
			d.targetedUs("Attacker " + i, T + i);
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
		d.targetedUs(null, T);
		d.targetedUs("", T);
		d.tookDamage(T);
		assertEquals(0, d.attackerCount());
		assertNull(d.killer(null, T + 100));
	}
}
