package com.anvil;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.List;
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
import javax.swing.plaf.basic.BasicComboBoxRenderer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.ui.components.PluginErrorPanel;

/**
 * Always-on progress sidebar — a {@link PluginPanel} in the RuneLite toolbar that shows, per
 * connected clan, how many tiles are done and which tiles are nearest completion.
 *
 * <p>The panel is intentionally decoupled from the network: it reads everything through
 * {@link SidebarDataSource} (currently {@link MockSidebarDataSource}; the real multi-home layer
 * drops in later with no changes here — see {@code docs/FEDERATION.md} and {@code FEDERATION_WIRE.md}
 * §7). It owns four view states — <em>loading</em>, <em>error</em>, <em>empty</em>, <em>ready</em> —
 * a clan filter across {@code List<ConnectionView>}, a manual Refresh button, and an auto-refresh poll
 * that only runs while the panel is open.</p>
 *
 * <p><b>Threading:</b> all Swing mutation stays on the EDT. {@code onActivate}/{@code onDeactivate},
 * the refresh {@link Timer}, and {@link SwingWorker#done()} all run on the EDT; the only off-EDT work
 * is the blocking {@link SidebarDataSource#fetchConnections()} call inside the worker's background
 * method. Constructed once by Guice and reused (single toolbar panel), hence {@link Singleton}.</p>
 */
@Slf4j
@Singleton
public class AnvilSidebarPanel extends PluginPanel
{
	/** Auto-refresh cadence while the panel is open. Mirrors the plugin's other polls (config/board). */
	private static final int POLL_INTERVAL_MS = 15_000;

	private static final Color VALUE_COLOR = new Color(0x98_98_98);
	private static final int PROGRESS_BAR_HEIGHT = 6;

	/** Max activity rows rendered — keeps the sidebar glanceable; the rest collapse into a "+N more". */
	private static final int ACTIVITY_ROWS_SHOWN = 12;

	private final SidebarDataSource dataSource;

	// Site-relay federation (FEDERATION_WIRE.md §10, the plugin's ONLY federation path): the sidebar's data
	// source polls the home site's /federation/state and renders the clans the SITE fans out. The plugin
	// makes NO broker or clan connections. Non-null only when the bound data source exposes the capability.
	private final FederationStatusSource federationStatus;

	// Header controls (persistent across state changes).
	private final JComboBox<ConnectionView> clanFilter = new JComboBox<>();
	private final JButton refreshButton = new JButton("Refresh");

	// Site-relay "Connect clans" affordance (auto path). Shown ONLY when the site says federation is enabled
	// but this home isn't connected yet; a click POSTs /federation/connect (hosted = zero-click, self-host =
	// opens a broker login page + polls /state). Hidden entirely when connected (nothing to do) or off.
	private final JButton siteConnectButton = new JButton("Connect clans");
	private final JLabel siteConnectStatus = new JLabel();
	private final JPanel siteConnectRow = new JPanel(new BorderLayout(0, 2));
	private boolean siteConnectInFlight;

	private final JPanel content = new JPanel();

	// Only re-renders while the panel is visible; started on activate, stopped on deactivate.
	private final Timer autoRefresh;

	// Last successful snapshot, and which clan the user has selected — preserved across refreshes so an
	// auto-refresh doesn't reset the dropdown or flicker the list.
	private List<ConnectionView> connections = java.util.Collections.emptyList();
	private String selectedInstanceId;

	// Guards against ActionEvents fired while we rebuild the combo model, and against overlapping fetches.
	private boolean rebuildingFilter;
	private boolean fetchInFlight;

