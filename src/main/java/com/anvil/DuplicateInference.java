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
	private static final Map<String, Integer> CHAMPION_SCROLLS = new LinkedHashMap<>();
	static
	{
		CHAMPION_SCROLLS.put("hobgoblin*", ItemID.CHAMPIONS_CHALLENGE_HOBGOBLIN);
		CHAMPION_SCROLLS.put("goblin*", ItemID.CHAMPIONS_CHALLENGE_GOBLIN);
		CHAMPION_SCROLLS.put("imp", ItemID.CHAMPIONS_CHALLENGE_IMP);
		CHAMPION_SCROLLS.put("skeleton*", ItemID.CHAMPIONS_CHALLENGE_SKELETON);
		CHAMPION_SCROLLS.put("zombie*", ItemID.CHAMPIONS_CHALLENGE_ZOMBIE);
		CHAMPION_SCROLLS.put("ghoul", ItemID.CHAMPIONS_CHALLENGE_GHOUL);
		CHAMPION_SCROLLS.put("earth warrior", ItemID.CHAMPIONS_CHALLENGE_EARTHWARRIOR);
		CHAMPION_SCROLLS.put("jogre", ItemID.CHAMPIONS_CHALLENGE_JOGRE);
		CHAMPION_SCROLLS.put("lesser demon", ItemID.CHAMPIONS_CHALLENGE_LESSERDEMON);
		CHAMPION_SCROLLS.put("* giant", ItemID.CHAMPIONS_CHALLENGE_GIANT);
	}

	/** The champion's scroll item id {@code npc} drops, or null. */
	static Integer championScrollFor(String npc)
	{
		if (npc == null)
		{
			return null;
		}
		String n = npc.trim().toLowerCase(Locale.ROOT);
		for (Map.Entry<String, Integer> e : CHAMPION_SCROLLS.entrySet())
		{
			String k = e.getKey();
			if (k.endsWith("*") ? n.startsWith(k.substring(0, k.length() - 1))
				: k.startsWith("*") ? n.endsWith(k.substring(1)) : n.equals(k))
			{
				return e.getValue();
			}
		}
		return null;
	}
}
