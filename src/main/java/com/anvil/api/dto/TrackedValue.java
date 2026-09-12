package com.anvil.api.dto;

import java.util.List;

public class TrackedValue
{
	public int tileId;
	public int position;   // board position — the list mirrors the site's tile order (0 on old servers)
	public String label;
	public String description;
	public int points;
	public String category;
	public long thresholdGp;           // single: a haul must be worth ≥ this; total: the gp target to reach
	public String mode;                // "single" (one haul ≥ threshold) | "total" (hauls sum to threshold)
	public long currentGp;             // total mode: gp banked so far (the server's sum of submissions); 0 on single

	public List<String> sources;       // optional source filter: NPC/chest names, or "PvP"; empty = any
	public boolean completed;          // team already completed this tile
}
