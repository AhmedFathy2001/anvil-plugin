package com.anvil;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What killed us — as opposed to what we were killing.
 *
 * <p>The feed used to answer this with the last thing we landed a hit on, which is the only thing
 * the client volunteers and is wrong exactly when it matters. It reads fine at a boss, because the
 * boss you are hitting is the boss hitting you; it reads as nonsense the moment those differ, which
 * is why the clan feed filled up with "died to Abyssal portal", "died to Volatile earth" and, best
 * of all, "died to Jug". Those are things a player attacked. None of them killed anybody.
 *
 * <p>The game will not simply tell us. A hitsplat carries a type and an amount and no attacker
 * ({@link net.runelite.api.Hitsplat}), so "who dealt that damage" is not a question the API answers.
 * What it does answer is who is TARGETING us: an NPC or player acquiring us fires an interaction
 * change naming itself. So the killer is inferred from the things that had us in their sights when
 * we last took damage, which is a different guess from the old one and wrong in far fewer places.
 *
 * <p>When nothing was on us — a mechanic, a fall, poison after the fight — the answer is null and
 * the feed says only that someone died. That is a deliberate downgrade from the old behaviour: a
 * death with no killer is a smaller story than a death with the wrong one.
 *
 * <p>Pure and RuneLite-free so it is unit-tested directly (DeathAttributionTest). The plugin feeds
 * it names; it never holds an Actor, which would keep a dead NPC alive for as long as we did.
 */
final class DeathAttribution
{
	/**
	 * How long an attacker stays a candidate after we last took a hit. Long enough to cover the
	 * gap between a killing blow and the death animation, short enough that the thing that killed
	 * us half a minute ago in another fight is not still on the list.
	 */
	private static final long DAMAGE_WINDOW_MS = 15_000;

	/** Nothing sane has this many things attacking it; the cap is against a leak, not a crowd. */
	private static final int MAX_ATTACKERS = 24;

	/** Who has us targeted, and when they acquired us. Insertion-ordered so "most recent" is cheap. */
	private final Map<String, Long> attackers = new LinkedHashMap<>();

	/** When we last took damage from something other than ourselves. */
	private long lastDamageAt;

	/**
	 * Something acquired us as its target.
	 *
	 * <p>Re-acquiring refreshes the timestamp: an NPC that switches away and back is a better
	 * candidate than one that has been plinking at us since the start of the fight, because the
	 * thing that just turned to face you is the thing that just started hitting you.
	 */
	synchronized void targetedUs(String name, long nowMs)
	{
		if (name == null || name.isEmpty())
		{
			return;
		}
		attackers.remove(name); // re-insert so iteration order stays "oldest first"
		attackers.put(name, nowMs);
		while (attackers.size() > MAX_ATTACKERS)
		{
			attackers.remove(attackers.keySet().iterator().next());
		}
	}

	/** Something stopped targeting us — it is no longer a candidate for killing us. */
	synchronized void stoppedTargetingUs(String name)
	{
		if (name != null)
		{
			attackers.remove(name);
		}
	}

	/** We took a hit from something other than ourselves. Only the timing matters; see the class doc. */
	synchronized void tookDamage(long nowMs)
	{
		lastDamageAt = nowMs;
	}

	/** Forget everything. A new login, a new region, a fresh death — none of it carries over. */
	synchronized void clear()
	{
		attackers.clear();
		lastDamageAt = 0;
	}

	/**
	 * The killer, or null when nothing can honestly be named.
	 *
	 * <p>Two rules, in order:
	 *
	 * <ol>
	 *   <li>if one of the things targeting us is the thing we were fighting, it is that one — a
	 *       mutual fight is the least ambiguous case there is, and it is most deaths;
	 *   <li>otherwise the one that acquired us most recently, because in a room where several
	 *       things are on you the one that just turned to face you is the one that finished it.
	 * </ol>
	 *
	 * <p>Null when nothing had us targeted, or when the last damage we took is too old to connect
	 * to this death at all.
	 *
	 * @param weWereFighting the last thing we landed a hit on, used only to break a tie
	 */
	synchronized String killer(String weWereFighting, long nowMs)
	{
		if (lastDamageAt <= 0 || nowMs - lastDamageAt > DAMAGE_WINDOW_MS || attackers.isEmpty())
		{
			return null;
		}
		if (weWereFighting != null && !weWereFighting.isEmpty())
		{
			String fighting = weWereFighting.toLowerCase(Locale.ROOT);
			for (String name : attackers.keySet())
			{
				if (name.toLowerCase(Locale.ROOT).equals(fighting))
				{
					return name;
				}
			}
		}
		String newest = null;
		long newestAt = Long.MIN_VALUE;
		for (Map.Entry<String, Long> entry : attackers.entrySet())
		{
			if (entry.getValue() >= newestAt)
			{
				newestAt = entry.getValue();
				newest = entry.getKey();
			}
		}
		return newest;
	}

	/** How many things currently have us targeted — for the log line that explains an attribution. */
	synchronized int attackerCount()
	{
		return attackers.size();
	}
}
