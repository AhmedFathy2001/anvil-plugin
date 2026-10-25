package com.anvil;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.IntConsumer;
import javax.swing.*;
import net.runelite.api.Skill;
import net.runelite.client.hiscore.*;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.components.ProgressBar;

import static com.anvil.Ui.*;

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
	private GoalsView()
	{
	}

	static JPanel render(List<BingoApiClient.PlayerGoal> goals, Runnable onAdd, IntConsumer onRemove, String error)
	{
		JPanel block = block();

		JPanel head = header("Goals", null);
		JLabel add = onClick(small("+ Add goal", ColorScheme.BRAND_ORANGE), onAdd);
		add.setToolTipText("A skill level, a boss kill count, or a whole log page");
		head.add(add, BorderLayout.EAST);
		block.add(capH(head));

		if (error != null && !error.isEmpty())
		{
			block.add(gap(4));
			block.add(small(error, ColorScheme.PROGRESS_ERROR_COLOR));
		}

		if (goals == null || goals.isEmpty())
		{
			block.add(gap(6));
			block.add(small("Nothing set yet — 99 a skill, a KC to hit, a log page to finish.", MUTED));
			return block;
		}

		for (BingoApiClient.PlayerGoal g : goals)
		{
			block.add(gap(8));
			block.add(row(g, onRemove));
		}
		return block;
	}

	private static JPanel row(BingoApiClient.PlayerGoal g, IntConsumer onRemove)
	{
		JPanel row = left(new JPanel(new BorderLayout(0, 3)));
		row.setOpaque(false);

		JPanel line = Ui.row(6);
		line.add(small(g.label, g.done ? ColorScheme.PROGRESS_COMPLETE_COLOR : Color.WHITE), BorderLayout.CENTER);
		JPanel right = new JPanel(new GridLayout(1, 0, 6, 0));
		right.setOpaque(false);
		right.add(small(g.done ? "Done" : g.text, g.done ? ColorScheme.PROGRESS_COMPLETE_COLOR : MUTED));
		JLabel remove = onClick(new JLabel(new GlyphIcon(GlyphIcon.Shape.CLOSE, 8, MUTED)), () -> onRemove.accept(g.id));
		remove.setToolTipText("Remove this goal");
		line.add(right, BorderLayout.EAST);
		line.add(remove, BorderLayout.WEST);
		BingoApiClient.GoalDue due = g.done ? null : g.due;
		if (due != null)
		{
			JPanel top = new JPanel(new BorderLayout(0, 1));
			top.setOpaque(false);
			top.add(line, BorderLayout.NORTH);
			top.add(small(due.text, "overdue".equals(due.tone) ? ColorScheme.PROGRESS_ERROR_COLOR : MUTED), BorderLayout.SOUTH);
			row.add(top, BorderLayout.NORTH);
		}
		else
		{
			row.add(line, BorderLayout.NORTH);
		}

		if (g.current != null)
		{
			ProgressBar bar = new ProgressBar();
			bar.setMaximumValue((int) Math.min(Integer.MAX_VALUE, Math.max(1, g.of)));
			bar.setValue((int) Math.min(Integer.MAX_VALUE, Math.min(g.current, g.of)));
			bar.setLeftLabel("");
			bar.setRightLabel("");
			bar.setCenterLabel("");
			bar.setForeground(g.done ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.BRAND_ORANGE);
			bar.setBackground(ColorScheme.DARK_GRAY_COLOR);
			bar.setPreferredSize(new Dimension(0, 4));
			row.add(bar, BorderLayout.SOUTH);
		}
		return capH(row);
	}

	// ---- Progress (package-private for tests) -----------------------------------------------------

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
				what.setModel(new DefaultComboBoxModel<>(bosses.toArray(new String[0])));
				target.setModel(new SpinnerNumberModel(100, 1, 100_000, 50));
				targetLabel.setText("Kill count");
				target.setEnabled(true);
			}
			else if ("Log page".equals(k))
			{
				what.setModel(new DefaultComboBoxModel<>(pages.toArray(new String[0])));
				targetLabel.setText("Every slot");
				target.setEnabled(false);
			}
			else
			{
				what.setModel(new DefaultComboBoxModel<>(skills.toArray(new String[0])));
				target.setModel(new SpinnerNumberModel(99, 2, 99, 1));
				targetLabel.setText("Level");
				target.setEnabled(true);
			}
		});

		// A deadline is optional, so it's behind a tick: an always-on date field would set one by accident.
		JCheckBox hasDue = new JCheckBox("Set a deadline");
		Calendar inAMonth = Calendar.getInstance();
		inAMonth.add(Calendar.MONTH, 1);
		JSpinner due = new JSpinner(new SpinnerDateModel(inAMonth.getTime(), new Date(), null,
			Calendar.DAY_OF_MONTH));
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
			? new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format((Date) due.getValue())
			: null;
		return new NewGoal(wireKind, metric, t, dueOn);
	}
}
