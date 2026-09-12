package com.anvil.ui;

import com.anvil.api.dto.StartProof;
import com.anvil.ui.ActivityEntry;
import com.anvil.ui.view.ActiveTask;
import com.anvil.ui.view.BoardChoices;
import com.anvil.ui.view.TileProgressView;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The two pages the sidebar renders once it has a clan to show.
 *
 * <p>{@link #eventPage} is one event in full — its card, what is happening on it, and the nearest
 * tiles. {@link #eventListPage} is the clan's home: every event it runs, one row each.</p>
 *
 * <p>Home is always a real page, even for a clan running one event. It is where the clan's own
 * actions live — roster sync, profile sync, banner clips — and going "back" from an event has to
 * arrive somewhere. Opening straight onto a lone event's card left no room for any of that.</p>
 *
 * <p>Those actions ride at the bottom of an event page too: they belong to the clan, not to the page
 * you happen to be on. Losing them the moment you opened an event was half of why the roster button
 * looked like it came and went.</p>
 */
public class EventPages
{
	private final SidebarDataSource dataSource;
	private final LadderCard ladderCard;
	private final BoardCards cards;
	private final PanelActions panelActions;
	private final StartProofCard startProofCard;
	private final OtherClanBoards otherBoards;

	public EventPages(SidebarDataSource dataSource, LadderCard ladderCard, BoardCards cards,
		PanelActions panelActions, StartProofCard startProofCard, OtherClanBoards otherBoards)
	{
		this.dataSource = dataSource;
		this.ladderCard = ladderCard;
		this.cards = cards;
		this.panelActions = panelActions;
		this.startProofCard = startProofCard;
		this.otherBoards = otherBoards;
	}

	/** One event's full card. {@code entry} null (or a board entry) renders the board; weeklies get their own. */
	public JPanel eventPage(ConnectionView selected, BoardChoices.EventEntry entry, boolean withBack)
	{
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);

		if (withBack)
		{
			body.add(cards.backLink());
			body.add(SidebarChrome.gap(8));
		}

		if (entry != null && entry.weekly != null)
		{
			ladderCard.clearRefs(); // a weekly card holds no ladder labels — leave the tick idle
			body.add(WeeklyCards.buildWeeklyCard(entry.weekly));
			if (!entry.weekly.upcoming)
			{
				body.add(SidebarChrome.gap(12));
				body.add(WeeklyCards.buildWeeklyStandings(entry.weekly));
			}
			return body;
		}

		if (entry != null && entry.scheduled != null)
		{
			ladderCard.clearRefs();
			body.add(WeeklyCards.buildScheduledCard(entry.scheduled));
			return body;
		}

		if (selected.hasError())
		{
			body.add(SidebarChrome.warningLabel(selected.error));
			body.add(SidebarChrome.gap(8));
		}

		body.add(cards.buildSummary(selected));
		body.add(SidebarChrome.gap(12));

		// STARTING SHOT — the one thing here that blocks play, so it sits directly under the board
		// summary rather than below the feed. Home clan only: it's an obligation on THIS account at
		// the site we're authenticated against, not something a relayed clan can ask for.
		StartProof startProof =
			AnvilSidebarDataSource.LOCAL_INSTANCE_ID.equals(selected.instanceId) ? dataSource.startProof() : null;
		if (startProof != null)
		{
			body.add(startProofCard.build(startProof));
			body.add(SidebarChrome.gap(12));
		}

		// Active now — tiles you and teammates are working right now (deduped by tile).
		if (!selected.activeNow.isEmpty())
		{
			body.add(SidebarChrome.sectionHeader("Active now"));
			body.add(SidebarChrome.gap(6));
			boolean firstActive = true;
			for (ActiveTask task : selected.activeNow)
			{
				if (!firstActive)
				{
					body.add(SidebarChrome.gap(8));
				}
				body.add(ProgressRows.buildActiveRow(task));
				firstActive = false;
			}
			body.add(SidebarChrome.gap(12));
		}

		// Missions on an ordinary bingo: their own strip with the same countdown + live values a ladder
		// gets. Without this they only ever showed up as "New tile revealed" lines in the activity feed,
		// which is neither a timer nor a mission board. A ladder's card already IS this, so it's skipped.
		if (selected.ladder != null && !selected.ladder.ladderFormat && !selected.ladder.missions.isEmpty())
		{
			body.add(SidebarChrome.sectionHeader("Missions"));
			body.add(SidebarChrome.gap(6));
			body.add(ladderCard.buildMissionStrip(selected.ladder));
			body.add(SidebarChrome.gap(12));
		}

		// Player activity — credited events, newest first. Reveals are BOARD news, not something a
		// player did, so they're split out below rather than sitting in a feed of people's actions.
		List<ActivityEntry> actions = new ArrayList<>();
		List<ActivityEntry> reveals = new ArrayList<>();
		for (ActivityEntry e : selected.recentActivity)
		{
			(e.kind == ActivityEntry.Kind.REVEAL ? reveals : actions).add(e);
		}
		if (!actions.isEmpty())
		{
			// "Team activity" is a lie on an individual ladder, where every team is one person.
			body.add(SidebarChrome.sectionHeader(selected.ladder != null && selected.ladder.ladderFormat
				? "Recent activity" : "Team activity"));
			body.add(SidebarChrome.gap(6));
			body.add(ProgressRows.buildActivityFeed(actions));
			body.add(SidebarChrome.gap(12));
		}
		// A ladder's card and a bingo's mission strip already show what's open with a live countdown, so
		// repeating each drop as a feed line is noise. Only boards WITHOUT that strip list reveals.
		boolean missionStripShown = selected.ladder != null
			&& (selected.ladder.ladderFormat || !selected.ladder.missions.isEmpty());
		if (!reveals.isEmpty() && !missionStripShown)
		{
			body.add(SidebarChrome.sectionHeader("Just opened"));
			body.add(SidebarChrome.gap(6));
			body.add(ProgressRows.buildActivityFeed(reveals));
			body.add(SidebarChrome.gap(12));
		}

		// No live board at all (stub home card / event-less federated clan) → the summary line already
		// says why; an empty "Nearest tiles" section under it would just restate the absence.
		if (selected.tilesTotal > 0)
		{
			body.add(SidebarChrome.sectionHeader("Nearest tiles"));
			body.add(SidebarChrome.gap(6));

			if (selected.nearestTiles.isEmpty())
			{
				JLabel none = new JLabel("No tiles to show yet.");
				none.setFont(FontManager.getRunescapeSmallFont());
				none.setForeground(SidebarChrome.VALUE_COLOR);
				none.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
				body.add(none);
			}
			else
			{
				boolean first = true;
				for (TileProgressView tile : selected.nearestTiles)
				{
					if (!first)
					{
						body.add(SidebarChrome.gap(8));
					}
					body.add(ProgressRows.buildTileRow(tile));
					first = false;
				}
			}
		}

		// Clan actions ride at the bottom of an event view too. They belong to the clan, not to the
		// page you happen to be on — losing them the moment you opened an event was half of why the
		// roster button looked like it came and went.
		body.add(SidebarChrome.gap(12));
		body.add(panelActions.buildPanelActions(selected));

		return body;
	}

	// ---- Events list (a clan running more than one thing) ------------------------------------------

	public JPanel eventListPage(ConnectionView c, List<BoardChoices.EventEntry> events)
	{
		ladderCard.clearRefs();

		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);

		if (c.hasError())
		{
			body.add(SidebarChrome.warningLabel(c.error));
			body.add(SidebarChrome.gap(8));
		}

		if (events.isEmpty())
		{
			// Nothing running is a state, not an error: say so and leave the clan's own actions below.
			JLabel none = new JLabel("No active event yet.");
			none.setFont(FontManager.getRunescapeSmallFont());
			none.setForeground(SidebarChrome.VALUE_COLOR);
			none.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
			body.add(none);
			body.add(SidebarChrome.gap(12));
		}
		else
		{
			body.add(SidebarChrome.sectionHeader("Events"));
			body.add(SidebarChrome.gap(6));
			boolean first = true;
			for (BoardChoices.EventEntry e : events)
			{
				if (!first)
				{
					body.add(SidebarChrome.gap(8));
				}
				body.add(cards.buildEventCard(c, e));
				first = false;
			}
			body.add(SidebarChrome.gap(12));
		}

		// Everything else you are playing, in your other clans. Only in the merged view — picking a
		// clan in the dropdown is a filter, and a filter that still showed the others would not be one.
		if (dataSource.chosenClan().isEmpty())
		{
			JPanel elsewhere = otherBoards.build();
			if (elsewhere != null)
			{
				body.add(elsewhere);
				body.add(SidebarChrome.gap(12));
			}
		}

		// The clan's own controls, under whatever is running. Home only, and only what this account
		// can actually do — see SidebarDataSource.actionsFor.
		body.add(panelActions.buildPanelActions(c));
		body.add(SidebarChrome.gap(12));
		body.add(panelActions.buildBannerSounds());
		return body;
	}
}
