package com.anvil;

import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/**
 * Quest points, combat-achievement points and tier, and achievement diaries — the account progress
 * the hiscores never publish (site: lib/memberProgress).
 *
 * <p>The plugin already SEES all of it: it parses every quest, diary and combat-task completion line
 * to credit tiles and post to Discord. It just spent each one and forgot it, so the site could never
 * answer "how many quest points does this member have" — a thing the game knows exactly.
 *
 * <p>Reads varbits, so it must run on the client thread. What it produces is a small map of key →
 * value; the caller diffs it against what it last sent and pushes only the difference, which is
 * usually nothing.
 */
final class AccountProgress
{
	/** Diary completion varbits, region by region, in tier order: easy, medium, hard, elite. */
	private static final int[][] DIARY_VARBITS = {
		{VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE, VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE},
		{VarbitID.DESERT_DIARY_EASY_COMPLETE, VarbitID.DESERT_DIARY_MEDIUM_COMPLETE, VarbitID.DESERT_DIARY_HARD_COMPLETE, VarbitID.DESERT_DIARY_ELITE_COMPLETE},
		{VarbitID.FALADOR_DIARY_EASY_COMPLETE, VarbitID.FALADOR_DIARY_MEDIUM_COMPLETE, VarbitID.FALADOR_DIARY_HARD_COMPLETE, VarbitID.FALADOR_DIARY_ELITE_COMPLETE},
		{VarbitID.FREMENNIK_DIARY_EASY_COMPLETE, VarbitID.FREMENNIK_DIARY_MEDIUM_COMPLETE, VarbitID.FREMENNIK_DIARY_HARD_COMPLETE, VarbitID.FREMENNIK_DIARY_ELITE_COMPLETE},
		{VarbitID.KANDARIN_DIARY_EASY_COMPLETE, VarbitID.KANDARIN_DIARY_MEDIUM_COMPLETE, VarbitID.KANDARIN_DIARY_HARD_COMPLETE, VarbitID.KANDARIN_DIARY_ELITE_COMPLETE},
		{VarbitID.KOUREND_DIARY_EASY_COMPLETE, VarbitID.KOUREND_DIARY_MEDIUM_COMPLETE, VarbitID.KOUREND_DIARY_HARD_COMPLETE, VarbitID.KOUREND_DIARY_ELITE_COMPLETE},
		{VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE, VarbitID.LUMBRIDGE_DIARY_MEDIUM_COMPLETE, VarbitID.LUMBRIDGE_DIARY_HARD_COMPLETE, VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE},
		{VarbitID.MORYTANIA_DIARY_EASY_COMPLETE, VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE, VarbitID.MORYTANIA_DIARY_HARD_COMPLETE, VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE},
		{VarbitID.VARROCK_DIARY_EASY_COMPLETE, VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE, VarbitID.VARROCK_DIARY_HARD_COMPLETE, VarbitID.VARROCK_DIARY_ELITE_COMPLETE},
		{VarbitID.WESTERN_DIARY_EASY_COMPLETE, VarbitID.WESTERN_DIARY_MEDIUM_COMPLETE, VarbitID.WESTERN_DIARY_HARD_COMPLETE, VarbitID.WESTERN_DIARY_ELITE_COMPLETE},
		{VarbitID.WILDERNESS_DIARY_EASY_COMPLETE, VarbitID.WILDERNESS_DIARY_MEDIUM_COMPLETE, VarbitID.WILDERNESS_DIARY_HARD_COMPLETE, VarbitID.WILDERNESS_DIARY_ELITE_COMPLETE},
	};

	/**
	 * Karamja is the odd one out: only its ELITE tier has a completion varbit. Easy, medium and hard
	 * are tracked as task COUNTS whose totals we would have to hardcode — and would then be wrong
	 * about after any update that adds a task — so they are left out rather than guessed at. The site
	 * says as much next to the number.
	 */
	private static final int KARAMJA_ELITE = VarbitID.KARAMJA_DIARY_ELITE_COMPLETE;

	private static final String[] DIARY_KEYS = {"diaryEasy", "diaryMedium", "diaryHard", "diaryElite"};

	/**
	 * The key each region's tier mask is stored under, in the same order as {@link #DIARY_VARBITS},
	 * with Karamja last — it only contributes its elite bit. Matches DIARY_REGIONS on the site.
	 */
	private static final String[] DIARY_REGION_KEYS = {
		"diaryArdougne", "diaryDesert", "diaryFalador", "diaryFremennik", "diaryKandarin",
		"diaryKourend", "diaryLumbridge", "diaryMorytania", "diaryVarrock", "diaryWestern",
		"diaryWilderness",
	};

	private static final String KARAMJA_KEY = "diaryKaramja";

	private AccountProgress()
	{
	}

