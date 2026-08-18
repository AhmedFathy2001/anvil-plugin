package com.anvil;

import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.Client;
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
		out.put("caPoints", caPoints);
		out.put("caTier", highestTier(client, caPoints));
		for (int tier = 0; tier < DIARY_KEYS.length; tier++)
		{
			out.put(DIARY_KEYS[tier], diaries[tier]);
		}
		return out;
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
