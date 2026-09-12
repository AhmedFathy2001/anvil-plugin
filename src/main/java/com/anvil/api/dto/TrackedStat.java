package com.anvil.api.dto;

import java.util.List;

public class TrackedStat
{
	public int tileId;
	public int position;   // board position — the list mirrors the site's tile order (0 on old servers)
	public String label;
	public String description;   // tile description, for the clog task accordion
	public int points;           // Leagues-style reward value (0 = not a points event)
	public String category;      // free-text grouping (boss/skill) for the clog task filter
	public String statName;     // e.g. "mining", "zulrah"
	public String statType;     // "skill" | "boss" | "kc"
	public int itemId = -1;     // boss KC tiles: the boss's representative clog item (icon); -1 for skills
	public String trackingMode; // "team" | "individual"
	public int currentAmount;   // gained XP / KC since baseline
	public int goalAmount;
	// Teammates (RSNs) actively grinding this stat tile right now, for the sidebar's "Active now".
	// The caller is never included (the plugin marks itself "You"). null on older servers that don't
	// compute it (the sidebar then falls back to an unnamed "a teammate" via config-count deltas);
	// an empty list means the server DID compute it and no teammate is currently active.
	public List<String> activeWorkers;
}
