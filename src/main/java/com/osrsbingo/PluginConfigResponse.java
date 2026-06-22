package com.osrsbingo;

import java.util.List;

public class PluginConfigResponse
{
	public EventInfo event;
	public TeamInfo team;
	public PlayerInfo player;
	public String codeword;
	public List<TrackedDrop> trackedDrops;
	public List<TrackedStat> trackedStats;
	public List<TrackedKill> trackedKills;        // NPC kill-count tiles (non-hiscores mobs)
	public List<TrackedTimed> trackedTimed;       // timed-clear tiles (activity under a time cap)
	public List<CompletedTile> completedTiles;   // team-level tile completions (all tile types)
	public List<TierBand> tiers;                 // admin-configured difficulty bands (points -> tier)

	// Merged read-bootstrap (GET /api/plugin/config now returns these so login is one call):
	public Webhooks webhooks;                          // plugin-posted notification destinations
	public List<String> funDeathMessages;              // server-managed 1/100 fun-death pool
	public List<String> deathTaunts;                   // server-managed death reaction lines (override baked-in)
	public List<String> spoonTaunts;                   // server-managed lucky-drop reaction lines (override baked-in)
	public List<String> alwaysNotifyItems;             // server-managed always-post item names (prestige drops)
	public BingoApiClient.ScheduleResponse schedule;   // was GET /api/plugin/schedule
	public BingoApiClient.ActiveWeekly activeWeekly;   // was GET /api/plugin/active-weekly

	public static class Webhooks
	{
		// Discord webhook URLs the plugin posts to directly. Null when unset on the site.
		public String rareDrops;
		public String deaths;
		public String combatAchievements;
		public String pvpKills;
		public String clips;
	}

	public static class EventInfo
	{
		public int id;
		public String name;
		public String startDate;
		public String endDate;
		public String forceEndedAt;
		// Drives which in-game Anvil view opens for the player's own active event:
		//   format="tilerace" -> race track; format="bingo" + scoringMode="points" -> accordion;
		//   format="bingo" + scoringMode="tiles" -> square grid. May be null on older servers.
		public String format;
		public String scoringMode;
	}

	public static class TeamInfo
	{
		public int id;
		public String name;
		public String color;
	}

	public static class PlayerInfo
	{
		public int id;
	}

	// One difficulty band: tiles with points >= min (and below the next band's min) fall in this
	// tier. Admin-configured server-side and served here so the Tier filter needs no plugin update.
	public static class TierBand
	{
		public String key;    // stable slug used in filter state
		public String label;  // shown on the Tier chip
		public int min;       // inclusive lower bound on points
	}

	public static class TrackedDrop
	{
		public int tileId;
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
	}

	public static class ItemRequirement
	{
		public int itemId;
		public String name;
		public int requiredAmount;
		public int currentAmount;
	}

	public static class CompletedTile
	{
		public int tileId;
		public String label;
		public int points;   // tile difficulty/reward value — used to pick the "hardest" to banner
	}

	// Kill-count tile: the plugin counts kills of any NPC named in targetNpcs (case-insensitive,
	// need NOT be on the hiscores) toward requiredAmount. Same submission flow as a simple drop.
	public static class TrackedKill
	{
		public int tileId;
		public String label;
		public String description;
		public int points;
		public String category;
		public List<String> targetNpcs;   // any of these NPC names counts a kill
		public int requiredAmount;
		public int currentAmount;
		public String trackingMode;        // "team" | "individual"/"solo"
	}

	// Timed-clear tile: the plugin times the named activity and submits a clear time. The tile
	// completes server-side when a submitted durationSeconds is ≤ thresholdSeconds (pass/fail).
	public static class TrackedTimed
	{
		public int tileId;
		public String label;
		public String description;
		public int points;
		public String category;
		public String activity;            // e.g. "Inferno", "Chambers of Xeric"
		public int thresholdSeconds;       // complete if a clear is at or under this
		public boolean completed;          // team already completed this tile
	}

	public static class TrackedStat
	{
		public int tileId;
		public String label;
		public String description;   // tile description, for the clog task accordion
		public int points;           // Leagues-style reward value (0 = not a points event)
		public String category;      // free-text grouping (boss/skill) for the clog task filter
		public String statName;     // e.g. "mining", "zulrah"
		public String statType;     // "skill" | "boss" | "kc"
		public String trackingMode; // "team" | "individual"
		public int currentAmount;   // gained XP / KC since baseline
		public int goalAmount;
	}
}
