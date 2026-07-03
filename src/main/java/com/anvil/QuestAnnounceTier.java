package com.anvil;

/**
 * Which quest completions get posted to the clan achievements channel. MASTER
 * covers Master and Grandmaster quests; a quest's tier comes from the baked
 * name sets in AnvilPlugin (game facts, shipped with the plugin like the drop
 * dataset — quests not in either set count as below Master).
 */
public enum QuestAnnounceTier
{
	ALL("All quests"),
	MASTER("Master & up"),
	GRANDMASTER("Grandmaster only"),
	OFF("Off");

	private final String label;

	QuestAnnounceTier(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
