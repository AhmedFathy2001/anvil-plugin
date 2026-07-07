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
	public List<TrackedPvp> trackedPvp;           // PvP-kill tiles (rival-team / bounty kills in dangerous PvP)
	public List<RosterEntry> pvpRoster;           // event roster (RSN -> teamId) for 'team:other' matching; empty unless a pvp tile exists
	public List<TrackedTimed> trackedTimed;       // timed-clear tiles (activity under a time cap)
	public List<TrackedLms> trackedLms;           // LMS placement tiles (finish top-N, M times)
	public List<TrackedValue> trackedValues;      // loot-value tiles (one haul worth >= thresholdGp)
	public List<TrackedGain> trackedGains;        // item-gain tiles (catch/cook/gather N, from inventory gains)
	public List<TrackedDeathless> trackedDeathless; // deathless-raid tiles (complete with zero party deaths)
	public List<TrackedDiary> trackedDiaries;     // achievement-diary completion tiles
	public List<TrackedCombatTask> trackedCombatTasks; // Combat Achievement task tiles
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
		public int position;   // board position — the list mirrors the site's tile order (0 on old servers)
		public String label;
		public String description;
		public int points;
		public String category;
		public List<String> targetNpcs;   // any of these NPC names counts a kill
		public int requiredAmount;
		public int currentAmount;
		public String trackingMode;        // "team" | "individual"/"solo"
	}

	// PvP-kill tile: the plugin credits a kill off the "You have defeated <name>!" line, which
	// the game sends only to the player it awards the kill (and loot / loot key) to — exactly
	// one credit per death. Only dangerous PvP counts (the Wilderness or a PvP world); safe
	// minigames (LMS, Soul Wars, Castle Wars, PvP Arena) and DMM never do. The victim must match
	// a selector: "team:other" = any member of a rival team (resolved against pvpRoster),
	// "rsn:<name>" = a named bounty (need not be in the event). Same submission flow as kill.
	public static class TrackedPvp
	{
		public int tileId;
		public int position;   // board position — the list mirrors the site's tile order (0 on old servers)
		public String label;
		public String description;
		public int points;
		public String category;
		public List<String> targets;      // selectors — "team:other" or "rsn:<name>" entries
		public int requiredAmount;
		public int currentAmount;
		public String trackingMode;        // "team" | "individual"/"solo"
	}

	// One event participant, for 'team:other' matching. Only players with a team are included.
	public static class RosterEntry
	{
		public String name;    // RSN as enrolled on the site
		public int teamId;
	}

	// Achievement-diary tile: the plugin credits a completion when the in-game diary completion
	// line matches one of the selectors — "<Area> <Tier>" strings with "Any" as a wildcard on
	// either side ("Ardougne Elite", "Any Elite", "Wilderness Any"). Same submission flow as kill.
	public static class TrackedDiary
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

	// Combat Achievement tile: the plugin credits a completion when the in-game "you've completed
	// a <tier> combat task" line matches one of the selectors — exact task names ("Whack-a-Mole")
	// or "Any <Tier>" wildcards ("Any Master"). Players who already own a task re-fire the line by
	// enabling the in-game "Repeat completion" setting. Same submission flow as diary.
	public static class TrackedCombatTask
	{
		public int tileId;
		public int position;   // board position — the list mirrors the site's tile order (0 on old servers)
		public String label;
		public String description;
		public int points;
		public String category;
		public List<String> tasks;        // selectors — any matching one counts a completion
		public int requiredAmount;
		public int currentAmount;
		public String trackingMode;        // "team" | "individual"/"solo"
	}

	// Timed-clear tile: the plugin times the named activity and submits a clear time. The tile
	// completes server-side when a submitted durationSeconds is ≤ thresholdSeconds (pass/fail).
	public static class TrackedTimed
	{
		public int tileId;
		public int position;   // board position — the list mirrors the site's tile order (0 on old servers)
		public String label;
		public String description;
		public int points;
		public String category;
		public String activity;            // e.g. "Inferno", "Chambers of Xeric"
		public int thresholdSeconds;       // complete if a clear is at or under this
		public int partySize;              // raids — exact party size required; 0/absent = any size
		public int itemId = -1;            // activity's signature reward (Colosseum → quiver) for the icon
		public boolean completed;          // team already completed this tile
	}

	public static class TrackedLms
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

	public static class TrackedGain
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

	public static class TrackedDeathless
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

	public static class TrackedValue
	{
		public int tileId;
		public int position;   // board position — the list mirrors the site's tile order (0 on old servers)
		public String label;
		public String description;
		public int points;
		public String category;
		public long thresholdGp;           // a single haul must be worth at least this
		public List<String> sources;       // optional source filter: NPC/chest names, or "PvP"; empty = any
		public boolean completed;          // team already completed this tile
	}

	public static class TrackedStat
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
	}
}