	@Inject
	public AnvilSidebarPanel(SidebarDataSource dataSource)
	{
		super(true); // wrap in RuneLite's scroll pane so a long nearest-tiles list scrolls
		this.dataSource = dataSource;
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

		refreshButton.setFocusPainted(false);
		refreshButton.addActionListener(e -> refresh());

		JPanel titleRow = new JPanel(new BorderLayout());
		titleRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		titleRow.add(title, BorderLayout.WEST);
		titleRow.add(refreshButton, BorderLayout.EAST);
		titleRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, titleRow.getPreferredSize().height));
		titleRow.setAlignmentX(LEFT_ALIGNMENT);

		// Site-relay "Connect clans" (auto path). Visibility is driven live from /federation/state
		// (see updateSiteConnectAffordance) — hidden until the site reports federation on & not connected.
		siteConnectButton.setFocusPainted(false);
		siteConnectButton.setForeground(ColorScheme.BRAND_ORANGE);
		siteConnectButton.addActionListener(e -> onSiteConnect());
		siteConnectStatus.setFont(FontManager.getRunescapeSmallFont());
		siteConnectStatus.setForeground(VALUE_COLOR);
		siteConnectStatus.setVisible(false);
		siteConnectRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		siteConnectRow.add(siteConnectButton, BorderLayout.NORTH);
		siteConnectRow.add(siteConnectStatus, BorderLayout.SOUTH);
		siteConnectRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, siteConnectRow.getPreferredSize().height));
		siteConnectRow.setAlignmentX(LEFT_ALIGNMENT);
		siteConnectRow.setVisible(false);

		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);
		top.add(titleRow);
		top.add(Box.createVerticalStrut(6));
		top.add(siteConnectRow);
		header.add(top, BorderLayout.NORTH);

		// Clan filter — a dropdown scales past the two-clan mock to the N connected homes multi-home
		// will bring. Selecting a clan re-renders from the held snapshot (no refetch).
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
	}

	@Override
	public void onDeactivate()
	{
		autoRefresh.stop();
	}

	// ---- Site-relay "Connect clans" flow (auto path, FEDERATION_WIRE.md §10.2) --------------------

	/**
	 * Show the site-relay connect button exactly when the home site reports federation enabled but not yet
	 * connected ({@link FederationState#needsConnect()}). Hosted homes are connected zero-click, so the row
	 * simply never appears; a self-host home shows it until the member finishes the one-time browser login.
	 * No-op when the bound data source isn't the auto-path source, or while a connect is in flight.
	 */
	private void updateSiteConnectAffordance()
	{
		if (federationStatus == null || siteConnectInFlight)
		{
			return;
		}
		boolean show = federationStatus.federationStatus().needsConnect();
		if (siteConnectRow.isVisible() != show)
		{
			siteConnectRow.setVisible(show);
			siteConnectRow.revalidate();
		}
	}

	/**
	 * Drive the §10.2 connect handshake off the EDT: {@code POST /federation/connect}. A trusted home
	 * returns connected immediately; a self-host opens a browser login and the data source polls
	 * {@code /state} back to connected. Status lines marshal back via {@code publish}; when it finishes we
	 * refresh so a now-connected home renders its clans and the button hides. The plugin makes no broker or
	 * clan connections — everything here is the home site + (for self-host) the broker's own browser page.
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

		new SwingWorker<FederationStatusSource.ConnectOutcome, String>()
		{
			@Override
			protected FederationStatusSource.ConnectOutcome doInBackground()
			{
				return federationStatus.connectFederation(this::publish);
			}

			@Override
			protected void process(List<String> chunks)
			{
				if (!chunks.isEmpty())
				{
					setSiteConnectStatus(chunks.get(chunks.size() - 1));
				}
			}

			@Override
			protected void done()
			{
				siteConnectInFlight = false;
				siteConnectButton.setEnabled(true);
				try
				{
					get(); // surface a background exception as the catch below
				}
				catch (Exception ex)
				{
					Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
					log.debug("site-relay connect flow failed", cause);
					setSiteConnectStatus("Connect failed — try again.");
				}
				refresh(); // re-render + re-evaluate the affordance with the newest /state
			}
		}.execute();
	}

	private void setSiteConnectStatus(String text)
	{
		siteConnectStatus.setText(text == null ? "" : text);
		siteConnectStatus.setVisible(text != null && !text.isEmpty());
		siteConnectRow.revalidate();
	}

	// ---- Refresh flow -----------------------------------------------------------------------------

	/**
	 * Kick a fetch off the EDT and re-render when it returns. Cheap to call repeatedly (manual button,
	 * auto-poll, on-activate) — overlapping calls are coalesced by {@link #fetchInFlight}.
	 */
	public void refresh()
	{
		if (fetchInFlight)
		{
			return;
		}
		fetchInFlight = true;
		refreshButton.setEnabled(false);
		if (connections.isEmpty())
		{
			// Nothing on screen yet — show the loading state. On a background poll we keep the current
			// list visible (no flicker) and just swap it in when the new data lands.
			renderLoading();
		}

		new SwingWorker<List<ConnectionView>, Void>()
		{
			@Override
			protected List<ConnectionView> doInBackground() throws Exception
			{
				return dataSource.fetchConnections();
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
				// The fetch just refreshed /federation/state — reflect it in the connect affordance.
				updateSiteConnectAffordance();
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
	 * One "Active now" row: tile name + a "who's on it" byline ("You", "Kayle", "You + Kayle") + a thin
	 * progress bar. Your own tasks lead in gold; teammate-only tasks stay neutral. The value/label live
	 * in {@code FontManager} JLabels (never painted inside the bar) so the text renders crisply.
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
		// Completions read as wins (green); your own actions stand out (gold); teammates stay neutral.
		if (e.isCompletion())
		{
			row.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
		}
		else if (e.self)
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
	 * §2/§6 — neutralize a federated string for Swing rendering. A {@link JLabel} (and {@link javax.swing.JToolTip})
	 * switches to an HTML view when its text begins with {@code <html}, so a federated value like
	 * {@code "<html><b>…</b>"} (a clan/tile/activity name from an untrusted upstream) could inject markup.
	 * Any string that starts — ignoring leading whitespace and case — with {@code <html} has its
	 * markup-significant characters escaped, so it can only ever render as <b>literal text</b>. Ordinary
	 * strings pass through untouched. This is the explicit sanitize the security model requires (no reliance
	 * on {@code putClientProperty("html.disable")}). Every federated field the panel renders routes through here.
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
		JPanel panel = new JPanel(new BorderLayout(0, 4));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		panel.setAlignmentX(LEFT_ALIGNMENT);

		String eventLine = c.eventName == null || c.eventName.isEmpty() ? c.clanName : c.eventName;
		JLabel event = new JLabel(plainText(eventLine));
		event.setFont(FontManager.getRunescapeFont());
		event.setForeground(ColorScheme.TEXT_COLOR);
		panel.add(event, BorderLayout.NORTH);

		// Count + bar together. The count is a FontManager JLabel — NOT painted inside the bar (the
		// bar's default L&F font rendered poorly); the bar is now a clean, string-less progress strip.
		JPanel bottom = new JPanel(new BorderLayout(0, 2));
		bottom.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel count = new JLabel(c.tilesComplete + " / " + c.tilesTotal + " tiles · " + c.completionPercent() + "%");
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

		panel.add(bottom, BorderLayout.CENTER);

		if (c.boardUrl != null && !c.boardUrl.isEmpty())
		{
			panel.add(boardLink(c.boardUrl), BorderLayout.SOUTH);
		}

		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
		return panel;
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
				LinkBrowser.browse(url);
			}
		});
		return link;
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
	private static final class ClanFilterRenderer extends BasicComboBoxRenderer
	{
		@Override
		public Component getListCellRendererComponent(JList<?> list, Object value, int index,
			boolean isSelected, boolean cellHasFocus)
		{
			super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
			if (value instanceof ConnectionView)
			{
				ConnectionView c = (ConnectionView) value;
				setText(plainText(c.hasError() ? c.clanName + "  (!)" : c.clanName));
				setFont(getFont().deriveFont(Font.PLAIN));
			}
			return this;
		}
	}
}
