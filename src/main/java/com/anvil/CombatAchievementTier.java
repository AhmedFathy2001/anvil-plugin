package com.anvil;

import lombok.RequiredArgsConstructor;
import java.util.Locale;
import net.runelite.api.gameval.VarbitID;

/**
 * The six Combat Achievement tiers, with the point value awarded for a task at that tier and the
 * varbit holding the cumulative points needed to unlock the tier's rewards. Used to detect both
 * individual task completions (chat message gives the tier name) and tier unlocks (a task pushing
 * total points across a threshold).
 */
@RequiredArgsConstructor
public enum CombatAchievementTier
{
	EASY(VarbitID.CA_THRESHOLD_EASY),
	MEDIUM(VarbitID.CA_THRESHOLD_MEDIUM),
	HARD(VarbitID.CA_THRESHOLD_HARD),
	ELITE(VarbitID.CA_THRESHOLD_ELITE),
	MASTER(VarbitID.CA_THRESHOLD_MASTER),
	GRANDMASTER(VarbitID.CA_THRESHOLD_GRANDMASTER);

	private final int thresholdVarbitId;


	/** "Easy" … "Grandmaster". */
	public String getDisplayName()
	{
		return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
	}

	public int getThresholdVarbitId()
	{
		return thresholdVarbitId;
	}

	@Override
	public String toString()
	{
		return getDisplayName();
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
			if (t.name().equalsIgnoreCase(name))
			{
				return t;
			}
		}
		return null;
	}
}
