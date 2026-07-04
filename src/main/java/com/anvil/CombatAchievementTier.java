package com.anvil;

import net.runelite.api.gameval.VarbitID;

/**
 * The six Combat Achievement tiers, with the point value awarded for a task at that tier and the
 * varbit holding the cumulative points needed to unlock the tier's rewards. Used to detect both
 * individual task completions (chat message gives the tier name) and tier unlocks (a task pushing
 * total points across a threshold).
 */
public enum CombatAchievementTier
{
	EASY("Easy", 1, VarbitID.CA_THRESHOLD_EASY),
	MEDIUM("Medium", 2, VarbitID.CA_THRESHOLD_MEDIUM),
	HARD("Hard", 3, VarbitID.CA_THRESHOLD_HARD),
	ELITE("Elite", 4, VarbitID.CA_THRESHOLD_ELITE),
	MASTER("Master", 5, VarbitID.CA_THRESHOLD_MASTER),
	GRANDMASTER("Grandmaster", 6, VarbitID.CA_THRESHOLD_GRANDMASTER);

	private final String displayName;
	private final int points;
	private final int thresholdVarbitId;

	CombatAchievementTier(String displayName, int points, int thresholdVarbitId)
	{
		this.displayName = displayName;
		this.points = points;
		this.thresholdVarbitId = thresholdVarbitId;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	public int getPoints()
	{
		return points;
	}

	public int getThresholdVarbitId()
	{
		return thresholdVarbitId;
	}

	@Override
	public String toString()
	{
		return displayName;
	}

	/** Resolves the tier from the word used in the completion message ("easy", "elite", …). */
	public static CombatAchievementTier byName(String name)
	{
		if (name == null)
		{
			return null;
		}
		for (CombatAchievementTier t : values())
		{
			if (t.displayName.equalsIgnoreCase(name) || t.name().equalsIgnoreCase(name))
			{
				return t;
			}
		}
		return null;
	}
}
