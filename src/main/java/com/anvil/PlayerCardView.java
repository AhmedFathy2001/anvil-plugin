package com.anvil;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.components.ProgressBar;

/**
 * Draws a {@link PlayerCard} for the side panel — the Lookup and You tabs.
 *
 * <p>The opposite of a hiscores table on purpose. At ~230px a table of every skill and boss is mostly
 * {@code 0 / --}; this shows what's notable, top to bottom — who they are, how far their log is,
 * what happened lately, what they kill most — and leaves the full tables to the website, one click
 * away. Every block is skipped when it has nothing in it, so a fresh account is a short card rather
 * than a page of zeroes.</p>
 */
final class PlayerCardView
{
	private static final Color MUTED = new Color(0x98_98_98);
	private static final int BLOCK_GAP = 6;

	private PlayerCardView()
	{
	}

	static JPanel render(PlayerCard card, ItemManager itemManager, SpriteManager spriteManager)
	{
		JPanel root = column();
		root.add(identity(card));

		if (card.clog != null && card.clog.total > 0)
		{
			root.add(gap(BLOCK_GAP));
			root.add(clog(card.clog));
		}
		if (card.latest != null && !card.latest.isEmpty())
		{
			root.add(gap(BLOCK_GAP));
			root.add(latest(card.latest, itemManager, spriteManager));
		}
		if (card.topBosses != null && !card.topBosses.isEmpty())
		{
			root.add(gap(BLOCK_GAP));
			root.add(bosses(card.topBosses, spriteManager));
		}

		root.add(gap(BLOCK_GAP));
		JButton open = new JButton("Open full profile");
		open.setFocusable(false);
		open.setFont(FontManager.getRunescapeSmallFont());
		open.addActionListener(e -> ProfileLookup.open(card.rsn));
		root.add(stretch(open));
		return root;
	}

	/** "Not on Anvil" — the same words whether the name is unknown or simply not shared. */
	static JPanel notFound(String rsn)
	{
		JPanel block = block();
		block.add(title(rsn));
		block.add(gap(4));
		block.add(small("Not on Anvil yet — or they haven't shared their profile.", MUTED));
		return block;
	}

	// ---- Blocks ------------------------------------------------------------------------------------

	private static JPanel identity(PlayerCard card)
	{
		JPanel block = block();
		block.add(title(card.rsn));
		if (card.clan != null && card.clan.name != null)
		{
			block.add(gap(2));
			block.add(small(card.clan.name, ColorScheme.BRAND_ORANGE));
		}
		StringBuilder line = new StringBuilder();
		if (card.totalLevel > 0)
		{
			line.append(String.format(Locale.ROOT, "%,d total", card.totalLevel));
		}
		if (card.combatLevel != null)
		{
			sep(line).append("cb ").append(card.combatLevel);
		}
		if (card.accountType != null && !card.accountType.isEmpty())
		{
			sep(line).append(accountTypeLabel(card.accountType));
		}
		if (line.length() > 0)
		{
			block.add(gap(2));
			block.add(small(line.toString(), MUTED));
		}
		return block;
	}

