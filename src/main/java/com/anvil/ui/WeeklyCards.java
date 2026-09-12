package com.anvil.ui;

import com.anvil.ui.view.ScheduledView;
import com.anvil.ui.view.WeeklyView;
import com.anvil.ui.view.Clock;
import com.anvil.ui.view.Standing;
import java.awt.BorderLayout;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The two competition cards: a weekly that is running, and a bingo that is scheduled.
 *
 * <p>Both answer the same question in one card — what is it, when does the clock run out, and where
 * do you stand — but a competition has a leaderboard rather than a board to fill, so it shows a top
 * few and your own row rather than tile counts.</p>
 */
public final class WeeklyCards
{
	/** How many standings rows fit before the list stops being a glance. */
	private static final int WEEKLY_ROWS_SHOWN = 10;

	private WeeklyCards()
	{
	}

	// ---- Weekly competition card (SOTW / BOTW) -----------------------------------------------------

	/** The weekly's own summary: what it tracks, how long is left, and where you stand in it. */
	public static JPanel buildWeeklyCard(WeeklyView w)
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		panel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

		panel.add(SidebarChrome.leftLabel(w.title, FontManager.getRunescapeFont(), ColorScheme.TEXT_COLOR));
		panel.add(SidebarChrome.leftLabel(w.kindLabel() + " · " + w.metricLabel(),
			FontManager.getRunescapeSmallFont(), ColorScheme.LIGHT_GRAY_COLOR));

		String timing = Clock.timingLabel(w.upcoming, w.startDate, w.endDate);
		String headcount = w.participants > 0
			? w.participants + (w.participants == 1 ? " player" : " players") : null;
		String line = timing == null ? headcount : (headcount == null ? timing : timing + " · " + headcount);
		if (line != null)
		{
			panel.add(SidebarChrome.leftLabel(line, FontManager.getRunescapeSmallFont(), SidebarChrome.VALUE_COLOR));
		}

		panel.add(SidebarChrome.gap(4));
		// Nothing has been gained yet in a comp that hasn't started — say what it'll be instead of "#0".
		panel.add(w.upcoming
			? SidebarChrome.leftLabel("No standings until it starts.",
				FontManager.getRunescapeSmallFont(), ColorScheme.LIGHT_GRAY_COLOR)
			: SidebarChrome.leftLabel(yourStandingLine(w), FontManager.getRunescapeSmallFont(),
				w.yourRank > 0 ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR));

