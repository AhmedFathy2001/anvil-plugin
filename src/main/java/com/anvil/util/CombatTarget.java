package com.anvil.util;

import javax.inject.Singleton;

/**
 * The last thing we hit, and when — the client's only answer to "who was I fighting?"
 *
 * <p>A hitsplat says how much damage and what type; it never says who dealt it. So the plugin keeps
 * the name of whatever we most recently landed a hit on, and two features read it:</p>
 *
 * <ul>
 *   <li>A death, to name the thing that killed us rather than the thing we were killing.</li>
 *   <li>A saved clip with nothing else notable in it, so the caption can read "Fighting Vorkath"
 *       instead of a timestamp with extra steps.</li>
 * </ul>
 *
 * <p><b>Both of those read it late</b>, which is the whole reason this is a value you snapshot
 * rather than a field you peek at. OBS can take a minute to write a clip; by the time it lands, the
 * live target is whatever the player wandered into since, and captioning the footage with a boss
 * that is not in it is exactly the bug the snapshot exists to avoid. {@link #snapshot()} takes the
 * pair together so the name and its timestamp cannot disagree.</p>
 */
@Singleton
public class CombatTarget
{
	/** Name and time, taken together — separately they can be read either side of a write. */
	public static final class Seen
	{
		public final String name;
		public final long at;

		public Seen(String name, long at)
		{
			this.name = name;
			this.at = at;
		}

		/** Was this seen recently enough to still be worth saying, as of {@code asOfMs}? */
		public boolean freshAt(long asOfMs, long windowMs)
		{
			long since = asOfMs - at;
			return name != null && since >= 0 && since <= windowMs;
		}
	}

	private volatile Seen seen = new Seen(null, 0L);

	/** We just landed a hit on this. Called from the client thread. */
	public void note(String name)
	{
		seen = new Seen(name, System.currentTimeMillis());
	}

	/** The pair as it stands, safe to hold on to. */
	public Seen snapshot()
	{
		return seen;
	}
}
