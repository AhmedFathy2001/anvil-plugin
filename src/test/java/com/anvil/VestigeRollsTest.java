package com.anvil;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class VestigeRollsTest
{
	private static PluginConfigResponse.RollTable duke()
	{
		PluginConfigResponse.RollTable t = new PluginConfigResponse.RollTable();
		t.boss = "Duke Sucellus";
		// Virtus ×3, chromium ingot, Magus vestige, Eye of the duke.
		t.rollItemIds = Arrays.asList(26241, 26243, 26245, 28276, 28281, 28321);
		t.vestigeItemId = 28281;
		t.vestigeName = "Magus vestige";
		t.rollsPerVestige = 3;
		return t;
	}

	@Test
	public void countsRollsAndCallsTheVestigeOneAhead()
	{
		VestigeRolls v = new VestigeRolls();
		VestigeRolls.Result r1 = v.record(duke(), 28276); // chromium ingot
		assertEquals(1, r1.state.rolls);
		assertFalse(r1.vestigeNext);
		assertTrue(r1.line.contains("Roll 1 of 3"));

		VestigeRolls.Result r2 = v.record(duke(), 26241); // Virtus mask
		assertEquals(2, r2.state.rolls);
		assertTrue("two rolls in, the next unique is the vestige", r2.vestigeNext);
		assertTrue(r2.line.contains("Magus vestige"));
	}

	@Test
	public void theVestigeResetsAndAnchorsTheCycle()
	{
		VestigeRolls v = new VestigeRolls();
		v.record(duke(), 28276);
		VestigeRolls.Result r = v.record(duke(), 28281); // Magus vestige
		assertTrue(r.vestige);
		assertEquals(0, r.state.rolls);
		assertTrue("a seen vestige anchors the cycle — the count is exact from here", r.state.exact);
		assertTrue(r.line.contains("cycle resets"));
	}

	@Test
	public void countsAreEstimatedUntilAVestigeIsSeen()
	{
		VestigeRolls v = new VestigeRolls();
		assertTrue(v.record(duke(), 28276).line.contains("(estimated)"));
		v.record(duke(), 28281); // vestige seen — anchored
		assertFalse(v.record(duke(), 28276).line.contains("estimated"));
	}

	@Test
	public void aNonVestigeWhereTheVestigeWasDueReAnchorsInsteadOfLying()
	{
		// Two rolls in (so we think the vestige is next), then a non-vestige: our count was wrong,
		// which is what happens when drops landed while the plugin wasn't running.
		VestigeRolls.State was = new VestigeRolls.State(2, true);
		VestigeRolls.Result r = VestigeRolls.advance(was, false, 3, "Magus vestige");
		assertEquals(1, r.state.rolls);
		assertFalse("we can't claim exact after being wrong", r.state.exact);
		assertTrue(r.line.contains("re-anchoring"));
	}

	@Test
	public void itemsOutsideTheRollTableAreNotRolls()
	{
		VestigeRolls v = new VestigeRolls();
		assertNull("the pet is its own roll, not a unique-table one", v.record(duke(), 28250));
		assertNull("awakener's orb likewise", v.record(duke(), 28334));
		assertEquals(0, v.get("Duke Sucellus").rolls);
	}

	@Test
	public void eachBossKeepsItsOwnCycle()
	{
		PluginConfigResponse.RollTable vard = new PluginConfigResponse.RollTable();
		vard.boss = "Vardorvis";
		vard.rollItemIds = Arrays.asList(26241, 28276, 28285, 28319);
		vard.vestigeItemId = 28285;
		vard.vestigeName = "Ultor vestige";
		vard.rollsPerVestige = 3;

		VestigeRolls v = new VestigeRolls();
		v.record(duke(), 28276);
		v.record(duke(), 26241);
		v.record(vard, 28276);
		assertEquals(2, v.get("Duke Sucellus").rolls);
		assertEquals(1, v.get("Vardorvis").rolls);
	}

	@Test
	public void stateSurvivesASerialiseRoundTrip()
	{
		VestigeRolls v = new VestigeRolls();
		v.record(duke(), 28276);
		v.record(duke(), 28281); // vestige — exact
		v.record(duke(), 26241);

		VestigeRolls back = VestigeRolls.parse(v.serialise());
		assertEquals(1, back.get("Duke Sucellus").rolls);
		assertTrue(back.get("duke sucellus").exact);
	}

	@Test
	public void aCorruptedConfigValueLosesOnlyThatBoss()
	{
		VestigeRolls back = VestigeRolls.parse("duke sucellus=2:1;vardorvis=notanumber;the whisperer=1:0");
		assertEquals(2, back.get("Duke Sucellus").rolls);
		assertEquals(0, back.get("Vardorvis").rolls);
		assertEquals(1, back.get("The Whisperer").rolls);
	}

	@Test
	public void anEmptyOrAbsentConfigStartsClean()
	{
		assertEquals(0, VestigeRolls.parse(null).get("Duke Sucellus").rolls);
		assertEquals(0, VestigeRolls.parse("").get("Duke Sucellus").rolls);
	}
}
