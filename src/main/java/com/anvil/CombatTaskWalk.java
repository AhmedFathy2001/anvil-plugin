package com.anvil;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.StructComposition;

/**
 * Which combat achievements this account has actually completed, task by task.
 *
 * <p>The game keeps every task as a struct — its name, its tier, and the varbit that says whether
 * it's done — listed in an enum. Reading those is the only honest way to answer "which tasks am I
 * missing": the chat line fires only as tasks complete, so anything cleared before the plugin was
 * installed would be invisible forever.
 *
 * <p>The catch is that the enum id and the struct's parameter numbers aren't in RuneLite's API, and
 * hardcoding a guess would silently mark tasks wrong. So this CALIBRATES: it tries the plausible
 * combinations and accepts one only when the points it derives match the game's own total exactly —
 * a number we read from a varbit RuneLite does name. A misread layout essentially cannot produce
 * the right total across hundreds of tasks, and when none match, this reports nothing at all rather
 * than something false. The winning combination is remembered for the session.
 */
@Slf4j
final class CombatTaskWalk
{
	/** What a task is worth, by tier, matching the game's own scoring. */
	private static final int[] TIER_POINTS = {1, 2, 3, 4, 5, 6};

	/** Enums that might list the task structs. The right one holds several hundred entries. */
	private static final int[] ENUM_CANDIDATES = {3981, 3980, 3982, 3979, 4149};
	private static final int[] NAME_PARAMS = {1308, 1307, 1309};
	private static final int[] TIER_PARAMS = {1310, 1309, 1311};
	private static final int[] VARBIT_PARAMS = {1306, 1312, 1313, 1307};

	/** A list this short isn't the combat achievements; it's some other enum that happened to parse. */
	private static final int MIN_TASKS = 200;

	/** The combination that reconciled, once found. Null until then. */
	private static volatile int[] calibrated;

	private CombatTaskWalk()
	{
	}

	/**
	 * Every completed task, or an empty list when we can't be sure.
	 *
	 * @param caPoints the game's own total, from VarbitID.CA_POINTS — what we reconcile against
	 */
	static List<AccountProgress.Item> completed(Client client, int caPoints)
	{
		if (client == null || caPoints <= 0)
		{
			return new ArrayList<>();
		}

		int[] combo = calibrated;
		if (combo != null)
		{
			List<AccountProgress.Item> done = read(client, combo, caPoints);
			if (!done.isEmpty())
			{
				return done;
			}
			// The layout moved under us (a game update), or the points changed mid-read. Re-calibrate
			// rather than report something that no longer reconciles.
			calibrated = null;
		}

		for (int enumId : ENUM_CANDIDATES)
		{
			EnumComposition tasks = safeEnum(client, enumId);
			if (tasks == null || tasks.size() < MIN_TASKS)
			{
				continue;
			}
			for (int nameParam : NAME_PARAMS)
			{
				for (int tierParam : TIER_PARAMS)
				{
					for (int varbitParam : VARBIT_PARAMS)
					{
						// Tiers may be numbered from 0 or from 1; both read the same way, so try each
						// and let the points decide which the game meant.
						for (int tierBase = 0; tierBase <= 1; tierBase++)
						{
							int[] candidate = {enumId, nameParam, tierParam, varbitParam, tierBase};
							List<AccountProgress.Item> done = read(client, candidate, caPoints);
							if (!done.isEmpty())
							{
								calibrated = candidate;
								log.debug("Anvil: combat task layout calibrated (enum {}, params {}/{}/{}, tier base {})",
									enumId, nameParam, tierParam, varbitParam, tierBase);
								return done;
							}
						}
					}
				}
			}
		}
		return new ArrayList<>();
	}

	/**
	 * Read the task list with one candidate layout, returning it ONLY when the points it implies
	 * match the game's total. Any mismatch — including a single misread task — returns nothing.
	 */
	private static List<AccountProgress.Item> read(Client client, int[] combo, int caPoints)
	{
		EnumComposition tasks = safeEnum(client, combo[0]);
		if (tasks == null || tasks.size() < MIN_TASKS)
		{
			return new ArrayList<>();
		}
		List<AccountProgress.Item> done = new ArrayList<>();
		int points = 0;
		for (int structId : tasks.getIntVals())
		{
			StructComposition task;
			try
			{
				task = client.getStructComposition(structId);
			}
			catch (RuntimeException e)
			{
				return new ArrayList<>(); // not a struct enum at all
			}
			if (task == null)
			{
				return new ArrayList<>();
			}
			String name = task.getStringValue(combo[1]);
			int tier = task.getIntValue(combo[2]) - combo[4];
			int varbitId = task.getIntValue(combo[3]);
			if (name == null || name.isEmpty() || tier < 0 || tier >= TIER_POINTS.length || varbitId <= 0)
			{
				return new ArrayList<>();
			}
			if (client.getVarbitValue(varbitId) > 0)
			{
				points += TIER_POINTS[tier];
				done.add(new AccountProgress.Item(structId, name, 2));
			}
		}
		return points == caPoints ? done : new ArrayList<>();
	}

	private static EnumComposition safeEnum(Client client, int id)
	{
		try
		{
			return client.getEnum(id);
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}
}
