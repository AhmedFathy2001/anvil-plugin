package com.anvil.ui.view;

import com.anvil.api.dto.ClanRef;
import com.anvil.ui.ConnectionView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What the sidebar can show, and what it offers to switch to.
 *
 * <p>Three questions, and all three are easy to get quietly wrong, so all three are answered here in
 * static methods with no Swing component in sight and pinned by {@code SidebarEventsTest}:</p>
 *
 * <ul>
 *   <li>what is selectable on one clan's card ({@link #eventsOf}),</li>
 *   <li>which OTHER clans have a live board worth listing ({@link #otherLiveBoards}),</li>
 *   <li>and what the clan dropdown says about each ({@link ClanChoice}).</li>
 * </ul>
 */
public final class BoardChoices
{
	/** The selection key for a clan's own board — the one entry not keyed by an event id. */
	public static final String BOARD_KEY = "board";

	private BoardChoices()
	{
	}

	/**
	 * The live boards in your OTHER clans — everything the merged view adds to the addressed clan's card.
	 *
	 * <p>Pure and static so the two rules that are easy to get quietly wrong can be tested without a
	 * Swing component in sight.</p>
	 *
	 * <p><b>Dedup by event id, not by name.</b> A co-hosted event belongs to every host, so somebody
	 * seated in two co-hosting clans has the same board listed under each. Showing it twice is wrong,
	 * and "Summer Bingo" is not a rare enough name to dedup on.</p>
	 *
	 * <p><b>The board on screen is excluded by its EVENT, not by its slug.</b> Same reason: when the
	 * board you are already looking at in full is co-hosted, the other host's row is that same board.
	 * Filtering only on the slug would list it again, under a different clan's name, as though it
	 * were somewhere else to go.</p>
	 *
	 * <p>And the board on screen is passed IN rather than read off the addressed clan's row, because
	 * those two can disagree. A co-host's own row names whatever that clan reports as live — its
	 * Skill of the Week, say — while the card above shows the co-hosted bingo the member is actually
	 * playing, held on a seat in the host clan. Reading the row would then miss it, and the same
	 * event appeared twice, its two tallies disagreeing.</p>
	 *
	 * @param shownBoard identity of the board the card above is rendering ({@code "bingo:<id>"}), or
	 *                   null/"" when there is none
	 */
	public static List<ClanRef> otherLiveBoards(
		List<ClanRef> clans, String addressedSlug, String shownBoard)
	{
		List<ClanRef> out = new ArrayList<>();
		if (clans == null || clans.isEmpty())
		{
			return out;
		}
		Set<String> seen = new HashSet<>();
		if (shownBoard != null && !shownBoard.isEmpty())
		{
			seen.add(shownBoard); // the board on screen, whichever clan's row also reports it
		}
		String addressed = addressedSlug == null ? "" : addressedSlug;
		for (ClanRef c : clans)
		{
			if (c != null && addressed.equalsIgnoreCase(c.slug) && c.live != null)
			{
				seen.add(c.live.identity()); // whatever else the addressed clan is running
			}
		}
		for (ClanRef c : clans)
		{
			if (c == null || c.live == null || c.slug == null || c.slug.isEmpty())
			{
				continue;
			}
			if (addressed.equalsIgnoreCase(c.slug) || !seen.add(c.live.identity()))
			{
				continue;
			}
			out.add(c);
		}
		return out;
	}

	/**
	 * One row of the clan dropdown: a clan to narrow to, or all of them.
	 *
	 * "All clans" is the DEFAULT and it is a real row, not an implied initial state, because it is
	 * also the only way back. A member who narrows to a clan that later goes quiet would otherwise be
	 * stuck watching a dead board while their live one runs somewhere else, with nothing in the UI
	 * admitting it.
	 *
	 * On "All clans" the site decides which board the plugin addresses — live event first — and the
	 * rest appear under "Also live". Choosing a clan narrows to it and moves where submissions file,
	 * which is a bigger deal than a filter usually is; the rows say so.
	 */
	public static final class ClanChoice
	{
		/** Clan slug, or "" for Auto. */
		public final String slug;
		public final String label;
		/** Second line — the board running there, or what this seat is. Never null. */
		public final String detail;

		public ClanChoice(String slug, String label, String detail)
		{
			this.slug = slug == null ? "" : slug;
			this.label = label == null ? "" : label;
			this.detail = detail == null ? "" : detail;
		}

		/** Auto first, then the clans in the order the site sent them (newest seat first). */
		public static List<ClanChoice> of(List<ClanRef> clans)
		{
			List<ClanChoice> out = new ArrayList<>();
			if (clans == null || clans.isEmpty())
			{
				return out;
			}
			out.add(new ClanChoice("", "All clans", "Everything you're playing"));
			for (ClanRef c : clans)
			{
				if (c == null || c.slug == null || c.slug.isEmpty())
				{
					continue;
				}
				out.add(new ClanChoice(c.slug, c.name == null || c.name.isEmpty() ? c.slug : c.name, detailOf(c)));
			}
			return out;
		}

		/** What is happening in this clan, in one short line — the reason to pick it or not. */
		private static String detailOf(ClanRef c)
		{
			if (c.live == null || c.live.eventName == null || c.live.eventName.isEmpty())
			{
				return "guest".equals(c.kind) ? "Guest \u00b7 nothing live" : "Nothing live";
			}
			// A competition has a leaderboard rather than a board to fill, so "0/0" would be a lie
			// dressed as progress. The name is the whole answer there.
			String head = c.live.isWeekly() || c.live.tilesTotal <= 0
				? c.live.eventName
				: c.live.eventName + "  " + c.live.tilesComplete + "/" + c.live.tilesTotal;
			// One line names ONE of them. Say how many were not named rather than letting an arbitrary
			// pick stand in for the whole clan — the number is why you would open the clan at all.
			int more = c.liveCount - 1;
			return more > 0 ? head + "  +" + more + " more" : head;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	/**
	 * Everything selectable on this clan card: its live board (when there is one) followed by the live
	 * weeklies. Static + package-private so the list/drill-in rule is unit-testable without Swing.
	 */
	public static List<EventEntry> eventsOf(ConnectionView c)
	{
		List<EventEntry> out = new ArrayList<>();
		boolean hasBoard = (c.eventName != null && !c.eventName.isEmpty()) || c.tilesTotal > 0;
		String boardTitle = null;
		if (hasBoard)
		{
			// Your own board leads — it's the one with progress on it.
			String title = c.eventName == null || c.eventName.isEmpty() ? c.clanName : c.eventName;
			boardTitle = title;
			out.add(new EventEntry(BOARD_KEY, title,
				c.ladder != null && c.ladder.ladderFormat ? "Ladder" : "Bingo", null, null));
		}
		for (WeeklyView w : c.weeklies)
		{
			out.add(new EventEntry("weekly:" + w.id, w.title, w.kindLabel(), w, null));
		}
		for (ScheduledView s : c.scheduled)
		{
			// NOT THE BOARD ABOVE, AGAIN. The live board is keyed by a constant and a scheduled entry
			// by its event id, so nothing stopped the same event appearing twice — once as your board
			// with its progress on it, and once from the schedule underneath saying "Running — you're
			// not in it", which is the same event contradicting itself two rows apart.
			//
			// Matched on the title because that is what this view carries; within one clan's own
			// schedule two live boards sharing a name would be the ambiguity, and that is a naming
			// problem rather than one worth a wider fix here.
			if (hasBoard && s.title != null && s.title.equals(boardTitle))
			{
				continue;
			}
			out.add(new EventEntry("event:" + s.id, s.title, s.kindLabel(), null, s));
		}
		return out;
	}

	public static EventEntry findEvent(List<EventEntry> events, String key)
	{
		if (key == null)
		{
			return null;
		}
		for (EventEntry e : events)
		{
			if (e.key.equals(key))
			{
				return e;
			}
		}
		return null;
	}

	/**
	 * One selectable event on a clan card — exactly one of three things: the clan's own board (both
	 * payloads null), a weekly competition, or another/soon bingo event off the schedule.
	 */
	public static final class EventEntry
	{
		/** Stable selection key, so the choice survives an auto-refresh. */
		public final String key;
		public final String title;
		/** "Bingo" / "Ladder" / "Skill of the Week" / "Boss of the Week". */
		public final String kind;
		/** The weekly this entry stands for; null unless it IS a weekly. */
		public final WeeklyView weekly;
		/** The scheduled bingo this entry stands for; null unless it IS one. */
		public final ScheduledView scheduled;

		public EventEntry(String key, String title, String kind, WeeklyView weekly,
			ScheduledView scheduled)
		{
			this.key = key;
			this.title = title == null ? "" : title;
			this.kind = kind == null ? "" : kind;
			this.weekly = weekly;
			this.scheduled = scheduled;
		}

		public boolean isBoard()
		{
			return weekly == null && scheduled == null;
		}
	}

	/** The clan's events as clickable cards — the landing view whenever a clan runs more than one. */
}
