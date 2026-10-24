package com.anvil;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class RaidGatesTest
{
	@Test
	public void raidLevelIsJudgedOnTheLevelTheRaidEndedAt()
	{
		assertNull(RaidGates.raidLevel("Tombs of Amascut: Expert Mode", 500, 500));
		assertNull(RaidGates.raidLevel("Tombs of Amascut", 500, 530));
		// A 500 that failed Sprint for It ends at 450: not good enough.
		assertNotNull(RaidGates.raidLevel("Tombs of Amascut: Expert Mode", 500, 450));
		// Unreadable is not a pass.
		assertNotNull(RaidGates.raidLevel("Tombs of Amascut", 500, 0));
		// No floor, or a raid with no raid level: nothing to judge.
		assertNull(RaidGates.raidLevel("Tombs of Amascut", 0, 0));
		assertNull(RaidGates.raidLevel("Theatre of Blood", 500, 0));
	}

	@Test
	public void wholePartyMustBeOnTheTeamAndAllBeSeen()
	{
		Set<String> team = new HashSet<>(Arrays.asList("drenvox", "fried silver", "minjoll"));
		assertNull(RaidGates.wholePartyFromTeam(Arrays.asList("Drenvox", "Fried Silver", "Minjoll"), team, 3));
		assertNotNull(RaidGates.wholePartyFromTeam(Arrays.asList("Drenvox", "Fried Silver", "Zezima"), team, 3));
		// Party of 3 but only two ever rendered: can't vouch for the third.
		assertNotNull(RaidGates.wholePartyFromTeam(Arrays.asList("Drenvox", "Fried Silver"), team, 3));
		assertNotNull(RaidGates.wholePartyFromTeam(Arrays.asList("Drenvox"), Collections.emptySet(), 1));
	}
}
