package com.anvil.api.dto;

public class TrackedDeathless
{
	public int tileId;
	public int position;   // board position — the list mirrors the site's tile order (0 on old servers)
	public String label;
	public String description;
	public int points;
	public String category;
	public String activity;            // the raid, e.g. "Theatre of Blood"
	public int requiredAmount;         // deathless runs needed
	public int currentAmount;          // team's submitted runs so far
	public int partySize;              // exact party size required; 0/absent = any size
	public int itemId = -1;            // activity's signature reward (icon), -1 = book sprite
	public boolean completed;          // team already completed this tile
}