	private static JPanel clog(PlayerCard.Clog clog)
	{
		JPanel block = block();
		block.add(header("Collection log", String.format(Locale.ROOT, "%,d / %,d", clog.obtained, clog.total)));
		block.add(gap(4));

		ProgressBar bar = new ProgressBar();
		bar.setMaximumValue(clog.total);
		bar.setValue(clog.obtained);
		bar.setCenterLabel(percent(clog.obtained, clog.total) + "%");
		bar.setLeftLabel("");
		bar.setRightLabel("");
		bar.setForeground(ColorScheme.BRAND_ORANGE);
		bar.setBackground(ColorScheme.DARK_GRAY_COLOR);
		bar.setPreferredSize(new Dimension(0, 14));
		bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 14));
		bar.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		block.add(bar);

		if (clog.groups != null && !clog.groups.isEmpty())
		{
			block.add(gap(6));
			// Two per row: six groups as six full-width rows is a second table, which is what we're avoiding.
			JPanel grid = new JPanel(new GridLayout(0, 2, 8, 4));
			grid.setOpaque(false);
			grid.setAlignmentX(JComponent.LEFT_ALIGNMENT);
			for (PlayerCard.Group g : clog.groups)
			{
				grid.add(miniBar(g.name, g.obtained, g.total));
			}
			block.add(grid);
		}
		return block;
	}

	private static JPanel latest(List<PlayerCard.Latest> rows, ItemManager itemManager, SpriteManager spriteManager)
	{
		JPanel block = block();
		block.add(header("Latest", null));
		block.add(gap(4));
		for (PlayerCard.Latest row : rows)
		{
			JPanel line = new JPanel(new BorderLayout(6, 0));
			line.setOpaque(false);
			line.setAlignmentX(JComponent.LEFT_ALIGNMENT);

			JLabel icon = new JLabel();
			icon.setPreferredSize(new Dimension(24, 22));
			icon.setHorizontalAlignment(SwingConstants.CENTER);
			if ("item".equals(row.kind) && row.itemId != null && itemManager != null)
			{
				itemManager.getImage(row.itemId).addTo(icon);
			}
			else
			{
				icon.setIcon(new GlyphIcon(GlyphIcon.Shape.CHECK, 12, ColorScheme.BRAND_ORANGE));
			}
			line.add(icon, BorderLayout.WEST);
			line.add(small(row.label, Color.WHITE), BorderLayout.CENTER);
			line.add(small(ago(row.at), MUTED), BorderLayout.EAST);
			line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
			block.add(line);
		}
		return block;
	}

	private static JPanel bosses(List<PlayerCard.Boss> rows, SpriteManager spriteManager)
	{
		JPanel block = block();
		block.add(header("Most killed", null));
		block.add(gap(4));
		JPanel grid = new JPanel(new GridLayout(0, 3, 4, 4));
		grid.setOpaque(false);
		grid.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		for (PlayerCard.Boss boss : rows)
		{
			JLabel cell = new JLabel(compact(boss.kc), SwingConstants.CENTER);
			cell.setFont(FontManager.getRunescapeSmallFont());
			cell.setForeground(Color.WHITE);
			cell.setHorizontalTextPosition(SwingConstants.RIGHT);
			cell.setIconTextGap(4);
			cell.setToolTipText(safe(String.format(Locale.ROOT, "%s — %,d kills", boss.label, boss.kc)));
			HiscoreSkill skill = hiscoreSkill(boss.label);
			if (skill != null && spriteManager != null)
			{
				spriteManager.addSpriteTo(cell, skill.getSpriteId(), 0);
			}
			else
			{
				cell.setText(safe(shortName(boss.label) + " " + compact(boss.kc)));
			}
			grid.add(cell);
		}
		block.add(grid);
		return block;
	}

	// ---- Pieces ------------------------------------------------------------------------------------

	private static JPanel column()
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		p.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		return p;
	}

	private static JPanel block()
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		p.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		return p;
	}

	// Every string on the card comes from the site — RSNs, a clan's own name, item and boss labels — and a
	// Swing label renders HTML when its text opens with <html>. AnvilSidebarPanel.plainText neutralises
	// that; labels and tooltips below all go through it.
	private static String safe(String text)
	{
		return AnvilSidebarPanel.plainText(text);
	}

	private static JLabel title(String text)
	{
		JLabel l = new JLabel(safe(text));
		l.setFont(FontManager.getRunescapeBoldFont());
		l.setForeground(Color.WHITE);
		l.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		return l;
	}

	private static JLabel small(String text, Color color)
	{
		JLabel l = new JLabel(safe(text));
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(color);
		l.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		return l;
	}

	private static JPanel header(String left, String right)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		JLabel l = new JLabel(left);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ColorScheme.BRAND_ORANGE);
		row.add(l, BorderLayout.WEST);
		if (right != null)
		{
			row.add(small(right, Color.WHITE), BorderLayout.EAST);
		}
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	/** "Bosses  62%" over a 3px bar — a group's share of its log at a glance. */
	private static JPanel miniBar(String name, int obtained, int total)
	{
		int pct = percent(obtained, total);
		JPanel cell = new JPanel(new BorderLayout(0, 2));
		cell.setOpaque(false);
		cell.setToolTipText(safe(String.format(Locale.ROOT, "%s: %,d / %,d", name, obtained, total)));
		JPanel text = new JPanel(new BorderLayout());
		text.setOpaque(false);
		text.add(small(name, MUTED), BorderLayout.WEST);
		text.add(small(pct + "%", Color.WHITE), BorderLayout.EAST);
		cell.add(text, BorderLayout.CENTER);
		JPanel bar = new JPanel()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				g.setColor(ColorScheme.DARK_GRAY_COLOR);
				g.fillRect(0, 0, getWidth(), getHeight());
				g.setColor(pct >= 100 ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.BRAND_ORANGE);
				g.fillRect(0, 0, getWidth() * pct / 100, getHeight());
			}
		};
		bar.setPreferredSize(new Dimension(0, 3));
		cell.add(bar, BorderLayout.SOUTH);
		return cell;
	}

	private static Component gap(int h)
	{
		return javax.swing.Box.createVerticalStrut(h);
	}

	private static JPanel stretch(JButton b)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		row.add(b, BorderLayout.CENTER);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, b.getPreferredSize().height));
		return row;
	}

	// ---- Formatting (package-private for tests) ---------------------------------------------------

	static int percent(int part, int whole)
	{
		return whole <= 0 ? 0 : (int) Math.floor(part * 100.0 / whole);
	}

	static String compact(long n)
	{
		if (n >= 1_000_000)
		{
			return trim(n / 1_000_000.0) + "m";
		}
		if (n >= 10_000)
		{
			return Math.round(n / 1000.0) + "k";
		}
		if (n >= 1_000)
		{
			return trim(n / 1000.0) + "k";
		}
		return Long.toString(n);
	}

	private static String trim(double v)
	{
		String s = String.format(Locale.ROOT, "%.1f", v);
		return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
	}

	/** "3d ago" from either timestamp format the site sends ("2026-10-12 08:00:00" or ISO). */
	static String ago(String at)
	{
		return ago(at, Instant.now());
	}

	static String ago(String at, Instant now)
	{
		if (at == null || at.isEmpty())
		{
			return "";
		}
		String iso = at.trim().replace(' ', 'T');
		if (!iso.endsWith("Z") && !iso.matches(".*[+-]\\d\\d:\\d\\d$"))
		{
			iso = iso + "Z";
		}
		try
		{
			long minutes = Math.max(0, Duration.between(Instant.parse(iso), now).toMinutes());
			if (minutes < 60)
			{
				return minutes <= 1 ? "just now" : minutes + "m ago";
			}
			long hours = minutes / 60;
			if (hours < 24)
			{
				return hours + "h ago";
			}
			long days = hours / 24;
			if (days < 14)
			{
				return days + "d ago";
			}
			if (days < 60)
			{
				return (days / 7) + "w ago";
			}
			if (days < 730)
			{
				return (days / 30) + "mo ago";
			}
			return (days / 365) + "y ago";
		}
		catch (DateTimeParseException e)
		{
			return "";
		}
	}

	static String accountTypeLabel(String type)
	{
		switch (type)
		{
			case "ironman":
				return "Ironman";
			case "hardcore":
			case "hardcore_ironman":
				return "Hardcore";
			case "ultimate":
			case "ultimate_ironman":
				return "Ultimate";
			case "group_ironman":
				return "Group iron";
			case "main":
			case "normal":
				return "Main";
			default:
				return Character.toUpperCase(type.charAt(0)) + type.substring(1).replace('_', ' ');
		}
	}

	/** RuneLite's hiscore entry for a boss the site names, so the card can use the game's own icon. */
	static HiscoreSkill hiscoreSkill(String label)
	{
		if (label == null)
		{
			return null;
		}
		for (HiscoreSkill s : HiscoreSkill.values())
		{
			if (s.getName().equalsIgnoreCase(label))
			{
				return s;
			}
		}
		return null;
	}

	private static String shortName(String label)
	{
		return label.length() <= 8 ? label : label.substring(0, 7) + "…";
	}

	private static StringBuilder sep(StringBuilder sb)
	{
		return sb.length() > 0 ? sb.append(" · ") : sb;
	}
}
