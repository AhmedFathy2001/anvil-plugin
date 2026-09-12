package com.anvil.ui;

import java.awt.Insets;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import javax.swing.JProgressBar;
import com.anvil.clog.model.TaskRow;
import com.anvil.ui.view.ActiveTask;
import com.anvil.ui.view.TileProgressView;
import java.awt.Color;
import java.awt.Dimension;
import java.util.List;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The rows that say how far along something is.
 *
 * <p>"Active now" (what this account is working on, and who else is on it), the team activity feed,
 * and a single tile's progress. Your own tasks lead in gold; teammate-only ones stay neutral.</p>
 *
 * <p>Values and labels are real {@link JLabel}s beside the bar rather than text painted inside it —
 * a bitmap font drawn over a progress fill is unreadable at this size.</p>
 */
public final class ProgressRows
{
	static final int PROGRESS_BAR_HEIGHT = 6;

	/** How many feed rows fit before the list stops being a glance. */
	private static final int ACTIVITY_ROWS_SHOWN = 12;

	private ProgressRows()
	{
	}

	public static JPanel buildActiveRow(ActiveTask task)
	{
		TaskRow tile = task.tile;
		Color accent = task.includesSelf ? ColorScheme.BRAND_ORANGE : ColorScheme.TEXT_COLOR;

		JPanel row = new JPanel(new GridBagLayout());
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

		GridBagConstraints gbc = new GridBagConstraints();
		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.weightx = 1;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.anchor = GridBagConstraints.WEST;

		JLabel name = new JLabel(SidebarChrome.plainText(SidebarChrome.ellipsize(tile.label, 24)));
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(accent);
		name.setToolTipText(SidebarChrome.plainText(tile.label));
		row.add(name, gbc);

		JLabel value = new JLabel(progressValue(tile));
		value.setFont(FontManager.getRunescapeSmallFont());
		value.setForeground(SidebarChrome.VALUE_COLOR);
		gbc.gridx = 1;
		gbc.weightx = 0;
		gbc.fill = GridBagConstraints.NONE;
		gbc.anchor = GridBagConstraints.EAST;
		row.add(value, gbc);

		JLabel who = new JLabel(SidebarChrome.plainText(task.workersLabel()));
		who.setFont(FontManager.getRunescapeSmallFont());
		who.setForeground(task.includesSelf ? ColorScheme.BRAND_ORANGE : SidebarChrome.VALUE_COLOR);
		gbc.gridx = 0;
		gbc.gridy = 1;
		gbc.gridwidth = 2;
		gbc.weightx = 1;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.anchor = GridBagConstraints.WEST;
		gbc.insets = new Insets(1, 0, 0, 0);
		row.add(who, gbc);

		JProgressBar bar = new JProgressBar(0, 100);
		bar.setValue(tile.goal > 0 ? Math.min(100, (int) Math.round(tile.current * 100.0 / tile.goal)) : 0);
		bar.setStringPainted(false);
		bar.setBorderPainted(false);
		bar.setForeground(task.includesSelf ? ColorScheme.BRAND_ORANGE : ColorScheme.PROGRESS_INPROGRESS_COLOR);
		bar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		bar.setPreferredSize(new Dimension(0, PROGRESS_BAR_HEIGHT));
		gbc.gridy = 2;
		gbc.insets = new Insets(3, 0, 0, 0);
		row.add(bar, gbc);

		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	/** "1.5M / 2M", "248 / 500", or "" for an untargeted tile — big numbers abbreviated for the narrow panel. */
	private static String progressValue(TaskRow tile)
	{
		return tile.goal > 0 ? formatCount(tile.current) + " / " + formatCount(tile.goal) : "";
	}

	/** Compact count: 1_507_300 → "1.5M", 2_000_000 → "2M", 15_000 → "15K", 500 → "500". */
	// One definition, shared with WeeklyView's gain formatting (which also has to know
	// about EHP/EHB milli-hours) so the card and the standings rows can't drift apart.
	private static String formatCount(long n)
	{
		return ConnectionView.formatCount(n);
	}

	/** The team activity feed — one colored line per event (newest first), capped so the panel stays glanceable. */
	public static JPanel buildActivityFeed(List<ActivityEntry> entries)
	{
		JPanel list = new JPanel();
		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(ColorScheme.DARK_GRAY_COLOR);
		list.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

		int shown = 0;
		for (ActivityEntry e : entries)
		{
			if (shown >= ACTIVITY_ROWS_SHOWN)
			{
				break;
			}
			if (shown > 0)
			{
				list.add(SidebarChrome.gap(3));
			}
			list.add(buildActivityRow(e));
			shown++;
		}
		if (entries.size() > ACTIVITY_ROWS_SHOWN)
		{
			list.add(SidebarChrome.gap(3));
			JLabel more = new JLabel("+" + (entries.size() - ACTIVITY_ROWS_SHOWN) + " more");
			more.setFont(FontManager.getRunescapeSmallFont());
			more.setForeground(SidebarChrome.VALUE_COLOR);
			more.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
			list.add(more);
		}
		list.setMaximumSize(new Dimension(Integer.MAX_VALUE, list.getPreferredSize().height));
		return list;
	}

	public static JLabel buildActivityRow(ActivityEntry e)
	{
		JLabel row = new JLabel(SidebarChrome.plainText(SidebarChrome.ellipsize(e.summary(), 36)));
		row.setFont(FontManager.getRunescapeSmallFont());
		row.setToolTipText(SidebarChrome.plainText(e.summary()));
		row.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
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
	public static JPanel buildTileRow(TileProgressView tile)
	{
		JPanel row = new JPanel(new GridBagLayout());
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

		GridBagConstraints gbc = new GridBagConstraints();
		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.weightx = 1;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.anchor = GridBagConstraints.WEST;

		JLabel name = new JLabel(SidebarChrome.plainText(tile.name));
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(tile.complete ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.TEXT_COLOR);
		row.add(name, gbc);

		JLabel value = new JLabel(progressText(tile));
		value.setFont(FontManager.getRunescapeSmallFont());
		value.setForeground(SidebarChrome.VALUE_COLOR);
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
		gbc.insets = new Insets(3, 0, 0, 0);
		row.add(bar, gbc);

		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	/** "c / t" for counted tiles, "42%" for very large targets (XP), or "Done"/"—". */
	private static String progressText(TileProgressView tile)
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
}
