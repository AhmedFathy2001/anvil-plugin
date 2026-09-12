package com.anvil.api.dto;

import java.util.List;

// Achievement-diary tile: the plugin credits a completion when the in-game diary completion
// line matches one of the selectors — "<Area> <Tier>" strings with "Any" as a wildcard on
// either side ("Ardougne Elite", "Any Elite", "Wilderness Any"). Same submission flow as kill.
public class TrackedDiary
{
	public int tileId;
	public int position;   // board position — the list mirrors the site's tile order (0 on old servers)
	public String label;
	public String description;
	public int points;
	public String category;
	public List<String> diaries;      // selectors — any matching one counts a completion
	public int requiredAmount;
	public int currentAmount;
	public String trackingMode;        // "team" | "individual"/"solo"
}
