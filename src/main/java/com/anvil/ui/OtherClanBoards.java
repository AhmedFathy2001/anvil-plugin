package com.anvil.ui;

import com.anvil.api.dto.ClanRef;
import java.awt.BorderLayout;
import javax.swing.Box;
import javax.swing.JProgressBar;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * "Also live" — the boards running in the member's OTHER clans.
 *
 * <p>Everything the merged view adds to the addressed clan's card. Clicking a row addresses that
 * clan, which moves where submissions file, so each row says what it is switching to rather than
 * reading as a filter.</p>
 *
 * <p>Which rows appear is {@link com.anvil.ui.view.BoardChoices#otherLiveBoards} — deduped by event
 * id rather than by name, because a co-hosted event belongs to every host and "Summer Bingo" is not
 * a rare enough name to dedup on.</p>
 */
public class OtherClanBoards
{
	private final SidebarDataSource dataSource;

	/** Address a clan and re-render. */
	private final java.util.function.Consumer<String> onChoose;

	public OtherClanBoards(SidebarDataSource dataSource, java.util.function.Consumer<String> onChoose)
	{
		this.dataSource = dataSource;
		this.onChoose = onChoose;
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
	public JPanel build()
	{
		List<ClanRef> others =
			com.anvil.ui.view.BoardChoices.otherLiveBoards(dataSource.clans(), dataSource.activeClan(), dataSource.addressedBoard());
		if (others.isEmpty())
		{
			return null;
		}

		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		panel.add(SidebarChrome.sectionHeader("Also live"));
		panel.add(SidebarChrome.gap(6));

		boolean first = true;
		for (ClanRef c : others)
		{
			if (!first)
			{
				panel.add(SidebarChrome.gap(6));
			}
			panel.add(buildRow(c));
			first = false;
		}
		return panel;
	}

	/** One "also live" row: whose board it is, what it is, and how far along. Click to go there. */
	private JPanel buildRow(ClanRef c)
	{
		String clanName = c.name == null || c.name.isEmpty() ? c.slug : c.name;

		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		card.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		card.setToolTipText("Switch to " + SidebarChrome.plainText(clanName) + " — the sidebar and your submissions follow");

		JPanel titleRow = new JPanel(new BorderLayout(6, 0));
		titleRow.setOpaque(false);
		titleRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
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

		card.add(SidebarChrome.leftLabel(SidebarChrome.ellipsize(c.live.eventName, BoardCards.CARD_LINE_CHARS),
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
			bar.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
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
				onChoose.accept(slug);
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
}
