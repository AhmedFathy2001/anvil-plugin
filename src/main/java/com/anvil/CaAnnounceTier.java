package com.anvil;

/**
 * The "Announce combat achievements" dropdown: the lowest task tier posted to the clan, or OFF for no
 * posts at all (tier clears included). The tiers share {@link CombatAchievementTier}'s order and names,
 * so ordinals compare directly and a value saved under the old tier-typed setting still loads.
 * Bingo tile credit never looks at this.
 */
public enum CaAnnounceTier
{
	EASY("Easy"),
	MEDIUM("Medium"),
	HARD("Hard"),
	ELITE("Elite"),
	MASTER("Master"),
	GRANDMASTER("Grandmaster"),
	OFF("Off");

	private final String label;

	CaAnnounceTier(String label)
	{
		this.label = label;
	}

	/** Whether a task of {@code tier} is posted; always false when OFF. */
	boolean posts(CombatAchievementTier tier)
	{
		return this != OFF && tier.ordinal() >= ordinal();
	}

	@Override
	public String toString()
	{
		return label;
	}
}
