package com.anvil.api;

import com.anvil.AnvilPlugin;
import com.anvil.api.dto.ActiveWeekly;
import com.anvil.api.dto.BoardTally;
import com.anvil.api.dto.ClanBoard;
import com.anvil.api.dto.ClanRef;
import com.anvil.api.dto.CompletedTile;
import com.anvil.api.dto.DropFacts;
import com.anvil.api.dto.EventInfo;
import com.anvil.api.dto.HomeBoard;
import com.anvil.api.dto.NotifyChannels;
import com.anvil.api.dto.PlayerInfo;
import com.anvil.api.dto.RollTable;
import com.anvil.api.dto.RosterEntry;
import com.anvil.api.dto.ScheduleResponse;
import com.anvil.api.dto.ServerInfo;
import com.anvil.api.dto.StartProof;
import com.anvil.api.dto.TeamInfo;
import com.anvil.api.dto.TierBand;
import com.anvil.api.dto.TrackedCombatTask;
import com.anvil.api.dto.TrackedDeathless;
import com.anvil.api.dto.TrackedDiary;
import com.anvil.api.dto.TrackedDrop;
import com.anvil.api.dto.TrackedGain;
import com.anvil.api.dto.TrackedKill;
import com.anvil.api.dto.TrackedLms;
import com.anvil.api.dto.TrackedPvp;
import com.anvil.api.dto.TrackedStat;
import com.anvil.api.dto.TrackedTimed;
import com.anvil.api.dto.TrackedValue;
import com.anvil.detect.ActivityStats;
import com.anvil.detect.StartProofRules;
import com.anvil.ui.AnvilSidebarDataSource;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Skill;

public class PluginConfigResponse
{
	/**
	 * Version/capability handshake, sent by sites ≥ v1.0.0 on every /config shape. Null on
	 * older sites — {@link #serverSupports(String)} treats that as the v1.0.0 baseline set,
	 * never as "supports nothing". Contract: docs/PLUGIN_WIRE.md in the anvil site repo.
	 */
	public ServerInfo server;

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
			String host = new URI(s).getHost();
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

	/**
	 * The clans worth offering in a switcher, home first.
	 *
	 * A single clan is not a choice, so it is not a dropdown — the panel hides the control entirely
	 * rather than showing one option that does nothing.
	 */
	public List<ClanRef> switchableClans()
	{
		return clans == null ? Collections.emptyList() : clans;
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

	/**
	 * THE WHOLE BOARD'S FRACTION, from the server — not from what this plugin can see.
	 *
	 * <p>The tracked lists carry only tiles the plugin can DETECT: a drop, a KC, an XP goal. A manual
	 * tile never arrives, so counting the rows in hand denominated a 25-tile board at 10 and reported
	 * "5 / 10 tiles · 50%" beside a website, a Discord post and this same plugin's clan-switcher row
	 * all saying 5 / 25. Null on a site that predates the field, where the local count is all there is.</p>
	 */
	public BoardTally board;

	/**
	 * How a BOARD is named across clans, so every side of the dedup builds the same string.
	 *
	 * A board id and a competition id come out of different tables and collide freely, which is why
	 * the kind is half of it — see {@link ClanBoard#identity()}.
	 */
	public static String boardIdentity(int eventId)
	{
		return "bingo:" + eventId;
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
	public List<Integer> alwaysNotifyItemIds;          // ^ resolved to item ids server-side, for reliable id-match
	public boolean showKillCount = true;               // server toggle: include boss/raid KC on rare-drop posts
	// Clan-wide rare-drop rarity floor (1-in-N). A member may post only drops at least this rare;
	// their own setting can be stricter but not looser. 0 / absent = no clan floor. Lets an admin
	// quiet a noisy channel for everyone from the site instead of asking each member to edit config.
	public int dropRarityFloor;
	// What the SERVER knows about a drop and the client cannot (site capability "drop-facts"):
	// which monster a pet actually comes from, and which items a source hands over every kill.
	// Null against an older site, and every read of it degrades to the old guess-from-context
	// behaviour rather than to nothing.
	public DropFacts dropFacts;
	public ScheduleResponse schedule;   // was GET /api/plugin/schedule
	public ActiveWeekly activeWeekly;   // was GET /api/plugin/active-weekly

}
