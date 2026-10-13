package com.anvil;

/**
 * The lowest quest difficulty posted to the clan achievements channel, or OFF for none. A quest's
 * difficulty comes from {@code questTiers} in anvil_text.json; a quest missing there (the wiki's
 * "Special" ones, such as most of Recipe for Disaster) posts only on ALL. ALL keeps its old name
 * so a saved "All quests" setting still loads; MASTER and GRANDMASTER likewise.
 */
public enum QuestAnnounceTier
{
	ALL("All (Novice & up)"),
	INTERMEDIATE("Intermediate & up"),
	EXPERIENCED("Experienced & up"),
	MASTER("Master & up"),
	GRANDMASTER("Grandmaster only"),
	OFF("Off");

	private final String label;

	QuestAnnounceTier(String label)
	{
		this.label = label;
	}

	/**
	 * Whether a quest of {@code difficulty} ("NOVICE" … "GRANDMASTER", or null when unknown) is
	 * posted. Unknown counts as the lowest tier.
	 */
	boolean posts(String difficulty)
	{
		if (this == OFF)
		{
			return false;
		}
		int rank = 0;
		if (difficulty != null)
		{
			switch (difficulty)
			{
				case "INTERMEDIATE": rank = 1; break;
				case "EXPERIENCED": rank = 2; break;
				case "MASTER": rank = 3; break;
				case "GRANDMASTER": rank = 4; break;
				default: rank = 0;
			}
		}
		return rank >= ordinal();
	}

	@Override
	public String toString()
	{
		return label;
	}
}
