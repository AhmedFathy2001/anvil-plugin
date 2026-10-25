package com.anvil;

import lombok.RequiredArgsConstructor;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.LongFunction;
import javax.swing.*;
import net.runelite.api.Skill;
import net.runelite.client.ui.ColorScheme;

import static com.anvil.PlayerCardView.header;
import static com.anvil.Ui.*;

/**
 * Two players side by side — you and whoever you looked up. A line per headline number with the
 * leader's in orange, then where each of you is ahead: skills by the size of the gap, the bosses
 * either of you kills most, and the log pages where your counts differ.
 */
final class CompareView
{
	private CompareView()
	{
	}

	static JPanel players(PlayerCard me, PlayerCard them, PlayerCardView.Icons icons)
	{
		PlayerCardView.Icons ic = icons == null ? PlayerCardView.Icons.NONE : icons;
		JPanel root = column();

		JPanel head = block();
		head.add(namesRow("You", them.rsn));
		head.add(gap(4));
		head.add(row("Total level", me.totalLevel, them.totalLevel, n -> String.format(Locale.ROOT, "%,d", n)));
		head.add(row("Combat", nz(me.combatLevel), nz(them.combatLevel), Long::toString));
		head.add(row("Total XP", nz(me.overallXp), nz(them.overallXp), PlayerCardView::compact));
		head.add(row("EHP", Math.round(nzd(me.ehp)), Math.round(nzd(them.ehp)), n -> n + "h"));
		head.add(row("EHB", Math.round(nzd(me.ehb)), Math.round(nzd(them.ehb)), n -> n + "h"));
		head.add(row("Log slots", me.clog == null ? -1 : me.clog.obtained, them.clog == null ? -1 : them.clog.obtained,
			n -> n < 0 ? "—" : String.format(Locale.ROOT, "%,d", n)));
		root.add(head);

		List<SkillDiff> skills = skillDiffs(me, them);
		if (!skills.isEmpty())
		{
			root.add(gap(6));
			JPanel block = block();
			int ahead = 0;
			int behind = 0;
			for (SkillDiff d : skills)
			{
				if (d.mine > d.theirs)
				{
					ahead++;
				}
				else if (d.theirs > d.mine)
				{
					behind++;
				}
			}
			block.add(header("Skills", "you lead " + ahead + " · they lead " + behind));
			block.add(gap(4));
			JPanel grid = left(new JPanel(new GridLayout(0, 2, 6, 2)));
			grid.setOpaque(false);
			for (SkillDiff d : skills)
			{
				JLabel cell = small(d.mine + " / " + d.theirs,
					d.mine > d.theirs ? ColorScheme.PROGRESS_COMPLETE_COLOR : d.theirs > d.mine ? ColorScheme.PROGRESS_ERROR_COLOR : MUTED);
				cell.setIconTextGap(6);
				cell.setToolTipText(d.skill.getName() + " — you " + d.mine + ", them " + d.theirs);
				if (ic.skills != null)
				{
					cell.setIcon(new ImageIcon(ic.skills.getSkillImage(d.skill, true)));
				}
				grid.add(cell);
			}
			block.add(grid);
			root.add(block);
		}

		List<long[]> bossRows = new ArrayList<>();
		List<String> bossNames = new ArrayList<>();
		bossDiffs(me, them, bossNames, bossRows);
		if (!bossRows.isEmpty())
		{
			root.add(gap(6));
			JPanel block = block();
			block.add(header("Bosses", "you / them"));
			block.add(gap(4));
			for (int i = 0; i < bossRows.size(); i++)
			{
				block.add(iconRow(bossNames.get(i), bossRows.get(i)[0], bossRows.get(i)[1], ic));
			}
			root.add(block);
		}

		List<String[]> pages = pageDiffs(me, them);
		if (!pages.isEmpty())
		{
			root.add(gap(6));
			JPanel block = block();
			block.add(header("Collection log", "pages that differ"));
			block.add(gap(4));
			for (String[] p : pages)
			{
				block.add(row(p[0], Long.parseLong(p[1]), Long.parseLong(p[2]), Long::toString));
			}
			root.add(block);
		}
		return root;
	}

	// ---- Rows --------------------------------------------------------------------------------------

	static JPanel namesRow(String left, String right)
	{
		return threeUp(new JLabel(), small(left, Color.WHITE), small(right, Color.WHITE));
	}

	/** "Label    a    b" with the bigger of the two in orange. Negative = unknown, never leads. */
	static JPanel row(String label, long a, long b, LongFunction<String> fmt)
	{
		Color lead = ColorScheme.BRAND_ORANGE;
		return threeUp(small(label, MUTED), small(fmt.apply(a), a >= 0 && a > b ? lead : Color.WHITE),
			small(fmt.apply(b), b >= 0 && b > a ? lead : Color.WHITE));
	}

