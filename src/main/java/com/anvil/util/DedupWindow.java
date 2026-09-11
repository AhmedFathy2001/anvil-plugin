package com.anvil.util;

import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.util.concurrent.TimeUnit;

/**
 * "Have I already dealt with this, recently?" — the plugin's most-repeated question.
 *
 * <p>The game says the same thing more than once. A boss drop arrives as a server loot event and
 * again as a client one; a raid completion prints two lines that both identify the run; a rare item
 * shows up in the loot event and in the collection-log line right behind it. Every tracker therefore
 * keeps a short memory of what it just handled, and ignores a repeat inside it.</p>
 *
 * <p>Eleven of those memories were hand-rolled as {@code Map<K, Long>} of key to epoch-millis, each
 * with its own {@code synchronized} block, its own {@code now - last < WINDOW} comparison, and its
 * own idea of whether to ever throw anything away. <b>Six of them never threw anything away</b> —
 * one entry per distinct item id, npc name or diary tier, kept until the client closed. Bounded by
 * the game's own tables rather than by anything we did, which is not the same as bounded.</p>
 *
 * <p>Guava's expiring cache is that data structure, and it is already on the classpath: RuneLite's
 * client depends on Guava, so this adds nothing to the jar. Entries evict themselves after the
 * window, so there is no sweep to schedule and no sweep to forget to schedule.</p>
 *
 * <p><b>One deliberate difference.</b> Expiry is measured on a monotonic clock rather than on
 * {@code System.currentTimeMillis()}, because that is what Guava's ticker is. In ordinary running
 * they are the same; where they differ is that the wall clock can jump — an NTP correction during a
 * raid used to be able to widen or collapse every window at once. The monotonic one cannot.</p>
 *
 * <p>Thread-safe without callers doing anything: {@link #claim} is one atomic operation, where the
 * hand-rolled version was a get and a put that every site had to remember to wrap.</p>
 */
public final class DedupWindow<K>
{
	private final Cache<K, Boolean> seen;

	public DedupWindow(long windowMs)
	{
		this(windowMs, Ticker.systemTicker());
	}

	/** Test seam: drive expiry from a fake clock instead of waiting out real seconds. */
	DedupWindow(long windowMs, Ticker ticker)
	{
		this.seen = CacheBuilder.newBuilder()
			.expireAfterWrite(windowMs, TimeUnit.MILLISECONDS)
			.ticker(ticker)
			.build();
	}

	/**
	 * Take this one if nobody has, in one atomic step.
	 *
	 * @return true when this is the first sighting of {@code key} inside the window — and records it,
	 *         so the next caller gets false. False means somebody already has it; the caller should
	 *         do nothing.
	 */
	public boolean claim(K key)
	{
		return seen.asMap().putIfAbsent(key, Boolean.TRUE) == null;
	}

	/**
	 * Remember it, whether or not it was already remembered, and restart its window.
	 *
	 * <p>For the sites where the decision to act and the record of having acted are deliberately
	 * apart — a drop that passes the dedup check still has several other gates to clear before it
	 * counts as handled, and must not be recorded until it does.</p>
	 */
	public void record(K key)
	{
		seen.put(key, Boolean.TRUE);
	}

	/** Has this been seen inside the window? Asks without claiming. */
	public boolean seen(K key)
	{
		return seen.getIfPresent(key) != null;
	}

	/**
	 * Take it back out, and say whether it was still there.
	 *
	 * <p>For the park-then-collect pairs: a PvP kill is parked when the hitsplat lands and collected
	 * when the loot arrives, and it must only ever be collected once.</p>
	 */
	public boolean consume(K key)
	{
		return seen.asMap().remove(key) != null;
	}

	/** Forget everything — for a logout, where the next login may be a different account. */
	void clear()
	{
		seen.invalidateAll();
	}
}
