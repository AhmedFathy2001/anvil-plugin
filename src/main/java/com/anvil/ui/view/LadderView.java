package com.anvil.ui.view;

import com.anvil.api.dto.EventInfo;
import com.anvil.api.dto.Mission;
import com.anvil.detect.LadderMissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The ladder, as the sidebar needs it: your rank, the next drop, and what is open right now.
 *
 * <p>Kept apart from the data source because it is pure shaping — an {@link EventInfo} in, a
 * {@link Ladder} out — and because the reveal wording below is the one place the plugin decides what
 * to say about a board that is deliberately hiding things.</p>
 *
 * <p>That wording is careful on purpose. A board with tiles still to reveal is not broken and not
 * empty; it is mid-reveal, and saying "nothing here" would read as the former. When the next reveal
 * has a time, it says so; when it does not, it says only that more is coming, rather than inventing
 * a schedule the site never promised.</p>
 */
public final class LadderView
{
	private LadderView()
	{
	}

	/**
	 * Fold missions into the sidebar's {@link Ladder} view-model: the countdown target,
	 * the caller's month + all-time rank, and the open missions.
	 *
	 * Built for a ladder (where it REPLACES the board summary) and, since a normal bingo can drop
	 * hidden missions mid-event too, for any board that currently has missions — there it renders as
	 * a strip under the usual summary. Null when neither applies, and the plain summary + reveal note
	 * render on their own.
	 */
	public static Ladder buildLadder(EventInfo event)
	{
		if (event == null)
		{
			return null;
		}
		boolean ladder = LadderMissions.isLadder(event.format);
		boolean hasMissions = event.missions != null && !event.missions.isEmpty();
		if (!ladder && !hasMissions)
		{
			return null;
		}
		List<Ladder.Mission> missions = new ArrayList<>();
		if (event.missions != null)
		{
			for (Mission m : event.missions)
			{
				if (m != null)
				{
					missions.add(new Ladder.Mission(m.tileId, m.label, m.points, m.revealedAt));
				}
			}
		}
		int monthRank = event.monthlyStandings != null ? event.monthlyStandings.yourRank : 0;
		long monthPoints = event.monthlyStandings != null ? event.monthlyStandings.yourPoints : 0;
		int allTimeRank = event.standings != null ? event.standings.yourRank : 0;
		return new Ladder(event.nextRevealAt, monthRank, monthPoints, allTimeRank,
			event.decay, missions, ladder);
	}

	/**
	 * Reveal-policy boards: the "still hidden" one-liner under the board summary, or null on classic
	 * boards / older servers (no field). Bounty draws on claim, the others on a clock the server sends.
	 */
	public static String revealNote(EventInfo event)
	{
		if (event == null || event.revealPolicy == null || event.revealPolicy.isEmpty() || event.hiddenTileCount <= 0)
		{
			return null;
		}
		boolean bounty = "bounty".equalsIgnoreCase(event.revealPolicy);
		String what = bounty
			? event.hiddenTileCount + (event.hiddenTileCount == 1 ? " bounty" : " bounties") + " left"
			: event.hiddenTileCount + (event.hiddenTileCount == 1 ? " tile" : " tiles") + " hidden";
		String next = bounty ? "next on claim" : nextRevealLabel(event.nextRevealAt);
		return what + (next == null ? "" : " · " + next);
	}

	/** "next in 42m" / "next in 3h 10m" from the server's ISO next-reveal stamp; null when absent/past. */
	private static String nextRevealLabel(String nextRevealAt)
	{
		if (nextRevealAt == null || nextRevealAt.isEmpty())
		{
			return null;
		}
		try
		{
			long at = Instant.parse(nextRevealAt).toEpochMilli();
			long mins = Math.max(0, (at - System.currentTimeMillis()) / 60_000);
			if (mins < 1)
			{
				return "next any minute";
			}
			if (mins < 60)
			{
				return "next in " + mins + "m";
			}
			return "next in " + (mins / 60) + "h " + (mins % 60) + "m";
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}
}
