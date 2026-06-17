package com.osrsbingo;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;

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

		// Footer with Refresh button
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

	/**
	 * The side panel is admin-only — it mounts only while the plugin is admin-linked. It now shows
	 * just the admin tools (link / clan sync / unlink) plus a connection status; the member-facing
	 * event, schedule, drop-tracking and weekly views live in the in-game collection log "Anvil" tab.
	 */
	private void rebuild()
	{
		contentPanel.removeAll();

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

		renderAdminSection();
		updateStatusLabel();

		contentPanel.revalidate();
		contentPanel.repaint();
	}

	/** Header connection status — admin-oriented, no player guidance text. */
	private void updateStatusLabel()
	{
		PluginConfigResponse cfg = plugin.getPluginConfig();
		if (cfg != null && cfg.event != null)
		{
			boolean stale = plugin.isLastRefreshFailed();
			statusLabel.setText(stale ? "Stale" : "Connected");
			statusLabel.setForeground(stale ? new Color(255, 200, 0) : new Color(0, 200, 83));
		}
		else if (plugin.hasAdminToken())
		{
			statusLabel.setText("Admin");
			statusLabel.setForeground(new Color(0, 200, 83));
		}
		else
		{
			statusLabel.setText("Disconnected");
			statusLabel.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		}
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

	private void renderAdminSection()
	{
		addSection("Admin link");
		if (plugin.hasAdminToken())
		{
			addLabel("Admin linked", new Color(0, 200, 83), true);
			// The token works from any character on the account, so show whoever's logged in now
			// rather than a single stored RSN (handy when one person plays several accounts).
			String rsn = plugin.getLocalPlayerName();
			addLabel(rsn == null || rsn.isEmpty() ? "Log in to a character" : "Playing as " + rsn,
				ColorScheme.LIGHT_GRAY_COLOR, false);

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

	private static String formatRelative(long timestampMs)
	{
		long delta = System.currentTimeMillis() - timestampMs;
		if (delta < 60_000) return "just now";
		if (delta < 3_600_000) return (delta / 60_000) + "m ago";
		if (delta < 86_400_000) return (delta / 3_600_000) + "h ago";
		return (delta / 86_400_000) + "d ago";
	}

}
