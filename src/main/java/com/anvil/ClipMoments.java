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
	/** Plenty for a clip window: a 60s buffer during a busy raid won't produce more than this. */
	private static final int MAX_ENTRIES = 40;

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
		if (text == null)
		{
			return;
		}
		String trimmed = text.trim();
		if (trimmed.isEmpty())
		{
			return;
		}
		if (entries.size() >= MAX_ENTRIES)
		{
			entries.removeFirst();
		}
		entries.addLast(new Moment(System.currentTimeMillis(), trimmed));
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
	synchronized String summarize(int windowSeconds, int maxItems)
	{
		long cutoff = System.currentTimeMillis() - (Math.max(1, windowSeconds) * 1000L) - WINDOW_GRACE_MS;
		// LinkedHashSet: dedup while keeping the order we walk them in (newest first).
		Set<String> recent = new LinkedHashSet<>();
		List<Moment> all = new ArrayList<>(entries);
		for (int i = all.size() - 1; i >= 0; i--)
		{
			Moment m = all.get(i);
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
