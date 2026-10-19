package com.anvil;

import java.awt.BorderLayout;
import java.awt.Color;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
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

	private static JPanel render(BingoApiClient.CaLookup cas)
	{
		JPanel col = column();
		col.add(PlayerCardView.header(reached(cas), String.format(Locale.ROOT, "%,d pts", cas.earned)));
		if (cas.nextTier != null && cas.nextAt != null && cas.nextAt > 0)
		{
			col.add(gap(4));
			col.add(capH(bar(PlayerCardView.percent(cas.earned, cas.nextAt), ColorScheme.BRAND_ORANGE, ColorScheme.DARKER_GRAY_COLOR, 6)));
			col.add(gap(2));
			col.add(left(small(String.format(Locale.ROOT, "%,d / %,d to %s", cas.earned, cas.nextAt, cas.nextTier), MUTED)));
		}
		if (cas.perTier != null)
		{
			JPanel tiers = PlayerCardView.grid(2, 8, 4);
			for (int t = 0; t < cas.perTier.size(); t++)
			{
				BingoApiClient.CaCount c = cas.perTier.get(t);
				tiers.add(PlayerCardView.miniBar(tierName(cas, t).replace("Grandmaster", "GM") + "  " + c.done + "/" + c.total, c.done, c.total));
			}
			col.add(gap(6));
			col.add(tiers);
		}

		Set<String> open = new HashSet<>();
		String[] query = {""};
		JPanel list = column();
		java.util.function.Predicate<String> fill = q ->
		{
			query[0] = q;
			list.removeAll();
			for (BingoApiClient.CaMonster m : cas.monsters)
			{
				boolean bossHit = PlayerCardView.matches(q, m.name);
				boolean any = false;
				JPanel tasks = column();
				for (BingoApiClient.CaTask task : m.tasks)
				{
					if ((missingOnly && task.d) || (!bossHit && !PlayerCardView.matches(q, task.n)))
					{
						continue;
					}
					any = true;
					tasks.add(taskRow(cas, task));
				}
				if (!any)
				{
					continue;
				}
				// A search that matched tasks rather than the boss opens the boss to show them.
				boolean expanded = open.contains(m.name) || (!q.isEmpty() && !bossHit);
				list.add(bossRow(m, expanded, () ->
				{
					if (!open.remove(m.name))
					{
						open.add(m.name);
					}
					refill(list, query[0]);
				}));
				if (expanded)
				{
					list.add(tasks);
				}
			}
			return list.getComponentCount() > 0;
		};
		list.putClientProperty(FILL, fill);

		JLabel toggle = small("", MUTED);
		Runnable paintToggle = () ->
		{
			toggle.setText((missingOnly ? "✓ " : "") + "Missing only");
			toggle.setForeground(missingOnly ? ColorScheme.BRAND_ORANGE : MUTED);
		};
		paintToggle.run();
		hoverable(toggle, ColorScheme.DARK_GRAY_COLOR, () ->
		{
			missingOnly = !missingOnly;
			paintToggle.run();
			refill(list, query[0]);
		});
		int count = 0;
		for (BingoApiClient.CaMonster m : cas.monsters)
		{
			count += m.total;
		}
		col.add(gap(8));
		JPanel bar = row(0);
		bar.add(small("Bosses", ColorScheme.BRAND_ORANGE), BorderLayout.WEST);
		bar.add(toggle, BorderLayout.EAST);
		col.add(capH(bar));
		col.add(gap(4));
		JPanel searchable = PlayerCardView.searchable("tasks", count, list, fill);
		searchable.setBorder(BorderFactory.createEmptyBorder());
		col.add(searchable);
		return col;
	}

	private static final String FILL = "anvil.casFill";

	@SuppressWarnings("unchecked")
	private static void refill(JPanel list, String q)
	{
		((java.util.function.Predicate<String>) list.getClientProperty(FILL)).test(q);
		list.revalidate();
		list.repaint();
	}

	private static JPanel bossRow(BingoApiClient.CaMonster m, boolean expanded, Runnable toggle)
	{
		boolean complete = m.done >= m.total;
		JPanel row = row(4);
		row.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
		row.add(small(m.name, complete ? MUTED : Color.WHITE), BorderLayout.CENTER);
		row.add(small(m.done + "/" + m.total + (complete ? " ✓" : expanded ? " ˅" : " ›"),
			complete ? DONE : ColorScheme.BRAND_ORANGE), BorderLayout.EAST);
		hoverable(row, ColorScheme.DARK_GRAY_COLOR, toggle);
		return capH(row);
	}

	private static JPanel taskRow(BingoApiClient.CaLookup cas, BingoApiClient.CaTask task)
	{
		JPanel row = row(4);
		row.setOpaque(true);
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(2, 12, 2, 4));
		row.add(small((task.d ? "✓ " : "✗ ") + task.n, task.d ? DONE : Color.WHITE), BorderLayout.CENTER);
		row.add(small(tierName(cas, task.t), MUTED), BorderLayout.EAST);
		String tip = esc(task.n) + " — " + esc(tierName(cas, task.t)) + (task.y != null ? " · " + esc(task.y) : "")
			+ (task.x != null ? "<br>" + esc(task.x) : "");
		row.setToolTipText(wrapHtml(tip, 180));
		return capH(row);
	}
}
