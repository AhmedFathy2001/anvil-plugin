package com.anvil;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Where an account sits in a boss's vestige rotation.
 *
 * <p>The DT2 bosses don't hand out their vestige at random: rolls of the unique table go
 * non-vestige, non-vestige, VESTIGE. So counting uniques since the last vestige tells a player
 * something they can act on — "that's roll 2, the next unique is the vestige" — which is worth far
 * more than another "you got a Virtus mask" line. The roll tables themselves (which items count,
 * which one is the vestige, how long the cycle is) come from the server, so a game change is an
 * edit there rather than a plugin release.
 *
 * <p>Honesty about what we can know: the cycle is account state on Jagex's side, and this plugin
 * only sees drops it was running for. A count is therefore ESTIMATED until a vestige is actually
 * observed — after that the cycle is anchored and the count is EXACT. The estimate is still worth
 * showing (it's right whenever the player has been running the plugin), it just says so.
 *
 * <p>RuneLite-free so it can be unit-tested; {@link AnvilPlugin} owns persistence.
 */
final class VestigeRolls
{
	/** One boss's cycle state. */
	static final class State
	{
		/** Rolls seen since the last vestige — 0 means "a vestige is 3 rolls away". */
		final int rolls;
		/** True once a vestige has been observed, which anchors the cycle. */
		final boolean exact;

		State(int rolls, boolean exact)
		{
			this.rolls = rolls;
			this.exact = exact;
		}
	}

	/** What a drop did to the cycle, and what to say about it. */
	static final class Result
	{
		final State state;
		/** True when this drop WAS the vestige. */
		final boolean vestige;
		/** True when the next unique from this boss is guaranteed to be the vestige. */
		final boolean vestigeNext;
		/** One line for chat and the drop post, or null when there's nothing worth saying. */
		final String line;

		Result(State state, boolean vestige, boolean vestigeNext, String line)
		{
			this.state = state;
			this.vestige = vestige;
			this.vestigeNext = vestigeNext;
			this.line = line;
		}
	}

	private final Map<String, State> byBoss = new HashMap<>();

	static String key(String boss)
	{
		return boss == null ? "" : boss.trim().toLowerCase(Locale.ROOT);
	}

	State get(String boss)
	{
		return byBoss.getOrDefault(key(boss), new State(0, false));
	}

	void put(String boss, State state)
	{
		byBoss.put(key(boss), state);
	}

	/**
	 * Fold one unique-table drop into the boss's cycle. Returns null when the item isn't part of
	 * this boss's roll table (nothing to count) or the table is unusable.
	 */
	Result record(PluginConfigResponse.RollTable table, int itemId)
	{
		if (table == null || table.rollItemIds == null || !table.rollItemIds.contains(itemId))
		{
			return null;
		}
		int cycle = Math.max(2, table.rollsPerVestige);
		State was = get(table.boss);
		Result r = advance(was, itemId == table.vestigeItemId, cycle, table.vestigeName);
		put(table.boss, r.state);
		return r;
	}

	/**
	 * The rule, in one place: a vestige resets the cycle and anchors it; anything else advances it.
	 * A non-vestige arriving where the vestige was due means our count was off (drops taken while
	 * the plugin wasn't watching) — start the cycle over from this drop and stop claiming exact.
	 */
	static Result advance(State was, boolean isVestige, int rollsPerVestige, String vestigeName)
	{
		String vestige = vestigeName == null || vestigeName.isEmpty() ? "vestige" : vestigeName;
		if (isVestige)
		{
			return new Result(new State(0, true), true, false,
				vestige + "! The cycle resets — the next one is " + rollsPerVestige + " rolls away.");
		}

		int next = was.rolls + 1;
		if (next >= rollsPerVestige)
		{
			// We thought the vestige was due and it wasn't: the count was wrong, not the game.
			return new Result(new State(1, false), false, false,
				"Roll 1 of " + rollsPerVestige + " (estimated — the count was off, re-anchoring)");
		}

		boolean vestigeNext = next == rollsPerVestige - 1;
		String line = "Roll " + next + " of " + rollsPerVestige
			+ (vestigeNext ? " — the next unique is the " + vestige + "!" : "")
			+ (was.exact ? "" : " (estimated)");
		return new Result(new State(next, was.exact), false, vestigeNext, line);
	}

	/** Serialised as "boss=rolls:exact;…" for the RuneLite config, so the cycle survives a restart. */
	String serialise()
	{
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, State> e : byBoss.entrySet())
		{
			if (sb.length() > 0)
			{
				sb.append(';');
			}
			sb.append(e.getKey()).append('=').append(e.getValue().rolls).append(':').append(e.getValue().exact ? 1 : 0);
		}
		return sb.toString();
	}

	static VestigeRolls parse(String raw)
	{
		VestigeRolls out = new VestigeRolls();
		if (raw == null || raw.trim().isEmpty())
		{
			return out;
		}
		for (String part : raw.split(";"))
		{
			String[] kv = part.split("=", 2);
			if (kv.length != 2)
			{
				continue;
			}
			String[] v = kv[1].split(":", 2);
			try
			{
				int rolls = Integer.parseInt(v[0].trim());
				boolean exact = v.length > 1 && "1".equals(v[1].trim());
				out.byBoss.put(key(kv[0]), new State(Math.max(0, rolls), exact));
			}
			catch (NumberFormatException ignored)
			{
				// A corrupted entry just loses that boss's count — it re-estimates from the next drop.
			}
		}
		return out;
	}
}
