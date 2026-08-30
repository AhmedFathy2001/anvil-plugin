package com.anvil;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.Box;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.plaf.basic.BasicArrowButton;
import javax.swing.plaf.basic.BasicComboBoxRenderer;
import javax.swing.plaf.basic.BasicComboBoxUI;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;
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

	private static final Color VALUE_COLOR = new Color(0x98_98_98);
	private static final int PROGRESS_BAR_HEIGHT = 6;

	// Anvil theme for the interactive widgets: flat dark surfaces with the gold/orange accent the
	// rest of the sidebar (title, bars, links) already uses — default Swing chrome sticks out badly.
	private static final Color WIDGET_BG = ColorScheme.DARKER_GRAY_COLOR;
	private static final Color WIDGET_BG_HOVER = ColorScheme.DARK_GRAY_HOVER_COLOR;
	private static final Color WIDGET_BORDER = new Color(0x47_47_47);

	/** Wrap width for the connect-status line so a long notice wraps instead of clipping (tooltip carries full text). */
	private static final int STATUS_WRAP_PX = PluginPanel.PANEL_WIDTH - 40;

	/** Max activity rows rendered — keeps the sidebar glanceable; the rest collapse into a "+N more". */
	private static final int ACTIVITY_ROWS_SHOWN = 12;

	/** Clip rows on the home page before the rest collapse into a "+N more" line. */
	private static final int BANNER_CLIPS_SHOWN = 8;

	/** Leaderboard rows rendered on a weekly card before the caller's own (out-of-view) row is spliced in. */
	private static final int WEEKLY_ROWS_SHOWN = 10;

	/** Selection key for a clan's own bingo/ladder board in the events list. */
	private static final String BOARD_EVENT_KEY = "board";

	/** Roughly what fits on one small-font line at {@link PluginPanel#PANEL_WIDTH} — event cards clip to it. */
	private static final int CARD_LINE_CHARS = 30;

	private final SidebarDataSource dataSource;

	// Header controls (persistent across state changes).
	private final JComboBox<ConnectionView> clanFilter = new JComboBox<>();
	private final JButton refreshButton = new JButton("Refresh");

	// Device sign-in (home-native, DeviceSignIn): shown when a Site URL is configured but no
	// Account Token yet — replaces the copy-the-token-from-your-profile step.
	private final BingoApiClient apiClient;
	private final net.runelite.client.config.ConfigManager configManager;
	/** RuneLite's shared client-lifetime scheduler — paces the sign-in flow's approval polls. */
	private final ScheduledExecutorService executor;
	private final JButton signInButton = new JButton("Sign in with Discord");
	private final JLabel signInStatus = new JLabel();
	private final JPanel signInRow = new JPanel(new BorderLayout(0, 2));
	private boolean signInInFlight;

	private final JPanel content = new JPanel();

	// Only re-renders while the panel is visible; started on activate, stopped on deactivate.
	private final Timer autoRefresh;

	// --- Ladder missions board (DMM-All-Stars style) --------------------------------------------
	/** How long a card pulses gold after a new mission / claim (signalled from the plugin off-EDT). */
	private static final int LADDER_FLASH_MS = 4000;
	private static final Color LADDER_FLASH_COLOR = ColorScheme.BRAND_ORANGE;
	/** Ticks the live countdown + per-mission grow/decay value once a second, with NO refetch. */
	private final Timer ladderTick = new Timer(1000, e -> tickLadder());
	/** The currently-rendered ladder card's data (countdown target, decay, missions), or null. */
	private ConnectionView.Ladder ladderState;
	/** Held label refs for the rendered ladder card so the tick updates them in place. */
	private JLabel ladderCountdownLabel;
	private final List<LadderValueLabel> ladderValueLabels = new ArrayList<>();
	private JPanel ladderCardPanel;
	/** Wall-clock (ms) until which the card pulses; written off-EDT by {@link #flashLadder()}. */
	private volatile long ladderFlashUntil;
	/** True while the card currently shows a coloured (flash) border — lets the tick reset it once. */
	private boolean ladderFlashPainted;

	// Last snapshot + selected clan — preserved across refreshes so auto-refresh doesn't reset/flicker the list.
	private List<ConnectionView> connections = java.util.Collections.emptyList();
	private String selectedInstanceId;

	/** Which event the member drilled into, or null for the list. Only meaningful when a clan runs several. */
	private String selectedEventKey;

	// Guards against ActionEvents fired while we rebuild the combo model, and against overlapping fetches.
	private boolean rebuildingFilter;
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

		styleFlatButton(refreshButton, Color.WHITE);
		refreshButton.addActionListener(e -> refresh(true));

		JPanel titleRow = new JPanel(new BorderLayout());
		titleRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		titleRow.add(title, BorderLayout.WEST);
		titleRow.add(refreshButton, BorderLayout.EAST);
		titleRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, titleRow.getPreferredSize().height));
		titleRow.setAlignmentX(LEFT_ALIGNMENT);

		// Sign-in affordance — visible only in the "Site URL set, no token" state (see refreshSignInRow).
		styleFlatButton(signInButton, ColorScheme.BRAND_ORANGE);
		signInButton.addActionListener(e -> startSignIn());
		signInStatus.setFont(FontManager.getRunescapeSmallFont());
		signInStatus.setForeground(VALUE_COLOR);
		signInStatus.setVisible(false);
		signInRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		signInRow.add(signInButton, BorderLayout.NORTH);
		signInRow.add(signInStatus, BorderLayout.SOUTH);
		signInRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, signInRow.getPreferredSize().height));
		signInRow.setAlignmentX(LEFT_ALIGNMENT);
		signInRow.setVisible(false);

		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);
		top.add(titleRow);
		top.add(Box.createVerticalStrut(6));
		top.add(signInRow);
		header.add(top, BorderLayout.NORTH);

		// Clan filter — selecting a clan re-renders from the held snapshot (no refetch).
		styleClanFilter(clanFilter);
		clanFilter.setRenderer(new ClanFilterRenderer());
		clanFilter.setFocusable(false);
		clanFilter.addActionListener(e ->
		{
			if (rebuildingFilter)
			{
				return;
			}
			ConnectionView sel = (ConnectionView) clanFilter.getSelectedItem();
			if (sel != null)
			{
				selectedInstanceId = sel.instanceId;
				selectedEventKey = null; // another clan's events are a different list — start at the top
				renderSelected();
			}
		});
		header.add(clanFilter, BorderLayout.SOUTH);

		return header;
	}

	// ---- Lifecycle (Activatable) — poll only while the panel is on screen -------------------------

	@Override
	public void onActivate()
	{
		refresh();
		autoRefresh.start();
		ladderTick.start();
	}

	@Override
	public void onDeactivate()
	{
		autoRefresh.stop();
		ladderTick.stop();
	}

	// ---- Device sign-in (DeviceSignIn) ------------------------------------------------------

	/** Show the Sign-in button exactly while a Site URL is configured but no Account Token exists. */
	private void refreshSignInRow()
	{
		if (!signInInFlight)
		{
			signInRow.setVisible(apiClient.needsSignIn());
			signInRow.revalidate();
			signInRow.repaint();
		}
	}

	/** Run the device sign-in (async, every step on the shared executor); on success store the token —
	 * onConfigChanged does the rest. Both callbacks arrive off the EDT, so they marshal via invokeLater. */
	private void startSignIn()
	{
		if (signInInFlight)
		{
			return;
		}
		signInInFlight = true;
		signInButton.setEnabled(false);
		setSignInStatus("Starting…");

		new DeviceSignIn(apiClient, executor).run(
			line -> SwingUtilities.invokeLater(() -> setSignInStatus(line)),
			result -> SwingUtilities.invokeLater(() ->
			{
				signInInFlight = false;
				signInButton.setEnabled(true);
				if (result.outcome == DeviceSignIn.Outcome.SIGNED_IN)
				{
					// Storing the token fires the plugin's onConfigChanged → client reconfigure,
					// identity stamp + greet, and a sidebar refresh — the same path as a manual paste.
					configManager.setConfiguration("osrsbingo", "playerToken", result.token);

					// Read it straight back. A signed-in-but-empty config is the one failure mode a
					// member can't diagnose: the site says they approved, the panel keeps asking them
					// to sign in, and nothing explains why. It happens when the config write doesn't
					// take — a synced RuneLite profile clobbered by a second client, most often — and
					// it MUST NOT look like the sign-in itself failed. Say what happened and give them
					// the manual route, which always works.
					String stored = configManager.getConfiguration("osrsbingo", "playerToken");
					if (stored == null || stored.isEmpty())
					{
						log.warn("Anvil: signed in but the token did not persist to the RuneLite config");
						setSignInStatus("Signed in, but RuneLite didn't save the token — paste it "
							+ "from Profile → RuneLite plugin on the site.");
					}
					else
					{
						setSignInStatus("");
					}
				}
				refreshSignInRow();
				refresh();
			}));
	}

	/**
	 * The line under the sign-in button — most importantly the one carrying the approval code.
	 *
	 * <p>It used to be a bare setText on a label inside a row whose maximum height was capped at
	 * CONSTRUCTION time, while the label was still hidden. So the row could never grow to fit it: the
	 * status was set, the panel was told to show it, and the member saw an empty box where the code
	 * they had been sent to find was supposed to be. Re-cap on every change, like the site-connect
	 * row already does, and wrap as HTML so a long line breaks instead of being clipped at the edge
	 * of a narrow sidebar.
	 */
	private void setSignInStatus(String text)
	{
		String plain = text == null ? "" : text;
		boolean show = !plain.isEmpty();
		String escaped = plain.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		signInStatus.setText(show ? "<html><body style='width:" + STATUS_WRAP_PX + "px'>" + escaped + "</body></html>" : "");
		signInStatus.setToolTipText(show ? plain : null);
		signInStatus.setVisible(show);
		signInRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, signInRow.getPreferredSize().height));
		signInRow.revalidate();
		signInRow.repaint();
	}

	// ---- Refresh flow -----------------------------------------------------------------------------

	/**
	 * Forget everything on screen, because it belongs to a site or an account we are no longer using.
	 *
	 * <p>Called the moment the Site URL or the token changes. Without it the panel kept rendering the
	 * previous clan's events, board and roster until a fetch against the NEW credentials succeeded —
	 * which is indefinitely when the new ones are wrong, so the member sits looking at a clan they
	 * just left and reasonably concludes the change didn't take.
	 */
	public void clearForCredentialChange()
	{
		connections = java.util.Collections.emptyList();
		selectedInstanceId = null;
		selectedEventKey = null;
		rebuildingFilter = true;
		clanFilter.removeAllItems();
		rebuildingFilter = false;
		clanFilter.setVisible(false);
		setSignInStatus("");
		refreshSignInRow();
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
				refreshSignInRow();
			}
		}.execute();
	}

	/** New snapshot in hand — refresh the clan filter (preserving selection) and render. Runs on the EDT. */
	private void onConnections(List<ConnectionView> fetched)
	{
		connections = fetched == null ? java.util.Collections.emptyList() : fetched;

		if (connections.isEmpty())
		{
			selectedInstanceId = null;
			selectedEventKey = null;
			rebuildFilter();
			renderEmpty();
			return;
		}

		// Keep the current selection if that clan is still connected; otherwise land on the clan this
		// account actually belongs to.
		if (findSelected() == null)
		{
			selectedInstanceId = landingClan(connections).instanceId;
			selectedEventKey = null;
		}
		rebuildFilter();
		renderSelected();
	}

	/**
	 * Which clan the sidebar opens on. Normally the one the plugin is addressing. But a player can be
	 * a mere GUEST there while being a real member of another clan they hold a seat in (an alt's clan,
	 * a friend's, one they joined later); for them the guest board is noise and their own clan is the
	 * point. So when we KNOW this account is a guest here and a member there, we land there instead.
	 *
	 * <p>Both halves must be positive evidence ({@link ConnectionView#member} is tri-state): an older
	 * home that doesn't send the flag, or a login screen where membership hasn't been answered yet,
	 * keeps the home default rather than guessing. Only the DEFAULT moves — the clan dropdown keeps its
	 * home-first order, and a member's own pick always wins until that clan drops out.</p>
	 */
	static ConnectionView landingClan(List<ConnectionView> connections)
	{
		ConnectionView home = null;
		for (ConnectionView c : connections)
		{
			if (AnvilSidebarDataSource.LOCAL_INSTANCE_ID.equals(c.instanceId))
			{
				home = c;
				break;
			}
		}
		if (home == null)
		{
			home = connections.get(0); // no home card at all (its fetch failed) — first relayed clan
		}
		if (!home.isGuestHere())
		{
			return home;
		}
		for (ConnectionView c : connections)
		{
			if (c.isMemberHere())
			{
				return c;
			}
		}
		return home;
	}

	private ConnectionView findSelected()
	{
		if (selectedInstanceId == null)
		{
			return null;
		}
		for (ConnectionView c : connections)
		{
			if (selectedInstanceId.equals(c.instanceId))
			{
				return c;
			}
		}
		return null;
	}

	/** Repopulate the dropdown from {@link #connections}, suppressing the selection ActionEvent storm. */
	private void rebuildFilter()
	{
		rebuildingFilter = true;
		try
		{
			DefaultComboBoxModel<ConnectionView> model = new DefaultComboBoxModel<>();
			ConnectionView toSelect = null;
			for (ConnectionView c : connections)
			{
				model.addElement(c);
				if (c.instanceId.equals(selectedInstanceId))
				{
					toSelect = c;
				}
			}
			clanFilter.setModel(model);
			if (toSelect != null)
			{
				clanFilter.setSelectedItem(toSelect);
			}
			clanFilter.setVisible(connections.size() > 1); // no point showing a one-option filter
		}
		finally
		{
			rebuildingFilter = false;
		}
	}

	// ---- Render states ----------------------------------------------------------------------------

	private void renderLoading()
	{
		clanFilter.setVisible(false);
		JLabel loading = new JLabel("Loading progress…", SwingConstants.CENTER);
		loading.setForeground(VALUE_COLOR);
		loading.setBorder(BorderFactory.createEmptyBorder(24, 0, 0, 0));
		setContent(loading);
	}

	private void renderError(String message)
	{
		clanFilter.setVisible(false);
		PluginErrorPanel error = new PluginErrorPanel();
		error.setContent("Couldn't load progress",
			(message == null || message.isEmpty() ? "Something went wrong." : message)
				+ " Use Refresh to try again.");
		setContent(error);
	}

	private void renderEmpty()
	{
		clanFilter.setVisible(false);
		PluginErrorPanel empty = new PluginErrorPanel();
		empty.setContent("No connected clans",
			"Link a clan on your Anvil site and its board progress will show up here.");
		setContent(empty);
	}

	/**
	 * Render the selected clan: one event drills straight in (today's board card), several show the
	 * events list until the member picks one. A drilled-into event that stopped running (a weekly ended
	 * mid-session) falls back to the list rather than a blank card.
	 */
	private void renderSelected()
	{
		ConnectionView selected = findSelected();
		if (selected == null)
		{
			renderEmpty();
			return;
		}

		List<EventEntry> events = eventsOf(selected);
		EventEntry chosen = findEvent(events, selectedEventKey);
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
	private void renderEvent(ConnectionView selected, EventEntry entry, boolean withBack)
	{
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);

		if (withBack)
		{
			body.add(backLink());
			body.add(gap(8));
		}

		if (entry != null && entry.weekly != null)
		{
			clearLadderRefs(); // a weekly card holds no ladder labels — leave the tick idle
			body.add(buildWeeklyCard(entry.weekly));
			if (!entry.weekly.upcoming)
			{
				body.add(gap(12));
				body.add(buildWeeklyStandings(entry.weekly));
			}
			setContent(body);
			return;
		}

		if (entry != null && entry.scheduled != null)
		{
			clearLadderRefs();
			body.add(buildScheduledCard(entry.scheduled));
			setContent(body);
			return;
		}

		if (selected.hasError())
		{
			body.add(warningLabel(selected.error));
			body.add(gap(8));
		}

		body.add(buildSummary(selected));
		body.add(gap(12));

		// STARTING SHOT — the one thing here that blocks play, so it sits directly under the board
		// summary rather than below the feed. Home clan only: it's an obligation on THIS account at
		// the site we're authenticated against, not something a relayed clan can ask for.
		PluginConfigResponse.StartProof startProof =
			AnvilSidebarDataSource.LOCAL_INSTANCE_ID.equals(selected.instanceId) ? dataSource.startProof() : null;
		if (startProof != null)
		{
			body.add(buildStartProofCard(startProof));
			body.add(gap(12));
		}

		// Active now — tiles you and teammates are working right now (deduped by tile).
		if (!selected.activeNow.isEmpty())
		{
			body.add(sectionHeader("Active now"));
			body.add(gap(6));
			boolean firstActive = true;
			for (ConnectionView.ActiveTask task : selected.activeNow)
			{
				if (!firstActive)
				{
					body.add(gap(8));
				}
				body.add(buildActiveRow(task));
				firstActive = false;
			}
			body.add(gap(12));
		}

		// Missions on an ordinary bingo: their own strip with the same countdown + live values a ladder
		// gets. Without this they only ever showed up as "New tile revealed" lines in the activity feed,
		// which is neither a timer nor a mission board. A ladder's card already IS this, so it's skipped.
		if (selected.ladder != null && !selected.ladder.ladderFormat && !selected.ladder.missions.isEmpty())
		{
			body.add(sectionHeader("Missions"));
			body.add(gap(6));
			body.add(buildMissionStrip(selected.ladder));
			body.add(gap(12));
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
			body.add(sectionHeader(selected.ladder != null && selected.ladder.ladderFormat
				? "Recent activity" : "Team activity"));
			body.add(gap(6));
			body.add(buildActivityFeed(actions));
			body.add(gap(12));
		}
		// A ladder's card and a bingo's mission strip already show what's open with a live countdown, so
		// repeating each drop as a feed line is noise. Only boards WITHOUT that strip list reveals.
		boolean missionStripShown = selected.ladder != null
			&& (selected.ladder.ladderFormat || !selected.ladder.missions.isEmpty());
		if (!reveals.isEmpty() && !missionStripShown)
		{
			body.add(sectionHeader("Just opened"));
			body.add(gap(6));
			body.add(buildActivityFeed(reveals));
			body.add(gap(12));
		}

		// No live board at all (stub home card / event-less federated clan) → the summary line already
		// says why; an empty "Nearest tiles" section under it would just restate the absence.
		if (selected.tilesTotal > 0)
		{
			body.add(sectionHeader("Nearest tiles"));
			body.add(gap(6));

			if (selected.nearestTiles.isEmpty())
			{
				JLabel none = new JLabel("No tiles to show yet.");
				none.setFont(FontManager.getRunescapeSmallFont());
				none.setForeground(VALUE_COLOR);
				none.setAlignmentX(LEFT_ALIGNMENT);
				body.add(none);
			}
			else
			{
				boolean first = true;
				for (ConnectionView.TileProgressView tile : selected.nearestTiles)
				{
					if (!first)
					{
						body.add(gap(8));
					}
					body.add(buildTileRow(tile));
					first = false;
				}
			}
		}

		// Clan actions ride at the bottom of an event view too. They belong to the clan, not to the
		// page you happen to be on — losing them the moment you opened an event was half of why the
		// roster button looked like it came and went.
		body.add(gap(12));
		body.add(buildPanelActions(selected));

		setContent(body);
	}

	// ---- Events list (a clan running more than one thing) ------------------------------------------

	/**
	 * Everything selectable on this clan card: its live board (when there is one) followed by the live
	 * weeklies. Static + package-private so the list/drill-in rule is unit-testable without Swing.
	 */
	static List<EventEntry> eventsOf(ConnectionView c)
	{
		List<EventEntry> out = new ArrayList<>();
		boolean hasBoard = (c.eventName != null && !c.eventName.isEmpty()) || c.tilesTotal > 0;
		if (hasBoard)
		{
			// Your own board leads — it's the one with progress on it.
			String title = c.eventName == null || c.eventName.isEmpty() ? c.clanName : c.eventName;
			out.add(new EventEntry(BOARD_EVENT_KEY, title,
				c.ladder != null && c.ladder.ladderFormat ? "Ladder" : "Bingo", null, null));
		}
		for (ConnectionView.WeeklyView w : c.weeklies)
		{
			out.add(new EventEntry("weekly:" + w.id, w.title, w.kindLabel(), w, null));
		}
		for (ConnectionView.ScheduledView s : c.scheduled)
		{
			out.add(new EventEntry("event:" + s.id, s.title, s.kindLabel(), null, s));
		}
		return out;
	}

	private static EventEntry findEvent(List<EventEntry> events, String key)
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
	static final class EventEntry
	{
		/** Stable selection key, so the choice survives an auto-refresh. */
		final String key;
		final String title;
		/** "Bingo" / "Ladder" / "Skill of the Week" / "Boss of the Week". */
		final String kind;
		/** The weekly this entry stands for; null unless it IS a weekly. */
		final ConnectionView.WeeklyView weekly;
		/** The scheduled bingo this entry stands for; null unless it IS one. */
		final ConnectionView.ScheduledView scheduled;

		EventEntry(String key, String title, String kind, ConnectionView.WeeklyView weekly,
			ConnectionView.ScheduledView scheduled)
		{
			this.key = key;
			this.title = title == null ? "" : title;
			this.kind = kind == null ? "" : kind;
			this.weekly = weekly;
			this.scheduled = scheduled;
		}

		boolean isBoard()
		{
			return weekly == null && scheduled == null;
		}
	}

	/** The clan's events as clickable cards — the landing view whenever a clan runs more than one. */
	private void renderEventList(ConnectionView c, List<EventEntry> events)
	{
		clearLadderRefs();

		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);

		if (c.hasError())
		{
			body.add(warningLabel(c.error));
			body.add(gap(8));
		}

		if (events.isEmpty())
		{
			// Nothing running is a state, not an error: say so and leave the clan's own actions below.
			JLabel none = new JLabel("No active event yet.");
			none.setFont(FontManager.getRunescapeSmallFont());
			none.setForeground(VALUE_COLOR);
			none.setAlignmentX(LEFT_ALIGNMENT);
			body.add(none);
			body.add(gap(12));
		}
		else
		{
			body.add(sectionHeader("Events"));
			body.add(gap(6));
			boolean first = true;
			for (EventEntry e : events)
			{
				if (!first)
				{
					body.add(gap(8));
				}
				body.add(buildEventCard(c, e));
				first = false;
			}
			body.add(gap(12));
		}

		// The clan's own controls, under whatever is running. Home only, and only what this account
		// can actually do — see SidebarDataSource.actionsFor.
		body.add(buildPanelActions(c));
		body.add(gap(12));
		body.add(buildBannerSounds());
		setContent(body);
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

		panel.add(sectionHeader("This clan"));
		panel.add(gap(6));

		// Two buttons that do the same KIND of thing belong on one line: stacked full-width they read
		// as a list of unrelated commands, and they cost two rows of a panel that has none to spare.
		// Side by side the labels have to be short, which is why the pair says "Sync roster" and the
		// lone button (nothing to sit beside) keeps the longer "Sync clan roster".
		boolean both = actions.canSyncProfile && actions.canSyncRoster;
		if (both)
		{
			panel.add(buttonRow(
				actionButton("Sync profile", "Send your collection log and best times to this clan's site",
					dataSource::syncProfile),
				actionButton("Sync roster", "Push the in-game clan member list to the site",
					dataSource::syncRoster)));
		}
		else if (actions.canSyncProfile)
		{
			panel.add(fullWidth(actionButton("Sync profile",
				"Send your collection log and best times to this clan's site", dataSource::syncProfile)));
		}
		else if (actions.canSyncRoster)
		{
			panel.add(fullWidth(actionButton("Sync clan roster",
				"Push the in-game clan member list to the site", dataSource::syncRoster)));
		}

		if (!actions.canSyncRoster && actions.rosterNote != null)
		{
			if (actions.canSyncProfile)
			{
				panel.add(gap(4));
			}
			JLabel note = new JLabel(plainText(actions.rosterNote));
			note.setFont(FontManager.getRunescapeSmallFont());
			note.setForeground(VALUE_COLOR);
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

		java.util.List<String> clips = dataSource.bannerSounds();
		panel.add(sectionHeader("Banner sounds"));
		panel.add(gap(6));

		if (clips.isEmpty())
		{
			JLabel none = new JLabel("None added — drop .wav files in the folder.");
			none.setFont(FontManager.getRunescapeSmallFont());
			none.setForeground(VALUE_COLOR);
			none.setAlignmentX(LEFT_ALIGNMENT);
			panel.add(none);
			panel.add(gap(4));
		}
		else
		{
			for (String clip : clips.subList(0, Math.min(clips.size(), BANNER_CLIPS_SHOWN)))
			{
				panel.add(buildClipRow(clip, dataSource.bannerSoundOn(clip)));
				panel.add(gap(2));
			}
			if (clips.size() > BANNER_CLIPS_SHOWN)
			{
				JLabel more = new JLabel("+" + (clips.size() - BANNER_CLIPS_SHOWN) + " more in the folder");
				more.setBorder(BorderFactory.createEmptyBorder(2, CLIP_TEXT_INSET, 0, 0));
				more.setFont(FontManager.getRunescapeSmallFont());
				more.setForeground(VALUE_COLOR);
				more.setAlignmentX(LEFT_ALIGNMENT);
				panel.add(more);
			}
			panel.add(gap(4));
		}

		JButton add = new JButton("Add clip");
		styleFlatButton(add, Color.WHITE);
		add.setToolTipText("Pick .wav files to copy into the sounds folder");
		add.addActionListener(e -> dataSource.importBannerSounds());
		JButton open = new JButton("Copy folder path");
		styleFlatButton(open, Color.WHITE);
		open.setToolTipText("Copy the sounds folder's path — paste it into your file manager to rename or delete clips");
		open.addActionListener(e -> dataSource.copyBannerSoundsPath());
		panel.add(buttonRow(add, open));
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
		row.setToolTipText(plainText(display) + (on ? " — click to mute" : " — muted; click to unmute"));

		JPanel swatch = new JPanel();
		swatch.setPreferredSize(new Dimension(8, 8));
		swatch.setMinimumSize(new Dimension(8, 8));
		swatch.setMaximumSize(new Dimension(8, 8));
		swatch.setBackground(on ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.DARK_GRAY_COLOR);
		swatch.setBorder(BorderFactory.createLineBorder(on
			? ColorScheme.PROGRESS_COMPLETE_COLOR : WIDGET_BORDER));
		// Centre the 8px square against the text line rather than stretching it down the row.
		JPanel swatchBox = new JPanel(new GridBagLayout());
		swatchBox.setOpaque(false);
		swatchBox.setPreferredSize(new Dimension(CLIP_TEXT_INSET - 6, 12));
		swatchBox.add(swatch);

		JLabel name = new JLabel(plainText(ellipsize(display, 26)));
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(on ? ColorScheme.TEXT_COLOR : VALUE_COLOR);

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
				row.setBackground(WIDGET_BG_HOVER);
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
	private JPanel buildEventCard(ConnectionView c, EventEntry entry)
	{
		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		card.setAlignmentX(LEFT_ALIGNMENT);
		card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		card.setToolTipText(plainText(entry.title));

		// Title + a chevron marking the row as a drill-in.
		JPanel titleRow = new JPanel(new BorderLayout(6, 0));
		titleRow.setOpaque(false);
		titleRow.setAlignmentX(LEFT_ALIGNMENT);
		JLabel title = new JLabel(plainText(ellipsize(entry.title, 22)));
		title.setFont(FontManager.getRunescapeFont());
		title.setForeground(ColorScheme.TEXT_COLOR);
		JLabel chevron = new JLabel("›");
		chevron.setFont(FontManager.getRunescapeBoldFont());
		chevron.setForeground(ColorScheme.BRAND_ORANGE);
		titleRow.add(title, BorderLayout.CENTER);
		titleRow.add(chevron, BorderLayout.EAST);
		titleRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, titleRow.getPreferredSize().height));
		card.add(titleRow);

		card.add(leftLabel(entry.kind, FontManager.getRunescapeSmallFont(), ColorScheme.LIGHT_GRAY_COLOR));

		if (entry.isBoard())
		{
			card.add(leftLabel(ellipsize(boardStatusLine(c), CARD_LINE_CHARS),
				FontManager.getRunescapeSmallFont(), VALUE_COLOR));
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
				bar.setPreferredSize(new Dimension(0, PROGRESS_BAR_HEIGHT));
				bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, PROGRESS_BAR_HEIGHT));
				bar.setAlignmentX(LEFT_ALIGNMENT);
				card.add(Box.createVerticalStrut(4));
				card.add(bar);
			}
		}
		else if (entry.weekly != null)
		{
			ConnectionView.WeeklyView w = entry.weekly;
			card.add(leftLabel(ellipsize(w.metricLabel() + timingSuffix(w.upcoming, w.startDate, w.endDate),
				CARD_LINE_CHARS), FontManager.getRunescapeSmallFont(), VALUE_COLOR));
			if (!w.upcoming)
			{
				card.add(leftLabel(ellipsize(yourStandingLine(w), CARD_LINE_CHARS),
					FontManager.getRunescapeSmallFont(),
					w.yourRank > 0 ? ColorScheme.BRAND_ORANGE : VALUE_COLOR));
			}
		}
		else
		{
			ConnectionView.ScheduledView s = entry.scheduled;
			String timing = timingLabel(!s.live, s.startDate, s.endDate);
			card.add(leftLabel(ellipsize(timing == null ? s.sizeLabel() : timing, CARD_LINE_CHARS),
				FontManager.getRunescapeSmallFont(), VALUE_COLOR));
			// A live event you're not in is worth flagging as joinable; an upcoming one just needs its size.
			card.add(leftLabel(ellipsize(s.live ? "Running — you're not in it" : s.sizeLabel(), CARD_LINE_CHARS),
				FontManager.getRunescapeSmallFont(), VALUE_COLOR));
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
				card.setBackground(WIDGET_BG_HOVER);
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

	// ---- Weekly competition card (SOTW / BOTW) -----------------------------------------------------

	/** The weekly's own summary: what it tracks, how long is left, and where you stand in it. */
	private JPanel buildWeeklyCard(ConnectionView.WeeklyView w)
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		panel.setAlignmentX(LEFT_ALIGNMENT);

		panel.add(leftLabel(w.title, FontManager.getRunescapeFont(), ColorScheme.TEXT_COLOR));
		panel.add(leftLabel(w.kindLabel() + " · " + w.metricLabel(),
			FontManager.getRunescapeSmallFont(), ColorScheme.LIGHT_GRAY_COLOR));

		String timing = timingLabel(w.upcoming, w.startDate, w.endDate);
		String headcount = w.participants > 0
			? w.participants + (w.participants == 1 ? " player" : " players") : null;
		String line = timing == null ? headcount : (headcount == null ? timing : timing + " · " + headcount);
		if (line != null)
		{
			panel.add(leftLabel(line, FontManager.getRunescapeSmallFont(), VALUE_COLOR));
		}

		panel.add(gap(4));
		// Nothing has been gained yet in a comp that hasn't started — say what it'll be instead of "#0".
		panel.add(w.upcoming
			? leftLabel("No standings until it starts.",
				FontManager.getRunescapeSmallFont(), ColorScheme.LIGHT_GRAY_COLOR)
			: leftLabel(yourStandingLine(w), FontManager.getRunescapeSmallFont(),
				w.yourRank > 0 ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR));

		if (w.url != null && !w.url.isEmpty())
		{
			// Nothing to stand in yet on an upcoming comp — link to the comp itself instead.
			panel.add(w.upcoming ? eventLink(w.url) : boardLink(w.url));
		}

		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
		return panel;
	}

	/**
	 * A bingo on the schedule that isn't yours: what it is, when it runs, how big, and a link to the
	 * site to sign up. No progress section — there's no board of yours to track yet.
	 */
	private JPanel buildScheduledCard(ConnectionView.ScheduledView s)
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		panel.setAlignmentX(LEFT_ALIGNMENT);

		panel.add(leftLabel(s.title, FontManager.getRunescapeFont(), ColorScheme.TEXT_COLOR));
		panel.add(leftLabel(s.kindLabel(), FontManager.getRunescapeSmallFont(), ColorScheme.LIGHT_GRAY_COLOR));

		String timing = timingLabel(!s.live, s.startDate, s.endDate);
		if (timing != null)
		{
			panel.add(leftLabel(timing, FontManager.getRunescapeSmallFont(),
				s.live ? ColorScheme.PROGRESS_COMPLETE_COLOR : VALUE_COLOR));
		}
		if (!s.sizeLabel().isEmpty())
		{
			panel.add(leftLabel(s.sizeLabel(), FontManager.getRunescapeSmallFont(), VALUE_COLOR));
		}

		panel.add(gap(4));
		panel.add(leftLabel(s.live ? "You're not enrolled in this one." : "Sign up on the site to take part.",
			FontManager.getRunescapeSmallFont(), ColorScheme.LIGHT_GRAY_COLOR));

		if (s.url != null && !s.url.isEmpty())
		{
			panel.add(eventLink(s.url));
		}

		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
		return panel;
	}

	/** "You: #3 · +1.2M xp", or an honest line when the caller isn't on the board (or it wouldn't load). */
	private static String yourStandingLine(ConnectionView.WeeklyView w)
	{
		if (w.yourRank > 0)
		{
			return "You: #" + w.yourRank + " · +" + w.formatGain(w.yourGained);
		}
		return w.top.isEmpty() ? "Standings unavailable" : "You're not on the board yet";
	}

	/** The head of the weekly's leaderboard, with the caller's row spliced in when it's further down. */
	private JPanel buildWeeklyStandings(ConnectionView.WeeklyView w)
	{
		JPanel list = new JPanel();
		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(ColorScheme.DARK_GRAY_COLOR);
		list.setAlignmentX(LEFT_ALIGNMENT);

		list.add(sectionHeader("Standings"));
		list.add(gap(6));

		if (w.top.isEmpty())
		{
			list.add(leftLabel("No one has scored yet.", FontManager.getRunescapeSmallFont(), VALUE_COLOR));
			list.setMaximumSize(new Dimension(Integer.MAX_VALUE, list.getPreferredSize().height));
			return list;
		}

		int shown = 0;
		for (ConnectionView.Standing s : w.top)
		{
			if (shown >= WEEKLY_ROWS_SHOWN && !s.self)
			{
				continue;
			}
			// The caller's row is kept even when it ranks below the cut — mark the jump so #3 → #37 reads right.
			if (shown >= WEEKLY_ROWS_SHOWN)
			{
				list.add(gap(3));
				list.add(leftLabel("⋯", FontManager.getRunescapeSmallFont(), VALUE_COLOR));
			}
			list.add(gap(3));
			list.add(buildStandingRow(s, w));
			shown++;
		}

		list.setMaximumSize(new Dimension(Integer.MAX_VALUE, list.getPreferredSize().height));
		return list;
	}

	/** One standings row: rank, RSN, gain. The caller's row leads in gold like their own activity does. */
	private JPanel buildStandingRow(ConnectionView.Standing s, ConnectionView.WeeklyView w)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(LEFT_ALIGNMENT);

		JLabel rank = new JLabel(String.valueOf(s.rank));
		rank.setFont(FontManager.getRunescapeSmallFont());
		rank.setForeground(VALUE_COLOR);
		rank.setPreferredSize(new Dimension(22, rank.getPreferredSize().height));
		rank.setHorizontalAlignment(SwingConstants.RIGHT);

		JLabel name = new JLabel(plainText(ellipsize(s.rsn, 16)));
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(s.self ? ColorScheme.BRAND_ORANGE : ColorScheme.TEXT_COLOR);
		name.setToolTipText(plainText(s.rsn));

		JLabel gained = new JLabel("+" + w.formatGain(s.gained));
		gained.setFont(FontManager.getRunescapeSmallFont());
		gained.setForeground(s.self ? ColorScheme.BRAND_ORANGE : VALUE_COLOR);
		gained.setHorizontalAlignment(SwingConstants.RIGHT);

		row.add(rank, BorderLayout.WEST);
		row.add(name, BorderLayout.CENTER);
		row.add(gained, BorderLayout.EAST);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	/** " · ends in 2d 4h" / " · starts in 3d 1h" for the compact list row; "" when there's no usable date. */
	private static String timingSuffix(boolean upcoming, String startIso, String endIso)
	{
		String label = timingLabel(upcoming, startIso, endIso);
		return label == null ? "" : " · " + label.substring(0, 1).toLowerCase() + label.substring(1);
	}

	/** What matters about the clock right now: when it starts if it hasn't, else when it ends. */
	static String timingLabel(boolean upcoming, String startIso, String endIso)
	{
		return upcoming ? gapLabel("Starts in ", startIso, "Starting…") : endsInLabel(endIso);
	}

	/** "Ends in 2d 4h" / "Ends in 42m" / "Ended", or null when the date is missing or unparseable. */
	static String endsInLabel(String endIso)
	{
		return gapLabel("Ends in ", endIso, "Ended");
	}

	/** "{prefix}2d 4h" until {@code iso}; {@code passed} once it's behind us; null when unparseable. */
	private static String gapLabel(String prefix, String iso, String passed)
	{
		long at = epochMillis(iso);
		if (at < 0)
		{
			return null;
		}
		long left = at - System.currentTimeMillis();
		if (left <= 0)
		{
			return passed;
		}
		long mins = left / 60_000;
		if (mins < 60)
		{
			return prefix + Math.max(1, mins) + "m";
		}
		long hours = mins / 60;
		if (hours < 24)
		{
			return prefix + hours + "h " + (mins % 60) + "m";
		}
		return prefix + (hours / 24) + "d " + (hours % 24) + "h";
	}

	/** ISO date ("2026-06-21") or UTC datetime → epoch millis, or -1 when unparseable. */
	private static long epochMillis(String iso)
	{
		if (iso == null || iso.length() < 10)
		{
			return -1;
		}
		try
		{
			if (iso.length() == 10)
			{
				return java.time.LocalDate.parse(iso)
					.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
			}
			String s = iso.trim().replace(' ', 'T');
			return java.time.Instant.parse(s.endsWith("Z") ? s : s + "Z").toEpochMilli();
		}
		catch (RuntimeException e)
		{
			return -1;
		}
	}

	// ---- Component builders -----------------------------------------------------------------------

	/** A small gold section label, matching the panel's header style. */
	private static JLabel sectionHeader(String text)
	{
		JLabel header = new JLabel(text);
		header.setFont(FontManager.getRunescapeSmallFont());
		header.setForeground(ColorScheme.BRAND_ORANGE);
		header.setAlignmentX(LEFT_ALIGNMENT);
		return header;
	}

	/**
	 * One "Active now" row: tile name + a "who's on it" byline + a thin progress bar. Your own tasks lead in
	 * gold, teammate-only stay neutral. Value/label are JLabels (never painted inside the bar) for crisp text.
	 */
	/**
	 * The starting-shot prompt: where to stand, this account's keyword, and the one button that
	 * captures the frame, burns the proof banner onto it and files it. Rendered only while a shot is
	 * actually owed — {@link SidebarDataSource#startProof()} returns null the moment one is filed.
	 */
	private JPanel buildStartProofCard(PluginConfigResponse.StartProof proof)
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
			JLabel where = new JLabel(plainText("Go to " + proof.location));
			where.setFont(FontManager.getRunescapeSmallFont());
			where.setForeground(Color.WHITE);
			where.setAlignmentX(LEFT_ALIGNMENT);
			where.setToolTipText(plainText(proof.location));
			card.add(gap(4));
			card.add(where);
		}

		if (proof.keyword != null && !proof.keyword.isEmpty())
		{
			JLabel word = new JLabel(plainText("Keyword: " + proof.keyword));
			word.setFont(FontManager.getRunescapeSmallFont());
			word.setForeground(VALUE_COLOR);
			word.setAlignmentX(LEFT_ALIGNMENT);
			card.add(gap(2));
			card.add(word);
		}

		if (proof.maxSessionMinutes > 0)
		{
			// Said here as well as in chat, because it's the one requirement you can't fix after the
			// fact: the logout is what flushes the hiscores the event's baseline is read from.
			JLabel relog = new JLabel(plainText("Log out and back in first (within " + proof.maxSessionMinutes + " min)"));
			relog.setFont(FontManager.getRunescapeSmallFont());
			relog.setForeground(Color.WHITE);
			relog.setAlignmentX(LEFT_ALIGNMENT);
			relog.setToolTipText("Hiscores only save when you log out, so this is what sets your starting totals.");
			card.add(gap(2));
			card.add(relog);
		}

		if ("rejected".equals(proof.status))
		{
			card.add(gap(2));
			card.add(warningLabel("Your last shot was rejected — take another."));
		}

		// WHY it matters, which the card never said. A player who reads "starting shot needed" and
		// carries on has no way to know their drops are landing in a review queue meanwhile.
		card.add(gap(4));
		card.add(warningLabel("Drops you send now are held for review until this is filed."));

		String left = StartProofRules.describeWindow(proof, System.currentTimeMillis());
		if (left != null)
		{
			JLabel expires = new JLabel(plainText("Asked for another " + left + ", then it lapses"));
			expires.setFont(FontManager.getRunescapeSmallFont());
			expires.setForeground(Color.WHITE);
			expires.setAlignmentX(LEFT_ALIGNMENT);
			expires.setToolTipText("Six hours in, the game has logged everyone out anyway, so the shot stops being asked for.");
			card.add(gap(2));
			card.add(expires);
		}

		JButton take = new JButton("Take starting shot");
		styleFlatButton(take, ColorScheme.BRAND_ORANGE);
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
			javax.swing.Timer restore = new javax.swing.Timer(4000, ev ->
			{
				take.setText("Take starting shot");
				take.setEnabled(true);
			});
			restore.setRepeats(false);
			restore.start();
		});
		card.add(gap(6));
		card.add(take);

		return card;
	}

	private JPanel buildActiveRow(ConnectionView.ActiveTask task)
	{
		ClogTaskModel.TaskRow tile = task.tile;
		Color accent = task.includesSelf ? ColorScheme.BRAND_ORANGE : ColorScheme.TEXT_COLOR;

		JPanel row = new JPanel(new GridBagLayout());
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(LEFT_ALIGNMENT);

		GridBagConstraints gbc = new GridBagConstraints();
		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.weightx = 1;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.anchor = GridBagConstraints.WEST;

		JLabel name = new JLabel(plainText(ellipsize(tile.label, 24)));
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(accent);
		name.setToolTipText(plainText(tile.label));
		row.add(name, gbc);

		JLabel value = new JLabel(progressValue(tile));
		value.setFont(FontManager.getRunescapeSmallFont());
		value.setForeground(VALUE_COLOR);
		gbc.gridx = 1;
		gbc.weightx = 0;
		gbc.fill = GridBagConstraints.NONE;
		gbc.anchor = GridBagConstraints.EAST;
		row.add(value, gbc);

		JLabel who = new JLabel(plainText(task.workersLabel()));
		who.setFont(FontManager.getRunescapeSmallFont());
		who.setForeground(task.includesSelf ? ColorScheme.BRAND_ORANGE : VALUE_COLOR);
		gbc.gridx = 0;
		gbc.gridy = 1;
		gbc.gridwidth = 2;
		gbc.weightx = 1;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.anchor = GridBagConstraints.WEST;
		gbc.insets = new java.awt.Insets(1, 0, 0, 0);
		row.add(who, gbc);

		JProgressBar bar = new JProgressBar(0, 100);
		bar.setValue(tile.goal > 0 ? Math.min(100, (int) Math.round(tile.current * 100.0 / tile.goal)) : 0);
		bar.setStringPainted(false);
		bar.setBorderPainted(false);
		bar.setForeground(task.includesSelf ? ColorScheme.BRAND_ORANGE : ColorScheme.PROGRESS_INPROGRESS_COLOR);
		bar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		bar.setPreferredSize(new Dimension(0, PROGRESS_BAR_HEIGHT));
		gbc.gridy = 2;
		gbc.insets = new java.awt.Insets(3, 0, 0, 0);
		row.add(bar, gbc);

		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	/** "1.5M / 2M", "248 / 500", or "" for an untargeted tile — big numbers abbreviated for the narrow panel. */
	private static String progressValue(ClogTaskModel.TaskRow tile)
	{
		return tile.goal > 0 ? formatCount(tile.current) + " / " + formatCount(tile.goal) : "";
	}

	/** Compact count: 1_507_300 → "1.5M", 2_000_000 → "2M", 15_000 → "15K", 500 → "500". */
	// One definition, shared with ConnectionView.WeeklyView's gain formatting (which also has to know
	// about EHP/EHB milli-hours) so the card and the standings rows can't drift apart.
	private static String formatCount(long n)
	{
		return ConnectionView.formatCount(n);
	}

	/** The team activity feed — one colored line per event (newest first), capped so the panel stays glanceable. */
	private JPanel buildActivityFeed(List<ActivityEntry> entries)
	{
		JPanel list = new JPanel();
		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(ColorScheme.DARK_GRAY_COLOR);
		list.setAlignmentX(LEFT_ALIGNMENT);

		int shown = 0;
		for (ActivityEntry e : entries)
		{
			if (shown >= ACTIVITY_ROWS_SHOWN)
			{
				break;
			}
			if (shown > 0)
			{
				list.add(gap(3));
			}
			list.add(buildActivityRow(e));
			shown++;
		}
		if (entries.size() > ACTIVITY_ROWS_SHOWN)
		{
			list.add(gap(3));
			JLabel more = new JLabel("+" + (entries.size() - ACTIVITY_ROWS_SHOWN) + " more");
			more.setFont(FontManager.getRunescapeSmallFont());
			more.setForeground(VALUE_COLOR);
			more.setAlignmentX(LEFT_ALIGNMENT);
			list.add(more);
		}
		list.setMaximumSize(new Dimension(Integer.MAX_VALUE, list.getPreferredSize().height));
		return list;
	}

	private JLabel buildActivityRow(ActivityEntry e)
	{
		JLabel row = new JLabel(plainText(ellipsize(e.summary(), 36)));
		row.setFont(FontManager.getRunescapeSmallFont());
		row.setToolTipText(plainText(e.summary()));
		row.setAlignmentX(LEFT_ALIGNMENT);
		// Completions read as wins (green); reveals are announcements (gold, like your own actions);
		// your own actions stand out (gold); teammates stay neutral.
		if (e.isCompletion())
		{
			row.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
		}
		else if (e.kind == ActivityEntry.Kind.REVEAL || e.self)
		{
			row.setForeground(ColorScheme.BRAND_ORANGE);
		}
		else
		{
			row.setForeground(ColorScheme.TEXT_COLOR);
		}
		return row;
	}

	/**
	 * §2/§6 — neutralize a federated string for Swing. A {@link JLabel}/{@link javax.swing.JToolTip} renders as
	 * HTML when its text begins (ignoring leading whitespace, case-insensitive) with {@code <html}, so an
	 * untrusted clan/tile/activity name could inject markup. Such strings get their markup chars escaped so they
	 * render only as literal text; ordinary strings pass through. The explicit sanitize the security model
	 * requires (no reliance on {@code html.disable}); every federated field the panel renders routes through here.
	 */
	static String plainText(String s)
	{
		if (s == null)
		{
			return "";
		}
		int i = 0;
		while (i < s.length() && Character.isWhitespace(s.charAt(i)))
		{
			i++;
		}
		if (s.regionMatches(true, i, "<html", 0, 5))
		{
			return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		}
		return s;
	}

	/** Clip an over-long label to width with an ellipsis so a feed/spotlight row never overflows the panel. */
	private static String ellipsize(String s, int max)
	{
		if (s == null)
		{
			return "";
		}
		return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)).trim() + "…";
	}

	/**
	 * Drop the held ladder-card refs. Only one card renders at a time, so every render path that isn't
	 * building a ladder card must clear them — otherwise the 1 s tick keeps updating labels that are no
	 * longer on screen.
	 */
	private void clearLadderRefs()
	{
		ladderState = null;
		ladderCountdownLabel = null;
		ladderCardPanel = null;
		ladderValueLabels.clear();
	}

	private JPanel buildSummary(ConnectionView c)
	{
		// Only one card renders at a time (renderSelected), so the ladder tick binds to a single set of
		// held refs. Reset them each render; a non-ladder card leaves the tick idle.
		clearLadderRefs();
		// A ladder IS its missions board, so the card replaces the summary. A bingo that merely carries
		// missions keeps its tile-count summary and gets the mission strip as its own section below.
		if (c.ladder != null && c.ladder.ladderFormat)
		{
			return buildLadderCard(c);
		}

		JPanel panel = new JPanel(new BorderLayout(0, 4));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		panel.setAlignmentX(LEFT_ALIGNMENT);

		String eventLine = c.eventName == null || c.eventName.isEmpty() ? c.clanName : c.eventName;
		JLabel event = new JLabel(plainText(eventLine));
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
			count.setForeground(VALUE_COLOR);
			bottom.add(count, BorderLayout.NORTH);

			JProgressBar bar = new JProgressBar(0, 100);
			bar.setValue(c.completionPercent());
			bar.setStringPainted(false);
			bar.setForeground(c.completionPercent() >= 100
				? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.BRAND_ORANGE);
			bar.setBackground(ColorScheme.DARK_GRAY_COLOR);
			bar.setBorderPainted(false);
			bar.setPreferredSize(new Dimension(0, PROGRESS_BAR_HEIGHT + 1));
			bottom.add(bar, BorderLayout.CENTER);

			// Reveal-policy boards: "4 tiles hidden · next in 42m" under the bar, so members know
			// more is coming (and when) without opening the site. Null on classic boards.
			if (c.revealNote != null && !c.revealNote.isEmpty())
			{
				JLabel reveal = new JLabel(plainText(c.revealNote));
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
			south.add(boardLink(c.boardUrl));
		}
		if (south.getComponentCount() > 0)
		{
			panel.add(south, BorderLayout.SOUTH);
		}

		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
		return panel;
	}

	/**
	 * One open mission: label on the left, live grow/decay value on the right. Shared by the ladder
	 * card and the bingo mission strip so both age their values on the same per-second tick — the
	 * label is registered with {@link #ladderValueLabels} either way.
	 */
	private JPanel buildMissionRow(ConnectionView.Ladder.Mission m, ConnectionView.Ladder l, long now)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setAlignmentX(LEFT_ALIGNMENT);

		JLabel name = new JLabel(plainText(ellipsize(m.label, 22)));
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(ColorScheme.TEXT_COLOR);

		long val = LadderMissions.liveValue(m.face, m.revealedAtIso, l.decay, now);
		JLabel value = new JLabel(LadderMissions.valueLabel(m.face, val));
		value.setFont(FontManager.getRunescapeSmallFont());
		value.setForeground(valueColor(m.face, val));
		value.setHorizontalAlignment(SwingConstants.RIGHT);

		row.add(name, BorderLayout.CENTER);
		row.add(value, BorderLayout.EAST);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		ladderValueLabels.add(new LadderValueLabel(value, m.face, m.revealedAtIso));
		return row;
	}

	/**
	 * Missions on an ordinary bingo — the countdown to the next drop plus what's open right now, with
	 * the same live values a ladder shows. No rank line: a bingo scores by team, not by a personal
	 * ladder position.
	 */
	private JPanel buildMissionStrip(ConnectionView.Ladder l)
	{
		final long now = System.currentTimeMillis();
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		panel.setAlignmentX(LEFT_ALIGNMENT);

		JLabel countdown = leftLabel(countdownText(l, now), FontManager.getRunescapeBoldFont(), ColorScheme.BRAND_ORANGE);
		panel.add(countdown);
		panel.add(gap(4));
		for (ConnectionView.Ladder.Mission m : l.missions)
		{
			panel.add(buildMissionRow(m, l, now));
		}
		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));

		// Bind the per-second tick to this strip, exactly as the ladder card does for itself — without
		// it the countdown would sit frozen until the next config poll.
		ladderState = l;
		ladderCountdownLabel = countdown;
		ladderCardPanel = panel;
		return panel;
	}

	/**
	 * The DMM-All-Stars-style missions board for a ladder event: your rank, a live per-second countdown
	 * to the next drop, and the currently-open missions with a live grow/decay value each. Replaces the
	 * tile-count summary + reveal note (a rotating daily ladder has no fixed board to count). The held
	 * label refs let {@link #tickLadder()} update the countdown + values once a second without a refetch.
	 */
	private JPanel buildLadderCard(ConnectionView c)
	{
		ConnectionView.Ladder l = c.ladder;
		final long now = System.currentTimeMillis();

		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		panel.setAlignmentX(LEFT_ALIGNMENT);

		String eventLine = c.eventName == null || c.eventName.isEmpty() ? c.clanName : c.eventName;
		panel.add(leftLabel(eventLine, FontManager.getRunescapeFont(), ColorScheme.TEXT_COLOR));
		panel.add(leftLabel(rankLine(l), FontManager.getRunescapeSmallFont(), VALUE_COLOR));
		panel.add(gap(6));

		JLabel countdown = leftLabel(countdownText(l, now), FontManager.getRunescapeBoldFont(), ColorScheme.BRAND_ORANGE);
		panel.add(countdown);
		panel.add(gap(6));

		if (l.missions.isEmpty())
		{
			panel.add(leftLabel("Waiting for the next mission…", FontManager.getRunescapeSmallFont(), ColorScheme.LIGHT_GRAY_COLOR));
		}
		else
		{
			panel.add(leftLabel("Active missions", FontManager.getRunescapeSmallFont(), ColorScheme.LIGHT_GRAY_COLOR));
			panel.add(gap(2));
			for (ConnectionView.Ladder.Mission m : l.missions)
			{
				panel.add(buildMissionRow(m, l, now));
			}
		}

		if (c.boardUrl != null && !c.boardUrl.isEmpty())
		{
			panel.add(gap(6));
			panel.add(boardLink(c.boardUrl));
		}

		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));

		// Bind the tick to this card.
		ladderState = l;
		ladderCountdownLabel = countdown;
		ladderCardPanel = panel;
		return panel;
	}

	/** A left-aligned JLabel (BoxLayout children default to centre), sanitized for federated text. */
	private JLabel leftLabel(String text, Font font, Color color)
	{
		JLabel label = new JLabel(plainText(text));
		label.setFont(font);
		label.setForeground(color);
		label.setAlignmentX(LEFT_ALIGNMENT);
		return label;
	}

	/** "You: #4 this month · #12 all-time", or an encouraging line when the caller hasn't scored yet. */
	private static String rankLine(ConnectionView.Ladder l)
	{
		if (l.monthRank <= 0)
		{
			return "You: unranked — finish a mission to get on the board";
		}
		String line = "You: #" + l.monthRank + " this month · " + l.monthPoints + " pts";
		if (l.allTimeRank > 0)
		{
			line += " · #" + l.allTimeRank + " all-time";
		}
		return line;
	}

	/** "Next mission in 12:34" / "New mission dropping…" / "Next mission: on a claim" (bounty, no clock). */
	private static String countdownText(ConnectionView.Ladder l, long now)
	{
		String cd = LadderMissions.countdown(l.nextRevealAtIso, now);
		if (cd == null)
		{
			return "Next mission drops on a claim";
		}
		return "now".equals(cd) ? "New mission dropping…" : "Next mission in " + cd;
	}

	/** Grey when unchanged, green when it grew, orange when it's decaying. */
	private static Color valueColor(long face, long current)
	{
		if (current == face)
		{
			return VALUE_COLOR;
		}
		return current > face ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.BRAND_ORANGE;
	}

	/** Live per-second refresh of the shown ladder card: the countdown, each mission's value, the flash. */
	private void tickLadder()
	{
		final long now = System.currentTimeMillis();
		ConnectionView.Ladder l = ladderState;
		if (l != null)
		{
			if (ladderCountdownLabel != null)
			{
				ladderCountdownLabel.setText(plainText(countdownText(l, now)));
			}
			for (LadderValueLabel v : ladderValueLabels)
			{
				long val = LadderMissions.liveValue(v.face, v.revealedAtIso, l.decay, now);
				v.label.setText(LadderMissions.valueLabel(v.face, val));
				v.label.setForeground(valueColor(v.face, val));
			}
		}
		applyFlash(now);
	}

	/** Pulse the card border gold while a new-mission / claim signal is fresh (see {@link #flashLadder()}). */
	private void applyFlash(long now)
	{
		if (ladderCardPanel == null)
		{
			return;
		}
		if (now < ladderFlashUntil)
		{
			boolean on = ((ladderFlashUntil - now) / 350) % 2 == 0;
			ladderCardPanel.setBorder(BorderFactory.createMatteBorder(8, 8, 8, 8,
				on ? LADDER_FLASH_COLOR : ColorScheme.DARKER_GRAY_COLOR));
			ladderFlashPainted = true;
		}
		else if (ladderFlashPainted)
		{
			ladderCardPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
			ladderFlashPainted = false;
		}
	}

	/**
	 * Signal a fresh new-mission / claim so the ladder card pulses gold for a few seconds. Called from the
	 * plugin's config-refresh diff OFF the EDT — a plain volatile write the 1s tick picks up on the EDT.
	 */
	public void flashLadder()
	{
		ladderFlashUntil = System.currentTimeMillis() + LADDER_FLASH_MS;
	}

	/** Held ref for one mission's value label so the tick recomputes its grow/decay value in place. */
	private static final class LadderValueLabel
	{
		final JLabel label;
		final int face;
		final String revealedAtIso;

		LadderValueLabel(JLabel label, int face, String revealedAtIso)
		{
			this.label = label;
			this.face = face;
			this.revealedAtIso = revealedAtIso;
		}
	}

	/** A clickable "View standings" link opening the board's site page in the system browser. */
	private JLabel boardLink(String url)
	{
		return siteLink("View standings ↗", "Open the full standings on the Anvil site", url);
	}

	/** As {@link #boardLink}, for an event you're not in yet — the site page is where you sign up. */
	private JLabel eventLink(String url)
	{
		return siteLink("View event ↗", "Open this event on the Anvil site", url);
	}

	private JLabel siteLink(String text, String tooltip, String url)
	{
		JLabel link = new JLabel(text);
		link.setFont(FontManager.getRunescapeSmallFont());
		link.setForeground(ColorScheme.BRAND_ORANGE);
		link.setToolTipText(tooltip);
		link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		link.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
		link.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				if (isSafeHttpUrl(url)) // defense-in-depth: never a javascript:/data:/file: or creds@host URL
				{
					LinkBrowser.browse(url);
				}
			}
		});
		return link;
	}

	/** True only for a well-formed absolute {@code http}/{@code https} URL with a host and no embedded
	 *  credentials — refuses {@code javascript:} / {@code data:} / {@code file:} and {@code user@host} tricks. */
	static boolean isSafeHttpUrl(String url)
	{
		if (url == null || url.isEmpty())
		{
			return false;
		}
		try
		{
			java.net.URI u = new java.net.URI(url);
			String scheme = u.getScheme();
			return u.getHost() != null && u.getUserInfo() == null
				&& ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme));
		}
		catch (java.net.URISyntaxException ex)
		{
			return false;
		}
	}

	private JPanel buildTileRow(ConnectionView.TileProgressView tile)
	{
		JPanel row = new JPanel(new GridBagLayout());
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(LEFT_ALIGNMENT);

		GridBagConstraints gbc = new GridBagConstraints();
		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.weightx = 1;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.anchor = GridBagConstraints.WEST;

		JLabel name = new JLabel(plainText(tile.name));
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(tile.complete ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.TEXT_COLOR);
		row.add(name, gbc);

		JLabel value = new JLabel(progressText(tile));
		value.setFont(FontManager.getRunescapeSmallFont());
		value.setForeground(VALUE_COLOR);
		gbc.gridx = 1;
		gbc.weightx = 0;
		gbc.fill = GridBagConstraints.NONE;
		gbc.anchor = GridBagConstraints.EAST;
		row.add(value, gbc);

		JProgressBar bar = new JProgressBar(0, 100);
		bar.setValue(tile.percent());
		bar.setStringPainted(false);
		bar.setBorderPainted(false);
		bar.setForeground(tile.complete
			? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_INPROGRESS_COLOR);
		bar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		bar.setPreferredSize(new Dimension(0, PROGRESS_BAR_HEIGHT));
		gbc.gridx = 0;
		gbc.gridy = 1;
		gbc.gridwidth = 2;
		gbc.weightx = 1;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.insets = new java.awt.Insets(3, 0, 0, 0);
		row.add(bar, gbc);

		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	/** "c / t" for counted tiles, "42%" for very large targets (XP), or "Done"/"—". */
	private static String progressText(ConnectionView.TileProgressView tile)
	{
		if (tile.complete)
		{
			return "Done";
		}
		if (tile.target <= 0)
		{
			return "—";
		}
		if (tile.target >= 100_000)
		{
			// Big numeric goals (XP, gp) read better as a percentage than as raw counts.
			return tile.percent() + "%";
		}
		return tile.current + " / " + tile.target;
	}

	private JLabel warningLabel(String message)
	{
		JLabel warn = new JLabel(plainText(message));
		warn.setFont(FontManager.getRunescapeSmallFont());
		warn.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		warn.setAlignmentX(LEFT_ALIGNMENT);
		return warn;
	}

	/** Swap the single content component and repaint. */
	private void setContent(Component component)
	{
		SwingUtilities.invokeLater(() ->
		{
			content.removeAll();
			if (component instanceof javax.swing.JComponent)
			{
				((javax.swing.JComponent) component).setAlignmentX(LEFT_ALIGNMENT);
			}
			content.add(component);
			content.revalidate();
			content.repaint();
		});
	}

	private static Component gap(int height)
	{
		return javax.swing.Box.createVerticalStrut(height);
	}

	/** Renders a {@link ConnectionView} in the clan dropdown by name, flagging an unreachable home. */
	// ---- Anvil-themed widget chrome ---------------------------------------------------------------

	/** Flat dark button matching the sidebar theme: dark surface, thin border, hover lift, no L&F chrome. */
	/** A themed action button in the panel's orange, wired to one thing it does. */
	private static JButton actionButton(String label, String tooltip, Runnable onClick)
	{
		JButton b = new JButton(label);
		styleFlatButton(b, ColorScheme.BRAND_ORANGE);
		b.setAlignmentX(LEFT_ALIGNMENT);
		b.setToolTipText(tooltip);
		b.addActionListener(e -> onClick.run());
		return b;
	}

	/**
	 * Two buttons on one line, equal halves.
	 *
	 * <p>The height cap matters: a grid inside a vertical BoxLayout will happily stretch to whatever
	 * space is left, which turns a pair of buttons into a pair of slabs.
	 */
	private static JPanel buttonRow(JButton left, JButton right)
	{
		JPanel row = new JPanel(new GridLayout(1, 2, 4, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.add(left);
		row.add(right);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	/** One button holding a whole row, so a lone action doesn't sit in half a line. */
	private static JPanel fullWidth(JButton button)
	{
		JPanel row = new JPanel(new GridLayout(1, 1));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.add(button);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	private static void styleFlatButton(JButton b, Color foreground)
	{
		b.setFocusPainted(false);
		b.setForeground(foreground);
		b.setBackground(WIDGET_BG);
		b.setFont(FontManager.getRunescapeSmallFont());
		b.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(WIDGET_BORDER),
			BorderFactory.createEmptyBorder(4, 10, 4, 10)));
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		b.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				if (b.isEnabled())
				{
					b.setBackground(WIDGET_BG_HOVER);
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				b.setBackground(WIDGET_BG);
			}
		});
	}

	/** Theme the clan-filter combo: dark flat field, gold arrow, dark popup — default Swing sticks out. */
	private static void styleClanFilter(JComboBox<ConnectionView> combo)
	{
		combo.setBackground(WIDGET_BG);
		combo.setForeground(Color.WHITE);
		combo.setFont(FontManager.getRunescapeSmallFont());
		combo.setBorder(BorderFactory.createLineBorder(WIDGET_BORDER));
		combo.setUI(new BasicComboBoxUI()
		{
			@Override
			protected JButton createArrowButton()
			{
				BasicArrowButton arrow = new BasicArrowButton(
					SwingConstants.SOUTH, WIDGET_BG, WIDGET_BG, ColorScheme.BRAND_ORANGE, WIDGET_BG);
				arrow.setBorder(BorderFactory.createEmptyBorder());
				return arrow;
			}
		});
	}

	private static final class ClanFilterRenderer extends BasicComboBoxRenderer
	{
		@Override
		public Component getListCellRendererComponent(JList<?> list, Object value, int index,
			boolean isSelected, boolean cellHasFocus)
		{
			super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
			// index >= 0 → popup row; -1 → the closed field. Dark rows, gold-tinted hover selection.
			setOpaque(true);
			setBackground(isSelected && index >= 0 ? WIDGET_BG_HOVER : WIDGET_BG);
			setForeground(isSelected && index >= 0 ? ColorScheme.BRAND_ORANGE : Color.WHITE);
			setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
			if (value instanceof ConnectionView)
			{
				ConnectionView c = (ConnectionView) value;
				setText(plainText(c.hasError() ? c.clanName + "  (!)" : c.clanName));
				setFont(FontManager.getRunescapeSmallFont());
			}
			return this;
		}
	}
}
