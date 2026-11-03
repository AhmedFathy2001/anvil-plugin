package com.anvil;

import java.util.*;

/**
 * The raid-run gates a timed or deathless tile can add on top of "the raid was completed": the ToA
 * raid level the run ended at, and who was in it. Each returns null when the run passes, else the
 * reason it doesn't, worded for the player's chat box.
 */
final class RaidGates
{
	private RaidGates()
	{
	}

	/** Only ToA has a raid level; a floor on any other raid's tile is ignored rather than failing it. */
	static boolean isToa(String activity)
	{
		return activity != null && activity.toLowerCase(Locale.ROOT).contains("tombs of amascut");
	}

	/**
	 * {@code level} is the raid level the raid ENDED at — read at the chest, because a failed
	 * time-limit invocation only takes its cut once the party reaches the chest room (the caller
	 * holds the run until then). 0 = it couldn't be read.
	 */
	static String raidLevel(String activity, int minLevel, int level)
	{
		if (minLevel <= 0 || !isToa(activity))
		{
			return null;
		}
		if (level <= 0)
		{
			return "couldn't read the raid level";
		}
		return level >= minLevel ? null : "raid level " + level + ", tile requires " + minLevel;
	}

	/**
	 * Every player seen in the raid is on the team. {@code seen} is everyone rendered in the instance
	 * (you included) and {@code partySize} the party the game reports: seeing fewer names than that
	 * means someone was never on screen, so the run can't be vouched for.
	 */
	static String wholePartyFromTeam(Collection<String> seen, Set<String> team, int partySize)
	{
		if (team == null || team.isEmpty())
		{
			return "the team roster isn't loaded";
		}
		int named = 0;
		for (String name : seen)
		{
			String n = Rsn.normalize(name);
			if (n.isEmpty())
			{
				continue;
			}
			if (!team.contains(n))
			{
				return name + " isn't on your team";
			}
			named++;
		}
		if (partySize > named)
		{
			return "only saw " + named + " of the party of " + partySize;
		}
		return null;
	}
}
