package com.anvil;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Naming the duplicates the game won't name — and refusing to guess when it can't be sure. */
public class DuplicateInferenceTest
{
	private static PluginConfigResponse.DropFacts.Pet pet(String kind, String skill, String... sources)
	{
		PluginConfigResponse.DropFacts.Pet p = new PluginConfigResponse.DropFacts.Pet();
		p.kind = kind;
		p.skill = skill;
		p.sources = Arrays.asList(sources);
		return p;
	}

	private static PluginConfigResponse.DropFacts facts()
	{
		PluginConfigResponse.DropFacts f = new PluginConfigResponse.DropFacts();
		f.pets = new HashMap<>();
		f.pets.put("vorki", pet("npc", null, "Vorkath"));
		f.pets.put("olmlet", pet("event", null, "Chambers of Xeric", "Chambers of Xeric Challenge Mode"));
		f.pets.put("rock golem", pet("skill", "Mining"));
		f.pets.put("heron", pet("skill", "Fishing"));
		// Two pets from one source: no single answer.
		f.pets.put("pet a", pet("npc", null, "Shared Boss"));
		f.pets.put("pet b", pet("npc", null, "Shared Boss"));
		return f;
	}

	@Test
	public void aBossNamesItsPet()
	{
		assertEquals("vorki", DuplicateInference.petForSource(facts(), "vorkath"));
		assertEquals("olmlet", DuplicateInference.petForSource(facts(), "Chambers of Xeric Challenge Mode"));
	}

	@Test
	public void aSkillNamesItsPet()
	{
		assertEquals("rock golem", DuplicateInference.petForSkill(facts(), "Mining"));
		assertNull(DuplicateInference.petForSkill(facts(), "Cooking"));
	}

	@Test
	public void ambiguityIsNotAnAnswer()
	{
		assertNull(DuplicateInference.petForSource(facts(), "Shared Boss"));
		assertNull(DuplicateInference.petForSource(facts(), "Goblin"));
		assertNull(DuplicateInference.petForSource(null, "Vorkath"));
		PluginConfigResponse.DropFacts empty = new PluginConfigResponse.DropFacts();
		empty.pets = Collections.emptyMap();
		assertNull(DuplicateInference.petForSkill(empty, "Mining"));
	}

	@Test
	public void championScrollsComeFromOneMonsterEach()
	{
		assertEquals(Integer.valueOf(ItemID.CHAMPIONS_CHALLENGE_GOBLIN), DuplicateInference.championScrollFor("Goblin"));
		assertEquals(Integer.valueOf(ItemID.CHAMPIONS_CHALLENGE_HOBGOBLIN), DuplicateInference.championScrollFor("Hobgoblin"));
		assertEquals(Integer.valueOf(ItemID.CHAMPIONS_CHALLENGE_GIANT), DuplicateInference.championScrollFor("Hill Giant"));
		assertEquals(Integer.valueOf(ItemID.CHAMPIONS_CHALLENGE_LESSERDEMON), DuplicateInference.championScrollFor("Lesser demon"));
		assertNull(DuplicateInference.championScrollFor("Greater demon"));
		assertNull(DuplicateInference.championScrollFor(null));
	}
}
