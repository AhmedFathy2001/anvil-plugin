package com.anvil.api.dto;

import java.util.List;

// PvP-kill tile: the plugin credits a kill off the "You have defeated <name>!" line, which
// the game sends only to the player it awards the kill (and loot / loot key) to — exactly
// one credit per death. Only dangerous PvP counts (the Wilderness or a PvP world); safe
// minigames (LMS, Soul Wars, Castle Wars, PvP Arena) and DMM never do. The victim must match
// a selector: "any" = any player at all, "team:other" = any member of a rival team (resolved against pvpRoster),
// "rsn:<name>" = a named bounty (need not be in the event). Same submission flow as kill.
public class TrackedPvp
{
	public int tileId;
	public int position;   // board position — the list mirrors the site's tile order (0 on old servers)
	public String label;
	public String description;
	public int points;
	public String category;
	public List<String> targets;      // selectors — "any", "team:other" or "rsn:<name>" entries
	public int requiredAmount;
	public int currentAmount;
	public String trackingMode;        // "team" | "individual"/"solo"
	// Minimum loot value (gp) a kill must yield to count. 0 = no minimum (credit off the death,
	// including loot-key kills). > 0 defers the credit to PlayerLootReceived and only counts a
	// kill whose priced loot reaches this floor (so loot-key / no-loot kills never credit it).
	public int minLootValue;
}
