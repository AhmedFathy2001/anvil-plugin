package com.anvil.track;

import com.anvil.notify.LootSourceMemory;
import com.anvil.notify.RareDropNotifier;
import java.util.Collection;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;

/**
 * Every way loot can reach the plugin, and what each one is allowed to credit.
 *
 * <p>Four events carry loot and they overlap, so the rules about which may count a KILL are the
 * whole point of this class:</p>
 *
 * <ul>
 *   <li><b>ServerNpcLoot</b> — the server's own signal, and the only one for corpse-looted bosses
 *       (Araxxor, Maggot King's stomach loot). It fires once per real kill, so a barraged clump of
 *       same-tick deaths is counted in full.</li>
 *   <li><b>NpcLootReceived</b> — the client-side equivalent, which under-fires clumps. It counts
 *       kills and value only when the server event did not, so the two never double.</li>
 *   <li><b>LootReceived</b> — chests, caskets, barrows, implings, opened loot keys. The source is
 *       classified so a per-tile filter can reject a drop from the wrong place.</li>
 *   <li><b>PlayerLootReceived</b> — a PvP kill's loot.</li>
 * </ul>
 */
@Singleton
public class LootRouter
{
	private final DropTracker drops;
	private final KillTracker kills;
	private final ValueTracker values;
	private final PvpTracker pvp;
	private final AchievementTiles achTiles;
	private final LmsTracker lms;
	private final RareDropNotifier rareDrops;
	private final RecapCounters counters;
	private final LootSourceMemory lootSource;

	@Inject
	LootRouter(DropTracker drops, KillTracker kills, ValueTracker values, PvpTracker pvp,
		AchievementTiles achTiles, LmsTracker lms, RareDropNotifier rareDrops,
		RecapCounters counters, LootSourceMemory lootSource)
	{
		this.drops = drops;
		this.kills = kills;
		this.values = values;
		this.pvp = pvp;
		this.achTiles = achTiles;
		this.lms = lms;
		this.rareDrops = rareDrops;
		this.counters = counters;
		this.lootSource = lootSource;
	}

	/**
	 * Server-authoritative NPC loot.
	 *
	 * <p>Kill counting stays on NpcLootReceived + the Jagex KC chat line (which already covers these
	 * bosses), so kills never double; the drop-credit dedup and the per-item rare-drop dedup absorb
	 * the overlap on everything else.</p>
	 */
	public void onServerNpcLoot(String npcName, Collection<ItemStack> items)
	{
		drops.noteServerLootSeen();
		achTiles.trackVestigeRolls(npcName, items);
		drops.processLoot(npcName, items, "npc");
		rareDrops.maybeNotifyRareDrop(npcName, items, "npc");
		// Count kills + value off the SERVER event — it fires once per real kill, so a barraged clump
		// of same-tick deaths is counted in full (NpcLootReceived under-fires those).
		values.processValueTiles(npcName, items, "npc");
		kills.processNpcKill(npcName);
	}

	public void onNpcLootReceived(String npcName, Collection<ItemStack> items)
	{
		achTiles.trackVestigeRolls(npcName, items);
		drops.processLoot(npcName, items, "npc");
		counters.recordEventLoot(npcName, items, "npc");
		rareDrops.maybeNotifyRareDrop(npcName, items, "npc");
		// Kill + value counting is owned by ServerNpcLoot when the client emits it (accurate under
		// clumps); fall back to this client-side event only when it doesn't, so the two never
		// double-count a kill. Everything above runs either way — those are per-DROP, not per-kill,
		// and ServerNpcLoot carries the same items.
		if (!drops.serverLootSeen())
		{
			values.processValueTiles(npcName, items, "npc");
			kills.processNpcKill(npcName);
		}
	}

