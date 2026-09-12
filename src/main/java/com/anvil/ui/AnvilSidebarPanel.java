package com.anvil.ui;

import com.anvil.api.BingoApiClient;
import com.anvil.api.dto.ClanRef;
import com.anvil.api.dto.StartProof;
import com.anvil.detect.StartProofRules;
import com.anvil.io.BannerSoundService;
import com.anvil.io.DeviceSignIn;
import com.anvil.ui.view.ActiveTask;
import com.anvil.ui.view.BoardChoices;
import com.anvil.ui.view.Clock;
import com.anvil.ui.view.ScheduledView;
import com.anvil.ui.view.TileProgressView;
import com.anvil.ui.view.WeeklyView;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridBagLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Player;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.PluginErrorPanel;

/**
 * Always-on progress sidebar — a {@link PluginPanel} showing, per connected clan, tiles done and nearest
 * completion. Decoupled from the network: reads everything through {@link SidebarDataSource} (site-relay or
 * single-home; see {@code FEDERATION_WIRE.md} §7/§10). Owns four view states (loading/error/empty/ready), a
 * clan filter, a manual Refresh, and an auto-refresh poll that runs only while the panel is open.
 *
 * <p>A clan can run several things at once — its board plus a live SOTW/BOTW. One event renders straight
 * in; several land on the clickable events list ({@link #eventsOf}) and drill into a board or weekly card
 * from there, with the choice held in {@link #selectedEventKey} so a refresh never bounces the member out.</p>
 *
 * <p><b>Threading:</b> all Swing mutation stays on the EDT; off-EDT work is the blocking
 * {@link SidebarDataSource#fetchConnections()} inside the worker plus the connect flow's scheduled steps,
 * whose callbacks marshal back via invokeLater. {@link Singleton} — one toolbar panel.</p>
 */
@Slf4j
@Singleton
public class AnvilSidebarPanel extends PluginPanel
{
	/** Auto-refresh cadence while the panel is open. Mirrors the plugin's other polls (config/board). */
	private static final int POLL_INTERVAL_MS = 15_000;


	// Anvil theme for the interactive widgets: flat dark surfaces with the gold/orange accent the
	// rest of the sidebar (title, bars, links) already uses — default Swing chrome sticks out badly.

	/** Wrap width for the connect-status line so a long notice wraps instead of clipping (tooltip carries full text). */


	/** Clip rows on the home page before the rest collapse into a "+N more" line. */
	private static final int BANNER_CLIPS_SHOWN = 8;


	/** Selection key for a clan's own bingo/ladder board in the events list. */

	/** Roughly what fits on one small-font line at {@link PluginPanel#PANEL_WIDTH} — event cards clip to it. */
	private static final int CARD_LINE_CHARS = 30;

	private final SidebarDataSource dataSource;

	// Header controls (persistent across state changes).
	// Which clan the plugin is pointed at. One Anvil serves every clan and a person can hold seats in
	// several, so this is a real choice rather than a filter over data we already have: picking here
	// re-addresses everything the plugin does — board, stat pushes, submissions, notifications.
	private final JComboBox<BoardChoices.ClanChoice> clanPicker = new JComboBox<>();
	private final JButton refreshButton = new JButton("Refresh");

	// Device sign-in (home-native, DeviceSignIn): shown when a Site URL is configured but no
	// Account Token yet — replaces the copy-the-token-from-your-profile step.
	private final BingoApiClient apiClient;
	private final net.runelite.client.config.ConfigManager configManager;
	/** RuneLite's shared client-lifetime scheduler — paces the sign-in flow's approval polls. */
	private final ScheduledExecutorService executor;
	/** "Sign in with Discord", shown only while there is no Account Token. */
	private final SignInRow signIn;

	private final JPanel content = new JPanel();

	// Only re-renders while the panel is visible; started on activate, stopped on deactivate.
	private final Timer autoRefresh;

	/** The ladder missions board and its one-second tick. */
	private final LadderCard ladderCard = new LadderCard();

	// Last snapshot + selected clan — preserved across refreshes so auto-refresh doesn't reset/flicker the list.
	private List<ConnectionView> connections = Collections.emptyList();

	/** Which event the member drilled into, or null for the list. Only meaningful when a clan runs several. */
	private String selectedEventKey;

	// Guards against ActionEvents fired while we rebuild the combo model, and against overlapping fetches.
	private boolean rebuildingPicker;
	private boolean fetchInFlight;

	@Inject
	public AnvilSidebarPanel(SidebarDataSource dataSource, BingoApiClient apiClient,
		net.runelite.client.config.ConfigManager configManager, ScheduledExecutorService executor)
	{
		super(true); // wrap in RuneLite's scroll pane so a long nearest-tiles list scrolls
		this.dataSource = dataSource;
		this.apiClient = apiClient;
		this.configManager = configManager;
		this.executor = executor;
		// The row fetches once a token lands, rather than waiting out the fifteen-second poll.
		this.signIn = new SignInRow(apiClient, configManager, executor, this::refresh);

		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(BORDER_OFFSET, BORDER_OFFSET, BORDER_OFFSET, BORDER_OFFSET));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		add(buildHeader(), BorderLayout.NORTH);

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(content, BorderLayout.CENTER);

		autoRefresh = new Timer(POLL_INTERVAL_MS, e -> refresh());
		autoRefresh.setCoalesce(true);

