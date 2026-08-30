package com.anvil;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PluginConfigResponse
{
	/**
	 * Version/capability handshake, sent by sites ≥ v1.0.0 on every /config shape. Null on
	 * older sites — {@link #serverSupports(String)} treats that as the v1.0.0 baseline set,
	 * never as "supports nothing". Contract: docs/PLUGIN_WIRE.md in the anvil site repo.
	 */
	public ServerInfo server;

	public static class ServerInfo
	{
		public String version;      // site semver, e.g. "1.0.0"
		public String sha;          // exact commit the site image was built from
		public int apiLevel;        // breaking-change counter; bumps are rare and loud
		public List<String> capabilities;
		/**
		 * The address this deployment would rather be reached at, e.g. "https://anvilosrs.com".
		 *
		 * One site now serves every clan, so a per-clan subdomain is a legacy address: it works, but
		 * it resolves the clan from the hostname instead of from your token, which stops being
		 * right the moment you join a second clan. The canonical address keeps working either way.
		 *
		 * SENT BY THE SERVER rather than baked in here, because Anvil is self-hostable — a
		 * hard-coded anvilosrs.com would tell every self-hoster to point at somebody else's site.
		 * Null on older sites and on any deployment that names none, in which case we say nothing.
		 */
		public String canonicalUrl;
	}

	/**
	 * Whether the configured URL should be swapped for the one the site prefers.
	 *
	 * Only when the site both advertises a canonical address AND says it can resolve a clan without
	 * one — otherwise moving someone to the apex would break them, which is a far worse outcome
	 * than an out-of-date URL that works.
	 *
	 * Compared on host alone: scheme and trailing slashes are noise, and a user who typed a path is
	 * still on a working address.
	 */
	public String suggestedUrlMigration(String configuredUrl)
	{
		if (server == null || server.canonicalUrl == null || server.canonicalUrl.isEmpty()) return null;
		if (!serverSupports("apex-routing")) return null;
		if (configuredUrl == null || configuredUrl.isEmpty()) return null;

		String have = hostOf(configuredUrl);
		String want = hostOf(server.canonicalUrl);
		if (have == null || want == null || have.equalsIgnoreCase(want)) return null;
		return server.canonicalUrl;
	}

	/** Host portion of a URL, lowercased, or null if it cannot be read as one. */
	static String hostOf(String url)
	{
		try
		{
			String s = url.trim();
			if (!s.toLowerCase().startsWith("http://") && !s.toLowerCase().startsWith("https://"))
			{
				s = "https://" + s;
			}
			String host = new java.net.URI(s).getHost();
			return host == null ? null : host.toLowerCase();
		}
		catch (Exception e)
		{
			return null;
		}
	}

	/** Everything the plugin-facing API already supported when the handshake first shipped (site v1.0.0). */
	private static final Set<String> BASELINE_CAPABILITIES = new HashSet<>(Arrays.asList(
		"stats-live", "drop-tiles", "kill-tiles", "timed-tiles", "lms-tiles", "value-tiles",
		"gain-tiles", "deathless-tiles", "pvp-tiles", "diary-tiles", "ca-tiles", "clog-tiles",
		"weekly", "schedule", "notify", "counters", "activity-feed", "ladder",
		"reveal-modes", "config-etag"));

	/**
	 * Gate new plugin surfaces on this instead of calling an endpoint and 404ing: self-hosted
	 * sites can lag the hub plugin by months, and each federated connection may be on a
	 * different version. Capabilities newer than the baseline return false until the site
	 * explicitly advertises them — the surface should hide, not error.
	 */
	public boolean serverSupports(String capability)
	{
		if (server == null || server.capabilities == null)
		{
			return BASELINE_CAPABILITIES.contains(capability);
		}
		return server.capabilities.contains(capability);
	}

	// ── WHICH CLAN, AND WHICH OTHERS ──────────────────────────────────────────────────────────
	//
	// One Anvil serves every clan, so the canonical Site URL names none of them and the server picks
	// one from the token: a live event first, then the latest-started of several, then the newest
	// seat. That guess is nearly always right and completely invisible, which is the problem — a
	// member on two boards had no way to see which one their drops were filing into.
	//
	// So the server says which clan it answered for, and lists the rest. The plugin addresses the one
	// it means with a /c/<slug> prefix from then on, and offers the list as a dropdown.
	//
	// Null on a site that predates this (see the `clan-switch` capability): then there is one clan,
	// the address names it, and there was never a choice to make.

	/** The clan THIS response is about — whatever named it: a path, a host, or the token. */
	public ClanRef activeClan;

	/** Every clan the token's owner holds a seat in. Empty/null on an older site. */
	public List<ClanRef> clans;

	public static class ClanRef
	{
		public String slug;
		public String name;
		/** 'member' or 'guest' — a dropdown says which of these is your home. Null on activeClan. */
		public String kind;
		/** The most relevant thing running here, or null. */
		public ClanBoard live;
		/**
		 * How many things are running here in total — boards plus the competition.
		 *
		 * {@link #live} is one line in a dropdown, so it names ONE. Without this a clan running five
		 * boards reads exactly like a clan running one, and the named board is an arbitrary pick
		 * presented as the whole answer. Zero on a site too old to send it, which is indistinguishable
		 * from "nothing running" — and on such a site there is no count to show anyway.
		 */
		public int liveCount;
	}

	/**
	 * A live board in one of the person's clans.
	 *
	 * Shaped like {@link HomeBoard} but a type of its own because it carries the event id, and the id
	 * is load-bearing: a co-hosted event belongs to every host, so the same board arrives listed under
	 * each of them and the id is the only thing that says so. {@link HomeBoard} is the summary for the
	 * clan the plugin is addressing, where the question never comes up.
	 */
	public static class ClanBoard
	{
		/**
		 * "bingo" or "weekly" — and half of this thing's identity.
		 *
		 * A board id and a competition id come out of different tables and collide freely, so anything
		 * deduping across clans has to key on the PAIR. Null on a site that predates the field, where
		 * only boards were ever reported.
		 */
		public String kind;
		public int eventId;
		public String eventName;
		/** Zero on a weekly: a competition has a leaderboard, not a board to fill. */
		public int tilesComplete;
		public int tilesTotal;
		public boolean pointsScored;

		/** True for a SOTW/BOTW — no board to fill, so no progress bar and no tile tally. */
		public boolean isWeekly()
		{
			return "weekly".equals(kind);
		}

		/** Identity across clans: the id alone is ambiguous between the two tables it can come from. */
		public String identity()
		{
			return (kind == null ? "bingo" : kind) + ":" + eventId;
		}
	}

	/**
	 * The clans worth offering in a switcher, home first.
	 *
	 * A single clan is not a choice, so it is not a dropdown — the panel hides the control entirely
	 * rather than showing one option that does nothing.
	 */
	public List<ClanRef> switchableClans()
	{
		return clans == null ? java.util.Collections.emptyList() : clans;
	}

	/** The slug this response was resolved for, or null on a site that does not say. */
	public String activeClanSlug()
	{
		return activeClan == null || activeClan.slug == null || activeClan.slug.isEmpty()
			? null
			: activeClan.slug;
	}

	public EventInfo event;
	public TeamInfo team;
	public PlayerInfo player;
	/** The clan's display name (sidebar clan-filter label + logged-out home card). Null on older sites. */
	public String clanName;
	/**
	 * Server-resolved board summary keyed to the token's USER (linked member → live enrollment) —
	 * lets the sidebar show the home board at the login screen, before an in-game account resolves.
	 * Null when the user isn't enrolled in a live event (or on older sites).
	 */
	public HomeBoard homeBoard;

	public static class HomeBoard
	{
		public String eventName;
		public int tilesComplete;
		public int tilesTotal;
		public boolean pointsScored;
	}
	public String codeword;

	// Activity names for the personal-best import. RuneLite files its stored bests in a config scope
	// a plugin can READ by key but cannot LIST, so the import has to ask by name — and the names come
	// from the site so a new boss doesn't need a plugin release. Null on older sites.
	public List<String> pbActivities;
	/**
	 * The player varps combat-achievement completion is bit-packed into, in bit order. Read them,
	 * send the numbers, and let the site decode: which task each bit is belongs with the catalogue,
	 * not here, so a game update that adds a varp is a data change there rather than a release here.
	 * Null on sites that predate it — then nothing is read.
	 */
	public List<Integer> caVarps;

	// STARTING SHOT — the anti-stack proof (site: lib/startProof). Non-null only on an event that
	// requires one; null everywhere else, including on sites that predate the 'start-proof'
	// capability, so the button simply never appears there.
	public StartProof startProof;

	public static class StartProof
	{
		/** Does this event ask for a starting shot at all? */
		public boolean required;
		/** Has the location been drawn (i.e. has the event gone live)? Nothing exists before this. */
		public boolean drawn;
		/** Where the player must be standing. Null until the draw. */
		public String location;
		/**
		 * The same place as game coordinates, when the host pinned it on the site's map. Null when
		 * they only named it (and on sites that predate the pin), in which case position isn't
		 * checked at all — see {@link StartProofRules}.
		 */
		public Spot spot;
		/**
		 * How long this game session may have been running when the shot is taken, in minutes.
		 * 0 = the host isn't asking. The point is the LOGOUT before it: hiscores only flush then,
		 * so a session older than the event start means a stale baseline for every stat tile.
		 */
		public int maxSessionMinutes;
		/** This player's own keyword, derived server-side from the draw stamp. Null until the draw. */
		public String keyword;
		/** True while this player still owes a shot (never filed one, or theirs was rejected). */
		public boolean needsUpload;
		/**
		 * Is a shot still being ASKED for? The site stops asking six hours after the start: OSRS
		 * force-logs everyone by then, so nobody is still sitting on the stack the shot exists to
		 * catch. `needsUpload` already folds this in — this is here so the panel can say how long
		 * is left rather than having the card vanish without explanation.
		 */
		public boolean windowOpen;
		/** ISO instant when the ask lapses. Null before the draw, and on sites too old to send it. */
		public String windowEndsAt;
		/** "pending" | "accepted" | "rejected", or null when nothing is on file. */
		public String status;
		public String imageUrl;

		/** The drawn spot on the world map: where, and how many squares from it still counts. */
		public static class Spot
		{
			public int x;
			public int y;
			public int radius;
		}
	}
	// Set on a no-active-event response when the logged-in RSN IS a player in a live bingo but this
	// account isn't linked to it — the plugin warns so tracking isn't silently off. Null otherwise.
	public String unlinkedActiveEvent;
	public List<TrackedDrop> trackedDrops;
	public List<TrackedStat> trackedStats;
	// Lowercased in-game KC-line boss names ("zulrah", "chambers of xeric challenge mode") for the
	// event's boss-KC hiscores tiles. When the plugin sees "Your <boss> kill count is: N" for one of
	// these, it pushes the absolute KC to /api/plugin/stats so the tile updates in real time instead
	// of waiting ~1h for the hiscores cron. Empty/absent = push nothing (older servers, or no KC tiles).
	public List<String> trackedKcNames;
	// Skill names (lowercase, e.g. "mining") the event's skill-XP tiles track — the plugin pushes
	// real-time absolute XP for these off StatChanged, like trackedKcNames for boss KC. Empty/absent
	// = push nothing (older servers, or no skill tiles).
	public List<String> trackedSkillNames;
	// Site stat keys ("cluesElite", "collectionsLogged") for the hiscores counters that are neither a
	// boss nor a skill. Pushed by KEY rather than in-game name — the plugin reads each from a named
	// varbit, so there's nothing to map — and only for the ones the client can actually answer
	// (see ActivityStats). Requires the 'activity-stats' capability; empty/absent on older servers.
	public List<String> trackedActivityKeys;
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
	public List<Integer> optionalTileIds;        // tile IDs flagged optional (bonus) — excluded from score/points totals
	public List<TierBand> tiers;                 // admin-configured difficulty bands (points -> tier)
	public List<RollTable> rollTables;           // bosses whose vestige is on a fixed roll rotation

	// Merged read-bootstrap (GET /api/plugin/config now returns these so login is one call):
	public NotifyChannels notify;                      // which clan notification channels are enabled server-side
	public List<String> funDeathMessages;              // server-managed 1/100 fun-death pool
	public List<String> deathTaunts;                   // server-managed death reaction lines (override baked-in)
	public List<String> spoonTaunts;                   // server-managed lucky-drop reaction lines (override baked-in)
	public List<String> alwaysNotifyItems;             // server-managed always-post item names (prestige drops)
	public boolean showKillCount = true;               // server toggle: include boss/raid KC on rare-drop posts
	// Clan-wide rare-drop rarity floor (1-in-N). A member may post only drops at least this rare;
	// their own setting can be stricter but not looser. 0 / absent = no clan floor. Lets an admin
	// quiet a noisy channel for everyone from the site instead of asking each member to edit config.
	public int dropRarityFloor;
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
		public String format;         // "tilerace" | "bingo" | "ladder" | null
		public String scoringMode;
		// Reveal-policy events (showdown / lucky draw / bounty / ladder rotation) only — null/0 on
		// classic events and older servers. The tracked* lists already contain ONLY revealed, still-open
		// tiles.
		public String revealPolicy;   // "scheduled" | "interval" | "bounty" | "rotating" | null
		public int hiddenTileCount;   // tiles not yet revealed
		public String nextRevealAt;   // ISO time of the next reveal; null when none is scheduled
		// Points ramp for reveal-mode events: a mission's live value scales from 100% at reveal toward
		// targetPct% over `hours` (<100 decays, >100 grows). Null when off. Lets the ladder board show a
		// live grow/decay value per mission (see AnvilSidebarDataSource.liveValue).
		public Decay decay;
		// Open missions on a reveal-mode board (revealed + still-open), with face points and reveal time
		// so the plugin can render the "active missions" list + per-second countdown. Absent on classic.
		public List<Mission> missions;
		// Ladder events only: the individual leaderboard both all-time and for the current month, each
		// with the caller's own rank. Drives the in-game missions board's standings + "You: #N".
		public Standings standings;
		public Standings monthlyStandings;
		// Lock-out (bounty / lockout) events: the most recent EVENT-WIDE claims, so the plugin can
		// announce "X claimed <mission>" to other players. Absent on non-lockout events.
		public List<Claim> recentClaims;
	}

	public static class Decay
	{
		public int targetPct;   // ramp target as a percent of face (50 = decays to half; 200 = grows to 2x)
		public int hours;       // hours over which the ramp reaches targetPct
	}

	public static class Mission
	{
		public int tileId;
		public String label;
		public int points;        // face value before the decay ramp
		public String revealedAt; // ISO time this mission went live; null when unknown
		public String category;
		// Per-mission scoring so a bingo can mix missions with different behaviour. `decay` (may be
		// null) drives this mission's live grow/decay value; `lockout` marks first-to-clear-locks.
		// Absent on older sites — fall back to the event-level decay.
		public Decay decay;
		public boolean lockout;
	}

	public static class Standings
	{
		public int yourRank;      // caller's 1-based rank; 0 when they have no scoring row yet
		public long yourPoints;
		public int yourTasks;
		public int total;         // full board length (entries is capped)
		public List<StandingEntry> entries;
	}

	public static class StandingEntry
	{
		public int rank;
		public String rsn;
		public long points;
		public int tasks;
	}

	public static class Claim
	{
		public int tileId;
		public String label;
		public int points;
		public String rsn;        // finisher's RSN; null when unattributed
		public String at;         // ISO completion time
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
		// How this collection's sets combine: "any" (default, and what an older server sends nothing
		// for) = the sets are alternatives, satisfy ONE; "all" = every set must be satisfied.
		public String groupMode;
		// The most this tile can be credited from a SINGLE kill, whatever that kill dropped.
		// 0/absent = uncapped (every tracked item, and every item in a stack, counts). 1 makes the
		// tile count ROLLS: a DT2 kill handing you a vestige and an ingot rolled its unique table
		// once, so it credits once.
		public int perKillCap;
	}

	public static class ItemRequirement
	{
		public int itemId;
		public String name;
		public int requiredAmount;
		public int currentAmount;
		// Set name: ungrouped (null/blank) items are ALWAYS required; items sharing a group form one
		// set. How the sets combine is the tile's groupMode. Null/absent on a plain "collect all of
		// these" collection.
		public String group;
		// How many DISTINCT items in this set satisfy it. 0/absent = all of them (a full set), which
		// is what every collection authored before set modes existed means. 1 is "any one item from
		// this source" — with groupMode "all" that's a unique from each DT2 boss.
		public int groupRequire;
	}

	/**
	 * A boss whose vestige is on a fixed rotation (the DT2 four): its unique table rolls
	 * non-vestige, non-vestige, VESTIGE, so the count since the last vestige tells a player how
	 * close they are. Server-side data (the site's lib/rollTables) so a cadence change or a
	 * corrected item list needs no plugin release. Empty/absent on older servers — the tracker
	 * then simply says nothing.
	 */
	public static class RollTable
	{
		public String boss;                 // NPC name as the loot event reports it
		public List<Integer> rollItemIds;   // every item that counts as one roll
		public int vestigeItemId;
		public String vestigeName;
		public int rollsPerVestige;         // counting the vestige itself: 1, 2, vestige
	}

	public static class CompletedTile
	{
		public int tileId;
		public String label;
		public int points;   // tile difficulty/reward value — used to pick the "hardest" to banner
		public String completedBy; // crediting player of the finishing submission; null for stat/manual tiles
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
		// Shared kills. "per-kill" = a kill several members were in credits ONCE (the server
		// correlates their reports); coopMinMembers = the kill only counts with at least that many
		// of the team in it. Either one makes this client attach what it could see of its company.
		// Absent/"per-member" + 0 on older servers, which is the historical every-member counting.
		public String coopCredit;
		public int coopMinMembers;
		// What one credit IS, for player-facing wording only ("kill", "lap", …). Agility-lap tiles
		// ride this list because the game's lap counter is the same chat line boss KC uses, so the
		// matching is identical and only the noun differs. Null on older servers → "kill".
		public String unit;

		/** Singular noun for one credit on this tile — "kill" unless the server says otherwise. */
		public String unitNoun()
		{
			return unit == null || unit.trim().isEmpty() ? "kill" : unit.trim();
		}

		public boolean needsCoopFingerprint()
		{
			return "per-kill".equalsIgnoreCase(coopCredit) || coopMinMembers > 0;
		}
	}

	// PvP-kill tile: the plugin credits a kill off the "You have defeated <name>!" line, which
	// the game sends only to the player it awards the kill (and loot / loot key) to — exactly
	// one credit per death. Only dangerous PvP counts (the Wilderness or a PvP world); safe
	// minigames (LMS, Soul Wars, Castle Wars, PvP Arena) and DMM never do. The victim must match
	// a selector: "any" = any player at all, "team:other" = any member of a rival team (resolved against pvpRoster),
	// "rsn:<name>" = a named bounty (need not be in the event). Same submission flow as kill.
	public static class TrackedPvp
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
		public long thresholdGp;           // single: a haul must be worth ≥ this; total: the gp target to reach
		public String mode;                // "single" (one haul ≥ threshold) | "total" (hauls sum to threshold)
		public long currentGp;             // total mode: gp banked so far (the server's sum of submissions); 0 on single

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
		// Teammates (RSNs) actively grinding this stat tile right now, for the sidebar's "Active now".
		// The caller is never included (the plugin marks itself "You"). null on older servers that don't
		// compute it (the sidebar then falls back to an unnamed "a teammate" via config-count deltas);
		// an empty list means the server DID compute it and no teammate is currently active.
		public java.util.List<String> activeWorkers;
	}
}
