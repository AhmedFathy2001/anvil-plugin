package com.anvil;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import static com.anvil.PlayerCardView.MUTED;
import static com.anvil.PlayerCardView.block;
import static com.anvil.PlayerCardView.column;
import static com.anvil.PlayerCardView.gap;
import static com.anvil.PlayerCardView.header;
import static com.anvil.PlayerCardView.small;
import static com.anvil.PlayerCardView.title;

/**
 * A {@link ClanCard}: who the clan is, how hard it played this week and who carried it, its strongest
 * accounts, what dropped lately and what's running. Under Events in the Clan tab (your clan) and in
 * Lookup (any listed clan) — the clan side of Anvil, next to the boards.
 */
final class ClanCardView
{
	private ClanCardView()
	{
	}

	/**
	 * @param onPlayer  open a member's player card (names are clickable); null = plain text
	 * @param onCompare "Compare with my clan"; null hides the button (it's your own clan)
	 */
	static JPanel render(ClanCard card, PlayerCardView.Icons icons, Consumer<String> onPlayer, Runnable onCompare)
	{
		PlayerCardView.Icons ic = icons == null ? PlayerCardView.Icons.NONE : icons;
		JPanel root = column();

		JPanel id = block();
		JPanel nameRow = new JPanel(new BorderLayout(6, 0));
		nameRow.setOpaque(false);
		nameRow.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		nameRow.add(title(card.name), BorderLayout.CENTER);
		JLabel web = new JLabel(new GlyphIcon(GlyphIcon.Shape.EXTERNAL, 12, MUTED));
		web.setToolTipText("Open the clan on the Anvil website");
		web.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		web.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				net.runelite.client.util.LinkBrowser.browse(BingoApiClient.CANONICAL_SITE + "/c/"
					+ java.net.URLEncoder.encode(card.slug, java.nio.charset.StandardCharsets.UTF_8));
			}
		});
		nameRow.add(web, BorderLayout.EAST);
		nameRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, nameRow.getPreferredSize().height));
		id.add(nameRow);
		id.add(gap(2));
		id.add(small(String.format(Locale.ROOT, "%,d members · %,d active this week", card.members, card.activeThisWeek), MUTED));
		if (card.clog != null)
		{
			id.add(small(String.format(Locale.ROOT, "Avg log %,d slots · %,d synced", card.clog.average, card.clog.synced), MUTED));
		}
		if (card.competitions != null && !card.competitions.isEmpty())
		{
			StringBuilder sb = new StringBuilder("Running: ");
			for (int i = 0; i < card.competitions.size(); i++)
			{
				ClanCard.Competition c = card.competitions.get(i);
				sb.append(i > 0 ? " · " : "").append("boss".equals(c.type) ? "BOTW " : "skill".equals(c.type) ? "SOTW " : "").append(c.label);
			}
			id.add(gap(2));
			id.add(small(sb.toString(), ColorScheme.BRAND_ORANGE));
		}
		root.add(id);

		if (card.topWeek != null && !card.topWeek.isEmpty())
		{
			root.add(gap(6));
			JPanel week = block();
			week.add(header("This week", String.format(Locale.ROOT, "%.0fh EHP+EHB total", card.totalEhp + card.totalEhb)));
			week.add(gap(4));
			int place = 1;
			for (ClanCard.Gainer g : card.topWeek)
			{
				week.add(nameLine(place++ + ".  " + g.rsn, String.format(Locale.ROOT, "+%.1fh", g.hours), g.rsn, onPlayer));
			}
			root.add(week);
		}

		if (card.topPlayers != null && !card.topPlayers.isEmpty())
		{
			root.add(gap(6));
			JPanel top = block();
			top.add(header("Top accounts", "by total XP"));
			top.add(gap(4));
			for (ClanCard.Player p : card.topPlayers)
			{
				top.add(nameLine(p.rsn, PlayerCardView.compact(p.overallXp) + " xp", p.rsn, onPlayer));
			}
			root.add(top);
		}

		if (card.highlights != null && !card.highlights.isEmpty())
		{
			root.add(gap(6));
			JPanel hl = block();
			hl.add(header("Lately in the clan", null));
			hl.add(gap(4));
			for (ClanCard.Highlight h : card.highlights)
			{
				JPanel line = new JPanel(new BorderLayout(6, 0));
				line.setOpaque(false);
				line.setAlignmentX(JComponent.LEFT_ALIGNMENT);
				JLabel icon = new JLabel();
				icon.setPreferredSize(new Dimension(24, 22));
				icon.setHorizontalAlignment(SwingConstants.CENTER);
				if (h.itemId != null && ic.items != null)
				{
					ic.items.getImage(h.itemId).addTo(icon);
				}
				line.add(icon, BorderLayout.WEST);
				JPanel text = column();
				text.setOpaque(false);
				text.add(small(h.label, Color.WHITE));
				text.add(small(h.rsn + ("pet".equals(h.kind) ? " · pet" : ""), MUTED));
				line.add(text, BorderLayout.CENTER);
				line.add(small(PlayerCardView.ago(h.at), MUTED), BorderLayout.EAST);
				line.setMaximumSize(new Dimension(Integer.MAX_VALUE, line.getPreferredSize().height));
				hl.add(line);
				hl.add(gap(2));
			}
			root.add(hl);
		}

		if (onCompare != null)
		{
			root.add(gap(6));
			JButton compare = new JButton("Compare with my clan");
			compare.setFocusable(false);
			compare.setFont(FontManager.getRunescapeSmallFont());
			compare.addActionListener(e -> onCompare.run());
			JPanel row = new JPanel(new BorderLayout());
			row.setOpaque(false);
			row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
			row.add(compare, BorderLayout.CENTER);
			row.setMaximumSize(new Dimension(Integer.MAX_VALUE, compare.getPreferredSize().height));
			root.add(row);
		}
		return root;
	}

	/** "Name ......... value" — the name opens that player's card when {@code onPlayer} is given. */
	private static JPanel nameLine(String text, String value, String rsn, Consumer<String> onPlayer)
	{
		JPanel line = new JPanel(new BorderLayout(6, 0));
		line.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		line.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
		line.add(small(text, Color.WHITE), BorderLayout.CENTER);
		line.add(small(value, ColorScheme.BRAND_ORANGE), BorderLayout.EAST);
		line.setMaximumSize(new Dimension(Integer.MAX_VALUE, line.getPreferredSize().height));
		if (onPlayer != null)
		{
			line.setToolTipText("Look up " + AnvilSidebarPanel.plainText(rsn));
			PlayerCardView.hoverable(line, ColorScheme.DARKER_GRAY_COLOR, () -> onPlayer.accept(rsn));
		}
		else
		{
			line.setOpaque(false);
		}
		return line;
	}

	/** Two clans side by side: one line per measure, the leader's number in orange. */
	static JPanel compare(ClanCard a, ClanCard b)
	{
		JPanel block = block();
		block.add(CompareView.namesRow(a.name, b.name));
		block.add(gap(4));
		block.add(CompareView.row("Members", a.members, b.members, Long::toString));
		block.add(CompareView.row("Active this week", a.activeThisWeek, b.activeThisWeek, Long::toString));
		block.add(CompareView.row("Total EHP", Math.round(a.totalEhp), Math.round(b.totalEhp), n -> String.format(Locale.ROOT, "%,dh", n)));
		block.add(CompareView.row("Total EHB", Math.round(a.totalEhb), Math.round(b.totalEhb), n -> String.format(Locale.ROOT, "%,dh", n)));
		block.add(CompareView.row("Avg log slots", a.clog == null ? -1 : a.clog.average, b.clog == null ? -1 : b.clog.average,
			n -> n < 0 ? "—" : String.format(Locale.ROOT, "%,d", n)));
		long weekA = sumHours(a.topWeek);
		long weekB = sumHours(b.topWeek);
		block.add(CompareView.row("Top 3 this week", weekA, weekB, n -> n + "h"));
		return block;
	}

	private static long sumHours(List<ClanCard.Gainer> g)
	{
		double t = 0;
		if (g != null)
		{
			for (ClanCard.Gainer x : g)
			{
				t += x.hours;
			}
		}
		return Math.round(t);
	}

}
