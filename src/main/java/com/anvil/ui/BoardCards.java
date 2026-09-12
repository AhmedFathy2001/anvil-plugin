package com.anvil.ui;

import com.anvil.ui.view.ScheduledView;
import com.anvil.ui.view.WeeklyView;
import javax.swing.Box;
import javax.swing.JProgressBar;
import com.anvil.ui.view.BoardChoices;
import com.anvil.ui.view.Clock;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The cards that say what a board IS: one clan's summary, and one row per event it runs.
 *
 * <p>A ladder is its missions board, so {@link LadderCard} replaces the summary outright. A bingo
 * that merely carries missions keeps its tile-count summary and gets the mission strip below it.</p>
 */
public class BoardCards
{
	/** Roughly what fits on one small-font line at the panel's width — cards clip to it. */
	static final int CARD_LINE_CHARS = 30;

	public JPanel buildSummary(ConnectionView c)
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
		panel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

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

	/** One row of the events list: name, kind, a one-line status, and (for a board) its progress bar. */
	public JPanel buildEventCard(ConnectionView c, BoardChoices.EventEntry entry)
	{
		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		card.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		card.setToolTipText(SidebarChrome.plainText(entry.title));

		// Title + a chevron marking the row as a drill-in.
		JPanel titleRow = new JPanel(new BorderLayout(6, 0));
		titleRow.setOpaque(false);
		titleRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
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
				bar.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
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
				onSelect.accept(entry.key);
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
	public static String boardStatusLine(ConnectionView c)
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
	public JLabel backLink()
	{
		JLabel back = new JLabel("‹ All events");
		back.setFont(FontManager.getRunescapeSmallFont());
		back.setForeground(ColorScheme.BRAND_ORANGE);
		back.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		back.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		back.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				onSelect.accept(null);
			}
		});
		return back;
	}

	private final LadderCard ladderCard;

	/** Drill into one event, or back out to the list when handed null. */
	private final java.util.function.Consumer<String> onSelect;

	public BoardCards(LadderCard ladderCard, java.util.function.Consumer<String> onSelect)
	{
		this.ladderCard = ladderCard;
		this.onSelect = onSelect;
	}
}
