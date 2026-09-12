package com.anvil.api.dto;

public class TrackedLms
{
	public int tileId;
	public int position;   // board position — the list mirrors the site's tile order (0 on old servers)
	public String label;
	public String description;
	public int points;
	public String category;
	public int placementCap;           // finish at or under this placement (1 = win)
	public int requiredAmount;         // qualifying games needed to complete the tile
	public int currentAmount;          // team's submitted qualifying games so far
	public boolean completed;          // team already completed this tile
}