	/**
	 * Sample everything, right now. Client thread only.
	 *
	 * <p>Returns an empty map when the varbits read as an empty account — a few ticks after login the
	 * client has the values zeroed, and sending that would be a push claiming somebody lost their
	 * quest cape. The server max-merges as a second line of defence, but not sending nonsense is the
	 * first one.
	 */
	static Map<String, Integer> sample(Client client)
	{
		Map<String, Integer> out = new LinkedHashMap<>();
		if (client == null)
		{
			return out;
		}

		int questPoints = client.getVarpValue(VarPlayerID.QP);
		int caPoints = client.getVarbitValue(VarbitID.CA_POINTS);

		// Everything zero is what an unpopulated client looks like, not what an account looks like:
		// even a fresh account has quest points from the tutorial's first quest soon enough, and a
		// real one never has all of these at zero at once.
		int[] diaries = countDiaries(client);
		int diaryTotal = diaries[0] + diaries[1] + diaries[2] + diaries[3];
		if (questPoints <= 0 && caPoints <= 0 && diaryTotal <= 0)
		{
			return out;
		}

		out.put("questPoints", questPoints);
		out.put("questsCompleted", countQuests(client));
		out.put("caPoints", caPoints);
		out.put("caTier", highestTier(client, caPoints));
		// Every tier cleared rather than only the highest, so a profile can light them cumulatively.
		out.put("caTiers", tierMask(client, caPoints));
		for (int tier = 0; tier < DIARY_KEYS.length; tier++)
		{
			out.put(DIARY_KEYS[tier], diaries[tier]);
		}
		// Per region as well as per tier: the counts say how many, these say which.
		for (int region = 0; region < DIARY_VARBITS.length; region++)
		{
			out.put(DIARY_REGION_KEYS[region], regionMask(client, DIARY_VARBITS[region]));
		}
		out.put(KARAMJA_KEY, client.getVarbitValue(KARAMJA_ELITE) > 0 ? DIARY_ELITE_BIT : 0);
		return out;
	}

	/** Tier bits inside a region mask — the same numbering the site uses. */
	private static final int DIARY_EASY_BIT = 1;
	private static final int DIARY_MEDIUM_BIT = 2;
	private static final int DIARY_HARD_BIT = 4;
	private static final int DIARY_ELITE_BIT = 8;

	/** One region's four tiers, as a mask. */
	private static int regionMask(Client client, int[] region)
	{
		int mask = 0;
		int[] bits = {DIARY_EASY_BIT, DIARY_MEDIUM_BIT, DIARY_HARD_BIT, DIARY_ELITE_BIT};
		for (int tier = 0; tier < region.length; tier++)
		{
			if (client.getVarbitValue(region[tier]) > 0)
			{
				mask |= bits[tier];
			}
		}
		return mask;
	}

	/**
	 * Quests finished, out of everything this client build knows about.
	 *
	 * <p>An enum walk of ~200 quests, each a varp read — cheap enough for the half-minute tick that
	 * calls it, and the only way to the number: the game keeps no "quests completed" counter, only
	 * quest points, and those weight quests differently.
	 */
	private static int countQuests(Client client)
	{
		int done = 0;
		for (Quest quest : Quest.values())
		{
			if (quest.getState(client) == QuestState.FINISHED)
			{
				done++;
			}
		}
		return done;
	}

	/** One quest, as the site lists it: the game's own id and name, and which of the three states. */
	static final class Item
	{
		final int id;
		final String name;
		final int state;

		Item(int id, String name, int state)
		{
			this.id = id;
			this.name = name;
			this.state = state;
		}
	}

	/**
	 * EVERY quest with its state, not just the finished ones.
	 *
	 * <p>"Which of these haven't I done" is the question a quest list gets opened for, and a list of
	 * completions can't answer it. Names travel with the ids so the site never needs a quest dataset
	 * of its own — one released next month lists itself.
	 *
	 * <p>Client thread, like everything else here.
	 */
	static java.util.List<Item> quests(Client client)
	{
		java.util.List<Item> out = new java.util.ArrayList<>();
		if (client == null)
		{
			return out;
		}
		for (Quest quest : Quest.values())
		{
			QuestState state = quest.getState(client);
			int code = state == QuestState.FINISHED ? 2 : state == QuestState.IN_PROGRESS ? 1 : 0;
			out.add(new Item(quest.getId(), quest.getName(), code));
		}
		return out;
	}

	/** Bit per combat-achievement tier cleared: bit 0 Easy … bit 5 Grandmaster. */
	private static int tierMask(Client client, int caPoints)
	{
		int mask = 0;
		int index = 0;
		for (CombatAchievementTier t : CombatAchievementTier.values())
		{
			int threshold = client.getVarbitValue(t.getThresholdVarbitId());
			if (threshold > 0 && caPoints >= threshold)
			{
				mask |= 1 << index;
			}
			index++;
		}
		return mask;
	}

	/** Regions complete at each tier: easy, medium, hard, elite. */
	private static int[] countDiaries(Client client)
	{
		int[] counts = new int[4];
		for (int[] region : DIARY_VARBITS)
		{
			for (int tier = 0; tier < region.length; tier++)
			{
				if (client.getVarbitValue(region[tier]) > 0)
				{
					counts[tier]++;
				}
			}
		}
		if (client.getVarbitValue(KARAMJA_ELITE) > 0)
		{
			counts[3]++;
		}
		return counts;
	}

	/**
	 * The highest combat-achievement tier this account has cleared, 0 (none) through 6 (Grandmaster).
	 *
	 * <p>Read from POINTS against each tier's threshold varbit rather than from the tier-status
	 * varbits: the thresholds are what the game itself compares, they're the same numbers the
	 * completion post already uses, and a tier the player cleared but never claimed the reward for
	 * still counts as cleared.
	 */
	private static int highestTier(Client client, int caPoints)
	{
		int highest = 0;
		int tier = 0;
		for (CombatAchievementTier t : CombatAchievementTier.values())
		{
			tier++;
			int threshold = client.getVarbitValue(t.getThresholdVarbitId());
			if (threshold > 0 && caPoints >= threshold)
			{
				highest = tier;
			}
		}
		return highest;
	}
}
