package com.anvil;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import net.runelite.api.Skill;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.hiscore.HiscoreSkillType;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.components.ProgressBar;

/**
 * The You tab's goals: "99 Slayer", "500 Vorkath", "Barrows log complete".
 *
 * <p>Only the target is stored (site {@code player_goals}); progress is read from the same player
 * card the tab already shows, so a goal costs no polling and always agrees with the numbers above
 * it. A goal the card can't measure yet (no stats tracked, log not synced) says so instead of
 * showing a zero.</p>
 */
final class GoalsView
{
	private static final Color MUTED = new Color(0x98_98_98);

	private GoalsView()
	{
	}

	/** Where a goal stands: {@code current} of {@code target} in the goal's own unit. */
	static final class Progress
	{
		final long current;
		final long target;
		/** Null when the card has nothing to measure it with. */
		final String text;
		final boolean known;

		Progress(long current, long target, String text, boolean known)
		{
			this.current = current;
			this.target = target;
			this.text = text;
			this.known = known;
		}

		boolean done()
		{
			return known && current >= target;
		}
	}

	static JPanel render(List<BingoApiClient.PlayerGoal> goals, PlayerCard card, Runnable onAdd, IntConsumer onRemove,
		String error)
	{
		JPanel block = new JPanel();
		block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
		block.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		block.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		block.setAlignmentX(JComponent.LEFT_ALIGNMENT);

		JPanel head = new JPanel(new BorderLayout());
		head.setOpaque(false);
		head.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		JLabel title = new JLabel("Goals");
		title.setFont(FontManager.getRunescapeSmallFont());
		title.setForeground(ColorScheme.BRAND_ORANGE);
		head.add(title, BorderLayout.WEST);
		JLabel add = new JLabel("+ Add goal");
		add.setFont(FontManager.getRunescapeSmallFont());
		add.setForeground(ColorScheme.BRAND_ORANGE);
		add.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		add.setToolTipText("A skill level, a boss kill count, or a whole log page");
		add.addMouseListener(click(onAdd));
		head.add(add, BorderLayout.EAST);
		head.setMaximumSize(new Dimension(Integer.MAX_VALUE, head.getPreferredSize().height));
		block.add(head);

		if (error != null && !error.isEmpty())
		{
			block.add(Box.createVerticalStrut(4));
			block.add(small(error, ColorScheme.PROGRESS_ERROR_COLOR));
		}

		if (goals == null || goals.isEmpty())
		{
			block.add(Box.createVerticalStrut(6));
			block.add(small("Nothing set yet — 99 a skill, a KC to hit, a log page to finish.", MUTED));
			return block;
		}

		for (BingoApiClient.PlayerGoal g : goals)
		{
			block.add(Box.createVerticalStrut(8));
			block.add(row(g, progress(g, card), onRemove));
		}
		return block;
	}

