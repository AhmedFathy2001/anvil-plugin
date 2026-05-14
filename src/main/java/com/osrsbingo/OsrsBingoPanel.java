package com.osrsbingo;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class OsrsBingoPanel extends PluginPanel
{
	private final OsrsBingoPlugin plugin;
	private final JPanel contentPanel;
	private final JLabel statusLabel;

	public OsrsBingoPanel(OsrsBingoPlugin plugin)
	{
		super(false);
		this.plugin = plugin;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Header
		JPanel headerPanel = new JPanel(new BorderLayout());
		headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		headerPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

		JLabel title = new JLabel("Anvil");
		title.setForeground(new Color(255, 215, 0)); // Gold
		title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
		headerPanel.add(title, BorderLayout.WEST);

		statusLabel = new JLabel();
		statusLabel.setFont(statusLabel.getFont().deriveFont(11f));
		headerPanel.add(statusLabel, BorderLayout.EAST);

		add(headerPanel, BorderLayout.NORTH);

		// Content. Pinning preferred + max width to PluginPanel.PANEL_WIDTH (minus the
		// scrollbar) so children with `Integer.MAX_VALUE` max-width don't push the
		// content panel wider than the visible viewport — which used to cause progress
		// numbers and buttons to render off-screen on the right edge.
		contentPanel = new JPanel();
		contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
		contentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		contentPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
		// Aggressively cap the content width so children with big preferred sizes can't
		// push past the visible panel edge. We subtract scrollbar width + padding so the
		// rightmost text never gets clipped.
		final int contentMaxWidth = PluginPanel.PANEL_WIDTH - 28;
		contentPanel.setMaximumSize(new Dimension(contentMaxWidth, Integer.MAX_VALUE));
		contentPanel.setPreferredSize(new Dimension(contentMaxWidth, contentPanel.getPreferredSize() != null
			? contentPanel.getPreferredSize().height
			: 600));

		JScrollPane scrollPane = new JScrollPane(contentPanel);
		scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);
		scrollPane.setBorder(null);
		add(scrollPane, BorderLayout.CENTER);

		// Footer with Refresh + Retry buttons
		JPanel footerPanel = new JPanel(new GridLayout(1, 1, 5, 0));
		footerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		footerPanel.setBorder(new EmptyBorder(5, 10, 5, 10));

		JButton refreshBtn = new JButton("Refresh");
		refreshBtn.addActionListener(e -> plugin.triggerRefresh());
		footerPanel.add(refreshBtn);

		add(footerPanel, BorderLayout.SOUTH);

		update();
	}

	public void update()
	{
		SwingUtilities.invokeLater(this::rebuild);
	}

	private void rebuild()
	{
		contentPanel.removeAll();

		// Drill-in detail view: when the user clicked a schedule row, render only that
		// item's detail and a back button. Bypasses the rest of the side panel.
		OsrsBingoPlugin.ScheduleSelection sel = plugin.getSelectedScheduleItem();
		if (sel != null)
		{
			BingoApiClient.EventTile tileSel = plugin.getSelectedTile();
			if (tileSel != null) {
				renderTileDetail(sel, tileSel);
			} else {
				renderScheduleDetail(sel);
			}
			contentPanel.revalidate();
			contentPanel.repaint();
			return;
		}

		// Insecure-URL warning — posting tokens/screenshots over plain HTTP exposes them on the wire.
		String apiUrl = plugin.getConfiguredApiUrl();
		if (apiUrl != null && apiUrl.startsWith("http://"))
		{
			JLabel warn = new JLabel("<html><b>⚠ Insecure site URL</b><br>Your Site URL uses http://. Tokens and screenshots are sent in the clear. Use https:// instead.</html>");
			warn.setForeground(new Color(255, 120, 120));
			warn.setBorder(new EmptyBorder(0, 0, 8, 0));
			warn.setAlignmentX(Component.LEFT_ALIGNMENT);
			contentPanel.add(warn);
		}

		// Admin link section (always shown)
		renderAdminSection();
		contentPanel.add(Box.createVerticalStrut(10));

		// Upcoming schedule (bingo + weekly)
		renderScheduleSection();

		// Weekly competition section (only when one is live)
		renderWeeklySection();

		// Guest banner (once hello resolves)
		Boolean known = plugin.getKnownMember();
		if (known != null && !known)
		{
			JLabel guest = new JLabel("<html><i>You're tracked as a guest.<br>A clan admin can promote you.</i></html>");
			guest.setForeground(new Color(255, 200, 0));
			guest.setBorder(new EmptyBorder(4, 0, 8, 0));
			guest.setAlignmentX(Component.LEFT_ALIGNMENT);
			contentPanel.add(guest);
		}

		PluginConfigResponse cfg = plugin.getPluginConfig();

		if (cfg == null)
		{
			boolean hasPlayer = plugin.hasPlayerToken();
			boolean hasAdmin = plugin.hasAdminToken();

			if (!hasPlayer && hasAdmin)
			{
				// Admin-only mode — no player token by design, so the missing /api/plugin/config
				// response isn't an error. Show "Admin only" so the user understands the state
				// instead of seeing a misleading "Failed to reach server" banner.
				statusLabel.setText("Admin only");
				statusLabel.setForeground(new Color(0, 200, 83));
			}
			else if (hasPlayer && plugin.isLastRefreshFailed())
			{
				// Player token IS set but the fetch failed — that's a real connection problem.
				statusLabel.setText("Connection Error");
				statusLabel.setForeground(new Color(255, 200, 0));

				JLabel msg = new JLabel("<html>Failed to reach server.<br>Check your connection and<br>plugin settings.</html>");
				msg.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				msg.setAlignmentX(Component.LEFT_ALIGNMENT);
				contentPanel.add(msg);
			}
			else
			{
				statusLabel.setText("Disconnected");
				statusLabel.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);

				JLabel msg = new JLabel("<html>Configure your Site URL and<br>Player Token in plugin settings, or link this plugin as admin.</html>");
				msg.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				msg.setAlignmentX(Component.LEFT_ALIGNMENT);
				contentPanel.add(msg);
			}
		}
		else
		{
			if (plugin.isLastRefreshFailed())
			{
				statusLabel.setText("Stale");
				statusLabel.setForeground(new Color(255, 200, 0)); // Yellow/amber
			}
			else
			{
				statusLabel.setText("Connected");
				statusLabel.setForeground(new Color(0, 200, 83));
			}

			// Event info
			addSection("Event");
			addLabel(cfg.event.name, Color.WHITE, true);
			addLabel(cfg.team.name, hexToColor(cfg.team.color), false);

			contentPanel.add(Box.createVerticalStrut(10));

			// Has the event ended? If yes, replace the live tracking sections with a
			// "this event has ended" notice — drop tracking and stat tiles are no longer
			// meaningful and the player should drill into the schedule list to find
			// what's currently active. We still surface the event name above so the
			// player isn't confused about which event the panel was for.
			boolean eventEnded = false;
			if (cfg.event.forceEndedAt != null && !cfg.event.forceEndedAt.isEmpty()) {
				eventEnded = true;
			} else if (cfg.event.endDate != null && !cfg.event.endDate.isEmpty()) {
				try {
					eventEnded = java.time.Instant.parse(cfg.event.endDate).isBefore(java.time.Instant.now());
				} catch (Exception ignored) { /* leave as false */ }
			}

			if (eventEnded) {
				JLabel ended = new JLabel("<html>This event has ended.<br>"
					+ "Past results live on the website. Pick another event from the schedule below.</html>");
				ended.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				ended.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
				ended.setAlignmentX(Component.LEFT_ALIGNMENT);
				ended.setBorder(new EmptyBorder(4, 0, 0, 0));
				contentPanel.add(ended);
			} else {
				// Tracked stats (skill XP / boss KC) — rendered as cards similar to drops so the
				// player gets a single, scannable progress view across all tile types.
				if (cfg.trackedStats != null && !cfg.trackedStats.isEmpty())
				{
					addSection("Stat Tiles");
					for (PluginConfigResponse.TrackedStat stat : cfg.trackedStats)
					{
						contentPanel.add(buildStatCard(stat));
						contentPanel.add(Box.createVerticalStrut(6));
					}
					contentPanel.add(Box.createVerticalStrut(4));
				}

				// Tracked drops
				if (cfg.trackedDrops != null && !cfg.trackedDrops.isEmpty())
				{
					addSection("Tracked Drops");
					for (PluginConfigResponse.TrackedDrop drop : cfg.trackedDrops)
					{
						contentPanel.add(buildDropCard(drop));
						contentPanel.add(Box.createVerticalStrut(6));
					}
				}
				else if (cfg.trackedStats == null || cfg.trackedStats.isEmpty())
				{
					addSection("Tracked Drops");
					addLabel("No drop tiles configured", ColorScheme.LIGHT_GRAY_COLOR, false);
				}
			}
		}

		// Pending submissions section (always shown if there are any)
		List<PendingSubmissionStore.PendingSubmission> pendingList = PendingSubmissionStore.loadAll();
		if (!pendingList.isEmpty())
		{
			contentPanel.add(Box.createVerticalStrut(14));
			addSection("Pending (" + pendingList.size() + ")");

			SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
			for (PendingSubmissionStore.PendingSubmission pending : pendingList)
			{
				JPanel pendingRow = new JPanel(new BorderLayout());
				pendingRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				pendingRow.setBorder(new EmptyBorder(4, 8, 4, 8));
				pendingRow.setAlignmentX(Component.LEFT_ALIGNMENT);
				pendingRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));

				JLabel pendingLabel = new JLabel(pending.label);
				pendingLabel.setForeground(new Color(255, 200, 0));
				pendingLabel.setFont(pendingLabel.getFont().deriveFont(11f));
				pendingRow.add(pendingLabel, BorderLayout.WEST);

				JLabel timeLabel = new JLabel(sdf.format(new Date(pending.timestamp)));
				timeLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				timeLabel.setFont(timeLabel.getFont().deriveFont(10f));
				pendingRow.add(timeLabel, BorderLayout.EAST);

				contentPanel.add(pendingRow);
				contentPanel.add(Box.createVerticalStrut(2));
			}

			// Retry Now button
			JButton retryBtn = new JButton("Retry Now");
			retryBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
			retryBtn.addActionListener(e ->
			{
				if (plugin != null)
				{
					plugin.triggerRefresh();
				}
			});
			contentPanel.add(Box.createVerticalStrut(4));
			contentPanel.add(retryBtn);
		}

		contentPanel.revalidate();
		contentPanel.repaint();
	}

	private void addSection(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(new Color(255, 215, 0)); // Gold
		label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		label.setBorder(new EmptyBorder(0, 0, 4, 0));
		contentPanel.add(label);
	}

	private void addLabel(String text, Color color, boolean bold)
	{
		JLabel label = new JLabel(text);
		label.setForeground(color);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		if (bold)
		{
			label.setFont(label.getFont().deriveFont(Font.BOLD));
		}
		contentPanel.add(label);
	}

	private Color hexToColor(String hex)
	{
		try
		{
			return Color.decode(hex);
		}
		catch (Exception e)
		{
			return Color.WHITE;
		}
	}

	private void renderAdminSection()
	{
		addSection("Admin link");
		if (plugin.hasAdminToken())
		{
			String rsn = plugin.getAdminLinkedRsn();
			addLabel("Linked as " + (rsn == null || rsn.isEmpty() ? "?" : rsn), new Color(0, 200, 83), true);

			long syncAt = plugin.getLastSyncAt();
			String syncText = syncAt > 0
				? "Last sync: " + formatRelative(syncAt)
				: "No sync yet";
			addLabel(syncText, ColorScheme.LIGHT_GRAY_COLOR, false);
			String summary = plugin.getLastSyncSummary();
			if (summary != null && !summary.isEmpty())
			{
				addLabel(summary, ColorScheme.LIGHT_GRAY_COLOR, false);
			}

			// Force a finite width so BoxLayout actually places the buttons inside the
			// panel — `Integer.MAX_VALUE` left them rendered off the right edge in some
			// L&F sizings, producing a phantom empty band where they should appear.
			final int adminRowWidth = PluginPanel.PANEL_WIDTH - 32;
			JPanel btnRow = new JPanel(new GridLayout(1, 2, 4, 0));
			btnRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
			btnRow.setAlignmentX(Component.LEFT_ALIGNMENT);
			btnRow.setPreferredSize(new Dimension(adminRowWidth, 28));
			btnRow.setMaximumSize(new Dimension(adminRowWidth, 28));
			btnRow.setBorder(new EmptyBorder(6, 0, 0, 0));

			JButton syncBtn = new JButton("Sync clan");
			syncBtn.setEnabled(plugin.isClanScrapeAvailable());
			if (!syncBtn.isEnabled())
			{
				syncBtn.setToolTipText("Open the clan tab in OSRS to enable");
			}
			syncBtn.addActionListener(e ->
			{
				syncBtn.setEnabled(false);
				syncBtn.setText("Syncing...");
				plugin.syncClanRoster((ok, msg) -> SwingUtilities.invokeLater(() ->
				{
					JOptionPane.showMessageDialog(this, msg, ok ? "Clan synced" : "Sync failed",
						ok ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
					update();
				}));
			});
			btnRow.add(syncBtn);

			JButton unlinkBtn = new JButton("Unlink");
			unlinkBtn.addActionListener(e -> plugin.unlinkAdmin());
			btnRow.add(unlinkBtn);

			contentPanel.add(btnRow);
		}
		else
		{
			String code = plugin.getAdminLinkCode();
			boolean hasCode = code != null && code.trim().length() == 6;
			String msg = hasCode
				? "Press Link to exchange the code."
				: "Generate a 6-char code on the site's admin page and paste it into plugin config.";
			JLabel l = new JLabel("<html>" + msg + "</html>");
			l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			l.setAlignmentX(Component.LEFT_ALIGNMENT);
			contentPanel.add(l);

			JButton linkBtn = new JButton("Link as admin");
			linkBtn.setEnabled(hasCode);
			linkBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
			linkBtn.setBorder(new EmptyBorder(4, 8, 4, 8));
			linkBtn.addActionListener(e ->
			{
				linkBtn.setEnabled(false);
				linkBtn.setText("Linking...");
				plugin.linkAdmin((ok, resultMsg) -> SwingUtilities.invokeLater(() ->
				{
					if (!ok)
					{
						JOptionPane.showMessageDialog(this, resultMsg, "Link failed", JOptionPane.WARNING_MESSAGE);
					}
					update();
				}));
			});
			JPanel wrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
			wrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
			wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
			wrap.setBorder(new EmptyBorder(6, 0, 0, 0));
			wrap.add(linkBtn);
			contentPanel.add(wrap);
		}
	}

	private void renderScheduleSection()
	{
		BingoApiClient.ScheduleResponse sched = plugin.getSchedule();
		if (sched == null)
		{
			return;
		}
		boolean hasBingos = sched.bingos != null && !sched.bingos.isEmpty();
		boolean hasWeeklies = sched.weeklies != null && !sched.weeklies.isEmpty();
		if (!hasBingos && !hasWeeklies)
		{
			return;
		}

		// Bucket by current status so the section header isn't a lie. The previous version
		// labelled everything "Upcoming" even when items were already live.
		java.util.List<Runnable> activeRows = new java.util.ArrayList<>();
		java.util.List<Runnable> upcomingRows = new java.util.ArrayList<>();

		if (hasBingos)
		{
			for (BingoApiClient.ScheduledBingo b : sched.bingos)
			{
				final boolean active = "active".equalsIgnoreCase(b.status);
				final Color accent = active ? new Color(0, 200, 83) : new Color(255, 215, 0);
				final OsrsBingoPlugin.ScheduleSelection sel = new OsrsBingoPlugin.ScheduleSelection(
					"bingo", b.id, b.title, b.startDate, b.endDate, b.status, null, b.boardSize, b.tileCount);
				Runnable add = () -> {
					contentPanel.add(buildScheduleRow("Bingo", b.title, b.startDate, b.endDate, accent, active, sel));
					contentPanel.add(Box.createVerticalStrut(3));
				};
				if (active) activeRows.add(add); else upcomingRows.add(add);
			}
		}
		if (hasWeeklies)
		{
			for (BingoApiClient.ScheduledWeekly w : sched.weeklies)
			{
				final boolean active = "active".equalsIgnoreCase(w.status);
				final Color accent;
				if (active) accent = new Color(0, 200, 83);
				else if ("upcoming".equalsIgnoreCase(w.status)) accent = new Color(100, 160, 255);
				else accent = ColorScheme.LIGHT_GRAY_COLOR;
				final OsrsBingoPlugin.ScheduleSelection sel = new OsrsBingoPlugin.ScheduleSelection(
					"weekly", w.id, w.title, w.startDate, w.endDate, w.status, w.metric, null, null);
				Runnable add = () -> {
					contentPanel.add(buildScheduleRow("Weekly", w.title, w.startDate, w.endDate, accent, active, sel));
					contentPanel.add(Box.createVerticalStrut(3));
				};
				if (active) activeRows.add(add); else upcomingRows.add(add);
			}
		}

		if (!activeRows.isEmpty())
		{
			addSection("Active now");
			for (Runnable r : activeRows) r.run();
			contentPanel.add(Box.createVerticalStrut(8));
		}
		if (!upcomingRows.isEmpty())
		{
			addSection("Upcoming");
			for (Runnable r : upcomingRows) r.run();
		}
		contentPanel.add(Box.createVerticalStrut(10));
	}

	private JPanel buildScheduleRow(String kind, String title, String start, String end, Color accent, boolean active)
	{
		return buildScheduleRow(kind, title, start, end, accent, active, null);
	}

	private JPanel buildScheduleRow(String kind, String title, String start, String end, Color accent, boolean active,
		final OsrsBingoPlugin.ScheduleSelection selection)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(6, 10, 6, 10));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));
		if (selection != null)
		{
			row.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			row.addMouseListener(new java.awt.event.MouseAdapter() {
				@Override
				public void mouseClicked(java.awt.event.MouseEvent e)
				{
					plugin.selectScheduleItem(selection);
				}
				@Override
				public void mouseEntered(java.awt.event.MouseEvent e)
				{
					row.setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
				}
				@Override
				public void mouseExited(java.awt.event.MouseEvent e)
				{
					row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				}
			});
		}

		JPanel left = new JPanel();
		left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
		left.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel kindLabel = new JLabel(kind.toUpperCase());
		kindLabel.setForeground(accent);
		// Use a system-default sans font at a slightly larger size — RuneLite's default
		// pixel font becomes hard to read at 9pt. SansSerif is universally available and
		// scales legibly.
		kindLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
		left.add(kindLabel);

		JLabel titleLabel = new JLabel(title);
		titleLabel.setForeground(Color.WHITE);
		titleLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
		left.add(titleLabel);

		row.add(left, BorderLayout.WEST);

		// Format date+time in the user's local timezone — "ends Thu May 1, 23:00" beats
		// the previous "ends 2026-05-01" UTC truncation that confused everyone in non-UTC zones.
		String whenText = active ? "ends " + formatLocal(end) : formatLocal(start);
		JLabel when = new JLabel(whenText);
		when.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		when.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
		row.add(when, BorderLayout.EAST);

		return row;
	}

	private void renderScheduleDetail(OsrsBingoPlugin.ScheduleSelection sel)
	{
		// Helper that constrains a label to its preferred height — without this BoxLayout
		// stretches each label to fill leftover vertical space and the detail view ends up
		// with a giant empty band between header and content.
		java.util.function.BiConsumer<JComponent, Integer> capHeight = (c, h) ->
			c.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));

		// Header with back button
		JButton backBtn = new JButton("← Back");
		backBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
		backBtn.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
		backBtn.setBorder(new EmptyBorder(4, 8, 4, 8));
		backBtn.addActionListener(e -> plugin.clearScheduleSelection());
		JPanel backWrap = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
		backWrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		backWrap.setAlignmentX(Component.LEFT_ALIGNMENT);
		backWrap.setBorder(new EmptyBorder(0, 0, 8, 0));
		backWrap.add(backBtn);
		capHeight.accept(backWrap, 32);
		contentPanel.add(backWrap);

		boolean active = "active".equalsIgnoreCase(sel.status);
		Color accent;
		if (active) accent = new Color(0, 200, 83);
		else if ("upcoming".equalsIgnoreCase(sel.status)) accent = new Color(100, 160, 255);
		else if ("completed".equalsIgnoreCase(sel.status)) accent = ColorScheme.LIGHT_GRAY_COLOR;
		else accent = new Color(255, 215, 0);

		JLabel kindLabel = new JLabel(sel.kind.toUpperCase());
		kindLabel.setForeground(accent);
		kindLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
		kindLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		capHeight.accept(kindLabel, 14);
		contentPanel.add(kindLabel);

		JLabel titleLabel = new JLabel(sel.title);
		titleLabel.setForeground(Color.WHITE);
		titleLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
		titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		titleLabel.setBorder(new EmptyBorder(2, 0, 6, 0));
		capHeight.accept(titleLabel, 28);
		contentPanel.add(titleLabel);

		JLabel statusLabel = new JLabel(prettyStatus(sel.status));
		statusLabel.setForeground(accent);
		statusLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
		statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		capHeight.accept(statusLabel, 16);
		contentPanel.add(statusLabel);

		if (sel.metric != null && !sel.metric.isEmpty())
		{
			JLabel metricLabel = new JLabel("Metric: " + sel.metric);
			metricLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			metricLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
			metricLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
			capHeight.accept(metricLabel, 16);
			contentPanel.add(metricLabel);
		}

		// Bingo-only meta strip: board size and tile count.
		if ("bingo".equalsIgnoreCase(sel.kind) && sel.boardSize != null)
		{
			String tileText = sel.tileCount != null
				? sel.boardSize + "×" + sel.boardSize + " · " + sel.tileCount + " tiles"
				: sel.boardSize + "×" + sel.boardSize + " board";
			JLabel meta = new JLabel(tileText);
			meta.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			meta.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
			meta.setAlignmentX(Component.LEFT_ALIGNMENT);
			capHeight.accept(meta, 16);
			contentPanel.add(meta);
		}

		contentPanel.add(Box.createVerticalStrut(10));

		// Date strip
		JPanel dates = new JPanel();
		dates.setLayout(new BoxLayout(dates, BoxLayout.Y_AXIS));
		dates.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		dates.setBorder(new EmptyBorder(8, 10, 8, 10));
		dates.setAlignmentX(Component.LEFT_ALIGNMENT);
		dates.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));

		JLabel startTitle = new JLabel("STARTS");
		startTitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		startTitle.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
		dates.add(startTitle);
		JLabel startVal = new JLabel(formatLocal(sel.startDate));
		startVal.setForeground(Color.WHITE);
		startVal.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
		dates.add(startVal);

		dates.add(Box.createVerticalStrut(6));

		JLabel endTitle = new JLabel("ENDS");
		endTitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		endTitle.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
		dates.add(endTitle);
		JLabel endVal = new JLabel(formatLocal(sel.endDate));
		endVal.setForeground(Color.WHITE);
		endVal.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
		dates.add(endVal);

		contentPanel.add(dates);
		contentPanel.add(Box.createVerticalStrut(10));

		// Tile list for bingos — works at any board size where a grid would be
		// illegible (5×5 in a 220px-wide side panel makes each cell ~40px which is
		// fine for an overview but useless for picking specific tiles). The list is
		// scrollable inside the surrounding JScrollPane.
		if ("bingo".equalsIgnoreCase(sel.kind))
		{
			BingoApiClient.EventDetail detail = plugin.getSelectedEventDetail();

			JLabel tilesHeader = new JLabel("TILES");
			tilesHeader.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			tilesHeader.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
			tilesHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
			tilesHeader.setBorder(new EmptyBorder(0, 0, 4, 0));
			contentPanel.add(tilesHeader);

			if (detail == null || detail.tiles == null) {
				JLabel loading = new JLabel("Loading tiles…");
				loading.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				loading.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
				loading.setAlignmentX(Component.LEFT_ALIGNMENT);
				contentPanel.add(loading);
			} else {
				for (BingoApiClient.EventTile t : detail.tiles) {
					contentPanel.add(buildTileListRow(t));
					contentPanel.add(Box.createVerticalStrut(3));
				}
			}
			contentPanel.add(Box.createVerticalStrut(10));
		}

		// "Open on website" button — quickest path to the full event page since the plugin
		// panel can't render a board / leaderboard at a useful size.
		String apiUrl = plugin.getConfiguredApiUrl();
		if (apiUrl != null && !apiUrl.isEmpty())
		{
			final String url = "bingo".equalsIgnoreCase(sel.kind)
				? apiUrl + "/events/" + sel.id
				: apiUrl + "/weekly";
			JButton open = new JButton("Open on website");
			open.setAlignmentX(Component.LEFT_ALIGNMENT);
			open.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
			open.addActionListener(e -> {
				try { net.runelite.client.util.LinkBrowser.browse(url); }
				catch (Exception ignored) { /* best-effort */ }
			});
			JPanel openWrap = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
			openWrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
			openWrap.setAlignmentX(Component.LEFT_ALIGNMENT);
			capHeight.accept(openWrap, 36);
			openWrap.add(open);
			contentPanel.add(openWrap);
		}

		// Glue at the bottom keeps everything anchored to the top — without it, BoxLayout
		// distributes the leftover space evenly and the components float to the middle.
		contentPanel.add(Box.createVerticalGlue());
	}

	/**
	 * Stat-tile card. Same card chrome as buildDropCard but tailored to skill XP /
	 * boss KC progress. Goal can be very large (e.g. 50,000 XP), so we format with
	 * thousands separators for readability.
	 */
	private JPanel buildStatCard(PluginConfigResponse.TrackedStat stat)
	{
		final boolean complete = stat.goalAmount > 0 && stat.currentAmount >= stat.goalAmount;
		final int rowWidth = PluginPanel.PANEL_WIDTH - 36;

		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(new EmptyBorder(8, 10, 8, 10));
		card.setAlignmentX(Component.LEFT_ALIGNMENT);

		// Header: title + (current / goal). BorderLayout pinned to a fixed width so
		// the EAST progress badge always renders inside the visible card area.
		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		header.setAlignmentX(Component.LEFT_ALIGNMENT);
		header.setPreferredSize(new Dimension(rowWidth, 22));
		header.setMaximumSize(new Dimension(rowWidth, 22));

		JLabel name = new JLabel(stat.label);
		name.setForeground(Color.WHITE);
		name.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
		header.add(name, BorderLayout.WEST);

		String progressText = String.format("%,d / %,d", stat.currentAmount, stat.goalAmount);
		JLabel progress = new JLabel(complete ? progressText + " ✓" : progressText);
		progress.setForeground(complete ? new Color(0, 200, 83) : new Color(255, 215, 0));
		progress.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
		header.add(progress, BorderLayout.EAST);
		card.add(header);

		// Type/skill subline
		String subline = (stat.statName == null ? "" : stat.statName)
			+ (stat.trackingMode != null ? "  ·  " + stat.trackingMode : "");
		JLabel sub = new JLabel(subline);
		sub.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		sub.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
		sub.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.add(sub);

		// Progress bar
		double pct = stat.goalAmount > 0
			? Math.min(1.0, (double) stat.currentAmount / stat.goalAmount)
			: 0.0;
		card.add(Box.createVerticalStrut(4));
		card.add(buildProgressBar(pct, complete));

		final int statCardWidth = PluginPanel.PANEL_WIDTH - 32;
		card.setPreferredSize(new Dimension(statCardWidth, 64));
		card.setMaximumSize(new Dimension(statCardWidth, 64));
		return card;
	}

	/**
	 * Single-row tile entry for the schedule detail list view. Click anywhere on the
	 * row to drill into the tile detail — same flow as the previous grid cells.
	 */
	private JPanel buildTileListRow(final BingoApiClient.EventTile t)
	{
		final JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(6, 8, 6, 8));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
		row.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));

		// Position number on the left as a tiny chip — gives a sense of board order.
		JLabel pos = new JLabel(String.valueOf(t.position + 1));
		pos.setForeground(new Color(255, 215, 0));
		pos.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
		pos.setBorder(new EmptyBorder(0, 0, 0, 8));
		pos.setAlignmentY(Component.CENTER_ALIGNMENT);
		row.add(pos);

		// Title + type stacked vertically
		final JPanel mid = new JPanel();
		mid.setLayout(new BoxLayout(mid, BoxLayout.Y_AXIS));
		mid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		mid.setAlignmentY(Component.CENTER_ALIGNMENT);

		JLabel name = new JLabel(t.label == null || t.label.isEmpty() ? "(unnamed)" : t.label);
		name.setForeground(Color.WHITE);
		name.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
		name.setAlignmentX(Component.LEFT_ALIGNMENT);
		mid.add(name);

		String type = t.tileType == null ? "standard" : t.tileType.toLowerCase();
		String typeText;
		Color typeColor;
		switch (type) {
			case "drop":
				typeText = "drop";
				typeColor = new Color(100, 200, 255);
				break;
			case "stat":
				typeText = "stat-tracked";
				typeColor = new Color(150, 220, 130);
				break;
			default:
				typeText = "standard";
				typeColor = ColorScheme.LIGHT_GRAY_COLOR;
		}
		if (t.optional != null && t.optional) typeText += " · optional";
		JLabel typeBadge = new JLabel(typeText);
		typeBadge.setForeground(typeColor);
		typeBadge.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
		typeBadge.setAlignmentX(Component.LEFT_ALIGNMENT);
		mid.add(typeBadge);

		row.add(mid);
		row.add(Box.createHorizontalGlue());

		JLabel chevron = new JLabel("›");
		chevron.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		chevron.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
		chevron.setAlignmentY(Component.CENTER_ALIGNMENT);
		row.add(chevron);

		row.addMouseListener(new java.awt.event.MouseAdapter() {
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e) {
				plugin.selectTile(t);
			}
			@Override
			public void mouseEntered(java.awt.event.MouseEvent e) {
				row.setBackground(new Color(60, 50, 30));
				mid.setBackground(new Color(60, 50, 30));
			}
			@Override
			public void mouseExited(java.awt.event.MouseEvent e) {
				row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				mid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
		});

		return row;
	}

	/**
	 * Builds a single tracked-drop card. BoxLayout Y_AXIS so contents stack at their
	 * preferred heights and the card never gets stretched by leftover vertical space —
	 * the previous BorderLayout CENTER caused 200-pixel empty bands inside cards that
	 * had item requirements.
	 */
	private JPanel buildDropCard(PluginConfigResponse.TrackedDrop drop)
	{
		final boolean complete = drop.currentAmount >= drop.requiredAmount;
		final boolean hasItems = drop.itemRequirements != null && !drop.itemRequirements.isEmpty();

		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(new EmptyBorder(8, 10, 8, 10));
		card.setAlignmentX(Component.LEFT_ALIGNMENT);

		// Header — BorderLayout WEST/EAST is the most reliable way to pin progress to
		// the right inside a BoxLayout Y_AXIS parent. Forcing preferredSize.width to
		// the panel width prevents the header from collapsing to its children's
		// preferred widths (which is what hid the progress text in earlier passes).
		final int rowWidth = PluginPanel.PANEL_WIDTH - 48; // panel padding + card padding + scrollbar
		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		header.setAlignmentX(Component.LEFT_ALIGNMENT);
		header.setPreferredSize(new Dimension(rowWidth, 22));
		header.setMaximumSize(new Dimension(rowWidth, 22));

		JLabel name = new JLabel(drop.label);
		name.setForeground(Color.WHITE);
		name.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
		header.add(name, BorderLayout.WEST);

		String progressText = drop.currentAmount + "/" + drop.requiredAmount;
		JLabel progress = new JLabel(complete ? progressText + " ✓" : progressText);
		progress.setForeground(complete ? new Color(0, 200, 83) : new Color(255, 215, 0));
		progress.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
		header.add(progress, BorderLayout.EAST);

		card.add(header);

		// Slim progress bar so a glance reads completion without doing math
		double pct = drop.requiredAmount > 0
			? Math.min(1.0, (double) drop.currentAmount / drop.requiredAmount)
			: 0.0;
		card.add(Box.createVerticalStrut(4));
		card.add(buildProgressBar(pct, complete));

		// Sub-items (one row each, fixed height — no stretch)
		if (hasItems)
		{
			card.add(Box.createVerticalStrut(6));
			JPanel items = new JPanel();
			items.setLayout(new BoxLayout(items, BoxLayout.Y_AXIS));
			items.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			items.setAlignmentX(Component.LEFT_ALIGNMENT);

			for (PluginConfigResponse.ItemRequirement req : drop.itemRequirements)
			{
				boolean itemComplete = req.currentAmount >= req.requiredAmount;
				JPanel row = new JPanel(new BorderLayout());
				row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				row.setAlignmentX(Component.LEFT_ALIGNMENT);
				row.setPreferredSize(new Dimension(rowWidth, 18));
				row.setMaximumSize(new Dimension(rowWidth, 18));

				JLabel itemName = new JLabel((itemComplete ? "✓ " : "• ") + req.name);
				itemName.setForeground(itemComplete ? new Color(0, 200, 83) : ColorScheme.LIGHT_GRAY_COLOR);
				itemName.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
				row.add(itemName, BorderLayout.WEST);

				JLabel itemProgress = new JLabel(req.currentAmount + "/" + req.requiredAmount);
				itemProgress.setForeground(itemComplete ? new Color(0, 200, 83) : ColorScheme.LIGHT_GRAY_COLOR);
				itemProgress.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
				row.add(itemProgress, BorderLayout.EAST);

				items.add(row);
			}
			card.add(items);
		}

		// Manual submit button when incomplete
		if (!complete)
		{
			card.add(Box.createVerticalStrut(8));
			JButton submitBtn = new JButton("Manual submit");
			submitBtn.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
			submitBtn.setMargin(new Insets(3, 8, 3, 8));
			submitBtn.addActionListener(e -> openManualSubmitDialog(drop));
			JPanel btnWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
			btnWrap.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			btnWrap.setAlignmentX(Component.LEFT_ALIGNMENT);
			btnWrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
			btnWrap.add(submitBtn);
			card.add(btnWrap);
		}

		// Cap the card's max height to the sum of its actual contents — keeps BoxLayout
		// from inflating it. Header (22) + bar (8) + optional items (18 each, plus 6 gap)
		// + optional button (30, plus 8 gap) + paddings.
		int height = 22 + 4 + 8 + 16; // base + paddings
		if (hasItems) height += 6 + drop.itemRequirements.size() * 18;
		if (!complete) height += 8 + 30;
		// Force the card to the panel width so BoxLayout Y_AXIS doesn't size it to its
		// children's preferred width and align it left of the panel — leaving the
		// progress badge orphaned beyond the visible edge.
		final int cardWidth = PluginPanel.PANEL_WIDTH - 32;
		card.setPreferredSize(new Dimension(cardWidth, height));
		card.setMaximumSize(new Dimension(cardWidth, height));

		return card;
	}

	/**
	 * Slim 4px progress bar. Fills the card width, colored gold (in-progress) or green
	 * (complete). Pure draw — no Swing JProgressBar overhead.
	 */
	private static JComponent buildProgressBar(final double pct, final boolean complete)
	{
		JPanel bar = new JPanel() {
			@Override
			protected void paintComponent(java.awt.Graphics g)
			{
				super.paintComponent(g);
				java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
				int w = getWidth();
				int h = getHeight();
				g2.setColor(new Color(45, 45, 45));
				g2.fillRoundRect(0, 0, w, h, 3, 3);
				int filled = (int) Math.round(w * Math.max(0, Math.min(1, pct)));
				g2.setColor(complete ? new Color(0, 200, 83) : new Color(255, 215, 0));
				g2.fillRoundRect(0, 0, filled, h, 3, 3);
				g2.dispose();
			}
		};
		bar.setOpaque(false);
		bar.setAlignmentX(Component.LEFT_ALIGNMENT);
		bar.setPreferredSize(new Dimension(Integer.MAX_VALUE, 4));
		bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 4));
		return bar;
	}

	private void renderTileDetail(OsrsBingoPlugin.ScheduleSelection sel, BingoApiClient.EventTile tile)
	{
		java.util.function.BiConsumer<JComponent, Integer> capHeight = (c, h) ->
			c.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));

		// Back button returns to the event detail (keeps schedule selection, just clears tile).
		JButton backBtn = new JButton("← Back to " + sel.title);
		backBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
		backBtn.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
		backBtn.setBorder(new EmptyBorder(4, 8, 4, 8));
		backBtn.addActionListener(e -> plugin.clearTileSelection());
		JPanel backWrap = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
		backWrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		backWrap.setAlignmentX(Component.LEFT_ALIGNMENT);
		backWrap.setBorder(new EmptyBorder(0, 0, 8, 0));
		backWrap.add(backBtn);
		capHeight.accept(backWrap, 32);
		contentPanel.add(backWrap);

		JLabel kindLabel = new JLabel("TILE #" + (tile.position + 1));
		kindLabel.setForeground(new Color(255, 215, 0));
		kindLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
		kindLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		capHeight.accept(kindLabel, 14);
		contentPanel.add(kindLabel);

		JLabel titleLabel = new JLabel(tile.label == null ? "(no label)" : tile.label);
		titleLabel.setForeground(Color.WHITE);
		titleLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
		titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		titleLabel.setBorder(new EmptyBorder(2, 0, 6, 0));
		capHeight.accept(titleLabel, 26);
		contentPanel.add(titleLabel);

		// Type tag with descriptive copy. The DB's tile_type column is sometimes left at
		// "standard" even when stat tracking is configured — treat populated stat fields
		// as the source of truth so the badge matches what the tile actually does.
		String tileType = tile.tileType == null ? "standard" : tile.tileType;
		boolean isStatTracked = tile.trackedStat != null && !tile.trackedStat.isEmpty();
		boolean isDrop = "drop".equalsIgnoreCase(tileType);
		String typeText;
		Color typeColor;
		if (isDrop)
		{
			typeText = "Drop tile";
			typeColor = new Color(100, 200, 255);
		}
		else if (isStatTracked)
		{
			boolean isBoss = "boss".equalsIgnoreCase(tile.statType) || "kc".equalsIgnoreCase(tile.statType);
			typeText = isBoss ? "Boss KC" : "Stat-tracked";
			typeColor = new Color(150, 220, 130);
		}
		else
		{
			typeText = "Standard";
			typeColor = ColorScheme.LIGHT_GRAY_COLOR;
		}
		JLabel typeLabel = new JLabel(typeText + (tile.optional != null && tile.optional ? " · optional" : ""));
		typeLabel.setForeground(typeColor);
		typeLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
		typeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		capHeight.accept(typeLabel, 16);
		contentPanel.add(typeLabel);

		contentPanel.add(Box.createVerticalStrut(8));

		// Tile-type-specific meta strip
		JPanel meta = new JPanel();
		meta.setLayout(new BoxLayout(meta, BoxLayout.Y_AXIS));
		meta.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		meta.setBorder(new EmptyBorder(8, 10, 8, 10));
		meta.setAlignmentX(Component.LEFT_ALIGNMENT);

		boolean hasMeta = false;
		if (tile.requiredAmount != null && tile.requiredAmount > 0)
		{
			meta.add(buildKv("REQUIRED", String.valueOf(tile.requiredAmount)));
			hasMeta = true;
		}
		if (tile.trackedStat != null && !tile.trackedStat.isEmpty())
		{
			meta.add(buildKv("TRACKED STAT", tile.trackedStat));
			hasMeta = true;
		}
		if (tile.statType != null && !tile.statType.isEmpty())
		{
			meta.add(buildKv("STAT TYPE", tile.statType));
			hasMeta = true;
		}
		if (tile.statGoal != null && tile.statGoal > 0)
		{
			meta.add(buildKv("GOAL", String.valueOf(tile.statGoal)));
			hasMeta = true;
		}
		if (tile.trackingMode != null && !tile.trackingMode.isEmpty())
		{
			meta.add(buildKv("MODE", tile.trackingMode));
			hasMeta = true;
		}

		if (hasMeta)
		{
			meta.setMaximumSize(new Dimension(Integer.MAX_VALUE, meta.getPreferredSize().height));
			contentPanel.add(meta);
			contentPanel.add(Box.createVerticalStrut(8));
		}

		if (tile.description != null && !tile.description.isEmpty())
		{
			JLabel descTitle = new JLabel("DESCRIPTION");
			descTitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			descTitle.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
			descTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
			capHeight.accept(descTitle, 14);
			contentPanel.add(descTitle);

			JLabel desc = new JLabel("<html><div style='width: 200px'>" + escapeHtml(tile.description) + "</div></html>");
			desc.setForeground(Color.WHITE);
			desc.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
			desc.setAlignmentX(Component.LEFT_ALIGNMENT);
			contentPanel.add(desc);
			contentPanel.add(Box.createVerticalStrut(8));
		}

		contentPanel.add(Box.createVerticalGlue());
	}

	private JPanel buildKv(String key, String value)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(2, 0, 2, 0));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel k = new JLabel(key);
		k.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		k.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
		row.add(k, BorderLayout.WEST);
		JLabel v = new JLabel(value);
		v.setForeground(Color.WHITE);
		v.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
		row.add(v, BorderLayout.EAST);
		return row;
	}

	private static String escapeHtml(String s)
	{
		if (s == null) return "";
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static String prettyStatus(String s)
	{
		if (s == null) return "";
		switch (s.toLowerCase())
		{
			case "active": return "Live now";
			case "upcoming": return "Upcoming";
			case "completed": return "Completed";
			default: return s;
		}
	}

	private static final java.time.format.DateTimeFormatter LOCAL_DATE_FMT =
		java.time.format.DateTimeFormatter.ofPattern("EEE MMM d, HH:mm");

	private static String formatLocal(String iso)
	{
		if (iso == null || iso.length() < 10) return "";
		try
		{
			java.time.Instant instant = java.time.Instant.parse(iso);
			return instant.atZone(java.time.ZoneId.systemDefault()).format(LOCAL_DATE_FMT);
		}
		catch (Exception e)
		{
			return iso.substring(0, Math.min(10, iso.length()));
		}
	}

	private void renderWeeklySection()
	{
		BingoApiClient.ActiveWeekly active = plugin.getActiveWeekly();
		if (active == null)
		{
			return;
		}
		addSection("Weekly comp");
		addLabel(active.title, Color.WHITE, true);
		if (active.metric != null && !active.metric.isEmpty())
		{
			addLabel(active.metric + (active.type != null ? " (" + active.type + ")" : ""),
				ColorScheme.LIGHT_GRAY_COLOR, false);
		}
		String summary = plugin.getWeeklyEnrollmentSummary();
		if (summary != null && !summary.isEmpty())
		{
			Color c = summary.startsWith("Enrolled —") ? new Color(0, 200, 83) : ColorScheme.LIGHT_GRAY_COLOR;
			addLabel(summary, c, false);
		}
		contentPanel.add(Box.createVerticalStrut(10));
	}

	private static String formatRelative(long timestampMs)
	{
		long delta = System.currentTimeMillis() - timestampMs;
		if (delta < 60_000) return "just now";
		if (delta < 3_600_000) return (delta / 60_000) + "m ago";
		if (delta < 86_400_000) return (delta / 3_600_000) + "h ago";
		return (delta / 86_400_000) + "d ago";
	}

	private void openManualSubmitDialog(PluginConfigResponse.TrackedDrop drop)
	{
		Window parent = SwingUtilities.getWindowAncestor(this);
		JDialog dialog = new JDialog(parent, "Manual submit — " + drop.label, Dialog.ModalityType.APPLICATION_MODAL);
		dialog.setLayout(new BorderLayout(8, 8));
		dialog.getRootPane().setBorder(new EmptyBorder(10, 10, 10, 10));

		JPanel form = new JPanel();
		form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));

		// Item selector (per-item mode only)
		JComboBox<PluginConfigResponse.ItemRequirement> itemCombo = null;
		if (drop.itemRequirements != null && !drop.itemRequirements.isEmpty())
		{
			List<PluginConfigResponse.ItemRequirement> incomplete = new java.util.ArrayList<>();
			for (PluginConfigResponse.ItemRequirement req : drop.itemRequirements)
			{
				if (req.currentAmount < req.requiredAmount)
				{
					incomplete.add(req);
				}
			}
			if (incomplete.isEmpty())
			{
				JOptionPane.showMessageDialog(this, "All items for this tile are already complete.", "Nothing to submit", JOptionPane.INFORMATION_MESSAGE);
				dialog.dispose();
				return;
			}
			itemCombo = new JComboBox<>(incomplete.toArray(new PluginConfigResponse.ItemRequirement[0]));
			itemCombo.setRenderer(new DefaultListCellRenderer()
			{
				@Override
				public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus)
				{
					super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
					if (value instanceof PluginConfigResponse.ItemRequirement)
					{
						PluginConfigResponse.ItemRequirement r = (PluginConfigResponse.ItemRequirement) value;
						setText(r.name + " (" + r.currentAmount + "/" + r.requiredAmount + ")");
					}
					return this;
				}
			});
			JPanel row = new JPanel(new BorderLayout(6, 0));
			row.add(new JLabel("Item:"), BorderLayout.WEST);
			row.add(itemCombo, BorderLayout.CENTER);
			form.add(row);
			form.add(Box.createVerticalStrut(8));
		}

		// Amount spinner
		int maxRemaining = drop.requiredAmount - drop.currentAmount;
		if (maxRemaining < 1) maxRemaining = 1;
		SpinnerNumberModel amountModel = new SpinnerNumberModel(1, 1, maxRemaining, 1);
		JSpinner amountSpinner = new JSpinner(amountModel);
		JPanel amountRow = new JPanel(new BorderLayout(6, 0));
		amountRow.add(new JLabel("Amount:"), BorderLayout.WEST);
		amountRow.add(amountSpinner, BorderLayout.CENTER);
		form.add(amountRow);
		form.add(Box.createVerticalStrut(8));

		JLabel hint = new JLabel("<html><i>A screenshot will be taken & uploaded.</i></html>");
		hint.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		form.add(hint);

		dialog.add(form, BorderLayout.CENTER);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		JButton cancel = new JButton("Cancel");
		JButton submit = new JButton("Submit");
		cancel.addActionListener(e -> dialog.dispose());
		final JComboBox<PluginConfigResponse.ItemRequirement> fCombo = itemCombo;
		submit.addActionListener(e ->
		{
			int amount = (Integer) amountSpinner.getValue();
			Integer itemId = null;
			if (fCombo != null)
			{
				PluginConfigResponse.ItemRequirement sel = (PluginConfigResponse.ItemRequirement) fCombo.getSelectedItem();
				if (sel != null)
				{
					itemId = sel.itemId;
				}
			}
			plugin.submitManually(drop, amount, itemId);
			dialog.dispose();
			update();
		});
		buttons.add(cancel);
		buttons.add(submit);
		dialog.add(buttons, BorderLayout.SOUTH);

		dialog.pack();
		dialog.setLocationRelativeTo(parent);
		dialog.setVisible(true);
	}
}
