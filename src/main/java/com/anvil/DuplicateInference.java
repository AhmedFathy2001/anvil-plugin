package com.anvil;

import java.util.Locale;
import java.util.Map;
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
		if (facts == null || facts.pets == null || source == null || source.trim().isEmpty())
		{
			return null;
		}
		String want = source.trim().toLowerCase(Locale.ROOT);
		String found = null;
		for (Map.Entry<String, PluginConfigResponse.DropFacts.Pet> e : facts.pets.entrySet())
		{
			PluginConfigResponse.DropFacts.Pet pet = e.getValue();
			if (pet == null || pet.sources == null)
			{
				continue;
			}
			for (String s : pet.sources)
			{
				if (s != null && s.trim().toLowerCase(Locale.ROOT).equals(want))
				{
					if (found != null && !found.equals(e.getKey()))
					{
						return null;
					}
					found = e.getKey();
				}
			}
		}
		return found;
	}

	/** The one skilling pet {@code skill} gives, or null. */
	static String petForSkill(PluginConfigResponse.DropFacts facts, String skill)
	{
		if (facts == null || facts.pets == null || skill == null || skill.trim().isEmpty())
		{
			return null;
		}
		String found = null;
		for (Map.Entry<String, PluginConfigResponse.DropFacts.Pet> e : facts.pets.entrySet())
		{
			PluginConfigResponse.DropFacts.Pet pet = e.getValue();
			if (pet != null && "skill".equals(pet.kind) && skill.trim().equalsIgnoreCase(pet.skill))
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

	/** The champion's scroll item id {@code npc} drops, or null. */
	static Integer championScrollFor(String npc)
	{
		if (npc == null)
		{
			return null;
		}
		String n = npc.trim().toLowerCase(Locale.ROOT);
		// Hobgoblin before goblin: one contains the other.
		if (n.startsWith("hobgoblin"))
		{
			return ItemID.CHAMPIONS_CHALLENGE_HOBGOBLIN;
		}
		if (n.startsWith("goblin"))
		{
			return ItemID.CHAMPIONS_CHALLENGE_GOBLIN;
		}
		if (n.equals("imp"))
		{
			return ItemID.CHAMPIONS_CHALLENGE_IMP;
		}
		if (n.startsWith("skeleton"))
		{
			return ItemID.CHAMPIONS_CHALLENGE_SKELETON;
		}
		if (n.startsWith("zombie"))
		{
			return ItemID.CHAMPIONS_CHALLENGE_ZOMBIE;
		}
		if (n.equals("ghoul"))
		{
			return ItemID.CHAMPIONS_CHALLENGE_GHOUL;
		}
		if (n.equals("earth warrior"))
		{
			return ItemID.CHAMPIONS_CHALLENGE_EARTHWARRIOR;
		}
		if (n.equals("jogre"))
		{
			return ItemID.CHAMPIONS_CHALLENGE_JOGRE;
		}
		if (n.equals("lesser demon"))
		{
			return ItemID.CHAMPIONS_CHALLENGE_LESSERDEMON;
		}
		if (n.endsWith(" giant"))
		{
			return ItemID.CHAMPIONS_CHALLENGE_GIANT;
		}
		return null;
	}
}
