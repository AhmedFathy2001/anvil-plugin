package com.anvil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The per-connection derived lookup indexes for a single Anvil home's board — the multi-home
 * generalisation of the {@code itemDropIndex}/{@code killNpcIndex}/… fields that {@link AnvilPlugin}
 * rebuilds for its own (primary) connection. Each {@link AnvilConnection} owns one of these, built
 * from <em>its own</em> polled {@link PluginConfigResponse}, so a game event can be matched against
 * every connection independently with <strong>no cross-talk</strong> (connection A's Zulrah tile and
 * connection B's Zulrah tile resolve from separate maps, credit separate teams).
 *
 * <p>The build logic here is a faithful copy of {@code AnvilPlugin.rebuildItemDropIndex} and friends
 * — same lowercasing, same {@link #normalizeBossName} / {@link #normalizeRsn} normalisation — so an
 * extra connection matches drops/kills/gains/KC/XP exactly the way the primary connection does. The
 * primary connection deliberately does <em>not</em> use this class: it keeps its existing in-plugin
 * indexes untouched, preserving today's behaviour byte-for-byte.</p>
 *
 * <p>Deliberately RuneLite-free and immutable so it is fully unit-testable.</p>
 */
public final class TileIndex
{
	private static final TileIndex EMPTY = new TileIndex(
		Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
		Collections.emptyMap(), Collections.emptySet(), Collections.emptySet(), Collections.emptyList());

	/** itemId → drop tiles that count it. */
	public final Map<Integer, List<PluginConfigResponse.TrackedDrop>> itemDropIndex;
	/** lowercased NPC name → kill tiles that count it. */
	public final Map<String, List<PluginConfigResponse.TrackedKill>> killNpcIndex;
	/** itemId → gain tiles that count it. */
	public final Map<Integer, List<PluginConfigResponse.TrackedGain>> gainItemIndex;
	/** normalized RSN → teamId, for PvP "team:other" selectors. */
	public final Map<String, Integer> pvpRosterIndex;
	/** normalized in-game KC-line boss names tracked as boss-KC tiles. */
	public final Set<String> trackedKcNames;
	/** lowercased skill names tracked as skill-XP tiles. */
	public final Set<String> trackedSkillNames;
	/** timed-clear tiles (matched by activity + cap at fan-out time; no cheap key exists). */
	public final List<PluginConfigResponse.TrackedTimed> timedTiles;

	private TileIndex(
		Map<Integer, List<PluginConfigResponse.TrackedDrop>> itemDropIndex,
		Map<String, List<PluginConfigResponse.TrackedKill>> killNpcIndex,
		Map<Integer, List<PluginConfigResponse.TrackedGain>> gainItemIndex,
		Map<String, Integer> pvpRosterIndex,
		Set<String> trackedKcNames,
		Set<String> trackedSkillNames,
		List<PluginConfigResponse.TrackedTimed> timedTiles)
	{
		this.itemDropIndex = itemDropIndex;
		this.killNpcIndex = killNpcIndex;
		this.gainItemIndex = gainItemIndex;
		this.pvpRosterIndex = pvpRosterIndex;
		this.trackedKcNames = trackedKcNames;
		this.trackedSkillNames = trackedSkillNames;
		this.timedTiles = timedTiles;
	}

	/** The all-empty index — a connection with no config yet, or no active event. */
	public static TileIndex empty()
	{
		return EMPTY;
	}

	/** Build every index from one connection's config. Null config ⇒ {@link #empty()}. */
	public static TileIndex build(PluginConfigResponse cfg)
	{
		if (cfg == null)
		{
			return EMPTY;
		}
		return new TileIndex(
			buildItemDropIndex(cfg),
			buildKillNpcIndex(cfg),
			buildGainItemIndex(cfg),
			buildPvpRosterIndex(cfg),
			buildTrackedKcNames(cfg),
			buildTrackedSkillNames(cfg),
			buildTimedTiles(cfg));
	}

	/** Drop tiles that count {@code itemId} on this connection (empty when none). */
	public List<PluginConfigResponse.TrackedDrop> dropsForItem(int itemId)
	{
		List<PluginConfigResponse.TrackedDrop> l = itemDropIndex.get(itemId);
		return l == null ? Collections.emptyList() : l;
	}

	/** Kill tiles that count kills of {@code npcNameLower} (already lowercased) on this connection. */
	public List<PluginConfigResponse.TrackedKill> killsForNpc(String npcNameLower)
	{
		List<PluginConfigResponse.TrackedKill> l = killNpcIndex.get(npcNameLower);
		return l == null ? Collections.emptyList() : l;
	}

	/** Gain tiles that count {@code itemId} on this connection (empty when none). */
	public List<PluginConfigResponse.TrackedGain> gainsForItem(int itemId)
	{
		List<PluginConfigResponse.TrackedGain> l = gainItemIndex.get(itemId);
		return l == null ? Collections.emptyList() : l;
	}

	private static Map<Integer, List<PluginConfigResponse.TrackedDrop>> buildItemDropIndex(PluginConfigResponse cfg)
	{
		Map<Integer, List<PluginConfigResponse.TrackedDrop>> index = new HashMap<>();
		if (cfg.trackedDrops != null)
		{
			for (PluginConfigResponse.TrackedDrop drop : cfg.trackedDrops)
			{
				if (drop != null && drop.itemIds != null)
				{
					for (Integer id : drop.itemIds)
					{
						if (id != null)
						{
							index.computeIfAbsent(id, k -> new ArrayList<>()).add(drop);
						}
					}
				}
			}
		}
		return index;
	}

	private static Map<String, List<PluginConfigResponse.TrackedKill>> buildKillNpcIndex(PluginConfigResponse cfg)
	{
		Map<String, List<PluginConfigResponse.TrackedKill>> index = new HashMap<>();
		if (cfg.trackedKills != null)
		{
			for (PluginConfigResponse.TrackedKill kill : cfg.trackedKills)
			{
				if (kill != null && kill.targetNpcs != null)
				{
					for (String npc : kill.targetNpcs)
					{
						if (npc != null && !npc.isEmpty())
						{
							index.computeIfAbsent(npc.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(kill);
						}
					}
				}
			}
		}
		return index;
	}

	private static Map<Integer, List<PluginConfigResponse.TrackedGain>> buildGainItemIndex(PluginConfigResponse cfg)
	{
		Map<Integer, List<PluginConfigResponse.TrackedGain>> index = new HashMap<>();
		if (cfg.trackedGains != null)
		{
			for (PluginConfigResponse.TrackedGain gain : cfg.trackedGains)
			{
				if (gain != null && gain.itemIds != null)
				{
					for (Integer id : gain.itemIds)
					{
						if (id != null)
						{
							index.computeIfAbsent(id, k -> new ArrayList<>()).add(gain);
						}
					}
				}
			}
		}
		return index;
	}

	private static Map<String, Integer> buildPvpRosterIndex(PluginConfigResponse cfg)
	{
		Map<String, Integer> index = new HashMap<>();
		if (cfg.pvpRoster != null)
		{
			for (PluginConfigResponse.RosterEntry entry : cfg.pvpRoster)
			{
				if (entry != null && entry.name != null && !entry.name.isEmpty())
				{
					index.put(normalizeRsn(entry.name), entry.teamId);
				}
			}
		}
		return index;
	}

	private static Set<String> buildTrackedKcNames(PluginConfigResponse cfg)
	{
		Set<String> names = new HashSet<>();
		if (cfg.trackedKcNames != null)
		{
			for (String n : cfg.trackedKcNames)
			{
				if (n != null && !n.isEmpty())
				{
					names.add(normalizeBossName(n));
				}
			}
		}
		return names;
	}

	private static Set<String> buildTrackedSkillNames(PluginConfigResponse cfg)
	{
		Set<String> names = new HashSet<>();
		if (cfg.trackedSkillNames != null)
		{
			for (String n : cfg.trackedSkillNames)
			{
				if (n != null && !n.isEmpty())
				{
					names.add(n.toLowerCase(Locale.ROOT).trim());
				}
			}
		}
		return names;
	}

	private static List<PluginConfigResponse.TrackedTimed> buildTimedTiles(PluginConfigResponse cfg)
	{
		if (cfg.trackedTimed == null || cfg.trackedTimed.isEmpty())
		{
			return Collections.emptyList();
		}
		List<PluginConfigResponse.TrackedTimed> tiles = new ArrayList<>();
		for (PluginConfigResponse.TrackedTimed t : cfg.trackedTimed)
		{
			if (t != null && t.activity != null && !t.activity.isEmpty())
			{
				tiles.add(t);
			}
		}
		return Collections.unmodifiableList(tiles);
	}

	/** Mirrors {@code AnvilPlugin.normalizeBossName}: lowercase, non-alphanumeric → space, collapse. */
	public static String normalizeBossName(String s)
	{
		return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
	}

	/** Mirrors {@code AnvilPlugin.normalizeRsn}: NBSP → space, trim, lowercase. */
	public static String normalizeRsn(String name)
	{
		return name == null ? "" : name.replace('\u00A0', ' ').trim().toLowerCase();
	}
}
