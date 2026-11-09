package com.anvil;

import java.util.*;

public class PluginConfigResponse {
	/** When this config was asked for (client clock): what it says about completions is as of then. Not on the wire. */
	transient long asOf;
	/**
	 * The site's capability list. The plugin never gates on site versions (the site deploys first);
	 * the only one read is {@code push-ws}, a runtime switch. Contract: docs/PLUGIN_WIRE.md in the site repo.
	 */
	public ServerInfo server;

	public static class ServerInfo {
		public List<String> capabilities;
	}

	public boolean serverSupports(String capability) {
		return server != null && server.capabilities != null && server.capabilities.contains(capability);
	}

	// ── WHICH CLAN, AND WHICH OTHERS ──────────────────────────────────────────────────────────
	//
	// One Anvil serves every clan, so the site address names none of them and the server picks
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

	public static class ClanRef {
		public String slug;
		public String name;
		/** Verified in-game clan-channel name; may differ from the site's display name. */
		public String gameName;
		/** Clan boost level in force; zero on older sites. */
		public int boostLevel;
		/** Clan-admin chosen boosted-name palette; null on older sites or the default look. */
		public List<String> boostNameStops;
		/** The clan's own look for dark / light surfaces (null: adapt boostNameStops). Drawn as-is. */
		public List<String> boostNameDarkStops;
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
		/**
		 * A creator space, not a clan. One person can be in several of each, so every dropdown row
		 * says which it is. False on an older site.
		 */
		public boolean space;
	}

	/**
	 * A live board in one of the person's clans.
	 *
	 * A {@link HomeBoard} plus its kind. The event id is load-bearing: a co-hosted event belongs to
	 * every host, so the same board arrives listed under each of them and the id is the only thing
	 * that says so.
	 */
	public static class ClanBoard extends HomeBoard {
		/**
		 * "bingo" or "weekly" — and half of this thing's identity.
		 *
		 * A board id and a competition id come out of different tables and collide freely, so anything
		 * deduping across clans has to key on the PAIR. Null on a site that predates the field, where
		 * only boards were ever reported.
		 */
		public String kind;
		// tilesComplete is zero on a weekly: a competition has a leaderboard, not a board to fill.

		/** True for a SOTW/BOTW — no board to fill, so no progress bar and no tile tally. */
		public boolean isWeekly() {
			return "weekly".equals(kind);
		}
	}

	/**
	 * The clans worth offering in a switcher, home first.
	 *
	 * A single clan is not a choice, so it is not a dropdown — the panel hides the control entirely
	 * rather than showing one option that does nothing.
	 */
	public List<ClanRef> switchableClans() {
		return clans == null ? Collections.emptyList() : clans;
	}

