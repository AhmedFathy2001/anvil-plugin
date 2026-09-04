package com.anvil;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where a drop came from, said in a way a reader can act on.
 *
 * RuneLite hands us a loot event and a bare source string, and those two together are not the same
 * question. "Loot Chest" is what the client calls an opened Wilderness loot key, so a post built
 * straight off the string says a haul came "from Loot Chest" — which names no content, hides that
 * the items were taken off a player, and reads like a PvM drop to everyone in the channel. A player
 * kill has the mirror problem: the source is the victim's RSN, so the post reads as though a monster
 * called <i>Zezima</i> dropped a whip.
 *
 * <p>The kind is already classified correctly upstream (loot keys are re-typed to "pvp" in
 * onLootReceived so PvM drop tiles reject their contents) — this is only about saying it out loud.
 *
 * <p>Also the reader for the server's {@code dropFacts}: which monster a pet is really from, and
 * whether an item was ever a roll at all. Both are the server's to know (a new boss is a dataset
 * regen there, a release here), so everything below degrades to the old behaviour on a null.
 *
 * <p>Pure and RuneLite-free, so it is unit-tested directly (DropSourceTest).
 */
final class DropSource
{
	private DropSource()
	{
	}

	/** The client's name for an opened Wilderness loot key. Not a chest, and not our wording. */
	static boolean isLootKey(String source)
	{
		if (source == null)
		{
			return false;
		}
		String s = source.toLowerCase(Locale.ROOT);
		return s.equals("loot chest") || s.contains("loot key");
	}

	/**
	 * The trailing phrase for a drop post: {@code " from Vorkath"}, {@code " from a loot key"},
	 * {@code " from a player kill (Zezima)"}, or {@code ""} when there is nothing true to say.
	 *
	 * <p>Returned with its leading space so a caller can append it unconditionally, the way the
	 * source string was appended before.
	 */
	static String fromPhrase(String source, String sourceKind)
	{
		String label = label(source, sourceKind);
		return label == null ? "" : " from " + label;
	}

	/**
	 * How to name this source, or null when it can't be named.
	 *
	 * <p>A loot key never names its victim — the key is opened long after and somewhere else, and
	 * the client does not carry who it came from — so it is named as itself rather than dressed up
	 * as content. A player kill does know, and naming them is the whole story of the drop.
	 */
	static String label(String source, String sourceKind)
	{
		if (isLootKey(source))
		{
			return "a loot key";
		}
		boolean named = source != null && !source.trim().isEmpty();
		if ("pvp".equals(sourceKind))
		{
			return named ? "a player kill (" + source.trim() + ")" : "a player kill";
		}
		if (!named)
		{
			return null;
		}
		if ("pickpocket".equals(sourceKind))
		{
			return "pickpocketing " + source.trim();
		}
		return source.trim();
	}

	/**
	 * What the count against this source is called.
	 *
	 * <p>A loot key's "kill count" is keys opened and a clue casket's is caskets, and labelling
	 * either of those KC invites the reader to compare it against a drop rate that has nothing to do
	 * with it. Everything else is a kill count, which is what "KC" has always meant here.
	 */
	static String countLabel(String source, String sourceKind)
	{
		if (isLootKey(source))
		{
			return "Keys opened";
		}
		if (source != null && source.toLowerCase(Locale.ROOT).startsWith("clue scroll"))
		{
			return "Caskets";
		}
		return "KC";
	}

	/**
	 * True when {@code source} hands {@code itemName} over on every kill, per the server's facts.
	 *
	 * <p>Guaranteed drops are the one case where a reaction line actively misfires: an ornament kit
	 * the boss owes you is nobody's spoon, and saying someone drier deserved it reads as a bot that
	 * doesn't know the game. Unknown facts (an older site, an item nothing has an opinion about)
	 * answer false, which is exactly today's behaviour.
	 *
	 * <p>A {@code "*"} source in the list means "wherever it drops" — what a clan override without
	 * named sources stores, and the only sane reading of one.
	 */
	static boolean isGuaranteed(PluginConfigResponse.DropFacts facts, String itemName, String source)
	{
		if (facts == null || facts.guaranteed == null || itemName == null)
		{
			return false;
		}
		List<String> sources = facts.guaranteed.get(itemName.toLowerCase(Locale.ROOT).trim());
		if (sources == null || sources.isEmpty())
		{
			return false;
		}
		if (sources.contains("*"))
		{
			return true;
		}
		if (source == null)
		{
			return false;
		}
		String key = source.toLowerCase(Locale.ROOT).trim();
		return sources.contains(key);
	}

	/** The server's entry for a pet, or null when it has nothing to say about it. */
	static PluginConfigResponse.DropFacts.Pet petEntry(PluginConfigResponse.DropFacts facts, String petName)
	{
		if (facts == null || facts.pets == null || petName == null)
		{
			return null;
		}
		return facts.pets.get(petName.toLowerCase(Locale.ROOT).trim());
	}

	/**
	 * Which monster this pet actually came from.
	 *
	 * <p>A pet fires no loot event, so the only thing the client can offer is whatever loot it
	 * happened to see last — a minion, a stray kill, the chest rather than the raid. The pet's name
	 * is the reliable key, and the server holds the map from it. The rules, in order:
	 *
	 * <ol>
	 *   <li>if the loot the client saw IS one of the pet's real sources, that one — it is the most
	 *       specific answer available and disambiguates a pet with several (Vet'ion / Calvar'ion);
	 *   <li>if the pet has exactly one source, that one, whatever the client thought;
	 *   <li>otherwise the source the player has the most kills at, which is the likeliest of them;
	 *   <li>failing all of that, the pet's first listed source, and if there are none, null — a
	 *       skilling pet has no monster and a made-up one is worse than a shorter post.
	 * </ol>
	 *
	 * @param observed  the loot source the client saw around the drop; may be null
	 * @param killCount source name -> the player's kill count there, for the tie-break; may be null
	 */
	static String resolvePetSource(PluginConfigResponse.DropFacts facts, String petName, String observed,
								   Map<String, Integer> killCount)
	{
		PluginConfigResponse.DropFacts.Pet pet = petEntry(facts, petName);
		if (pet == null || pet.sources == null || pet.sources.isEmpty())
		{
			// Nothing known about this pet: an older site, or a pet the catalogue has not placed.
			// The observed source is then the best guess there is, which is where we started.
			return pet == null ? observed : null;
		}
		List<String> sources = pet.sources;
		if (observed != null && !observed.trim().isEmpty())
		{
			for (String candidate : sources)
			{
				if (candidate.equalsIgnoreCase(observed.trim()))
				{
					return candidate;
				}
			}
		}
		if (sources.size() == 1)
		{
			return sources.get(0);
		}
		String best = null;
		int bestKc = -1;
		Map<String, Integer> counts = killCount == null ? Collections.emptyMap() : killCount;
		for (String candidate : sources)
		{
			Integer kc = counts.get(candidate.toLowerCase(Locale.ROOT));
			int value = kc == null ? -1 : kc;
			if (value > bestKc)
			{
				bestKc = value;
				best = candidate;
			}
		}
		return best != null ? best : sources.get(0);
	}
}
