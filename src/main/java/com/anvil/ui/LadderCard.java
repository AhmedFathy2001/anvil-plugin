package com.anvil.ui;

import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import com.anvil.detect.LadderMissions;
import com.anvil.ui.view.Ladder;
import java.awt.Color;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The DMM-All-Stars-style missions board for a ladder event.
 *
 * <p>Your rank, a live per-second countdown to the next drop, and the missions currently open with a
 * grow/decay value each. It replaces the tile-count summary rather than sitting under it — a rotating
 * daily ladder has no fixed board to count.</p>
 *
 * <h2>Why it holds references to its own labels</h2>
 *
 * <p>A mission's value moves every second, and the countdown with it. Re-fetching the board once a
 * second to show that would be absurd, so the card keeps the labels it built and a one-second Swing
 * timer writes into them directly. Exactly one card is on screen at a time, so {@link #clearRefs()}
 * runs on every render that is NOT a ladder card — otherwise the tick keeps updating labels that are
 * no longer visible.</p>
 */
public class LadderCard
{
	// --- Ladder missions board (DMM-All-Stars style) --------------------------------------------
	/** How long a card pulses gold after a new mission / claim (signalled from the plugin off-EDT). */
	private static final int LADDER_FLASH_MS = 4000;
	private static final Color LADDER_FLASH_COLOR = ColorScheme.BRAND_ORANGE;
	/** Ticks the live countdown + per-mission grow/decay value once a second, with NO refetch. */
	private final Timer ladderTick = new Timer(1000, e -> tickLadder());
	/** The currently-rendered ladder card's data (countdown target, decay, missions), or null. */
	private Ladder ladderState;
	/** Held label refs for the rendered ladder card so the tick updates them in place. */
	private JLabel ladderCountdownLabel;
	private final List<LadderValueLabel> ladderValueLabels = new ArrayList<>();
	private JPanel ladderCardPanel;
	/** Wall-clock (ms) until which the card pulses; written off-EDT by {@link #flashLadder()}. */
	private volatile long ladderFlashUntil;
	/** True while the card currently shows a coloured (flash) border — lets the tick reset it once. */
	private boolean ladderFlashPainted;
	public void clearRefs()
	{
		ladderState = null;
		ladderCountdownLabel = null;
		ladderCardPanel = null;
		ladderValueLabels.clear();
	}
	/**
	 * One open mission: label on the left, live grow/decay value on the right. Shared by the ladder
	 * card and the bingo mission strip so both age their values on the same per-second tick — the
	 * label is registered with {@link #ladderValueLabels} either way.
	 */
	private JPanel buildMissionRow(Ladder.Mission m, Ladder l, long now)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

		JLabel name = new JLabel(SidebarChrome.plainText(SidebarChrome.ellipsize(m.label, 22)));
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
	public JPanel buildMissionStrip(Ladder l)
	{
		final long now = System.currentTimeMillis();
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		panel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

		JLabel countdown = SidebarChrome.leftLabel(countdownText(l, now), FontManager.getRunescapeBoldFont(), ColorScheme.BRAND_ORANGE);
		panel.add(countdown);
		panel.add(SidebarChrome.gap(4));
		for (Ladder.Mission m : l.missions)
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
	/** Run the per-second tick while the panel is on screen — and only while it is. */
	public void startTicking()
	{
		ladderTick.start();
	}

	public void stopTicking()
	{
		ladderTick.stop();
	}

	public JPanel buildCard(ConnectionView c)
	{
		Ladder l = c.ladder;
		final long now = System.currentTimeMillis();

		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		panel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

		String eventLine = c.eventName == null || c.eventName.isEmpty() ? c.clanName : c.eventName;
		panel.add(SidebarChrome.leftLabel(eventLine, FontManager.getRunescapeFont(), ColorScheme.TEXT_COLOR));
		panel.add(SidebarChrome.leftLabel(rankLine(l), FontManager.getRunescapeSmallFont(), SidebarChrome.VALUE_COLOR));
		panel.add(SidebarChrome.gap(6));

		JLabel countdown = SidebarChrome.leftLabel(countdownText(l, now), FontManager.getRunescapeBoldFont(), ColorScheme.BRAND_ORANGE);
		panel.add(countdown);
		panel.add(SidebarChrome.gap(6));

		if (l.missions.isEmpty())
		{
			panel.add(SidebarChrome.leftLabel("Waiting for the next mission…", FontManager.getRunescapeSmallFont(), ColorScheme.LIGHT_GRAY_COLOR));
		}
		else
		{
			panel.add(SidebarChrome.leftLabel("Active missions", FontManager.getRunescapeSmallFont(), ColorScheme.LIGHT_GRAY_COLOR));
			panel.add(SidebarChrome.gap(2));
			for (Ladder.Mission m : l.missions)
			{
				panel.add(buildMissionRow(m, l, now));
			}
		}

		if (c.boardUrl != null && !c.boardUrl.isEmpty())
		{
			panel.add(SidebarChrome.gap(6));
			panel.add(SidebarChrome.boardLink(c.boardUrl));
		}

		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));

		// Bind the tick to this card.
		ladderState = l;
		ladderCountdownLabel = countdown;
		ladderCardPanel = panel;
		return panel;
	}

	/** "You: #4 this month · #12 all-time", or an encouraging line when the caller hasn't scored yet. */
	private static String rankLine(Ladder l)
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
	private static String countdownText(Ladder l, long now)
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
			return SidebarChrome.VALUE_COLOR;
		}
		return current > face ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.BRAND_ORANGE;
	}

	/** Live per-second refresh of the shown ladder card: the countdown, each mission's value, the flash. */
	private void tickLadder()
	{
		final long now = System.currentTimeMillis();
		Ladder l = ladderState;
		if (l != null)
		{
			if (ladderCountdownLabel != null)
			{
				ladderCountdownLabel.setText(SidebarChrome.plainText(countdownText(l, now)));
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
	public void flash()
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
}
