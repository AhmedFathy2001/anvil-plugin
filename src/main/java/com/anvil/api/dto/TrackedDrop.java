package com.anvil.api.dto;

import java.util.List;

public class TrackedDrop
{
	public int tileId;
	public int position;   // board position — the list mirrors the site's tile order (0 on old servers)
	public String label;
	public String description;   // tile description, for the clog task accordion
	public int points;           // Leagues-style reward value (0 = not a points event)
	public String category;      // free-text grouping (boss/skill) for the clog task filter
	public List<Integer> itemIds;
	public int requiredAmount;
	public int currentAmount;
	public List<ItemRequirement> itemRequirements;
	// null = accept any source. Otherwise must match one of: "npc", "event", "pvp".
	public List<String> acceptedSources;
	// null/empty = any source NPC. Otherwise the drop only counts when the loot source
	// name matches one of these (case-insensitive), e.g. ["Tekton"] for "onyx, Tekton only".
	public List<String> sourceNpcs;
	// Exact raid party size required for the drop to count ("solo Cursed phalanx");
	// 0/absent = any. Raid chests are looted inside the instance, so the deathless
	// party tracker doubles as the size source.
	public int partySize;
	// How this collection's sets combine: "any" (default, and what an older server sends nothing
	// for) = the sets are alternatives, satisfy ONE; "all" = every set must be satisfied.
	public String groupMode;
	// The most this tile can be credited from a SINGLE kill, whatever that kill dropped.
	// 0/absent = uncapped (every tracked item, and every item in a stack, counts). 1 makes the
	// tile count ROLLS: a DT2 kill handing you a vestige and an ingot rolled its unique table
	// once, so it credits once.
	public int perKillCap;
}
