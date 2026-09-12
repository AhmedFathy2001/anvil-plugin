package com.anvil.api.dto;

import java.util.List;

// Combat Achievement tile: the plugin credits a completion when the in-game "you've completed
// a <tier> combat task" line matches one of the selectors — exact task names ("Whack-a-Mole")
// or "Any <Tier>" wildcards ("Any Master"). Players who already own a task re-fire the line by
// enabling the in-game "Repeat completion" setting. Same submission flow as diary.
public class TrackedCombatTask
{
	public int tileId;
	public int position;   // board position — the list mirrors the site's tile order (0 on old servers)
	public String label;
	public String description;
	public int points;
	public String category;
	public List<String> tasks;        // selectors — any matching one counts a completion
	public int requiredAmount;
	public int currentAmount;
	public String trackingMode;        // "team" | "individual"/"solo"
}
