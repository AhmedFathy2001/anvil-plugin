package com.anvil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Progress this client has seen on a tile that the site hasn't confirmed yet — a kill, a drop, a KC
 * line, an XP drop — so the sidebar and the pinned tracker move the moment it happens instead of on
 * the next poll after the push lands.
 *
 * <p>A floor, not a replacement: every surface shows {@code max(site count, floor)}. It lives outside
 * the polled config, so a config refresh that lands before the push doesn't wind the count back. Each
 * floor goes away once the site reaches it, when the push failed ({@link #clear}), or after
 * {@link #TTL_MS} — a floor the site never reaches was wrong, and the site's number wins.
 */
final class OptimisticProgress
{
	static final long TTL_MS = 120_000;

	/** tileId → {floor, expiresAt}. */
	private final Map<Integer, long[]> floors = new ConcurrentHashMap<>();
	/**
	 * tileId → the highest count the site has been seen at, from any source (a tile push, the config).
	 * The next gain builds on this rather than on the caller's possibly older count: a teammate's
	 * progress pushed onto the tracker otherwise sat above our floor, and our own gains looked frozen
	 * until they climbed past it. Building on it can only undercount for a moment (if our earlier
	 * gains hadn't reached the site yet), never overcount.
	 */
	private final Map<Integer, Long> seenSite = new ConcurrentHashMap<>();

	/** This client now has the tile at {@code current} (an absolute team count it just bumped to). */
	void raise(int tileId, long current, long now)
	{
		if (tileId <= 0 || current <= 0)
		{
			return;
		}
		floors.merge(tileId, new long[]{current, now + TTL_MS},
			(old, add) -> new long[]{Math.max(old[0], add[0]), add[1]});
	}

	/** This client gained {@code delta} on a tile the site last put at {@code siteCurrent}. */
	void add(int tileId, long siteCurrent, long delta, long now)
	{
		if (tileId <= 0 || delta <= 0)
		{
			return;
		}
		long[] f = live(tileId, siteCurrent, now);
		long base = Math.max(Math.max(siteCurrent, seenSite.getOrDefault(tileId, 0L)), f == null ? 0 : f[0]);
		raise(tileId, base + delta, now);
	}

	/** What to show for a tile the site puts at {@code siteCurrent}. */
	long apply(int tileId, long siteCurrent, long now)
	{
		seen(tileId, siteCurrent);
		long[] f = live(tileId, siteCurrent, now);
		return f == null ? siteCurrent : Math.max(siteCurrent, f[0]);
	}

	/**
	 * The floor alone (0 when none or expired), without deciding it's been reached. For a caller that
	 * holds a count already including it — the panel's on-screen number — which must never be passed
	 * to {@link #apply} as the site's: that would read as "caught up" and throw the floor away.
	 */
	long floor(int tileId, long now)
	{
		long[] f = floors.get(tileId);
		return f == null || now > f[1] ? 0 : f[0];
	}

	/** The site has this tile at {@code siteCurrent} (see {@link #seenSite}). */
	void seen(int tileId, long siteCurrent)
	{
		if (tileId > 0 && siteCurrent > 0)
		{
			seenSite.merge(tileId, siteCurrent, Math::max);
		}
	}

	/** The push behind this tile's floor failed — back to what the site says. */
	void clear(int tileId)
	{
		floors.remove(tileId);
		seenSite.remove(tileId);
	}

	void reset()
	{
		floors.clear();
		seenSite.clear();
	}

	/** The tile's floor while it still means something; drops it once the site caught up or it expired. */
	private long[] live(int tileId, long siteCurrent, long now)
	{
		long[] f = floors.get(tileId);
		if (f == null)
		{
			return null;
		}
		if (siteCurrent >= f[0] || now > f[1])
		{
			floors.remove(tileId, f);
			return null;
		}
		return f;
	}
}
