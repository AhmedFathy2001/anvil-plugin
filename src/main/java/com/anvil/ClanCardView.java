package com.anvil;

import java.awt.*;
import java.util.Locale;
import java.util.function.Consumer;
import javax.swing.*;
import net.runelite.client.ui.ColorScheme;

import static com.anvil.PlayerCardView.header;
import static com.anvil.Ui.*;

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
		JPanel nameRow = row(6);
		nameRow.add(title(card.name), BorderLayout.CENTER);
		JLabel web = onClick(new JLabel(new GlyphIcon(GlyphIcon.Shape.EXTERNAL, 12, MUTED)), () ->
			net.runelite.client.util.LinkBrowser.browse(BingoApiClient.CANONICAL_SITE + "/c/"
				+ java.net.URLEncoder.encode(card.slug, java.nio.charset.StandardCharsets.UTF_8)));
		web.setToolTipText("Open the clan on the Anvil website");
		nameRow.add(web, BorderLayout.EAST);
		id.add(capH(nameRow));
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
			week.add(header("This week", String.format(Locale.ROOT, "+%,.0fh gained", card.weekHours)));
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
				JPanel line = row(6);
				JLabel icon = PlayerCardView.iconSlot(24, 22);
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
				hl.add(capH(line));
				hl.add(gap(2));
			}
			root.add(hl);
		}

		if (onCompare != null)
		{
			root.add(gap(6));
			JButton compare = new JButton("Compare with my clan");
			compare.setFocusable(false);
			compare.setFont(SMALL);
			compare.addActionListener(e -> onCompare.run());
			JPanel row = row(0);
			row.add(compare, BorderLayout.CENTER);
			row.setMaximumSize(new Dimension(Integer.MAX_VALUE, compare.getPreferredSize().height));
			root.add(row);
		}
		return root;
	}

	/** "Name ......... value" — the name opens that player's card when {@code onPlayer} is given. */
	private static JPanel nameLine(String text, String value, String rsn, Consumer<String> onPlayer)
	{
		JPanel line = row(6);
		line.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
		line.add(small(text, Color.WHITE), BorderLayout.CENTER);
		line.add(small(value, ColorScheme.BRAND_ORANGE), BorderLayout.EAST);
		capH(line);
		if (onPlayer != null)
		{
			line.setToolTipText("Look up " + plainText(rsn));
			hoverable(line, ColorScheme.DARKER_GRAY_COLOR, () -> onPlayer.accept(rsn));
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
		java.util.function.LongFunction<String> hours = n -> String.format(Locale.ROOT, "%,dh", n);
		block.add(CompareView.row("Total EHP", Math.round(a.totalEhp), Math.round(b.totalEhp), hours));
		block.add(CompareView.row("Total EHB", Math.round(a.totalEhb), Math.round(b.totalEhb), hours));
		block.add(CompareView.row("Avg log slots", a.clog == null ? -1 : a.clog.average, b.clog == null ? -1 : b.clog.average,
			n -> n < 0 ? "—" : String.format(Locale.ROOT, "%,d", n)));
		block.add(CompareView.row("Gained this week", Math.round(a.weekHours), Math.round(b.weekHours), hours));
		return block;
	}
}
