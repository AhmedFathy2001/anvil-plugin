package com.anvil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What happened recently, so a saved clip can say what it caught.
 *
 * A clip posted as "<rsn> saved a clip 🎬" tells nobody whether they are about to watch a vestige
 * drop or someone walking to a bank. The plugin already knows: within the same few seconds it
 * notices drops, kills, tile completions, deaths and mission announces. This keeps a short trail of
 * those, and when the replay buffer is flushed the caller asks for the ones that fall inside the
 * clip's own window — the buffer holds the last N seconds of footage, so the moments from the last
 * N seconds are exactly what is in it.
 *
 * Deliberately tiny and dumb: a bounded deque of (timestamp, text), no allocation on the hot path
 * beyond one record, and no dependency on anything else in the plugin. Touched from the client
 * thread (game events) and read from the executor when a clip lands, so every method is synchronized.
 */
class ClipMoments
{
	/**
	 * A safety net, not the eviction policy — see RETENTION_MS.
	 *
	 * This used to be 40 with plain FIFO eviction, on the reasoning that a 60s buffer can't produce
	 * more than that. True of the WINDOW, false of the WAIT: OBS can take minutes to write a clip on
	 * a busy machine, and a raid fills 40 entries long before then, so by the time the file landed
	 * the moments it was supposed to describe had been evicted by newer ones.
	 */
	private static final int MAX_ENTRIES = 400;

	/**
	 * How long a moment is kept. Long enough that a clip whose file arrives late can still find what
	 * it caught; short enough that this never becomes a session-long log.
	 */
	private static final long RETENTION_MS = 15 * 60 * 1000L;

	/**
	 * Grace added to the clip window when selecting moments. OBS writes the file a moment after the
	 * event, and the buffer's start edge is approximate, so a strict cutoff drops the very drop the
	 * player clipped.
	 */
	private static final long WINDOW_GRACE_MS = 3000L;

	private static final class Moment
	{
		final long at;
		final String text;

		Moment(long at, String text)
		{
			this.at = at;
			this.text = text;
		}
	}

	private final Deque<Moment> entries = new ArrayDeque<>();

	/** Note something worth clipping. No-ops on blank text. */
	synchronized void record(String text)
	{
		record(text, System.currentTimeMillis());
	}

	/**
	 * The same, at an explicit time. Exists so tests can lay moments out across minutes without
	 * sleeping through them — the behaviour worth pinning here is entirely about WHEN things
	 * happened relative to each other, and a test that can't control that can't check it.
	 */
	synchronized void record(String text, long atMs)
	{
		if (text == null)
		{
			return;
		}
		String trimmed = text.trim();
		if (trimmed.isEmpty())
		{
			return;
		}
		long now = atMs;
		// Age out first: dropping the oldest ENTRY is what let a busy raid evict the moments a
		// pending clip still needed. Dropping the oldest MINUTES can't.
		while (!entries.isEmpty() && now - entries.peekFirst().at > RETENTION_MS)
		{
			entries.removeFirst();
		}
		while (entries.size() >= MAX_ENTRIES)
		{
			entries.removeFirst();
		}
		entries.addLast(new Moment(now, trimmed));
	}

	synchronized void clear()
	{
		entries.clear();
	}

	/**
	 * A one-line summary of everything inside a clip of {@code windowSeconds}, newest first, or null
	 * when nothing notable happened. Duplicates collapse — ten kills of the same NPC in one window
	 * are one line, not ten.
	 *
	 * @param maxItems how many distinct moments to name before summarising the remainder
	 */
	synchronized String summarize(long asOfMs, int windowSeconds, int maxItems)
	{
		// The window is the footage: it ends when the clip was ASKED FOR, not when its file turned
		// up. Anchoring on "now" meant a clip OBS took ten minutes to write got captioned with
		// whatever happened in the last thirty seconds of those ten minutes — a drop the player
		// wasn't clipping, or a boss they'd since walked to.
		long end = asOfMs + WINDOW_GRACE_MS;
		long cutoff = asOfMs - (Math.max(1, windowSeconds) * 1000L) - WINDOW_GRACE_MS;
		// LinkedHashSet: dedup while keeping the order we walk them in (newest first).
		Set<String> recent = new LinkedHashSet<>();
		List<Moment> all = new ArrayList<>(entries);
		for (int i = all.size() - 1; i >= 0; i--)
		{
			Moment m = all.get(i);
			if (m.at > end)
			{
				continue; // happened after the clip ended — not in the footage
			}
			if (m.at < cutoff)
			{
				break; // entries are append-ordered, so everything earlier is older still
			}
			recent.add(m.text);
		}
		if (recent.isEmpty())
		{
			return null;
		}
		List<String> named = new ArrayList<>(recent);
		int limit = Math.max(1, maxItems);
		if (named.size() <= limit)
		{
			return String.join("\n", named);
		}
		String head = String.join("\n", named.subList(0, limit));
		int rest = named.size() - limit;
		return head + "\n…and " + rest + " more";
	}
}
