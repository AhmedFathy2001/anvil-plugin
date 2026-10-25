package com.anvil;

/**
 * The "Announce combat achievements" dropdown: the lowest task tier posted to the clan, or OFF for no
 * posts at all (tier clears included). Each tier is the {@link CombatAchievementTier} of the same name,
 * so a value saved under the old tier-typed setting still loads.
 * Bingo tile credit never looks at this.
 */
public enum CaAnnounceTier
{
	EASY,
	MEDIUM,
	HARD,
	ELITE,
	MASTER,
	GRANDMASTER,
	OFF;

	/** Whether a task of {@code tier} is posted; always false when OFF. */
	boolean posts(CombatAchievementTier tier)
	{
		return this != OFF && tier.compareTo(CombatAchievementTier.valueOf(name())) >= 0;
	}

	@Override
	public String toString()
	{
		return this == OFF ? "Off" : CombatAchievementTier.valueOf(name()).getDisplayName();
	}
}
