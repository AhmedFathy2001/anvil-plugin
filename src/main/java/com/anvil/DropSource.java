package com.anvil;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The reader for the server's {@code dropFacts}: which monster a pet is really from, and
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

	/**
	 * The in-game name for a wiki NPC name. The wiki tells same-named monsters apart with a
	 * parenthetical ("Armoured zombie (Zemouregal's Fort)"), but the game reports them all as plain
	 * "Armoured zombie", so a tile picked from the wiki list never matched. In-game NPC names carry
	 * no parentheses, so dropping the qualifier loses nothing that could have matched.
	 */
	static String inGameNpcName(String wikiName)
	{
		return wikiName == null ? null : wikiName.replaceFirst("\\s*\\([^)]*\\)\\s*$", "").trim();
	}

	/** True when the reported loot {@code source} is one of a tile's configured (wiki-named) NPCs. */
	static boolean sourceMatches(List<String> sourceNpcs, String source)
	{
		if (source == null || sourceNpcs == null)
		{
			return false;
		}
		for (String npc : sourceNpcs)
		{
			if (npc != null && (source.equalsIgnoreCase(npc) || source.equalsIgnoreCase(inGameNpcName(npc))))
			{
				return true;
			}
		}
		return false;
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