	/** Raid chests, clue caskets, barrows, implings, and opened loot keys. */
	public void onLootReceived(LootReceived event)
	{
		// Minigame loot isn't real — an LMS kill "drops" the victim's throwaway loadout — so never
		// post it to the drops channel or feed it to any bingo tile (drop/value/rare-drop).
		if (lms.inGame())
		{
			return;
		}
		// Classify the source so per-tile filters can reject drops from the wrong place (e.g. a
		// "CoX Dragon claws" tile shouldn't credit a PK loot key).
		String kind;
		switch (event.getType())
		{
			case NPC:
				kind = "npc";
				break;
			case PLAYER:
				kind = "pvp";
				break;
			case PICKPOCKET:
				kind = "pickpocket";
				break;
			default:
				kind = "event";
				break;   // raid chests / barrows / wt / clues
		}
		// A Wilderness loot key ("Loot Chest") holds PK loot, but RuneLite reports opening it as an
		// EVENT (like a raid chest), not a PLAYER kill. Without this override it dodges the "PvP loot
		// rejected by default" drop-tile guard, so PK'd items (dragon boots, berserker ring, …)
		// wrongly credit PvM drop tiles. Treat key contents as pvp: PvM tiles reject them, pvp/value
		// tiles keep them.
		if (lootSource.isLootKeyEvent(event.getName()))
		{
			kind = "pvp";
		}
		// Clue caskets arrive under RuneLite's casket/trail name, which varies by version
		// ("Reward Casket (Master)" / "Master Treasure Trail" / "Clue Scroll (Master)"); fold them all
		// to the "Clue Scroll (Tier)" the source picker offers so a clue-restricted drop tile matches.
		String source = normalizeClueSource(event.getName());
		// Count the OPEN itself, not just what fell out — this is what lets a kill tile target a
		// chest ("open Larran's big chest 20 times") or a casket tier. The loot event is the only
		// trustworthy signal for those: the game's own "You have opened the crystal chest 128
		// times." line is a query RESPONSE (it has a "never opened" form), so counting occurrences
		// of it would credit nothing for a real open and everything for someone re-asking.
		//
		// Routed through the loot-driven kill path on purpose: it already stands down for any
		// source that also prints a "count is:" chat line, so a CoX chest can't credit the same
		// raid twice. Restricted to EVENT loot — NPC kills come through onNpcLootReceived, and
		// loot keys were re-typed to "pvp" above.
		if ("event".equals(kind))
		{
			kills.processNpcKill(source);
		}
		drops.processLoot(source, event.getItems(), kind);
		values.processValueTiles(source, event.getItems(), kind);
		counters.recordEventLoot(source, event.getItems(), kind);
		rareDrops.maybeNotifyRareDrop(source, event.getItems(), kind);
	}

	public void onPlayerLootReceived(String victim, Collection<ItemStack> items)
	{
		if (lms.inGame())
		{
			// LMS PvP loot is minigame loot — not a real drop; skip tiles + the drops channel.
			return;
		}
		drops.processLoot(victim, items, "pvp");
		values.processValueTiles(victim, items, "pvp");
		counters.recordEventLoot(victim, items, "pvp");
		// Credit any PvP kill tile with a min-loot floor that was parked at the death and whose loot
		// (priced here) reaches the floor. No-op unless such a kill is pending for this victim.
		pvp.creditPvpMinLootKillTiles(victim, items);
		rareDrops.maybeNotifyRareDrop(victim, items, "pvp");
	}

	private static final String[] CLUE_TIERS = {"beginner", "easy", "medium", "hard", "elite", "master"};

	/**
	 * Normalise any clue-casket loot source to "Clue Scroll (Tier)" (the source-picker form); returns
	 * {@code name} unchanged when it isn't a tiered clue reward (e.g. a Tempoross casket, which has
	 * no clue tier).
	 */
	static String normalizeClueSource(String name)
	{
		if (name == null)
		{
			return null;
		}
		String lower = name.toLowerCase(Locale.ROOT);
		boolean clueish = lower.contains("clue") || lower.contains("treasure trail") || lower.contains("casket");
		if (!clueish)
		{
			return name;
		}
		for (String tier : CLUE_TIERS)
		{
			if (lower.contains(tier))
			{
				return "Clue Scroll (" + Character.toUpperCase(tier.charAt(0)) + tier.substring(1) + ")";
			}
		}
		return name;
	}
}
