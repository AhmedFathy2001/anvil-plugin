package com.anvil;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import net.runelite.client.ui.ColorScheme;

import static com.anvil.Ui.*;

/**
 * The Lookup card's "CAs" section: tier, points to the next one, each tier's count, then every boss
 * with its tasks — unfinished bosses first, a boss opening in place to its tasks, missing ones first.
 * Fetched the first time the section is shown; the hiscores don't publish CAs, so it's Anvil-only.
 */
final class PlayerCardCas
{
	private static final Color DONE = new Color(0x4C_AF_50);

	/** The "Missing only" switch; kept for the session, like the skill tab's virtual levels. */
	private static boolean missingOnly;

	private PlayerCardCas()
	{
	}

	static JPanel lazy(Consumer<Consumer<BingoApiClient.CaLookup>> loader)
	{
		JPanel holder = PlayerCardView.blockOf(small("Loading combat achievements…", MUTED));
		holder.putClientProperty(PlayerCardView.ON_FIRST_SHOW, (Runnable) () -> loader.accept(cas ->
		{
			holder.removeAll();
			holder.add(cas == null || cas.monsters == null || cas.monsters.isEmpty()
				? small("No combat achievements synced yet.", MUTED)
				: render(cas));
			holder.revalidate();
			holder.repaint();
		}));
		return holder;
	}

	static String tierName(BingoApiClient.CaLookup cas, int t)
	{
		return cas.tiers != null && t >= 0 && t < cas.tiers.size() ? cas.tiers.get(t) : "?";
	}

	/** The tier they've reached: the one before the tier they're working towards, or the last when there's none. */
	static String reached(BingoApiClient.CaLookup cas)
	{
		if (cas.tiers == null || cas.tiers.isEmpty())
		{
			return "—";
		}
		int next = cas.nextTier == null ? cas.tiers.size() : cas.tiers.indexOf(cas.nextTier);
		return next <= 0 ? "No tier yet" : cas.tiers.get(next - 1);
	}

	/** The tier filter (index, -1 = every tier) and whether the list is tasks rather than bosses. */
	private static int tierFilter = -1;
	private static boolean byTask;
	/** A flat task list stops here; the search narrows it. */
	private static final int MAX_TASK_ROWS = 150;

	private static JPanel render(BingoApiClient.CaLookup cas)
	{
		JPanel col = column();
		col.add(header(reached(cas), String.format(Locale.ROOT, "%,d pts", cas.earned)));
		if (cas.nextTier != null && cas.nextAt != null && cas.nextAt > 0)
		{
			col.add(gap(4));
			col.add(capH(bar(percent(cas.earned, cas.nextAt), ColorScheme.BRAND_ORANGE, ColorScheme.DARKER_GRAY_COLOR, 6)));
			col.add(gap(2));
			col.add(left(small(String.format(Locale.ROOT, "%,d / %,d to %s", cas.earned, cas.nextAt, cas.nextTier), MUTED)));
		}

		Set<String> open = new HashSet<>();
		String[] query = {""};
		JPanel list = column();
		List<Runnable> paint = new ArrayList<>();
		Runnable refill = () ->
		{
			paint.forEach(Runnable::run);
			fill(cas, list, query[0], open);
			list.revalidate();
			list.repaint();
		};

		if (cas.perTier != null)
		{
			JPanel tiers = grid(2, 6, 4);
			for (int t = 0; t < cas.perTier.size(); t++)
			{
				tiers.add(tierCell(cas, t, paint, refill));
			}
			col.add(gap(6));
			col.add(tiers);
		}

		// List (and search) by boss or by task.
		JLabel bosses = small("Bosses", MUTED);
		JLabel tasks = small("Tasks", MUTED);
		JLabel missing = small("", MUTED);
		paint.add(() ->
		{
			bosses.setForeground(byTask ? MUTED : ColorScheme.BRAND_ORANGE);
			tasks.setForeground(byTask ? ColorScheme.BRAND_ORANGE : MUTED);
			missing.setText((missingOnly ? "\u2713 " : "") + "Missing only");
			missing.setForeground(missingOnly ? ColorScheme.BRAND_ORANGE : MUTED);
		});
		hoverable(bosses, ColorScheme.DARK_GRAY_COLOR, () ->
		{
			byTask = false;
			refill.run();
		});
		hoverable(tasks, ColorScheme.DARK_GRAY_COLOR, () ->
		{
			byTask = true;
			refill.run();
		});
		hoverable(missing, ColorScheme.DARK_GRAY_COLOR, () ->
		{
			missingOnly = !missingOnly;
			refill.run();
		});
		paint.forEach(Runnable::run);
		int count = 0;
		for (BingoApiClient.CaMonster m : cas.monsters)
		{
			count += m.total;
		}
		col.add(gap(8));
		JPanel bar = row(0);
		JPanel modes = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		modes.setOpaque(false);
		modes.add(bosses);
		modes.add(small("  \u00b7  ", MUTED));
		modes.add(tasks);
		bar.add(modes, BorderLayout.WEST);
		bar.add(missing, BorderLayout.EAST);
		col.add(capH(bar));
		col.add(gap(4));
		JPanel searchable = PlayerCardView.searchable("tasks", count, list, q ->
		{
			query[0] = q;
			return fill(cas, list, q, open);
		});
		searchable.setBorder(BorderFactory.createEmptyBorder());
		col.add(searchable);
		return col;
	}

