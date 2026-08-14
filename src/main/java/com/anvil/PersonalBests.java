package com.anvil;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The account's best times, kept locally and pushed to the clan site.
 *
 * <p>Two ways in, because neither alone is enough:
 * <ul>
 *   <li><b>Live</b> — the completion messages the plugin already reads for timed tiles also carry
 *       "(new personal best)" / "Personal best: 7:59". Free: no extra chat parsing, and no waiting.</li>
 *   <li><b>Seed</b> — RuneLite's own chat-commands plugin has been recording these into its config
 *       for years. Importing that once turns a blank profile into a complete one on first login,
 *       which is what makes this feel like a sync rather than a slow accrual. The plugin reads it;
 *       this class only receives the numbers ({@link #seed}).</li>
 * </ul>
 *
 * <p>Times are centiseconds — the game separates runs by hundredths and so should a leaderboard.
 *
 * <p>The activity is supplied by the caller from the "Your X kill count is: N" line it already
 * parses, so a PB costs no additional regex on the chat hot path. Correlation works in both
 * directions because the game is inconsistent about ordering: the Inferno prints the duration
 * before the kill count, most bosses print it after.
 *
 * <p>RuneLite-free, so the correlation and flush rules are unit-testable without a client.
 */
final class PersonalBests
{
	/** How long a kill-count line and a duration line can be apart and still be the same kill. */
	static final long CORRELATION_MS = 6_000;
	/** Settle time before a push — a raid prints several lines at once. */
	static final long QUIET_MS = 3_000;

	/** Best known time per activity, keyed lowercase. Lower is better; that's the whole model. */
	private final Map<String, Integer> best = new HashMap<>();
	/** What the server already has, so an unchanged PB is never re-sent. */
	private final Map<String, Integer> sent = new HashMap<>();
	/** Activities whose best has moved since the last successful push. */
	private final Set<String> dirty = new LinkedHashSet<>();

	/** The most recent activity name from a kill-count line, and when we saw it. */
	private String recentActivity;
	private long recentActivityAt;
	/** A PB that arrived before its activity did (Inferno ordering), awaiting a name. */
	private int orphanCentis;
	private long orphanAt;

	private long lastChangeAt;

	/** The activity a kill-count line just named. Cheap: two field writes, no parsing. */
	void onActivitySeen(String activity, long now)
	{
		if (activity == null || activity.isEmpty())
		{
			return;
		}
		recentActivity = activity.toLowerCase();
		recentActivityAt = now;
		// A duration that arrived first now has its name.
		if (orphanCentis > 0 && now - orphanAt <= CORRELATION_MS)
		{
			record(recentActivity, orphanCentis, now);
			orphanCentis = 0;
		}
	}

	/**
	 * Offer a chat line. Returns true when it produced a new best.
	 *
	 * <p>Lines with no personal best in them cost one {@code indexOf} — this runs on every line of
	 * chat, so the negative path is the one that has to be free.
	 */
	boolean onChatLine(String message, long now)
	{
		Integer centis = PersonalBestParser.parseCentis(message);
		if (centis == null)
		{
			return false;
		}
		if (recentActivity != null && now - recentActivityAt <= CORRELATION_MS)
		{
			return record(recentActivity, centis, now);
		}
		// No name yet — hold it for the kill-count line that's about to arrive.
		orphanCentis = centis;
		orphanAt = now;
		return false;
	}

	/**
	 * Import times recorded by another source (RuneLite's chat-commands store).
	 *
	 * @return how many were adopted as new bests
	 */
	int seed(Map<String, Integer> centisByActivity, long now)
	{
		if (centisByActivity == null || centisByActivity.isEmpty())
		{
			return 0;
		}
		int adopted = 0;
		for (Map.Entry<String, Integer> e : centisByActivity.entrySet())
		{
			if (e.getKey() == null || e.getValue() == null)
			{
				continue;
			}
			if (record(e.getKey().toLowerCase(), e.getValue(), now))
			{
				adopted++;
			}
		}
		return adopted;
	}

	/** Keep the fastest. A PB only ever goes down, so a slower number is noise, not an update. */
	private boolean record(String activity, int centis, long now)
	{
		if (activity == null || activity.isEmpty() || centis <= 0)
		{
			return false;
		}
		Integer current = best.get(activity);
		if (current != null && current <= centis)
		{
			return false;
		}
		best.put(activity, centis);
		dirty.add(activity);
		lastChangeAt = now;
		return true;
	}

	boolean isDue(long now)
	{
		return !dirty.isEmpty() && now - lastChangeAt >= QUIET_MS;
	}

	/** The pending bests, activity → centiseconds. */
	Map<String, Integer> nextBatch()
	{
		Map<String, Integer> batch = new LinkedHashMap<>(dirty.size());
		for (String activity : dirty)
		{
			Integer centis = best.get(activity);
			if (centis != null)
			{
				batch.put(activity, centis);
			}
		}
		return batch;
	}

	/**
	 * Mark a batch delivered. Anything that improved again mid-flight stays dirty, so the better
	 * time is sent next round rather than being silently dropped.
	 */
	void onSent(Map<String, Integer> batch)
	{
		for (Map.Entry<String, Integer> e : batch.entrySet())
		{
			sent.put(e.getKey(), e.getValue());
			Integer current = best.get(e.getKey());
			if (current != null && current.equals(e.getValue()))
			{
				dirty.remove(e.getKey());
			}
		}
	}

	int knownCount()
	{
		return best.size();
	}

	int pendingCount()
	{
		return dirty.size();
	}

	/** Forget everything — the next account's bests are not this one's. */
	void reset()
	{
		best.clear();
		sent.clear();
		dirty.clear();
		recentActivity = null;
		orphanCentis = 0;
		lastChangeAt = 0;
	}

	/**
	 * The delivered bests as one config string: {@code <centis>|<activity>} per line.
	 *
	 * <p>Value first for the same reason as the collection log's state: activity names contain
	 * colons and spaces, an integer can't contain a pipe, so the parse needs no escaping.
	 */
	String serializeState()
	{
		StringBuilder out = new StringBuilder(sent.size() * 20);
		for (Map.Entry<String, Integer> e : sent.entrySet())
		{
			if (e.getKey() == null || e.getKey().indexOf('\n') >= 0)
			{
				continue;
			}
			if (out.length() > 0)
			{
				out.append('\n');
			}
			out.append(e.getValue()).append('|').append(e.getKey());
		}
		return out.toString();
	}

	/**
	 * Restore delivered state. These count as known bests too — otherwise every login would re-push
	 * the whole set to tell the server what it already stored.
	 */
	void restoreState(String raw)
	{
		if (raw == null || raw.isEmpty())
		{
			return;
		}
		for (String line : raw.split("\n"))
		{
			int pipe = line.indexOf('|');
			if (pipe <= 0 || pipe == line.length() - 1)
			{
				continue;
			}
			try
			{
				int centis = Integer.parseInt(line.substring(0, pipe));
				String activity = line.substring(pipe + 1);
				if (centis > 0)
				{
					sent.put(activity, centis);
					Integer current = best.get(activity);
					if (current == null || current > centis)
					{
						best.put(activity, centis);
					}
				}
			}
			catch (NumberFormatException e)
			{
				// Truncated or hand-edited: drop the line and let the next kill re-establish it.
			}
		}
	}
}