	/** The slug this response was resolved for, or null on a site that does not say. */
	public String activeClanSlug() {
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

	public static class HomeBoard extends BoardTally {
		/**
		 * WHICH board this is. Zero on a site that predates the field.
		 *
		 * <p>Load-bearing for the same reason {@link ClanBoard#eventId} is: a co-hosted board is
		 * reported by every host, so the panel has to be able to say that the summary it is
		 * rendering and a row under "Also live" are one event rather than two.</p>
		 */
		public int eventId;
		public String eventName;
	}

	/** Every live board this character is on, the addressed one first: the plugin tracks them all. */
	public List<HomeBoard> boards;

	/** Is any board live? Its tiles ride the tracked lists and the rule table, tagged by board. */
	public boolean live() {
		return boards != null && !boards.isEmpty();
	}

	/** Is this board one of them? */
	public boolean live(int eventId) {
		return boards != null && boards.stream().anyMatch(b -> b.eventId == eventId);
	}

	/**
	 * THE WHOLE BOARD'S FRACTION, from the server — not from what this plugin can see.
	 *
	 * <p>The tracked lists carry only tiles the plugin can DETECT: a drop, a KC, an XP goal. A manual
	 * tile never arrives, so counting the rows in hand denominated a 25-tile board at 10 and reported
	 * "5 / 10 tiles · 50%" beside a website, a Discord post and this same plugin's clan-switcher row
	 * all saying 5 / 25. Null on a site that predates the field, where the local count is all there is.</p>
	 */
	public BoardTally board;

	/** Earned/total for the board as a whole — points on a points board, tiles otherwise. */
	public static class BoardTally {
		public int tilesComplete;
		public int tilesTotal;
	}

	/**
	 * How a BOARD is named across clans, so every side of the dedup builds the same string.
	 *
	 * A board id and a competition id come out of different tables and collide freely, which is why
	 * the kind is half of it — see {@link ClanBoard#identity()}.
	 */
	public static String boardIdentity(int eventId) {
		return "bingo:" + eventId;
	}

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
	/**
	 * Counters read off a varbit/varp (site lib/gameCounters): each
	 * positive change credits kill tiles targeting the entry's name (GameValueCounters). Null on
	 * sites that predate it — then nothing is read.
	 */
	public List<GameValueCounters.Entry> gameCounters;

	// STARTING SHOT — the anti-stack proof (site: lib/startProof). Non-null only on an event that
	// requires one; null everywhere else, so the button simply never appears there.
	public StartProof startProof;

	public static class StartProof {
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
		/** ISO instant when the ask lapses. Null before the draw, and on sites too old to send it. */
		public String windowEndsAt;
		/** "pending" | "accepted" | "rejected", or null when nothing is on file. */
		public String status;

		/** The drawn spot on the world map: where, and how many squares from it still counts. */
		public static class Spot {
			public int x;
			public int y;
			public int radius;
		}
	}
	// Set on a no-active-event response when the logged-in RSN IS a player in a live bingo but this
	// account isn't linked to it — the plugin warns so tracking isn't silently off. Null otherwise.
	public String unlinkedActiveEvent;

	/**
	 * Whose the logged-in character is: {@code yours} | {@code other} (claimed by another Anvil
	 * account — nothing tracks for this token) | {@code ignored} (marked Not my account) |
	 * {@code unclaimed}. {@code null} at the login screen and on older sites.
	 */
	public String accountStanding;
	/** Every tracked tile's count, for the panel's live counts and a combo's part lines. */
	public List<TileCount> tiles;
	// Site stat keys ("cluesElite", "collectionsLogged") for the hiscores counters that are neither a
	// boss nor a skill. Pushed by KEY rather than in-game name — the plugin reads each from a named
	// varbit, so there's nothing to map — and only for the ones the client can actually answer
	// (see ActivityStats).
	public List<String> trackedActivityKeys;
	public List<RosterEntry> pvpRoster;           // event roster (RSN -> teamId) for 'team:other' matching; empty unless a pvp tile exists
	public List<CompletedTile> completedTiles;   // team-level tile completions (all tile types)
	public List<TrackedCombo> trackedCombos;     // combo tiles: their parts ride the lists above (partOf)
	public List<RollTable> rollTables;           // bosses whose vestige is on a fixed roll rotation

	// Merged read-bootstrap (GET /api/plugin/config now returns these so login is one call):
	public Me me;
	public List<String> alwaysNotifyItems;             // server-managed always-post item names (prestige drops)
	public List<Integer> alwaysNotifyItemIds;          // ^ resolved to item ids server-side, for reliable id-match
	public boolean showKillCount = true;               // server toggle: include boss/raid KC on rare-drop posts
	// Clan-wide rare-drop rarity floor (1-in-N). A member may post only drops at least this rare;
	// their own setting can be stricter but not looser. 0 / absent = no clan floor. Lets an admin
	// quiet a noisy channel for everyone from the site instead of asking each member to edit config.
	// What the SERVER knows about a drop and the client cannot:
	// which monster a pet actually comes from, and which items a source hands over every kill.
	// Null against an older site, and every read of it degrades to the old guess-from-context
	// behaviour rather than to nothing.
	public DropFacts dropFacts;

	/**
	 * Items that count toward luck on this site: reported as moments
	 * whatever they sell for, so an untradeable unique reaches the event's luck boards and not only
	 * its first collection-log unlock. Null on older sites.
	 */
	public List<Integer> luckItemIds;
	/** Which level-ups the clan and the player's own webhooks want; null on older sites. */
	public LevelRules.Sets levelRules;
	/**
	 * On a game-mode world, that mode's own thresholds: the loosest of the player's clans', each clan
	 * re-checking its own on the site. 0 / null = the main-game gate. Null on the main game.
	 */
	public ModeThresholds modeThresholds;

	public static class ModeThresholds {
		public LevelRules levelRules;
	}
	/**
	 * Server-supplied drop knowledge. Both maps are keyed by LOWERCASED name — the item's for
	 * {@code guaranteed}, the pet's for {@code pets} — because that is what the client has in hand
	 * when a post is being written, and it survives an item-id variant swap.
	 */
	public static class DropFacts {
		/** Pet name -> where it really comes from. */
		public Map<String, Pet> pets;

		public static class Pet {
			/** Every source that drops this pet. Empty for a skilling pet — there is no monster. */
			public List<String> sources;
			/** "npc" (killable, so a rate and a KC exist), "event" (raid/minigame), or "skill". */
			public String kind;
			/** For a skilling pet, the skill it comes from. Null otherwise. */
			public String skill;
		}
	}

	/** What this person's posts do, resolved by the site (lib/notifyPrefs): their /settings choices AND the clan's channels. */
	public static class Me {
		public boolean drops, pets, deaths, pvp, clog, levels, levelsClan, diaries;
		/** Screenshot gates: the site judges the post itself. */
		public long dropMin, keyMin;
		/** CombatAchievementTier names that post; quest difficulties that post ("*" = all). */
		public List<String> ca = new ArrayList<>(), quests = new ArrayList<>();
		/** Screenshot privacy: {pm, public, clanOthers, clanSelf, channel, group} (ChatFlair.hideMask). */
		public Map<String, Boolean> hide;
		/** Game lines that post, as the site resolved them (NotifyFacts.glob). */
		public List<Watch> watch = new ArrayList<>();
		/** Post kinds (or channels) whose announcement goes without a screenshot. Never tile proofs. */
		public List<String> noShot = new ArrayList<>();
		/** Hook kinds that are on, with their floor: "ge" (gp), "ext". */
		public Map<String, Long> on;
	}

	public static class Watch {
		public String k, g;
		public long every, min;
		public boolean shot;
	}


	public static class EventInfo {
		public int id;
		public String name;
		public String startDate;
		public String endDate;
		public String forceEndedAt;
		// Drives which in-game Anvil view opens for the player's own active event:
		//   format="tilerace" -> race track; format="bingo" + scoringMode="points" -> accordion;
		//   format="bingo" + scoringMode="tiles" -> square grid. May be null on older servers.
		public String format;         // "tilerace" | "bingo" | "ladder" | null
		// Reveal-policy events (showdown / lucky draw / bounty / ladder rotation) only — null/0 on
		// classic events and older servers. The tracked* lists already contain ONLY revealed, still-open
		// tiles.
		public String revealPolicy;   // "scheduled" | "interval" | "bounty" | "rotating" | null
		// Open missions on a reveal-mode board (revealed + still-open), for the in-game "New mission!"
		// banner. Absent on classic boards.
		public List<Mission> missions;
		// Lock-out (bounty / lockout) events: the most recent EVENT-WIDE claims, so the plugin can
		// announce "X claimed <mission>" to other players. Absent on non-lockout events.
		public List<Claim> recentClaims;
	}

	public static class Mission {
		public int tileId;
		public String label;
		public int points;        // face value before the decay ramp
		public String category;
	}

	public static class Claim {
		public int tileId;
		public String label;
		public int points;
		public String rsn;        // finisher's RSN; null when unattributed
		public String at;         // ISO completion time
	}

	public static class TeamInfo {
		public int id;
		public String name;
		public String color;
	}

	public static class PlayerInfo {
		public int id;
	}


	/** What every tile on the wire shares (tracked tiles and the board's). */
	public abstract static class TrackedTile {
		public int tileId;
		public int position;   // board position — the list mirrors the site's tile order (0 on old servers)
		public String label;
		public String description;   // tile description, for the clog task accordion
		public int points;           // Leagues-style reward value (0 = not a points event)
		public String category;      // free-text grouping (boss/skill) for the clog task filter
		// A COMBO's part (trackedCombos): credited exactly like any tile of its kind, but not a board
		// tile of its own — it folds under its combo, which is what pins and scores. Absent elsewhere.
		public Integer partOf;
		/** Another board's tile (its eventId); null on the addressed board. */
		public HomeBoard board;
	}

	/**
	 * A boss whose vestige is on a fixed rotation (the DT2 four): its unique table rolls
	 * non-vestige, non-vestige, VESTIGE, so the count since the last vestige tells a player how
	 * close they are. Server-side data (the site's lib/rollTables) so a cadence change or a
	 * corrected item list needs no plugin release. Empty/absent on older servers — the tracker
	 * then simply says nothing.
	 */
	public static class RollTable {
		public String boss;                 // NPC name as the loot event reports it
		public List<Integer> rollItemIds;   // every item that counts as one roll
		public int vestigeItemId;
		public String vestigeName;
		public int rollsPerVestige;         // counting the vestige itself: 1, 2, vestige
	}

	public static class CompletedTile {
		public int tileId;
		public String label;
		public int points;   // tile difficulty/reward value — used to pick the "hardest" to banner
		public String completedBy; // crediting player of the finishing submission; null for stat/manual tiles
		public Integer partOf; // a combo's part finishing: progress on the combo, not a tile complete
		public HomeBoard board; // as TrackedTile.board
	}

	/**
	 * A COMBO tile: done when its parts satisfy the rule ("any" one, "all", or at least {@code needed}
	 * of them — "nOf"). The parts ride the ordinary tracked lists tagged {@code partOf}; this says how
	 * they fold together. The site counts {@code done} and decides completion.
	 */
	public static class TrackedCombo extends TrackedTile {
		public String rule;
		public int needed;
		public int done;
		public int percent; // rule "mix" only: weighted progress 0–100, done at 100
		public List<ComboPart> parts;
		public boolean completed;
	}

	public static class ComboPart {
		public int tileId;
		public String label;
		public boolean completed;
	}

	// One event participant, for 'team:other' matching. Only players with a team are included.
	public static class RosterEntry {
		public String name;    // RSN as enrolled on the site
		public int teamId;
	}

	public static class TileCount {
		public int t;
		public long have;
		public long of;
		public boolean set; // a set / collection tile: counts pieces, not what the panel shows
	}
}