	private static JPanel row(BingoApiClient.PlayerGoal g, Progress p, IntConsumer onRemove)
	{
		JPanel row = new JPanel(new BorderLayout(0, 3));
		row.setOpaque(false);
		row.setAlignmentX(JComponent.LEFT_ALIGNMENT);

		JPanel line = new JPanel(new BorderLayout(6, 0));
		line.setOpaque(false);
		line.add(small(label(g), p.done() ? ColorScheme.PROGRESS_COMPLETE_COLOR : Color.WHITE), BorderLayout.CENTER);
		JPanel right = new JPanel(new GridLayout(1, 0, 6, 0));
		right.setOpaque(false);
		right.add(small(p.done() ? "Done" : p.text, p.done() ? ColorScheme.PROGRESS_COMPLETE_COLOR : MUTED));
		JLabel remove = new JLabel(new GlyphIcon(GlyphIcon.Shape.CLOSE, 8, MUTED));
		remove.setToolTipText("Remove this goal");
		remove.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		remove.addMouseListener(click(() -> onRemove.accept(g.id)));
		line.add(right, BorderLayout.EAST);
		line.add(remove, BorderLayout.WEST);
		String due = p.done() ? null : dueText(g.dueOn, java.time.LocalDate.now(java.time.ZoneOffset.UTC));
		if (due != null)
		{
			JPanel top = new JPanel(new BorderLayout(0, 1));
			top.setOpaque(false);
			top.add(line, BorderLayout.NORTH);
			top.add(small(due, due.startsWith("overdue") ? ColorScheme.PROGRESS_ERROR_COLOR : MUTED), BorderLayout.SOUTH);
			row.add(top, BorderLayout.NORTH);
		}
		else
		{
			row.add(line, BorderLayout.NORTH);
		}

		if (p.known)
		{
			ProgressBar bar = new ProgressBar();
			bar.setMaximumValue((int) Math.min(Integer.MAX_VALUE, Math.max(1, p.target)));
			bar.setValue((int) Math.min(Integer.MAX_VALUE, Math.min(p.current, p.target)));
			bar.setLeftLabel("");
			bar.setRightLabel("");
			bar.setCenterLabel("");
			bar.setForeground(p.done() ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.BRAND_ORANGE);
			bar.setBackground(ColorScheme.DARK_GRAY_COLOR);
			bar.setPreferredSize(new Dimension(0, 4));
			row.add(bar, BorderLayout.SOUTH);
		}
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	// ---- Progress (package-private for tests) -----------------------------------------------------

	/** "99 Slayer", "500 Vorkath", "Barrows Chests log". */
	static String label(BingoApiClient.PlayerGoal g)
	{
		switch (g.kind)
		{
			case "skill":
				return g.target + " " + titleCase(g.metric);
			case "boss":
				return String.format(Locale.ROOT, "%,d %s", g.target, humanise(g.metric));
			default:
				return g.metric + " log";
		}
	}

	static Progress progress(BingoApiClient.PlayerGoal g, PlayerCard card)
	{
		if ("skill".equals(g.kind))
		{
			PlayerCard.Skill s = card == null || card.skills == null ? null
				: card.skills.stream().filter(x -> g.metric.equalsIgnoreCase(x.key)).findFirst().orElse(null);
			if (s == null)
			{
				return new Progress(0, 1, "no stats yet", false);
			}
			long need = xpForLevel(g.target);
			int level = levelForXp(s.xp);
			return new Progress(s.xp, need, "Lv " + level + " / " + g.target, true);
		}
		if ("boss".equals(g.kind))
		{
			if (card == null || card.skills == null || card.skills.isEmpty())
			{
				return new Progress(0, g.target, "no stats yet", false);
			}
			int kc = card.bosses == null ? 0 : card.bosses.stream().filter(b -> g.metric.equalsIgnoreCase(b.key))
				.mapToInt(b -> b.kc).findFirst().orElse(0);
			return new Progress(kc, g.target, String.format(Locale.ROOT, "%,d / %,d", kc, g.target), true);
		}
		PlayerCard.Page page = card == null || card.clogPages == null ? null
			: card.clogPages.stream().filter(p -> g.metric.equalsIgnoreCase(p.name)).findFirst().orElse(null);
		if (page == null)
		{
			return new Progress(0, 1, "sync your log", false);
		}
		return new Progress(page.obtained, page.total, page.obtained + " / " + page.total, true);
	}

	/**
	 * "by 1 Dec · 50d left", "due today", "overdue by 3d" — calendar days, as the site counts them.
	 * Null when there's no deadline (or it can't be read).
	 */
	static String dueText(String dueOn, java.time.LocalDate today)
	{
		if (dueOn == null || dueOn.isEmpty())
		{
			return null;
		}
		java.time.LocalDate due;
		try
		{
			due = java.time.LocalDate.parse(dueOn);
		}
		catch (java.time.format.DateTimeParseException e)
		{
			return null;
		}
		long days = java.time.temporal.ChronoUnit.DAYS.between(today, due);
		if (days < 0)
		{
			return "overdue by " + (-days) + "d";
		}
		if (days == 0)
		{
			return "due today";
		}
		String when = due.format(java.time.format.DateTimeFormatter.ofPattern(
			due.getYear() == today.getYear() ? "d MMM" : "d MMM yyyy", Locale.ENGLISH));
		return "by " + when + " · " + days + "d left";
	}

	/** The game's XP table: XP needed for {@code level}, 1–99. */
	static long xpForLevel(int level)
	{
		double points = 0;
		for (int l = 1; l < level; l++)
		{
			points += Math.floor(l + 300.0 * Math.pow(2.0, l / 7.0));
		}
		return (long) Math.floor(points / 4);
	}

	static int levelForXp(long xp)
	{
		for (int level = 99; level >= 1; level--)
		{
			if (xp >= xpForLevel(level))
			{
				return level;
			}
		}
		return 1;
	}

	/** "alchemicalHydra" → "Alchemical Hydra". */
	static String humanise(String key)
	{
		if (key == null || key.isEmpty())
		{
			return "";
		}
		String spaced = key.replaceAll("([a-z])([A-Z])", "$1 $2");
		return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
	}

	private static String titleCase(String s)
	{
		return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	// ---- New-goal dialog ---------------------------------------------------------------------------

	/** What the member picked: kind, the name the site matches, and the target. */
	static final class NewGoal
	{
		final String kind;
		final String metric;
		final int target;
		/** YYYY-MM-DD, or null for no deadline. */
		final String dueOn;

		NewGoal(String kind, String metric, int target, String dueOn)
		{
			this.kind = kind;
			this.metric = metric;
			this.target = target;
			this.dueOn = dueOn;
		}
	}

	/** Ask for a goal. Null when cancelled. Log pages are offered only once the log is synced. */
	static NewGoal ask(Component parent, PlayerCard card)
	{
		List<String> kinds = new ArrayList<>();
		kinds.add("Skill level");
		kinds.add("Boss kills");
		List<String> pages = new ArrayList<>();
		if (card != null && card.clogPages != null)
		{
			card.clogPages.forEach(p -> pages.add(p.name));
		}
		if (!pages.isEmpty())
		{
			kinds.add("Log page");
		}

		List<String> skills = new ArrayList<>();
		for (Skill s : PlayerCardView.SKILL_GRID)
		{
			skills.add(s.getName());
		}
		List<String> bosses = new ArrayList<>();
		for (HiscoreSkill h : HiscoreSkill.values())
		{
			if (h.getType() == HiscoreSkillType.BOSS)
			{
				bosses.add(h.getName());
			}
		}
		bosses.sort(String.CASE_INSENSITIVE_ORDER);

		JComboBox<String> kind = new JComboBox<>(kinds.toArray(new String[0]));
		JComboBox<String> what = new JComboBox<>(skills.toArray(new String[0]));
		SpinnerNumberModel model = new SpinnerNumberModel(99, 2, 99, 1);
		JSpinner target = new JSpinner(model);
		JLabel targetLabel = new JLabel("Level");

		kind.addActionListener(e ->
		{
			String k = (String) kind.getSelectedItem();
			if ("Boss kills".equals(k))
			{
				what.setModel(new javax.swing.DefaultComboBoxModel<>(bosses.toArray(new String[0])));
				target.setModel(new SpinnerNumberModel(100, 1, 100_000, 50));
				targetLabel.setText("Kill count");
				target.setEnabled(true);
			}
			else if ("Log page".equals(k))
			{
				what.setModel(new javax.swing.DefaultComboBoxModel<>(pages.toArray(new String[0])));
				targetLabel.setText("Every slot");
				target.setEnabled(false);
			}
			else
			{
				what.setModel(new javax.swing.DefaultComboBoxModel<>(skills.toArray(new String[0])));
				target.setModel(new SpinnerNumberModel(99, 2, 99, 1));
				targetLabel.setText("Level");
				target.setEnabled(true);
			}
		});

		// A deadline is optional, so it's behind a tick: an always-on date field would set one by accident.
		javax.swing.JCheckBox hasDue = new javax.swing.JCheckBox("Set a deadline");
		java.util.Calendar inAMonth = java.util.Calendar.getInstance();
		inAMonth.add(java.util.Calendar.MONTH, 1);
		JSpinner due = new JSpinner(new javax.swing.SpinnerDateModel(inAMonth.getTime(), new java.util.Date(), null,
			java.util.Calendar.DAY_OF_MONTH));
		due.setEditor(new JSpinner.DateEditor(due, "d MMM yyyy"));
		due.setEnabled(false);
		hasDue.addActionListener(e -> due.setEnabled(hasDue.isSelected()));

		JPanel form = new JPanel(new GridLayout(0, 1, 0, 4));
		form.add(new JLabel("Goal"));
		form.add(kind);
		form.add(what);
		form.add(targetLabel);
		form.add(target);
		form.add(hasDue);
		form.add(due);

		int choice = JOptionPane.showConfirmDialog(parent, form, "New goal", JOptionPane.OK_CANCEL_OPTION,
			JOptionPane.PLAIN_MESSAGE);
		if (choice != JOptionPane.OK_OPTION || what.getSelectedItem() == null)
		{
			return null;
		}
		String k = (String) kind.getSelectedItem();
		String wireKind = "Boss kills".equals(k) ? "boss" : "Log page".equals(k) ? "clog" : "skill";
		String metric = (String) what.getSelectedItem();
		if ("skill".equals(wireKind))
		{
			metric = metric.toLowerCase(Locale.ROOT);
		}
		int t = "clog".equals(wireKind) ? 0 : ((Number) target.getValue()).intValue();
		String dueOn = hasDue.isSelected()
			? new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format((java.util.Date) due.getValue())
			: null;
		return new NewGoal(wireKind, metric, t, dueOn);
	}

	// ---- Pieces ------------------------------------------------------------------------------------

	private static JLabel small(String text, Color color)
	{
		JLabel l = new JLabel(AnvilSidebarPanel.plainText(text));
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(color);
		l.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		return l;
	}

	private static MouseAdapter click(Runnable r)
	{
		return new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				r.run();
			}
		};
	}
}