		renderLoading();
	}

	private JPanel buildHeader()
	{
		JPanel header = new JPanel(new BorderLayout(0, 8));
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

		JLabel title = new JLabel("Anvil Progress");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(ColorScheme.BRAND_ORANGE);

		SidebarChrome.styleFlatButton(refreshButton, Color.WHITE);
		refreshButton.addActionListener(e -> refresh(true));

		JPanel titleRow = new JPanel(new BorderLayout());
		titleRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		titleRow.add(title, BorderLayout.WEST);
		titleRow.add(refreshButton, BorderLayout.EAST);
		titleRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, titleRow.getPreferredSize().height));
		titleRow.setAlignmentX(LEFT_ALIGNMENT);

		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);
		top.add(titleRow);
		top.add(Box.createVerticalStrut(6));
		top.add(signIn.component());
		header.add(top, BorderLayout.NORTH);

		// Clan filter — selecting a clan re-renders from the held snapshot (no refetch).
		SidebarChrome.styleClanPicker(clanPicker);
		clanPicker.setRenderer(new SidebarChrome.ClanChoiceRenderer());
		clanPicker.setFocusable(false);
		clanPicker.addActionListener(e ->
		{
			if (rebuildingPicker)
			{
				return;
			}
			BoardChoices.ClanChoice sel = (BoardChoices.ClanChoice) clanPicker.getSelectedItem();
			if (sel == null || sel.slug.equals(dataSource.chosenClan()))
			{
				return;
			}
			// Not a filter over a snapshot we already hold — the other clan's board lives on the site,
			// so this hands the choice to the plugin (which persists it and re-addresses the client)
			// and shows the loading state until the answer comes back.
			selectedEventKey = null; // another clan's events are a different list — start at the top
			// No refresh() here on purpose. The source renders from the config the plugin holds, and
			// that config is still the old clan's until the refetch lands — refreshing now would repaint
			// the clan they just switched away from. The plugin repaints once it has the new answer.
			dataSource.chooseClan(sel.slug);
			renderLoading();
		});
		header.add(clanPicker, BorderLayout.SOUTH);

		return header;
	}

	// ---- Lifecycle (Activatable) — poll only while the panel is on screen -------------------------

	@Override
	public void onActivate()
	{
		refresh();
		autoRefresh.start();
		ladderCard.startTicking();
	}

	@Override
	public void onDeactivate()
	{
		autoRefresh.stop();
		ladderCard.stopTicking();
	}

	/**
	 * A new mission opened, or one was claimed — pulse the ladder card gold.
	 *
	 * <p>Signalled from the plugin, off the EDT, which is why the card writes a wall-clock deadline
	 * rather than repainting: the tick it already runs picks the tint up on its next pass.</p>
	 */
	public void flashLadder()
	{
		ladderCard.flash();
	}

	public void clearForCredentialChange()
	{
		connections = Collections.emptyList();
		selectedEventKey = null;
		rebuildingPicker = true;
		clanPicker.removeAllItems();
		rebuildingPicker = false;
		clanPicker.setVisible(false); // no config for the new credentials yet, so no clans to choose between
		signIn.clearStatus();
		signIn.refresh();
		renderLoading();
	}

	/** Fetch off the EDT and re-render on return. Cheap to call repeatedly; overlapping calls coalesce via {@link #fetchInFlight}. */
	public void refresh()
	{
		refresh(false);
	}

	/** As {@link #refresh()}; {@code manual} = the member clicked Refresh, so the source is asked to
	 * bypass what it normally caches (the weekly standings) rather than serve a minute-old answer. */
	private void refresh(boolean manual)
	{
		if (fetchInFlight)
		{
			return;
		}
		fetchInFlight = true;
		refreshButton.setEnabled(false);
		if (connections.isEmpty())
		{
			// Nothing on screen yet — show loading. A background poll keeps the current list visible (no flicker).
			renderLoading();
		}

		new SwingWorker<List<ConnectionView>, Void>()
		{
			@Override
			protected List<ConnectionView> doInBackground() throws Exception
			{
				return dataSource.fetchConnections(manual);
			}

			@Override
			protected void done()
			{
				fetchInFlight = false;
				refreshButton.setEnabled(true);
				try
				{
					onConnections(get());
				}
				catch (Exception ex)
				{
					// InterruptedException / ExecutionException(SidebarDataException) — surface the message.
					Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
					log.debug("sidebar fetch failed", cause);
					renderError(cause.getMessage());
				}
				signIn.refresh();
			}
		}.execute();
	}

	/** New snapshot in hand — refresh the clan dropdown and render the addressed clan. Runs on the EDT. */
	private void onConnections(List<ConnectionView> fetched)
	{
		connections = fetched == null ? Collections.emptyList() : fetched;
		rebuildClanPicker();

		if (connections.isEmpty())
		{
			selectedEventKey = null;
			renderEmpty();
			return;
		}
		renderSelected();
	}

	/**
	 * The clan the body is showing — the one this plugin is addressing.
	 *
	 * A list of one, in practice: the source renders the clan the client is pointed at, and pointing it
	 * somewhere else is a refetch rather than a re-render. It stays a list because a source is allowed
	 * to have nothing, and because the panel should not care which.
	 */
	private ConnectionView addressedClan()
	{
		return connections.isEmpty() ? null : connections.get(0);
	}

	/**
	 * Repopulate the clan dropdown, suppressing the selection-event storm a setModel provokes.
	 *
	 * <p>Fed from the site's list of this person's seats, not from the boards we happen to have loaded:
	 * the point of the control is to reach a clan whose board we have NOT loaded. Hidden below two
	 * entries, because one option that cannot be changed is not a choice.</p>
	 *
	 * <p>There used to be a rule here that landed a guest on their own clan instead. It is gone
	 * deliberately, not lost: the server applies the stronger version of it when it resolves a clanless
	 * request — a live event first, then a real membership before any guest seat — and two copies of a
	 * default that can disagree is worse than one copy that decides.</p>
	 */
	private void rebuildClanPicker()
	{
		List<BoardChoices.ClanChoice> choices = BoardChoices.ClanChoice.of(dataSource.clans());
		rebuildingPicker = true;
		try
		{
			DefaultComboBoxModel<BoardChoices.ClanChoice> model = new DefaultComboBoxModel<>();
			String chosen = dataSource.chosenClan();
			BoardChoices.ClanChoice toSelect = null;
			for (BoardChoices.ClanChoice c : choices)
			{
				model.addElement(c);
				if (c.slug.equals(chosen))
				{
					toSelect = c;
				}
			}
			clanPicker.setModel(model);
			// Nothing chosen — the site is deciding, which is the Auto row.
			clanPicker.setSelectedItem(toSelect != null ? toSelect : choices.isEmpty() ? null : choices.get(0));
			clanPicker.setVisible(choices.size() > 2);
		}
		finally
		{
			rebuildingPicker = false;
		}
	}


	// ---- Render states ----------------------------------------------------------------------------

	private void renderLoading()
	{
		// The picker STAYS. Switching clan renders this state on purpose, and yanking the control the
		// member is holding — then putting it back a second later — reads as the click having failed.
		JLabel loading = new JLabel("Loading progress…", SwingConstants.CENTER);
		loading.setForeground(SidebarChrome.VALUE_COLOR);
		loading.setBorder(BorderFactory.createEmptyBorder(24, 0, 0, 0));
		setContent(loading);
	}

	private void renderError(String message)
	{
		// A clan we cannot reach is still a clan they can switch away FROM, which is the likeliest thing
		// they want next. Keep the control; the dropdown's own rebuild decides whether it has rows.
		PluginErrorPanel error = new PluginErrorPanel();
		error.setContent("Couldn't load progress",
			(message == null || message.isEmpty() ? "Something went wrong." : message)
				+ " Use Refresh to try again.");
		setContent(error);
	}

	private void renderEmpty()
	{
		// TWO DIFFERENT NOTHINGS, and they were being told the same story. "Link a clan on your Anvil
		// site" is advice for somebody who HAS a site and an account; a fresh install has neither, and
		// reading it as their first contact with the plugin leaves them looking for a clan page nobody
		// has given them the address of. Signed out is the state to answer first, because it is the
		// state every new install starts in.
		if (apiClient.needsSignIn())
		{
			setContent(firstRunCard());
			return;
		}
		PluginErrorPanel empty = new PluginErrorPanel();
		empty.setContent("No connected clans",
			"Join your clan on the Anvil site — or start one, it's free — and its board progress shows up here.");
		setContent(empty);
	}

	/**
	 * What a fresh install sees: what this is, the one button that connects it, and where that goes.
	 *
	 * <p>The sign-in button itself lives in the header (it is shown in exactly this state), so this
	 * points at it rather than repeating it. What it adds is the half a stranger cannot guess — that
	 * the account is free, that it is theirs rather than their clan's, and that a person with no clan
	 * still gets something out of it. The link is the only address here, and it is the same one
	 * pressing Sign in would write.</p>
	 */
	private JPanel firstRunCard()
	{
		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(ColorScheme.DARK_GRAY_COLOR);
		card.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		card.setAlignmentX(LEFT_ALIGNMENT);

		card.add(SidebarChrome.sectionHeader("New here?"));
		card.add(SidebarChrome.gap(6));
		card.add(SidebarChrome.note("Anvil tracks your OSRS progress — collection log, personal bests, records — and"
			+ " your clan's bingo boards. Press Sign in with Discord above to make your free account"
			+ " and connect this client in one step."));
		card.add(SidebarChrome.gap(6));
		card.add(SidebarChrome.note("You don't need a clan: your profile is your own. If you run one, starting it"
			+ " there is free too."));
		card.add(SidebarChrome.siteLink(BingoApiClient.CANONICAL_SITE.replaceFirst("^https?://", "") + " ↗",
			"Open Anvil in your browser", BingoApiClient.CANONICAL_SITE));

		return card;
	}

	/**
	 * Render the selected clan: one event drills straight in (today's board card), several show the
	 * events list until the member picks one. A drilled-into event that stopped running (a weekly ended
	 * mid-session) falls back to the list rather than a blank card.
	 */
	private void renderSelected()
	{
		ConnectionView selected = addressedClan();
		if (selected == null)
		{
			renderEmpty();
			return;
		}

		List<BoardChoices.EventEntry> events = BoardChoices.eventsOf(selected);
		BoardChoices.EventEntry chosen = BoardChoices.findEvent(events, selectedEventKey);
		if (chosen != null)
		{
			renderEvent(selected, chosen, true);
			return;
		}
		// Home. Always a real page, even for one event: it's where the clan's own actions live —
		// roster sync, profile sync, banner clips — and going "back" from an event has to arrive
		// somewhere. Opening straight onto a lone event's card left no room for any of that.
		selectedEventKey = null;
		renderEventList(selected, events);
	}

	/** One event's full card. {@code entry} null (or a board entry) renders the board; weeklies get their own. */
	private void renderEvent(ConnectionView selected, BoardChoices.EventEntry entry, boolean withBack)
	{
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);

		if (withBack)
		{
			body.add(backLink());
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
			setContent(body);
			return;
		}

		if (entry != null && entry.scheduled != null)
		{
			ladderCard.clearRefs();
			body.add(WeeklyCards.buildScheduledCard(entry.scheduled));
			setContent(body);
			return;
		}

		if (selected.hasError())
		{
			body.add(SidebarChrome.warningLabel(selected.error));
			body.add(SidebarChrome.gap(8));
		}

		body.add(buildSummary(selected));
		body.add(SidebarChrome.gap(12));

		// STARTING SHOT — the one thing here that blocks play, so it sits directly under the board
		// summary rather than below the feed. Home clan only: it's an obligation on THIS account at
		// the site we're authenticated against, not something a relayed clan can ask for.
		StartProof startProof =
			AnvilSidebarDataSource.LOCAL_INSTANCE_ID.equals(selected.instanceId) ? dataSource.startProof() : null;
		if (startProof != null)
		{
			body.add(buildStartProofCard(startProof));
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
				none.setAlignmentX(LEFT_ALIGNMENT);
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
		body.add(buildPanelActions(selected));

		setContent(body);
	}

	// ---- Events list (a clan running more than one thing) ------------------------------------------

	private void renderEventList(ConnectionView c, List<BoardChoices.EventEntry> events)
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
			none.setAlignmentX(LEFT_ALIGNMENT);
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
				body.add(buildEventCard(c, e));
				first = false;
			}
			body.add(SidebarChrome.gap(12));
		}

		// Everything else you are playing, in your other clans. Only in the merged view — picking a
		// clan in the dropdown is a filter, and a filter that still showed the others would not be one.
		if (dataSource.chosenClan().isEmpty())
		{
			JPanel elsewhere = buildOtherClanBoards();
			if (elsewhere != null)
			{
				body.add(elsewhere);
				body.add(SidebarChrome.gap(12));
			}
		}

		// The clan's own controls, under whatever is running. Home only, and only what this account
		// can actually do — see SidebarDataSource.actionsFor.
		body.add(buildPanelActions(c));
		body.add(SidebarChrome.gap(12));
		body.add(buildBannerSounds());
		setContent(body);
	}

	/**
	 * "Also live" — your boards in the clans this plugin is NOT currently addressing.
	 *
	 * <p>Summaries rather than cards, and that is a limit rather than a design preference: the rich
	 * layers (nearest tiles, the live feed, the ladder) come out of the ONE config response the plugin
	 * polls, which is about one clan. What the site sends for the others is a name and a tally, so a
	 * name and a tally is what these rows can honestly show.</p>
	 *
	 * <p>Clicking one addresses that clan, which turns its summary into the full card — and moves
	 * where submissions file, which is why each row says so rather than looking like a tab.</p>
	 *
	 * @return null when there is nothing to add, so the caller adds no empty heading
	 */
	private JPanel buildOtherClanBoards()
	{
		List<ClanRef> others =
			BoardChoices.otherLiveBoards(dataSource.clans(), dataSource.activeClan(), dataSource.addressedBoard());
		if (others.isEmpty())
		{
			return null;
		}

		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setAlignmentX(LEFT_ALIGNMENT);
		panel.add(SidebarChrome.sectionHeader("Also live"));
		panel.add(SidebarChrome.gap(6));

		boolean first = true;
		for (ClanRef c : others)
		{
			if (!first)
			{
				panel.add(SidebarChrome.gap(6));
			}
			panel.add(buildOtherClanRow(c));
			first = false;
		}
		return panel;
	}

	/** One "also live" row: whose board it is, what it is, and how far along. Click to go there. */
	private JPanel buildOtherClanRow(ClanRef c)
	{
		String clanName = c.name == null || c.name.isEmpty() ? c.slug : c.name;

		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		card.setAlignmentX(LEFT_ALIGNMENT);
		card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		card.setToolTipText("Switch to " + SidebarChrome.plainText(clanName) + " — the sidebar and your submissions follow");

		JPanel titleRow = new JPanel(new BorderLayout(6, 0));
		titleRow.setOpaque(false);
		titleRow.setAlignmentX(LEFT_ALIGNMENT);
		JLabel title = new JLabel(SidebarChrome.plainText(SidebarChrome.ellipsize(clanName, 22)));
		title.setFont(FontManager.getRunescapeFont());
		title.setForeground(ColorScheme.TEXT_COLOR);
		JLabel chevron = new JLabel("›");
		chevron.setFont(FontManager.getRunescapeBoldFont());
		chevron.setForeground(ColorScheme.BRAND_ORANGE);
		titleRow.add(title, BorderLayout.CENTER);
		titleRow.add(chevron, BorderLayout.EAST);
		titleRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, titleRow.getPreferredSize().height));
		card.add(titleRow);

		card.add(SidebarChrome.leftLabel(SidebarChrome.ellipsize(c.live.eventName, CARD_LINE_CHARS),
			FontManager.getRunescapeSmallFont(), ColorScheme.LIGHT_GRAY_COLOR));
		if (c.live.isWeekly() || c.live.tilesTotal <= 0)
		{
			// A competition fills no board, so it gets neither a tally nor a bar — saying "0 / 0 tiles"
			// under a running SOTW reads as a broken board rather than as a leaderboard.
			card.add(SidebarChrome.leftLabel("Competition running", FontManager.getRunescapeSmallFont(), SidebarChrome.VALUE_COLOR));
		}
		else
		{
			card.add(SidebarChrome.leftLabel(c.live.tilesComplete + " / " + c.live.tilesTotal
				+ (c.live.pointsScored ? " points" : " tiles"), FontManager.getRunescapeSmallFont(), SidebarChrome.VALUE_COLOR));
		}
		if (c.liveCount > 1)
		{
			// This card shows one of them. Switching is how you reach the rest, so the row has to admit
			// there ARE others rather than presenting the freshest as everything happening there.
			card.add(SidebarChrome.leftLabel("+ " + (c.liveCount - 1) + " more here",
				FontManager.getRunescapeSmallFont(), ColorScheme.BRAND_ORANGE));
		}

		if (!c.live.isWeekly() && c.live.tilesTotal > 0)
		{
			JProgressBar bar = new JProgressBar(0, 100);
			bar.setValue(Math.max(0, Math.min(100, (int) Math.round(100.0 * c.live.tilesComplete / c.live.tilesTotal))));
			bar.setStringPainted(false);
			bar.setBorderPainted(false);
			bar.setForeground(ColorScheme.BRAND_ORANGE);
			bar.setBackground(ColorScheme.DARK_GRAY_COLOR);
			bar.setPreferredSize(new Dimension(0, ProgressRows.PROGRESS_BAR_HEIGHT));
			bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, ProgressRows.PROGRESS_BAR_HEIGHT));
			bar.setAlignmentX(LEFT_ALIGNMENT);
			card.add(Box.createVerticalStrut(4));
			card.add(bar);
		}

		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
		final String slug = c.slug;
		card.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				selectedEventKey = null;
				dataSource.chooseClan(slug);
				renderLoading();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				card.setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
		});
		return card;
	}

	/**
	 * Roster sync and profile sync, when this account can do them here.
	 *
	 * <p>A roster is scraped from the clan channel you're standing in, so the button is absent for
	 * any clan but your own — an admin elsewhere still can't see a roster they aren't in — and it
	 * says why rather than vanishing without explanation. Both buttons carry the same limits as
	 * their in-game counterparts, which live in the plugin, not here.
	 */
	private JPanel buildPanelActions(ConnectionView c)
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setAlignmentX(LEFT_ALIGNMENT);

		SidebarDataSource.PanelActions actions = dataSource.actionsFor(c.instanceId);
		if (!actions.canSyncRoster && !actions.canSyncProfile && actions.rosterNote == null)
		{
			return panel; // nothing this account can do here; no empty heading either
		}

		panel.add(SidebarChrome.sectionHeader("This clan"));
		panel.add(SidebarChrome.gap(6));

		// Two buttons that do the same KIND of thing belong on one line: stacked full-width they read
		// as a list of unrelated commands, and they cost two rows of a panel that has none to spare.
		// Side by side the labels have to be short, which is why the pair says "Sync roster" and the
		// lone button (nothing to sit beside) keeps the longer "Sync clan roster".
		boolean both = actions.canSyncProfile && actions.canSyncRoster;
		if (both)
		{
			panel.add(SidebarChrome.buttonRow(
				SidebarChrome.actionButton("Sync profile", "Send your collection log and best times to this clan's site",
					dataSource::syncProfile),
				SidebarChrome.actionButton("Sync roster", "Push the in-game clan member list to the site",
					dataSource::syncRoster)));
		}
		else if (actions.canSyncProfile)
		{
			panel.add(SidebarChrome.fullWidth(SidebarChrome.actionButton("Sync profile",
				"Send your collection log and best times to this clan's site", dataSource::syncProfile)));
		}
		else if (actions.canSyncRoster)
		{
			panel.add(SidebarChrome.fullWidth(SidebarChrome.actionButton("Sync clan roster",
				"Push the in-game clan member list to the site", dataSource::syncRoster)));
		}

		if (!actions.canSyncRoster && actions.rosterNote != null)
		{
			if (actions.canSyncProfile)
			{
				panel.add(SidebarChrome.gap(4));
			}
			JLabel note = new JLabel(SidebarChrome.plainText(actions.rosterNote));
			note.setFont(FontManager.getRunescapeSmallFont());
			note.setForeground(SidebarChrome.VALUE_COLOR);
			note.setAlignmentX(LEFT_ALIGNMENT);
			panel.add(note);
		}

		return panel;
	}

	/**
	 * Banner clips, which are files in a folder on THIS machine.
	 *
	 * <p>Nothing here is per account or per clan — swapping character or clan doesn't change which
	 * .wav files are on your disk — so the list is the same wherever you are in the panel. A lit
	 * swatch is in the play cycle, a hollow one is muted, and clicking the row flips it.
	 */
	private JPanel buildBannerSounds()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setAlignmentX(LEFT_ALIGNMENT);

		List<String> clips = dataSource.bannerSounds();
		panel.add(SidebarChrome.sectionHeader("Banner sounds"));
		panel.add(SidebarChrome.gap(6));

		if (clips.isEmpty())
		{
			JLabel none = new JLabel("None added — drop .wav files in the folder.");
			none.setFont(FontManager.getRunescapeSmallFont());
			none.setForeground(SidebarChrome.VALUE_COLOR);
			none.setAlignmentX(LEFT_ALIGNMENT);
			panel.add(none);
			panel.add(SidebarChrome.gap(4));
		}
		else
		{
			for (String clip : clips.subList(0, Math.min(clips.size(), BANNER_CLIPS_SHOWN)))
			{
				panel.add(buildClipRow(clip, dataSource.bannerSoundOn(clip)));
				panel.add(SidebarChrome.gap(2));
			}
			if (clips.size() > BANNER_CLIPS_SHOWN)
			{
				JLabel more = new JLabel("+" + (clips.size() - BANNER_CLIPS_SHOWN) + " more in the folder");
				more.setBorder(BorderFactory.createEmptyBorder(2, CLIP_TEXT_INSET, 0, 0));
				more.setFont(FontManager.getRunescapeSmallFont());
				more.setForeground(SidebarChrome.VALUE_COLOR);
				more.setAlignmentX(LEFT_ALIGNMENT);
				panel.add(more);
			}
			panel.add(SidebarChrome.gap(4));
		}

		JButton add = new JButton("Add clip");
		SidebarChrome.styleFlatButton(add, Color.WHITE);
		add.setToolTipText("Pick .wav files to copy into the sounds folder");
		add.addActionListener(e -> dataSource.importBannerSounds());
		JButton open = new JButton("Copy folder path");
		SidebarChrome.styleFlatButton(open, Color.WHITE);
		open.setToolTipText("Copy the sounds folder's path — paste it into your file manager to rename or delete clips");
		open.addActionListener(e -> dataSource.copyBannerSoundsPath());
		panel.add(SidebarChrome.buttonRow(add, open));
		return panel;
	}

	/** How far a clip's name sits from the panel edge — the swatch's width plus its gap. */
	private static final int CLIP_TEXT_INSET = 14;

	/**
	 * One clip: a lit swatch, its name, and a click that mutes or unmutes it.
	 *
	 * <p>Colour alone carried this before — a green name meant playing, a grey one meant muted — which
	 * is invisible if you don't already know the rule, and unreadable if you can't separate the two
	 * greens. The swatch says on/off the way the in-game list does, the row lights under the pointer
	 * so it's clearly a control, and the tooltip names the action rather than the state.
	 */
	private JPanel buildClipRow(String clip, boolean on)
	{
		String display = BannerSoundService.displayName(clip);

		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 4));
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.setToolTipText(SidebarChrome.plainText(display) + (on ? " — click to mute" : " — muted; click to unmute"));

		JPanel swatch = new JPanel();
		swatch.setPreferredSize(new Dimension(8, 8));
		swatch.setMinimumSize(new Dimension(8, 8));
		swatch.setMaximumSize(new Dimension(8, 8));
		swatch.setBackground(on ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.DARK_GRAY_COLOR);
		swatch.setBorder(BorderFactory.createLineBorder(on
			? ColorScheme.PROGRESS_COMPLETE_COLOR : SidebarChrome.WIDGET_BORDER));
		// Centre the 8px square against the text line rather than stretching it down the row.
		JPanel swatchBox = new JPanel(new GridBagLayout());
		swatchBox.setOpaque(false);
		swatchBox.setPreferredSize(new Dimension(CLIP_TEXT_INSET - 6, 12));
		swatchBox.add(swatch);

		JLabel name = new JLabel(SidebarChrome.plainText(SidebarChrome.ellipsize(display, 26)));
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(on ? ColorScheme.TEXT_COLOR : SidebarChrome.VALUE_COLOR);

		row.add(swatchBox, BorderLayout.WEST);
		row.add(name, BorderLayout.CENTER);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));

		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				dataSource.toggleBannerSound(clip);
				renderSelected();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(SidebarChrome.WIDGET_BG_HOVER);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARK_GRAY_COLOR);
			}
		});
		return row;
	}

	/** One row of the events list: name, kind, a one-line status, and (for a board) its progress bar. */
	private JPanel buildEventCard(ConnectionView c, BoardChoices.EventEntry entry)
	{
		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		card.setAlignmentX(LEFT_ALIGNMENT);
		card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		card.setToolTipText(SidebarChrome.plainText(entry.title));

		// Title + a chevron marking the row as a drill-in.
		JPanel titleRow = new JPanel(new BorderLayout(6, 0));
		titleRow.setOpaque(false);
		titleRow.setAlignmentX(LEFT_ALIGNMENT);
		JLabel title = new JLabel(SidebarChrome.plainText(SidebarChrome.ellipsize(entry.title, 22)));
		title.setFont(FontManager.getRunescapeFont());
		title.setForeground(ColorScheme.TEXT_COLOR);
		JLabel chevron = new JLabel("›");
		chevron.setFont(FontManager.getRunescapeBoldFont());
		chevron.setForeground(ColorScheme.BRAND_ORANGE);
		titleRow.add(title, BorderLayout.CENTER);
		titleRow.add(chevron, BorderLayout.EAST);
		titleRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, titleRow.getPreferredSize().height));
		card.add(titleRow);

		card.add(SidebarChrome.leftLabel(entry.kind, FontManager.getRunescapeSmallFont(), ColorScheme.LIGHT_GRAY_COLOR));

		if (entry.isBoard())
		{
			card.add(SidebarChrome.leftLabel(SidebarChrome.ellipsize(boardStatusLine(c), CARD_LINE_CHARS),
				FontManager.getRunescapeSmallFont(), SidebarChrome.VALUE_COLOR));
			// A ladder has no fixed board to fill, so its row shows standing instead of a progress bar.
			if (c.tilesTotal > 0 && c.ladder == null)
			{
				JProgressBar bar = new JProgressBar(0, 100);
				bar.setValue(c.completionPercent());
				bar.setStringPainted(false);
				bar.setBorderPainted(false);
				bar.setForeground(c.completionPercent() >= 100
					? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.BRAND_ORANGE);
				bar.setBackground(ColorScheme.DARK_GRAY_COLOR);
				bar.setPreferredSize(new Dimension(0, ProgressRows.PROGRESS_BAR_HEIGHT));
				bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, ProgressRows.PROGRESS_BAR_HEIGHT));
				bar.setAlignmentX(LEFT_ALIGNMENT);
				card.add(Box.createVerticalStrut(4));
				card.add(bar);
			}
		}
		else if (entry.weekly != null)
		{
			WeeklyView w = entry.weekly;
			card.add(SidebarChrome.leftLabel(SidebarChrome.ellipsize(w.metricLabel() + Clock.timingSuffix(w.upcoming, w.startDate, w.endDate),
				CARD_LINE_CHARS), FontManager.getRunescapeSmallFont(), SidebarChrome.VALUE_COLOR));
			if (!w.upcoming)
			{
				card.add(SidebarChrome.leftLabel(SidebarChrome.ellipsize(WeeklyCards.yourStandingLine(w), CARD_LINE_CHARS),
					FontManager.getRunescapeSmallFont(),
					w.yourRank > 0 ? ColorScheme.BRAND_ORANGE : SidebarChrome.VALUE_COLOR));
			}
		}
		else
		{
			ScheduledView s = entry.scheduled;
			String timing = Clock.timingLabel(!s.live, s.startDate, s.endDate);
			card.add(SidebarChrome.leftLabel(SidebarChrome.ellipsize(timing == null ? s.sizeLabel() : timing, CARD_LINE_CHARS),
				FontManager.getRunescapeSmallFont(), SidebarChrome.VALUE_COLOR));
			// A live event you're not in is worth flagging as joinable; an upcoming one just needs its size.
			card.add(SidebarChrome.leftLabel(SidebarChrome.ellipsize(s.live ? "Running — you're not in it" : s.sizeLabel(), CARD_LINE_CHARS),
				FontManager.getRunescapeSmallFont(), SidebarChrome.VALUE_COLOR));
		}

		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
		card.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				selectedEventKey = entry.key;
				renderSelected();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				card.setBackground(SidebarChrome.WIDGET_BG_HOVER);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
		});
		return card;
	}

	/** The board row's status line: "14 / 25 tiles · 56%", where you stand on a ladder, or why neither. */
	private static String boardStatusLine(ConnectionView c)
	{
		if (c.ladder != null && c.ladder.ladderFormat)
		{
			return c.ladder.monthRank > 0
				? "You: #" + c.ladder.monthRank + " · " + c.ladder.monthPoints + " pts this month"
				: "Unranked — claim a mission";
		}
		if (c.tilesTotal > 0)
		{
			return c.tilesComplete + " / " + c.tilesTotal + " " + c.unitNoun() + " · " + c.completionPercent() + "%";
		}
		return c.statusNote != null && !c.statusNote.isEmpty() ? c.statusNote : "No board to show yet.";
	}

	/** "‹ All events" — back out of a drilled-into event to the clan's list. */
	private JLabel backLink()
	{
		JLabel back = new JLabel("‹ All events");
		back.setFont(FontManager.getRunescapeSmallFont());
		back.setForeground(ColorScheme.BRAND_ORANGE);
		back.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		back.setAlignmentX(LEFT_ALIGNMENT);
		back.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				selectedEventKey = null;
				renderSelected();
			}
		});
		return back;
	}

	/**
	 * The starting-shot prompt: where to stand, this account's keyword, and the one button that
	 * captures the frame, burns the proof banner onto it and files it. Rendered only while a shot is
	 * actually owed — {@link SidebarDataSource#startProof()} returns null the moment one is filed.
	 */
	private JPanel buildStartProofCard(StartProof proof)
	{
		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setAlignmentX(LEFT_ALIGNMENT);
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.BRAND_ORANGE),
			BorderFactory.createEmptyBorder(6, 8, 8, 8)));

		JLabel title = new JLabel("Starting shot needed");
		title.setFont(FontManager.getRunescapeSmallFont());
		title.setForeground(ColorScheme.BRAND_ORANGE);
		title.setAlignmentX(LEFT_ALIGNMENT);
		card.add(title);

		if (proof.location != null && !proof.location.isEmpty())
		{
			JLabel where = new JLabel(SidebarChrome.plainText("Go to " + proof.location));
			where.setFont(FontManager.getRunescapeSmallFont());
			where.setForeground(Color.WHITE);
			where.setAlignmentX(LEFT_ALIGNMENT);
			where.setToolTipText(SidebarChrome.plainText(proof.location));
			card.add(SidebarChrome.gap(4));
			card.add(where);
		}

		if (proof.keyword != null && !proof.keyword.isEmpty())
		{
			JLabel word = new JLabel(SidebarChrome.plainText("Keyword: " + proof.keyword));
			word.setFont(FontManager.getRunescapeSmallFont());
			word.setForeground(SidebarChrome.VALUE_COLOR);
			word.setAlignmentX(LEFT_ALIGNMENT);
			card.add(SidebarChrome.gap(2));
			card.add(word);
		}

		if (proof.maxSessionMinutes > 0)
		{
			// Said here as well as in chat, because it's the one requirement you can't fix after the
			// fact: the logout is what flushes the hiscores the event's baseline is read from.
			JLabel relog = new JLabel(SidebarChrome.plainText("Log out and back in first (within " + proof.maxSessionMinutes + " min)"));
			relog.setFont(FontManager.getRunescapeSmallFont());
			relog.setForeground(Color.WHITE);
			relog.setAlignmentX(LEFT_ALIGNMENT);
			relog.setToolTipText("Hiscores only save when you log out, so this is what sets your starting totals.");
			card.add(SidebarChrome.gap(2));
			card.add(relog);
		}

		if ("rejected".equals(proof.status))
		{
			card.add(SidebarChrome.gap(2));
			card.add(SidebarChrome.warningLabel("Your last shot was rejected — take another."));
		}

		// WHY it matters, which the card never said. A player who reads "starting shot needed" and
		// carries on has no way to know their drops are landing in a review queue meanwhile.
		card.add(SidebarChrome.gap(4));
		card.add(SidebarChrome.warningLabel("Drops you send now are held for review until this is filed."));

		String left = StartProofRules.describeWindow(proof, System.currentTimeMillis());
		if (left != null)
		{
			JLabel expires = new JLabel(SidebarChrome.plainText("Asked for another " + left + ", then it lapses"));
			expires.setFont(FontManager.getRunescapeSmallFont());
			expires.setForeground(Color.WHITE);
			expires.setAlignmentX(LEFT_ALIGNMENT);
			expires.setToolTipText("Six hours in, the game has logged everyone out anyway, so the shot stops being asked for.");
			card.add(SidebarChrome.gap(2));
			card.add(expires);
		}

		JButton take = new JButton("Take starting shot");
		SidebarChrome.styleFlatButton(take, ColorScheme.BRAND_ORANGE);
		take.setAlignmentX(LEFT_ALIGNMENT);
		take.addActionListener(e ->
		{
			take.setEnabled(false);
			take.setText("Sending...");
			// The capture itself hops to the next rendered frame and then to a worker; the panel just
			// asks for it. The button goes away entirely on the next poll, once the site agrees.
			dataSource.captureStartProof();
			// ...unless the capture refused it — standing in the wrong place, or a session too old to
			// have flushed the hiscores. That answer arrives in chat, so the button has to come back
			// rather than sit on "Sending..." until the next poll redraws the card.
			Timer restore = new Timer(4000, ev ->
			{
				take.setText("Take starting shot");
				take.setEnabled(true);
			});
			restore.setRepeats(false);
			restore.start();
		});
		card.add(SidebarChrome.gap(6));
		card.add(take);

		return card;
	}

	private JPanel buildSummary(ConnectionView c)
	{
		// Only one card renders at a time (renderSelected), so the ladder tick binds to a single set of
		// held refs. Reset them each render; a non-ladder card leaves the tick idle.
		ladderCard.clearRefs();
		// A ladder IS its missions board, so the card replaces the summary. A bingo that merely carries
		// missions keeps its tile-count summary and gets the mission strip as its own section below.
		if (c.ladder != null && c.ladder.ladderFormat)
		{
			return ladderCard.buildCard(c);
		}

		JPanel panel = new JPanel(new BorderLayout(0, 4));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		panel.setAlignmentX(LEFT_ALIGNMENT);

		String eventLine = c.eventName == null || c.eventName.isEmpty() ? c.clanName : c.eventName;
		JLabel event = new JLabel(SidebarChrome.plainText(eventLine));
		event.setFont(FontManager.getRunescapeFont());
		event.setForeground(ColorScheme.TEXT_COLOR);
		panel.add(event, BorderLayout.NORTH);

		// Count is a FontManager JLabel, NOT painted inside the bar (default L&F font rendered poorly).
		JPanel bottom = new JPanel(new BorderLayout(0, 2));
		bottom.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		if (c.tilesTotal <= 0)
		{
			// No live board on this clan (federated homes with no running event report 0/0) — a bare
			// "0 / 0 tiles · 0%" reads like a bug, so say what's actually going on instead. The view
			// can carry a more specific line (e.g. the logged-out home's "Log in in-game …").
			JLabel none = new JLabel(c.statusNote != null && !c.statusNote.isEmpty()
				? c.statusNote : "No active event yet.");
			none.setFont(FontManager.getRunescapeSmallFont());
			none.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			bottom.add(none, BorderLayout.NORTH);
		}
		else
		{
			JLabel count = new JLabel(c.tilesComplete + " / " + c.tilesTotal + " " + c.unitNoun() + " · " + c.completionPercent() + "%");
			count.setFont(FontManager.getRunescapeSmallFont());
			count.setForeground(SidebarChrome.VALUE_COLOR);
			bottom.add(count, BorderLayout.NORTH);

			JProgressBar bar = new JProgressBar(0, 100);
			bar.setValue(c.completionPercent());
			bar.setStringPainted(false);
			bar.setForeground(c.completionPercent() >= 100
				? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.BRAND_ORANGE);
			bar.setBackground(ColorScheme.DARK_GRAY_COLOR);
			bar.setBorderPainted(false);
			bar.setPreferredSize(new Dimension(0, ProgressRows.PROGRESS_BAR_HEIGHT + 1));
			bottom.add(bar, BorderLayout.CENTER);

			// Reveal-policy boards: "4 tiles hidden · next in 42m" under the bar, so members know
			// more is coming (and when) without opening the site. Null on classic boards.
			if (c.revealNote != null && !c.revealNote.isEmpty())
			{
				JLabel reveal = new JLabel(SidebarChrome.plainText(c.revealNote));
				reveal.setFont(FontManager.getRunescapeSmallFont());
				reveal.setForeground(ColorScheme.BRAND_ORANGE);
				bottom.add(reveal, BorderLayout.SOUTH);
			}
		}

		panel.add(bottom, BorderLayout.CENTER);

		JPanel south = new JPanel();
		south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
		south.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		if (c.boardUrl != null && !c.boardUrl.isEmpty())
		{
			south.add(SidebarChrome.boardLink(c.boardUrl));
		}
		if (south.getComponentCount() > 0)
		{
			panel.add(south, BorderLayout.SOUTH);
		}

		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
		return panel;
	}

	/** Swap the single content component and repaint. */
	private void setContent(Component component)
	{
		SwingUtilities.invokeLater(() ->
		{
			content.removeAll();
			if (component instanceof JComponent)
			{
				((JComponent) component).setAlignmentX(LEFT_ALIGNMENT);
			}
			content.add(component);
			content.revalidate();
			content.repaint();
		});
	}

}
