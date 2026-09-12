package com.anvil.api.dto;

import java.util.List;
import java.util.Map;
import net.runelite.api.Item;

/**
 * Server-supplied drop knowledge. Both maps are keyed by LOWERCASED name — the item's for
 * {@code guaranteed}, the pet's for {@code pets} — because that is what the client has in hand
 * when a post is being written, and it survives an item-id variant swap.
 */
public class DropFacts
{
	/** Pet name -> where it really comes from. */
	public Map<String, Pet> pets;
	/**
	 * Item name -> the sources that drop it EVERY time, lowercased. A single {@code "*"} entry
	 * means "guaranteed wherever it drops" (a clan override that didn't name sources).
	 */
	public Map<String, List<String>> guaranteed;

	public static class Pet
	{
		/** Every source that drops this pet. Empty for a skilling pet — there is no monster. */
		public List<String> sources;
		/** "npc" (killable, so a rate and a KC exist), "event" (raid/minigame), or "skill". */
		public String kind;
		/** For a skilling pet, the skill it comes from. Null otherwise. */
		public String skill;
	}
}
