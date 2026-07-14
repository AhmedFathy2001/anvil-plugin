package com.anvil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The in-memory, bounded, deduplicated store behind the sidebar's live team feed. Consumes the
 * cursor-paginated batches returned by {@code GET /api/plugin/activity} and keeps only the most
 * recent {@link #capacity} entries, newest-first, for the panel to render.
 *
 * <p><b>Why bounded + dedup matters:</b> this lives for the whole RuneLite session. An unbounded
 * append would leak memory over a long event; re-ingesting an overlapping batch (the client resends
 * its cursor until it advances) would double every row. Both are prevented here — the ring caps at
 * {@link #capacity} and the id set rejects anything already shown.</p>
 *
 * <p>Deliberately RuneLite-free so it's fully unit-testable. Thread-safe: the poller ingests off the
 * Swing EDT while the panel reads on it, so every method synchronizes on {@code this} and reads hand
 * back an immutable snapshot.</p>
 */
public final class AnvilActivityLog
{
	/** Default ring size — enough scrollback to feel live without holding a session's worth of rows. */
	public static final int DEFAULT_CAPACITY = 50;

	/** The empty starting cursor the first poll sends (mirrors the server's {@code s0_c0} first-call value). */
	public static final String EMPTY_CURSOR = "s0_c0";

	private final int capacity;

	/** Newest-first. Head = most recent entry. */
	private final Deque<ActivityEntry> entries = new ArrayDeque<>();

	/** Mirrors {@link #entries} by id for O(1) dedup; kept in lock-step on add/evict. */
	private final Set<String> seenIds = new HashSet<>();

	private String cursor = EMPTY_CURSOR;

	public AnvilActivityLog()
	{
		this(DEFAULT_CAPACITY);
	}

	public AnvilActivityLog(int capacity)
	{
		this.capacity = Math.max(1, capacity);
	}

	/**
	 * Merge a server batch. {@code batch} is ascending (oldest→newest, as the endpoint returns it);
	 * genuinely-new entries are pushed to the head so the newest ends up on top. Already-seen ids are
	 * skipped. Advances the cursor to {@code newCursor}. Excess beyond {@link #capacity} is evicted
	 * oldest-first.
	 *
	 * @param newCursor the cursor to send on the next poll; {@code null}/blank leaves the cursor as-is
	 * @param batch     the entries returned by this poll (may be null/empty)
	 * @return how many entries were actually new — the panel can flash/notify on a positive count
	 */
	public synchronized int ingest(String newCursor, List<ActivityEntry> batch)
	{
		int added = 0;
		if (batch != null)
		{
			for (ActivityEntry e : batch)
			{
				if (e == null || e.id.isEmpty() || seenIds.contains(e.id))
				{
					continue;
				}
				entries.addFirst(e); // ascending input → head ends up newest
				seenIds.add(e.id);
				added++;
			}
		}
		if (newCursor != null && !newCursor.isEmpty())
		{
			cursor = newCursor;
		}
		trimToCapacity();
		return added;
	}

	/** Evict oldest (tail) entries until within capacity, keeping {@link #seenIds} in sync. */
	private void trimToCapacity()
	{
		while (entries.size() > capacity)
		{
			ActivityEntry evicted = entries.removeLast();
			if (evicted != null)
			{
				seenIds.remove(evicted.id);
			}
		}
	}

	/** Immutable snapshot, newest-first — safe to iterate on the EDT while the poller ingests. */
	public synchronized List<ActivityEntry> snapshot()
	{
		return Collections.unmodifiableList(new ArrayList<>(entries));
	}

	/** The cursor to send as {@code ?since=} on the next poll. */
	public synchronized String getCursor()
	{
		return cursor;
	}

	public synchronized int size()
	{
		return entries.size();
	}

	public synchronized boolean isEmpty()
	{
		return entries.isEmpty();
	}

	/**
	 * Drop everything and reset the cursor — call when the active event changes (a fresh event's feed
	 * must not inherit the previous one's rows or cursor). Idempotent.
	 */
	public synchronized void reset()
	{
		entries.clear();
		seenIds.clear();
		cursor = EMPTY_CURSOR;
	}
}
