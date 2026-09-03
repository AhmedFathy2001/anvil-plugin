package com.anvil;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Naming a drop's source, and reading the server's facts about it.
 */
public class DropSourceTest
{
	private static PluginConfigResponse.DropFacts facts()
	{
		PluginConfigResponse.DropFacts f = new PluginConfigResponse.DropFacts();
		f.pets = new HashMap<>();
		f.guaranteed = new HashMap<>();
		return f;
	}

	private static PluginConfigResponse.DropFacts.Pet pet(String kind, String... sources)
	{
		PluginConfigResponse.DropFacts.Pet p = new PluginConfigResponse.DropFacts.Pet();
		p.kind = kind;
		p.sources = Arrays.asList(sources);
		return p;
	}

	// ---- naming the source ------------------------------------------------------------------

	@Test
	public void lootKeyIsNamedAsOneRatherThanAsAChest()
	{
		// "Loot Chest" is RuneLite's name for an opened Wilderness key. Repeating it names no
		// content and reads like PvM loot to the whole channel.
		assertTrue(DropSource.isLootKey("Loot Chest"));
		assertTrue(DropSource.isLootKey("loot key"));
		assertFalse(DropSource.isLootKey("Chest (Bryophyta's lair)"));
		assertEquals(" from a loot key", DropSource.fromPhrase("Loot Chest", "pvp"));
	}

	@Test
	public void aPlayerKillSaysSoRatherThanReadingAsAMonster()
	{
		// The source of PvP loot is the victim's RSN, so the old wording had a monster called Zezima
		// dropping a whip.
		assertEquals(" from a player kill (Zezima)", DropSource.fromPhrase("Zezima", "pvp"));
		assertEquals(" from a player kill", DropSource.fromPhrase(null, "pvp"));
	}

	@Test
	public void ordinaryLootKeepsItsOwnName()
	{
		assertEquals(" from Vorkath", DropSource.fromPhrase("Vorkath", "npc"));
		assertEquals(" from Tombs of Amascut", DropSource.fromPhrase("Tombs of Amascut", "event"));
		assertEquals(" from pickpocketing Elves", DropSource.fromPhrase("Elves", "pickpocket"));
		// Nothing true to say beats a dangling "from".
		assertEquals("", DropSource.fromPhrase(null, "npc"));
		assertEquals("", DropSource.fromPhrase("  ", "npc"));
	}

	@Test
	public void countIsLabelledForWhatWasActuallyCounted()
	{
		assertEquals("Keys opened", DropSource.countLabel("Loot Chest", "pvp"));
		assertEquals("Caskets", DropSource.countLabel("Clue Scroll (Master)", "event"));
		assertEquals("KC", DropSource.countLabel("Vorkath", "npc"));
	}

	// ---- guaranteed drops -------------------------------------------------------------------

	@Test
	public void aGuaranteedDropIsRecognisedFromTheSourceThatOwesIt()
	{
		PluginConfigResponse.DropFacts f = facts();
		f.guaranteed.put("ancient blood ornament kit", Arrays.asList("duke sucellus", "vardorvis"));
		assertTrue(DropSource.isGuaranteed(f, "Ancient blood ornament kit", "Duke Sucellus"));
		assertTrue(DropSource.isGuaranteed(f, "ANCIENT BLOOD ORNAMENT KIT", "vardorvis"));
		// Same item, a source that does not owe it: still a real roll.
		assertFalse(DropSource.isGuaranteed(f, "Ancient blood ornament kit", "The Whisperer"));
	}

	@Test
	public void aStarSourceMeansWhereverItDrops()
	{
		// What a clan override that named no sources stores.
		PluginConfigResponse.DropFacts f = facts();
		f.guaranteed.put("some kit", Collections.singletonList("*"));
		assertTrue(DropSource.isGuaranteed(f, "Some kit", "Anything"));
		assertTrue(DropSource.isGuaranteed(f, "Some kit", null));
	}

	@Test
	public void anOlderSiteKnowsNothingAndChangesNothing()
	{
		assertFalse(DropSource.isGuaranteed(null, "Ancient blood ornament kit", "Duke Sucellus"));
		assertFalse(DropSource.isGuaranteed(facts(), "Ancient blood ornament kit", "Duke Sucellus"));
		assertFalse(DropSource.isGuaranteed(facts(), null, "Duke Sucellus"));
	}

	// ---- pets -------------------------------------------------------------------------------

	@Test
	public void aPetComesFromItsOwnBossNotFromWhateverDroppedLootLast()
	{
		PluginConfigResponse.DropFacts f = facts();
		f.pets.put("baby mole", pet("npc", "Giant Mole"));
		// The client had just seen loot from something else entirely — a stray kill on the way out.
		assertEquals("Giant Mole", DropSource.resolvePetSource(f, "Baby mole", "Hill Giant", null));
		// And with nothing observed at all, which is the skilling-adjacent case.
		assertEquals("Giant Mole", DropSource.resolvePetSource(f, "Baby mole", null, null));
	}

	@Test
	public void whatTheClientSawWinsWhenItIsOneOfTheRealSources()
	{
		PluginConfigResponse.DropFacts f = facts();
		f.pets.put("vet'ion jr.", pet("npc", "Calvar'ion", "Vet'ion"));
		assertEquals("Vet'ion", DropSource.resolvePetSource(f, "Vet'ion jr.", "vet'ion", null));
		assertEquals("Calvar'ion", DropSource.resolvePetSource(f, "Vet'ion jr.", "Calvar'ion", null));
	}

	@Test
	public void severalSourcesAndNoSightingFallBackToWhereTheyActuallyGrind()
	{
		PluginConfigResponse.DropFacts f = facts();
		f.pets.put("vet'ion jr.", pet("npc", "Calvar'ion", "Vet'ion"));
		Map<String, Integer> kc = new HashMap<>();
		kc.put("calvar'ion", 12);
		kc.put("vet'ion", 940);
		assertEquals("Vet'ion", DropSource.resolvePetSource(f, "Vet'ion jr.", null, kc));
		// No kills recorded anywhere: the first listed source beats naming nothing.
		assertEquals("Calvar'ion", DropSource.resolvePetSource(f, "Vet'ion jr.", null, null));
	}

	@Test
	public void aSkillingPetHasNoMonsterAndDoesNotBorrowOne()
	{
		PluginConfigResponse.DropFacts f = facts();
		f.pets.put("beaver", pet("skill"));
		// Whatever loot the client saw, a Beaver did not come from it.
		assertNull(DropSource.resolvePetSource(f, "Beaver", "Hill Giant", null));
	}

	@Test
	public void anUnknownPetKeepsWhateverTheClientSaw()
	{
		// A pet released after the site's last dataset regen: the old guess is still the best there
		// is, so nothing gets worse while the server catches up.
		assertEquals("New Boss", DropSource.resolvePetSource(facts(), "Newpet", "New Boss", null));
		assertEquals("New Boss", DropSource.resolvePetSource(null, "Newpet", "New Boss", null));
	}
}
