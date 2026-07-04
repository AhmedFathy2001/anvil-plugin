package com.anvil;

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
	public List<TrackedLms> trackedLms;           // LMS placement tiles (finish top-N, M times)
	public List<TrackedDiary> trackedDiaries;     // achievement-diary completion tiles
	public List<CompletedTile> completedTiles;   // team-level tile completions (all tile types)
	public List<TierBand> tiers;                 // admin-configured difficulty bands (points -> tier)

	// Merged read-bootstrap (GET /api/plugin/config now returns these so login is one call):
	public NotifyChannels notify;                      // which clan notification channels are enabled server-side
	public List<String> funDeathMessages;              // server-managed 1/100 fun-death pool
	public List<String> deathTaunts;                   // server-managed death reaction lines (override baked-in)
	public List<String> spoonTaunts;                   // server-managed lucky-drop reaction lines (override baked-in)
	public List<String> alwaysNotifyItems;             // server-managed always-post item names (prestige drops)
	public boolean showKillCount = true;               // server toggle: include boss/raid KC on rare-drop posts
	public BingoApiClient.ScheduleResponse schedule;   // was GET /api/plugin/schedule
	public BingoApiClient.ActiveWeekly activeWeekly;   // was GET /api/plugin/active-weekly

	public static class NotifyChannels
	{
		// True when the site has a Discord webhook configured for this notification type. The plugin
		// posts notifications to its OWN server (POST /api/plugin/config's sibling /api/plugin/notify),
		// which forwards them to Discord server-side — the plugin never receives or calls the webhook
		// URL itself. (RuneLite plugin-hub rule: a plugin may not take a URL from a response and call
		// it.) Clips are the exception: they upload straight to a user-pasted webhook in plugin config.
		public boolean rareDrops;
		public boolean deaths;
		public boolean combatAchievements;
		public boolean pvpKills;
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

	// Achievement-diary tile: the plugin credits a completion when the in-game diary completion
	// line matches one of the selectors — "<Area> <Tier>" strings with "Any" as a wildcard on
	// either side ("Ardougne Elite", "Any Elite", "Wilderness Any"). Same submission flow as kill.
	public static class TrackedDiary
	{
		public int tileId;
		public String label;
		public String description;
		public int points;
		public String category;
		public List<String> diaries;      // selectors — any matching one counts a completion
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

	public static class TrackedLms
	{
		public int tileId;
		public String label;
		public String description;
		public int points;
		public String category;
		public int placementCap;           // finish at or under this placement (1 = win)
		public int requiredAmount;         // qualifying games needed to complete the tile
		public int currentAmount;          // team's submitted qualifying games so far
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
