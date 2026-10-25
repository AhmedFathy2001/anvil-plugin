package com.anvil;

import java.util.*;
import java.util.function.Predicate;
import net.runelite.api.gameval.ItemID;

/**
 * Which item a "funny feeling" line was about.
 *
 * <p>A duplicate pet ("…would have been followed") and a duplicate champion's scroll ("…would have
 * received a Champion's scroll") name nothing: there is no item, no loot event and no collection-log
 * line. What the player was doing names it. A pet comes from what was just killed or looted, or from
 * the skill just trained; a champion's scroll comes from exactly one kind of monster.
 *
 * <p>Every answer here is a guess the site flags for staff, so ambiguity returns null rather than a
 * pick: a wrong credit costs a reviewer more than a manual proof does.
 */
final class DuplicateInference
{
	private DuplicateInference()
	{
	}

	/** The one pet {@code source} drops, or null when it drops none or several. */
	static String petForSource(PluginConfigResponse.DropFacts facts, String source)
	{
		String want = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
		return onlyPet(facts, want, pet -> pet.sources != null
			&& pet.sources.stream().anyMatch(s -> s != null && s.trim().toLowerCase(Locale.ROOT).equals(want)));
	}

	/** The one skilling pet {@code skill} gives, or null. */
	static String petForSkill(PluginConfigResponse.DropFacts facts, String skill)
	{
		String want = skill == null ? "" : skill.trim();
		return onlyPet(facts, want, pet -> "skill".equals(pet.kind) && want.equalsIgnoreCase(pet.skill));
	}

	/** The single pet matching {@code test}, or null when none or several do (or {@code want} is blank). */
	private static String onlyPet(PluginConfigResponse.DropFacts facts, String want, Predicate<PluginConfigResponse.DropFacts.Pet> test)
	{
		if (facts == null || facts.pets == null || want.isEmpty())
		{
			return null;
		}
		String found = null;
		for (Map.Entry<String, PluginConfigResponse.DropFacts.Pet> e : facts.pets.entrySet())
		{
			if (e.getValue() != null && test.test(e.getValue()))
			{
				if (found != null)
				{
					return null;
				}
				found = e.getKey();
			}
		}
		return found;
	}

	// Champion's scrolls by NPC name, checked in order: "x*" is a prefix (hobgoblin before goblin),
	// "*x" a suffix, anything else the whole name.
	private static final String[] CHAMPION_NPCS = {
		"hobgoblin*", "goblin*", "imp", "skeleton*", "zombie*", "ghoul", "earth warrior", "jogre", "lesser demon", "* giant",
	};
	private static final int[] CHAMPION_SCROLLS = {
		ItemID.CHAMPIONS_CHALLENGE_HOBGOBLIN, ItemID.CHAMPIONS_CHALLENGE_GOBLIN, ItemID.CHAMPIONS_CHALLENGE_IMP,
		ItemID.CHAMPIONS_CHALLENGE_SKELETON, ItemID.CHAMPIONS_CHALLENGE_ZOMBIE, ItemID.CHAMPIONS_CHALLENGE_GHOUL,
		ItemID.CHAMPIONS_CHALLENGE_EARTHWARRIOR, ItemID.CHAMPIONS_CHALLENGE_JOGRE, ItemID.CHAMPIONS_CHALLENGE_LESSERDEMON,
		ItemID.CHAMPIONS_CHALLENGE_GIANT,
	};

	/** The champion's scroll item id {@code npc} drops, or null. */
	static Integer championScrollFor(String npc)
	{
		String n = npc == null ? null : npc.trim().toLowerCase(Locale.ROOT);
		for (int i = 0; n != null && i < CHAMPION_NPCS.length; i++)
		{
			String k = CHAMPION_NPCS[i];
			if (k.endsWith("*") ? n.startsWith(k.substring(0, k.length() - 1))
				: k.startsWith("*") ? n.endsWith(k.substring(1)) : n.equals(k))
			{
				return CHAMPION_SCROLLS[i];
			}
		}
		return null;
	}
}