	/** A tier's count and bar; click to show only that tier, again to show every tier. */
	private static JPanel tierCell(BingoApiClient.CaLookup cas, int t, List<Runnable> paint, Runnable refill)
	{
		BingoApiClient.CaCount c = cas.perTier.get(t);
		JPanel cell = new JPanel(new BorderLayout(0, 2));
		cell.setBorder(BorderFactory.createEmptyBorder(2, 3, 2, 3));
		JPanel text = new JPanel(new BorderLayout());
		text.setOpaque(false);
		JLabel name = small(tierName(cas, t).replace("Grandmaster", "GM"), MUTED);
		text.add(name, BorderLayout.WEST);
		text.add(small(c.done + "/" + c.total, Color.WHITE), BorderLayout.EAST);
		cell.add(text, BorderLayout.CENTER);
		cell.add(thinBar(percent(c.done, c.total), ColorScheme.DARKER_GRAY_COLOR), BorderLayout.SOUTH);
		cell.setToolTipText("Show only " + tierName(cas, t) + " tasks");
		hoverable(cell, ColorScheme.DARK_GRAY_COLOR, () ->
		{
			tierFilter = tierFilter == t ? -1 : t;
			refill.run();
		});
		paint.add(() ->
		{
			boolean on = tierFilter == t;
			name.setForeground(on ? ColorScheme.BRAND_ORANGE : MUTED);
			cell.setBackground(on ? ColorScheme.DARKER_GRAY_COLOR : ColorScheme.DARK_GRAY_COLOR);
		});
		return cell;
	}

	private static boolean shows(BingoApiClient.CaTask task)
	{
		return (!missingOnly || !task.d) && (tierFilter < 0 || task.t == tierFilter);
	}

	/** Redraw {@code list} for the query and the switches; false when nothing's left to show. */
	private static boolean fill(BingoApiClient.CaLookup cas, JPanel list, String q, Set<String> open)
	{
		list.removeAll();
		int rows = 0;
		for (BingoApiClient.CaMonster m : cas.monsters)
		{
			if (byTask)
			{
				for (BingoApiClient.CaTask task : m.tasks)
				{
					if (shows(task) && (PlayerCardView.matches(q, task.n) || task.x != null && PlayerCardView.matches(q, task.x)))
					{
						if (++rows > MAX_TASK_ROWS)
						{
							list.add(left(small("More — narrow the search.", MUTED)));
							return true;
						}
						list.add(taskRow(cas, task, m.name));
					}
				}
				continue;
			}
			if (!PlayerCardView.matches(q, m.name))
			{
				continue;
			}
			// With a tier picked, a boss counts only that tier's tasks — and drops out without any.
			int done = 0;
			int total = 0;
			JPanel tasks = column();
			for (BingoApiClient.CaTask task : m.tasks)
			{
				if (tierFilter >= 0 && task.t != tierFilter)
				{
					continue;
				}
				total++;
				done += task.d ? 1 : 0;
				if (shows(task))
				{
					tasks.add(taskRow(cas, task, null));
				}
			}
			if (tasks.getComponentCount() == 0)
			{
				continue;
			}
			boolean expanded = open.contains(m.name);
			list.add(bossRow(m.name, done, total, expanded, () ->
			{
				if (!open.remove(m.name))
				{
					open.add(m.name);
				}
				fill(cas, list, q, open);
				list.revalidate();
				list.repaint();
			}));
			if (expanded)
			{
				list.add(tasks);
			}
		}
		return list.getComponentCount() > 0;
	}

	private static JPanel bossRow(String name, int done, int total, boolean expanded, Runnable toggle)
	{
		boolean complete = done >= total;
		JPanel row = row(4);
		row.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
		row.add(small(name, complete ? MUTED : Color.WHITE), BorderLayout.CENTER);
		row.add(small(done + "/" + total + (complete ? " \u2713" : expanded ? " \u02c5" : " \u203a"),
			complete ? DONE : ColorScheme.BRAND_ORANGE), BorderLayout.EAST);
		hoverable(row, ColorScheme.DARK_GRAY_COLOR, toggle);
		return capH(row);
	}

	/** One task; {@code boss} names its boss in the flat task list (null under its boss row). */
	private static JPanel taskRow(BingoApiClient.CaLookup cas, BingoApiClient.CaTask task, String boss)
	{
		JPanel row = row(4);
		row.setOpaque(true);
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(2, boss != null ? 4 : 12, 2, 4));
		row.add(small((task.d ? "✓ " : "✗ ") + task.n, task.d ? DONE : Color.WHITE), BorderLayout.CENTER);
		row.add(small(tierName(cas, task.t).replace("Grandmaster", "GM"), MUTED), BorderLayout.EAST);
		String tip = (boss != null ? esc(boss) + ": " : "") + esc(task.n) + " — " + esc(tierName(cas, task.t)) + (task.y != null ? " · " + esc(task.y) : "")
			+ (task.x != null ? "<br>" + esc(task.x) : "");
		row.setToolTipText(wrapHtml(tip, 180));
		return capH(row);
	}
}