		if (w.url != null && !w.url.isEmpty())
		{
			// Nothing to stand in yet on an upcoming comp — link to the comp itself instead.
			panel.add(w.upcoming ? SidebarChrome.eventLink(w.url) : SidebarChrome.boardLink(w.url));
		}

		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
		return panel;
	}

	/**
	 * A bingo on the schedule that isn't yours: what it is, when it runs, how big, and a link to the
	 * site to sign up. No progress section — there's no board of yours to track yet.
	 */
	public static JPanel buildScheduledCard(ScheduledView s)
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		panel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

		panel.add(SidebarChrome.leftLabel(s.title, FontManager.getRunescapeFont(), ColorScheme.TEXT_COLOR));
		panel.add(SidebarChrome.leftLabel(s.kindLabel(), FontManager.getRunescapeSmallFont(), ColorScheme.LIGHT_GRAY_COLOR));

		String timing = Clock.timingLabel(!s.live, s.startDate, s.endDate);
		if (timing != null)
		{
			panel.add(SidebarChrome.leftLabel(timing, FontManager.getRunescapeSmallFont(),
				s.live ? ColorScheme.PROGRESS_COMPLETE_COLOR : SidebarChrome.VALUE_COLOR));
		}
		if (!s.sizeLabel().isEmpty())
		{
			panel.add(SidebarChrome.leftLabel(s.sizeLabel(), FontManager.getRunescapeSmallFont(), SidebarChrome.VALUE_COLOR));
		}

		panel.add(SidebarChrome.gap(4));
		panel.add(SidebarChrome.leftLabel(s.live ? "You're not enrolled in this one." : "Sign up on the site to take part.",
			FontManager.getRunescapeSmallFont(), ColorScheme.LIGHT_GRAY_COLOR));

		if (s.url != null && !s.url.isEmpty())
		{
			panel.add(SidebarChrome.eventLink(s.url));
		}

		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
		return panel;
	}

	/** "You: #3 · +1.2M xp", or an honest line when the caller isn't on the board (or it wouldn't load). */
	public static String yourStandingLine(WeeklyView w)
	{
		if (w.yourRank > 0)
		{
			return "You: #" + w.yourRank + " · +" + w.formatGain(w.yourGained);
		}
		return w.top.isEmpty() ? "Standings unavailable" : "You're not on the board yet";
	}

	/** The head of the weekly's leaderboard, with the caller's row spliced in when it's further down. */
	public static JPanel buildWeeklyStandings(WeeklyView w)
	{
		JPanel list = new JPanel();
		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(ColorScheme.DARK_GRAY_COLOR);
		list.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

		list.add(SidebarChrome.sectionHeader("Standings"));
		list.add(SidebarChrome.gap(6));

		if (w.top.isEmpty())
		{
			list.add(SidebarChrome.leftLabel("No one has scored yet.", FontManager.getRunescapeSmallFont(), SidebarChrome.VALUE_COLOR));
			list.setMaximumSize(new Dimension(Integer.MAX_VALUE, list.getPreferredSize().height));
			return list;
		}

		int shown = 0;
		for (Standing s : w.top)
		{
			if (shown >= WEEKLY_ROWS_SHOWN && !s.self)
			{
				continue;
			}
			// The caller's row is kept even when it ranks below the cut — mark the jump so #3 → #37 reads right.
			if (shown >= WEEKLY_ROWS_SHOWN)
			{
				list.add(SidebarChrome.gap(3));
				list.add(SidebarChrome.leftLabel("⋯", FontManager.getRunescapeSmallFont(), SidebarChrome.VALUE_COLOR));
			}
			list.add(SidebarChrome.gap(3));
			list.add(buildStandingRow(s, w));
			shown++;
		}

		list.setMaximumSize(new Dimension(Integer.MAX_VALUE, list.getPreferredSize().height));
		return list;
	}

	/** One standings row: rank, RSN, gain. The caller's row leads in gold like their own activity does. */
	public static JPanel buildStandingRow(Standing s, WeeklyView w)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

		JLabel rank = new JLabel(String.valueOf(s.rank));
		rank.setFont(FontManager.getRunescapeSmallFont());
		rank.setForeground(SidebarChrome.VALUE_COLOR);
		rank.setPreferredSize(new Dimension(22, rank.getPreferredSize().height));
		rank.setHorizontalAlignment(SwingConstants.RIGHT);

		JLabel name = new JLabel(SidebarChrome.plainText(SidebarChrome.ellipsize(s.rsn, 16)));
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(s.self ? ColorScheme.BRAND_ORANGE : ColorScheme.TEXT_COLOR);
		name.setToolTipText(SidebarChrome.plainText(s.rsn));

		JLabel gained = new JLabel("+" + w.formatGain(s.gained));
		gained.setFont(FontManager.getRunescapeSmallFont());
		gained.setForeground(s.self ? ColorScheme.BRAND_ORANGE : SidebarChrome.VALUE_COLOR);
		gained.setHorizontalAlignment(SwingConstants.RIGHT);

		row.add(rank, BorderLayout.WEST);
		row.add(name, BorderLayout.CENTER);
		row.add(gained, BorderLayout.EAST);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	/** " · ends in 2d 4h" / " · starts in 3d 1h" for the compact list row; "" when there's no usable date. */
	// ---- Component builders -----------------------------------------------------------------------

	/**
	 * One "Active now" row: tile name + a "who's on it" byline + a thin progress bar. Your own tasks lead in
	 * gold, teammate-only stay neutral. Value/label are JLabels (never painted inside the bar) for crisp text.
	 */
}