	/** Three equal columns, the two figures right-aligned. */
	private static JPanel threeUp(JLabel first, JLabel l, JLabel r)
	{
		JPanel row = left(new JPanel(new GridLayout(1, 3, 6, 0)));
		row.setOpaque(false);
		row.add(first);
		l.setHorizontalAlignment(SwingConstants.RIGHT);
		r.setHorizontalAlignment(SwingConstants.RIGHT);
		row.add(l);
		row.add(r);
		return capH(row);
	}

	private static JPanel iconRow(String name, long a, long b, PlayerCardView.Icons ic)
	{
		JPanel row = Ui.row(6);
		JLabel icon = PlayerCardView.iconSlot(22, 20);
		PlayerCardView.bossIcon(icon, name, ic);
		row.add(icon, BorderLayout.WEST);
		row.add(row(name, a, b, n -> String.format(Locale.ROOT, "%,d", n)), BorderLayout.CENTER);
		return capH(row);
	}

	// ---- Diffs (package-private for tests) ---------------------------------------------------------

	@RequiredArgsConstructor
	static final class SkillDiff
	{
		final Skill skill;
		final int mine;
		final int theirs;

	}

	/** Every skill either of you has, biggest level gap first. */
	static List<SkillDiff> skillDiffs(PlayerCard me, PlayerCard them)
	{
		Map<String, Integer> a = levels(me);
		Map<String, Integer> b = levels(them);
		List<SkillDiff> out = new ArrayList<>();
		if (a.isEmpty() && b.isEmpty())
		{
			return out;
		}
		for (Skill s : PlayerCardView.SKILL_GRID)
		{
			String k = s.name().toLowerCase(Locale.ROOT);
			out.add(new SkillDiff(s, a.getOrDefault(k, 1), b.getOrDefault(k, 1)));
		}
		out.sort((x, y) -> Math.abs(y.mine - y.theirs) - Math.abs(x.mine - x.theirs));
		return out;
	}

	private static Map<String, Integer> levels(PlayerCard c)
	{
		Map<String, Integer> m = new HashMap<>();
		if (c.skills != null)
		{
			for (PlayerCard.Skill s : c.skills)
			{
				m.put(s.key.toLowerCase(Locale.ROOT), s.level);
			}
		}
		return m;
	}

	/** The ten bosses either of you has killed most, with both counts. */
	static void bossDiffs(PlayerCard me, PlayerCard them, List<String> names, List<long[]> rows)
	{
		Map<String, long[]> by = new HashMap<>();
		Map<String, String> label = new HashMap<>();
		for (int side = 0; side < 2; side++)
		{
			PlayerCard c = side == 0 ? me : them;
			if (c.bosses == null)
			{
				continue;
			}
			for (PlayerCard.Boss b : c.bosses)
			{
				by.computeIfAbsent(b.key, k -> new long[2])[side] = b.kc;
				label.putIfAbsent(b.key, b.label);
			}
		}
		by.entrySet().stream()
			.sorted((x, y) -> Long.compare(Math.max(y.getValue()[0], y.getValue()[1]), Math.max(x.getValue()[0], x.getValue()[1])))
			.limit(10)
			.forEach(e ->
			{
				names.add(label.get(e.getKey()));
				rows.add(e.getValue());
			});
	}

	/** Log pages where your counts differ, biggest gap first, at most eight: {name, mine, theirs}. */
	static List<String[]> pageDiffs(PlayerCard me, PlayerCard them)
	{
		Map<String, int[]> by = new HashMap<>();
		for (int side = 0; side < 2; side++)
		{
			PlayerCard c = side == 0 ? me : them;
			if (c.clogPages == null)
			{
				continue;
			}
			for (PlayerCard.Page p : c.clogPages)
			{
				by.computeIfAbsent(p.name, k -> new int[2])[side] = p.obtained;
			}
		}
		List<String[]> out = new ArrayList<>();
		by.entrySet().stream()
			.filter(e -> e.getValue()[0] != e.getValue()[1])
			.sorted((x, y) -> Math.abs(y.getValue()[0] - y.getValue()[1]) - Math.abs(x.getValue()[0] - x.getValue()[1]))
			.limit(8)
			.forEach(e -> out.add(new String[]{e.getKey(), Integer.toString(e.getValue()[0]), Integer.toString(e.getValue()[1])}));
		return out;
	}

	private static long nz(Number n)
	{
		return n == null ? -1 : n.longValue();
	}

	private static double nzd(Double d)
	{
		return d == null ? 0 : d;
	}
}
