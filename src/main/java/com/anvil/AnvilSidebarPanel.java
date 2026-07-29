package com.anvil;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
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

	private final SidebarDataSource dataSource;

	// Site-relay federation (FEDERATION_WIRE.md §10, the plugin's ONLY federation path): the data source polls
	// the home site's /federation/state; the plugin makes NO broker/clan connections. Non-null iff the source exposes it.
	private final FederationStatusSource federationStatus;

	// Header controls (persistent across state changes).
	private final JComboBox<ConnectionView> clanFilter = new JComboBox<>();
	private final JButton refreshButton = new JButton("Refresh");

	// Site-relay "Connect clans" affordance (auto path). Shown only when the site reports federation enabled but
	// not connected; a click POSTs /federation/connect (hosted = zero-click, self-host = broker login + poll /state).
	private final JButton siteConnectButton = new JButton("Connect clans");
	private final JLabel siteConnectStatus = new JLabel();
	private final JPanel siteConnectRow = new JPanel(new BorderLayout(0, 2));

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
	private boolean siteConnectInFlight;

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
		this.federationStatus = dataSource instanceof FederationStatusSource ? (FederationStatusSource) dataSource : null;

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

		// "Connect clans" visibility is driven live from /federation/state (see updateSiteConnectAffordance).
		styleFlatButton(siteConnectButton, ColorScheme.BRAND_ORANGE);
		// One button, two modes: "Connect clans" when signed out, "Disconnect" when signed in — route by current state.
		siteConnectButton.addActionListener(e ->
		{
			if (federationStatus != null && federationStatus.federationStatus().signedIn)
			{
				onSiteDisconnect();
			}
			else
			{
				onSiteConnect();
			}
		});
		siteConnectStatus.setFont(FontManager.getRunescapeSmallFont());
		siteConnectStatus.setForeground(VALUE_COLOR);
		siteConnectStatus.setVisible(false);
		siteConnectRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		siteConnectRow.add(siteConnectButton, BorderLayout.NORTH);
		siteConnectRow.add(siteConnectStatus, BorderLayout.SOUTH);
		siteConnectRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, siteConnectRow.getPreferredSize().height));
		siteConnectRow.setAlignmentX(LEFT_ALIGNMENT);
		siteConnectRow.setVisible(false);

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
		top.add(siteConnectRow);
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

	// ---- Site-relay "Connect clans" flow (auto path, FEDERATION_WIRE.md §10.2) --------------------

	/**
	 * Show the connect button when the home reports federation enabled but not connected. Hosted homes connect
	 * zero-click (row never appears); self-host shows it until login. No-op off the auto path or mid-connect.
	 */
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
		signInStatus.setVisible(true);
		signInStatus.setText("Starting…");

		new DeviceSignIn(apiClient, executor).run(
			line -> SwingUtilities.invokeLater(() -> signInStatus.setText(line)),
			result -> SwingUtilities.invokeLater(() ->
			{
				signInInFlight = false;
				signInButton.setEnabled(true);
				if (result.outcome == DeviceSignIn.Outcome.SIGNED_IN)
				{
					// Storing the token fires the plugin's onConfigChanged → client reconfigure,
					// identity stamp + greet, and a sidebar refresh — the same path as a manual paste.
					configManager.setConfiguration("osrsbingo", "playerToken", result.token);
					signInStatus.setVisible(false);
				}
				refreshSignInRow();
				refresh();
			}));
	}

	private void updateSiteConnectAffordance()
	{
		if (federationStatus == null || siteConnectInFlight)
		{
			return;
		}
		FederationState st = federationStatus.federationStatus();
		// Offered whenever federation is on: "Connect clans" until signed in, then "Disconnect" (durable via
		// /state's signedIn) — even signed-in with zero clans, the case that used to wrongly re-offer Connect.
		boolean show = st.enabled;
		siteConnectButton.setText(st.signedIn ? "Disconnect" : "Connect clans");
		// A quiet standing note when signed in but nothing to render, so the row isn't just a lone button.
		if (st.signedIn && st.clans.isEmpty())
		{
			setSiteConnectStatus("Signed in — no other Anvil clans are linked to yours yet.");
		}
		else
		{
			setSiteConnectStatus("");
		}
		if (siteConnectRow.isVisible() != show)
		{
			siteConnectRow.setVisible(show);
			siteConnectRow.revalidate();
		}
	}

	/**
	 * §10.2 connect handshake: {@code POST /federation/connect}. Trusted home returns connected; self-host
	 * opens a browser login, then the source schedules {@code /state} polls to connected. Asynchronous — the
	 * source runs every step on the shared executor and calls back on that thread, so both callbacks marshal
	 * to the EDT here. No broker/clan connections from the plugin.
	 */
	private void onSiteConnect()
	{
		if (federationStatus == null || siteConnectInFlight)
		{
			return;
		}
		siteConnectInFlight = true;
		siteConnectButton.setEnabled(false);
		setSiteConnectStatus("Connecting…");

		federationStatus.connectFederation(
			line -> SwingUtilities.invokeLater(() -> setSiteConnectStatus(line)),
			outcome -> SwingUtilities.invokeLater(() ->
			{
				siteConnectInFlight = false;
				siteConnectButton.setEnabled(true);
				refresh(); // re-render + re-evaluate the affordance with the newest /state
			}));
	}

	/**
	 * Federation logout off the EDT: {@code POST /disconnect} clears the server-side signed-in marker; the
	 * follow-up refresh re-reads {@code /state} ({@code signedIn:false}) so the button flips to "Connect clans".
	 */
	private void onSiteDisconnect()
	{
		if (federationStatus == null || siteConnectInFlight)
		{
			return;
		}
		siteConnectInFlight = true;
		siteConnectButton.setEnabled(false);
		setSiteConnectStatus("Disconnecting…");

		new SwingWorker<Boolean, Void>()
		{
			@Override
			protected Boolean doInBackground()
			{
				return federationStatus.disconnectFederation();
			}

			@Override
			protected void done()
			{
				siteConnectInFlight = false;
				siteConnectButton.setEnabled(true);
				try
				{
					if (!get())
					{
						setSiteConnectStatus("Disconnect failed — try again.");
					}
				}
				catch (Exception ex)
				{
					Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
					log.debug("site-relay disconnect flow failed", cause);
					setSiteConnectStatus("Disconnect failed — try again.");
				}
				refresh(); // re-render + re-evaluate the affordance with the newest /state
			}
		}.execute();
	}

	private void setSiteConnectStatus(String text)
	{
		String plain = text == null ? "" : text;
		boolean show = !plain.isEmpty();
		// Narrow sidebar: render as width-constrained HTML so a long line WRAPS, mirror full text into the tooltip.
		// HTML-escape first — the userCode segment is broker-supplied.
		String escaped = plain.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		siteConnectStatus.setText(show ? "<html><body style='width:" + STATUS_WRAP_PX + "px'>" + escaped + "</body></html>" : "");
		siteConnectStatus.setToolTipText(show ? plain : null);
		siteConnectStatus.setVisible(show);
		// Re-cap to the current preferred height (construction-time cap was button-only) so the wrapped status can grow.
		siteConnectRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, siteConnectRow.getPreferredSize().height));
		siteConnectRow.revalidate();
		siteConnectRow.repaint();
	}

	// ---- Refresh flow -----------------------------------------------------------------------------

	/** Fetch off the EDT and re-render on return. Cheap to call repeatedly; overlapping calls coalesce via {@link #fetchInFlight}. */
	public void refresh()
	{
		refresh(false);
	}

	/** As {@link #refresh()}; {@code manual} = the member clicked Refresh — the home is asked to bypass
	 * its federation re-sync throttle so the button acts on a just-changed network immediately. */
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
				// The fetch just refreshed /federation/state — reflect it in the connect affordance,
				// and surface the Sign-in button whenever the token is missing/was just rejected.
				updateSiteConnectAffordance();
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
			rebuildFilter();
			renderEmpty();
			return;
		}

		// Keep the current selection if that clan is still connected; otherwise fall back to the first.
		if (findSelected() == null)
		{
			selectedInstanceId = connections.get(0).instanceId;
		}
		rebuildFilter();
		renderSelected();
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

	private void renderSelected()
	{
		ConnectionView selected = findSelected();
		if (selected == null)
		{
			renderEmpty();
			return;
		}

		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);

		if (selected.hasError())
		{
			body.add(warningLabel(selected.error));
			body.add(gap(8));
		}

		body.add(buildSummary(selected));
		body.add(gap(12));

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

		// Team activity — incoming credited events, newest first.
		if (!selected.recentActivity.isEmpty())
		{
			body.add(sectionHeader("Team activity"));
			body.add(gap(6));
			body.add(buildActivityFeed(selected.recentActivity));
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

		setContent(body);
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
	private static String formatCount(int n)
	{
		if (n >= 1_000_000)
		{
			double m = n / 1_000_000.0;
			return (m == Math.floor(m) ? String.valueOf((int) m) : String.format("%.1f", m)) + "M";
		}
		if (n >= 10_000)
		{
			return (n / 1000) + "K";
		}
		return String.valueOf(n);
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

	private JPanel buildSummary(ConnectionView c)
	{
		// Only one card renders at a time (renderSelected), so the ladder tick binds to a single set of
		// held refs. Reset them each render; a non-ladder card leaves the tick idle.
		ladderState = null;
		ladderCountdownLabel = null;
		ladderCardPanel = null;
		ladderValueLabels.clear();
		if (c.ladder != null)
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
		JPanel shareRow = buildShareRow(c);
		if (shareRow != null)
		{
			south.add(shareRow);
		}
		if (south.getComponentCount() > 0)
		{
			panel.add(south, BorderLayout.SOUTH);
		}

		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
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
				panel.add(row);
				ladderValueLabels.add(new LadderValueLabel(value, m.face, m.revealedAtIso));
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

	/**
	 * Per-clan "Share my RSN" toggle — per ACCOUNT by design: it acts on the account currently logged
	 * in (the server resolves it from the request), so each of a member's accounts is shared with each
	 * clan individually. Only rendered on FEDERATED clan cards while the playing account is resolvable
	 * ({@code shareEligible}); never on the home card (the home already knows its own member).
	 */
	private JPanel buildShareRow(ConnectionView c)
	{
		if (federationStatus == null || AnvilSidebarDataSource.LOCAL_INSTANCE_ID.equals(c.instanceId))
		{
			return null;
		}
		FederationState st = federationStatus.federationStatus();
		if (!st.enabled || !st.shareEligible)
		{
			return null;
		}
		boolean shared = st.sharedInstanceIds.contains(c.instanceId);
		JButton share = new JButton(shared ? "Stop sharing my RSN" : "Share my RSN with this clan");
		styleFlatButton(share, shared ? ColorScheme.LIGHT_GRAY_COLOR : ColorScheme.BRAND_ORANGE);
		share.setToolTipText(shared
			? "This clan currently knows this account's RSN. Click to retract it — the change reaches them within seconds."
			: "Let this clan see THIS account's RSN so it can track and draft you. Shares only the name — never boards or game data.");
		share.addActionListener(e ->
		{
			share.setEnabled(false);
			new SwingWorker<Boolean, Void>()
			{
				@Override
				protected Boolean doInBackground()
				{
					boolean ok = apiClient.federationShare(c.instanceId, !shared);
					return ok;
				}

				@Override
				protected void done()
				{
					// Forced refresh: the share rides the next exchange relay — force it now so the
					// remote learns (or forgets) the RSN within seconds, and the button re-labels.
					refresh(true);
				}
			}.execute();
		});

		JPanel row = new JPanel(new BorderLayout());
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
		row.add(share, BorderLayout.WEST);
		return row;
	}

	/** A clickable "View standings" link opening the board's site page in the system browser. */
	private JLabel boardLink(String url)
	{
		JLabel link = new JLabel("View standings ↗");
		link.setFont(FontManager.getRunescapeSmallFont());
		link.setForeground(ColorScheme.BRAND_ORANGE);
		link.setToolTipText("Open this board's standings on the Anvil site");
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
