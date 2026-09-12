package com.anvil.api.dto;

import java.util.List;

public class TrackedGain
{
	public int tileId;
	public int position;   // board position — the list mirrors the site's tile order (0 on old servers)
	public String label;
	public String description;
	public int points;
	public String category;
	public List<Integer> itemIds;      // pool — any of these appearing in the inventory counts
	public int requiredAmount;         // total gains needed across the pool
	public int currentAmount;          // team's submitted gains so far
	public boolean completed;          // team already completed this tile
}
