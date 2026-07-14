package com.anvil;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
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

	private final SidebarDataSource dataSource;

	// Header controls (persistent across state changes).
	private final JComboBox<ConnectionView> clanFilter = new JComboBox<>();
	private final JButton refreshButton = new JButton("Refresh");
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
		header.add(titleRow, BorderLayout.NORTH);

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

		JLabel nearestHeader = new JLabel("Nearest tiles");
		nearestHeader.setFont(FontManager.getRunescapeSmallFont());
		nearestHeader.setForeground(ColorScheme.BRAND_ORANGE);
		nearestHeader.setAlignmentX(LEFT_ALIGNMENT);
		body.add(nearestHeader);
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

	private JPanel buildSummary(ConnectionView c)
	{
		JPanel panel = new JPanel(new BorderLayout(0, 4));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		panel.setAlignmentX(LEFT_ALIGNMENT);
		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));

		String eventLine = c.eventName == null || c.eventName.isEmpty() ? c.clanName : c.eventName;
		JLabel event = new JLabel(eventLine);
		event.setFont(FontManager.getRunescapeFont());
		event.setForeground(ColorScheme.TEXT_COLOR);
		panel.add(event, BorderLayout.NORTH);

		JProgressBar bar = new JProgressBar(0, 100);
		bar.setValue(c.completionPercent());
		bar.setStringPainted(true);
		bar.setString(c.tilesComplete + " / " + c.tilesTotal + " tiles (" + c.completionPercent() + "%)");
		bar.setForeground(c.completionPercent() >= 100
			? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.BRAND_ORANGE);
		bar.setBackground(ColorScheme.DARK_GRAY_COLOR);
		bar.setBorderPainted(false);
		panel.add(bar, BorderLayout.CENTER);

		return panel;
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

		JLabel name = new JLabel(tile.name);
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
		JLabel warn = new JLabel(message);
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
				setText(c.hasError() ? c.clanName + "  (!)" : c.clanName);
				setFont(getFont().deriveFont(Font.PLAIN));
			}
			return this;
		}
	}
}
