package com.anvil;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import net.runelite.api.Skill;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.components.ProgressBar;

/**
 * Draws a {@link PlayerCard} for the side panel — the Lookup and You tabs.
 *
 * <p>Two layers. The <b>summary</b> says who this is and what's notable: clan, totals, how far the log
 * is, what happened lately. Under it, a small switcher opens the <b>profile</b> itself — skills as the
 * game's own skill grid, bosses and clues as icon grids, the log as categories that open into pages.
 * The opposite of a hiscores table on purpose: at ~230px a table of every row is mostly
 * {@code 0 / --}, so every grid here holds only what has a number, and a section with nothing in it
 * isn't offered at all.</p>
 */
final class PlayerCardView
{
	private static final Color MUTED = new Color(0x98_98_98);
	private static final int BLOCK_GAP = 6;

	/** The game's skill tab, read left to right: what a player's eye already knows. */
	static final Skill[] SKILL_GRID = {
		Skill.ATTACK, Skill.HITPOINTS, Skill.MINING,
		Skill.STRENGTH, Skill.AGILITY, Skill.SMITHING,
		Skill.DEFENCE, Skill.HERBLORE, Skill.FISHING,
		Skill.RANGED, Skill.THIEVING, Skill.COOKING,
		Skill.PRAYER, Skill.CRAFTING, Skill.FIREMAKING,
		Skill.MAGIC, Skill.FLETCHING, Skill.WOODCUTTING,
		Skill.RUNECRAFT, Skill.SLAYER, Skill.FARMING,
		Skill.CONSTRUCTION, Skill.HUNTER, Skill.SAILING,
	};

	/** The icon sources a card draws from. Any of them may be null (tests): the card falls back to text. */
	static final class Icons
	{
		final ItemManager items;
		final SpriteManager sprites;
		final SkillIconManager skills;
		/** Loads one log page (off the EDT) and hands it back ON the EDT; null = pages don't open. */
		final java.util.function.BiConsumer<String, Consumer<BingoApiClient.ClogPageView>> pageLoader;

		Icons(ItemManager items, SpriteManager sprites, SkillIconManager skills)
		{
			this(items, sprites, skills, null);
		}

		Icons(ItemManager items, SpriteManager sprites, SkillIconManager skills,
			java.util.function.BiConsumer<String, Consumer<BingoApiClient.ClogPageView>> pageLoader)
		{
			this.items = items;
			this.sprites = sprites;
			this.skills = skills;
			this.pageLoader = pageLoader;
		}

		static final Icons NONE = new Icons(null, null, null);
	}

	private PlayerCardView()
	{
	}

	/**
	 * @param section   which profile section to open on ("Skills", "Bosses", "Log", "Clues"); null or
	 *                  one this card doesn't have → the first it does
	 * @param onSection told when the member switches section, so a re-render can reopen the same one
	 */
	static JPanel render(PlayerCard card, Icons icons, String section, Consumer<String> onSection)
	{
		Icons ic = icons == null ? Icons.NONE : icons;
		JPanel root = column();
		root.add(identity(card));

		if (card.clog != null && card.clog.total > 0)
		{
			root.add(gap(BLOCK_GAP));
			root.add(clogSummary(card.clog));
		}
		if (card.latest != null && !card.latest.isEmpty())
		{
			root.add(gap(BLOCK_GAP));
			root.add(latest(card.rsn, card.latest, ic));
		}

		// Built before the switcher so a boss can jump into it: clicking a boss opens the log page its
		// kills fill, which means switching to Log and opening that page in one move.
		JPanel log = card.clogPages != null && !card.clogPages.isEmpty()
			? logPages(card.rsn, card.clogPages, ic, pageKills(card.bosses))
			: null;
		Runnable[] showLog = new Runnable[1];
		Consumer<String> openLogPage = log == null || ic.pageLoader == null ? null : name ->
		{
			for (PlayerCard.Page p : card.clogPages)
			{
				if (p.name.equalsIgnoreCase(name))
				{
					if (showLog[0] != null)
					{
						showLog[0].run();
					}
					showPage(card.rsn, p, ic, log, (JComponent) log.getClientProperty(LOG_ROOT));
					return;
				}
			}
		};

		Map<String, JPanel> sections = new LinkedHashMap<>();
		if (card.skills != null && !card.skills.isEmpty())
		{
			sections.put("Skills", skills(card.skills, ic));
		}
		List<PlayerCard.Boss> bosses = card.bosses != null ? card.bosses : card.topBosses;
		if (bosses != null && !bosses.isEmpty())
		{
			sections.put("Bosses", bosses(bosses, ic, openLogPage));
		}
		if (log != null)
		{
			sections.put("Log", log);
		}
		if (card.activities != null && !card.activities.isEmpty())
		{
			sections.put("Clues", activities(card.activities, ic));
		}
		if (card.times != null && !card.times.isEmpty())
		{
			sections.put("Times", times(card.times, ic));
		}
		if (!sections.isEmpty())
		{
			root.add(gap(BLOCK_GAP));
			root.add(sectionSwitcher(sections, section, onSection, showLog));
		}
		return root;
	}

	/** Back-compat for callers that don't track a section. */
	static JPanel render(PlayerCard card, ItemManager itemManager, SpriteManager spriteManager)
	{
		return render(card, new Icons(itemManager, spriteManager, null), null, s -> { });
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

	// ---- Summary ------------------------------------------------------------------------------------

	private static JPanel identity(PlayerCard card)
	{
		JPanel block = block();

		// Name on the left, the website one small click away on the right — the panel IS the profile
		// now, so the browser is an icon, not the card's main button.
		JPanel nameRow = new JPanel(new BorderLayout(6, 0));
		nameRow.setOpaque(false);
		nameRow.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		nameRow.add(title(card.rsn), BorderLayout.CENTER);
		JLabel web = new JLabel(new GlyphIcon(GlyphIcon.Shape.EXTERNAL, 12, MUTED));
		web.setToolTipText("Open on the Anvil website");
		web.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		web.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				ProfileLookup.open(card.rsn);
			}
		});
		nameRow.add(web, BorderLayout.EAST);
		nameRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, nameRow.getPreferredSize().height));
		block.add(nameRow);

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
		if (isEmpty(card))
		{
			block.add(gap(6));
			block.add(small("No stats tracked yet.", MUTED));
		}
		return block;
	}

	/** Nothing to show past the name: no stats, no log, nothing lately. */
	static boolean isEmpty(PlayerCard card)
	{
		return card.totalLevel <= 0
			&& (card.clog == null || card.clog.total <= 0)
			&& (card.latest == null || card.latest.isEmpty())
			&& (card.skills == null || card.skills.isEmpty())
			&& (card.bosses == null || card.bosses.isEmpty())
			&& (card.topBosses == null || card.topBosses.isEmpty());
	}

	private static JPanel clogSummary(PlayerCard.Clog clog)
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

	private static JPanel latest(String rsn, List<PlayerCard.Latest> rows, Icons ic)
	{
		JPanel block = block();
		JPanel head = header("Latest", null);
		JLabel all = new JLabel("See all ›");
		all.setFont(FontManager.getRunescapeSmallFont());
		all.setForeground(MUTED);
		all.setToolTipText("Everything they've done lately, on the Anvil website");
		all.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		all.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				String url = ProfileLookup.profileUrl(rsn);
				if (url != null)
				{
					net.runelite.client.util.LinkBrowser.browse(url + "?tab=records");
				}
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				all.setForeground(ColorScheme.BRAND_ORANGE);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				all.setForeground(MUTED);
			}
		});
		head.add(all, BorderLayout.EAST);
		block.add(head);
		block.add(gap(4));
		for (PlayerCard.Latest row : rows)
		{
			JPanel line = new JPanel(new BorderLayout(6, 0));
			line.setOpaque(false);
			line.setAlignmentX(JComponent.LEFT_ALIGNMENT);

			JLabel icon = new JLabel();
			icon.setPreferredSize(new Dimension(24, 22));
			icon.setHorizontalAlignment(SwingConstants.CENTER);
			if ("item".equals(row.kind) && row.itemId != null && ic.items != null)
			{
				ic.items.getImage(row.itemId).addTo(icon);
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

	// ---- The profile: one section at a time --------------------------------------------------------

	private static JPanel sectionSwitcher(Map<String, JPanel> sections, String wanted, Consumer<String> onSection,
		Runnable[] showLog)
	{
		String first = sections.containsKey(wanted) ? wanted : sections.keySet().iterator().next();

		JPanel wrap = column();
		JPanel strip = new JPanel(new GridLayout(1, sections.size()));
		strip.setBackground(ColorScheme.DARK_GRAY_COLOR);
		strip.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		// One child at a time, swapped on click — not a CardLayout. A CardLayout has to be told its height,
		// and the boss and clue sprites arrive asynchronously AFTER it's measured, so the grid outgrew the
		// space it was given and the last row was cut off. A plain parent re-lays out when they land.
		JPanel body = new JPanel(new BorderLayout());
		body.setOpaque(false);
		body.setAlignmentX(JComponent.LEFT_ALIGNMENT);

		List<JLabel> labels = new ArrayList<>();
		for (Map.Entry<String, JPanel> e : sections.entrySet())
		{
			JLabel l = new JLabel(e.getKey(), SwingConstants.CENTER);
			l.setFont(FontManager.getRunescapeSmallFont());
			l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			labels.add(l);
			strip.add(l);
		}
		for (JLabel l : labels)
		{
			l.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					styleSwitch(labels, l.getText());
					show(body, sections.get(l.getText()));
					if (onSection != null)
					{
						onSection.accept(l.getText());
					}
				}
			});
		}
		styleSwitch(labels, first);
		show(body, sections.get(first));
		if (showLog != null && sections.containsKey("Log"))
		{
			showLog[0] = () ->
			{
				styleSwitch(labels, "Log");
				show(body, sections.get("Log"));
				if (onSection != null)
				{
					onSection.accept("Log");
				}
			};
		}

		strip.setMaximumSize(new Dimension(Integer.MAX_VALUE, strip.getPreferredSize().height));
		wrap.add(strip);
		wrap.add(gap(4));
		wrap.add(body);
		return wrap;
	}

	private static void show(JPanel body, JPanel section)
	{
		body.removeAll();
		body.add(section, BorderLayout.CENTER);
		body.revalidate();
		body.repaint();
	}

	private static void styleSwitch(List<JLabel> labels, String selected)
	{
		for (JLabel l : labels)
		{
			boolean on = l.getText().equals(selected);
			l.setForeground(on ? Color.WHITE : MUTED);
			l.setOpaque(true);
			l.setBackground(on ? ColorScheme.DARKER_GRAY_COLOR : ColorScheme.DARK_GRAY_COLOR);
			l.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
		}
	}

	/** The game's skill tab: icon + level, three across, XP on hover. */
	private static JPanel skills(List<PlayerCard.Skill> skills, Icons ic)
	{
		Map<String, PlayerCard.Skill> byKey = new LinkedHashMap<>();
		for (PlayerCard.Skill s : skills)
		{
			byKey.put(s.key.toLowerCase(Locale.ROOT), s);
		}
		JPanel block = block();
		JPanel grid = new JPanel(new GridLayout(0, 3, 2, 2));
		grid.setOpaque(false);
		grid.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		for (Skill skill : SKILL_GRID)
		{
			PlayerCard.Skill s = byKey.get(skill.name().toLowerCase(Locale.ROOT));
			int level = s != null ? s.level : 1;
			JLabel cell = new JLabel(Integer.toString(level));
			cell.setFont(FontManager.getRunescapeSmallFont());
			cell.setForeground(level >= 99 ? ColorScheme.BRAND_ORANGE : Color.WHITE);
			cell.setIconTextGap(6);
			cell.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
			cell.setOpaque(true);
			cell.setBackground(ColorScheme.DARK_GRAY_COLOR);
			cell.setToolTipText(s != null
				? String.format(Locale.ROOT, "%s — %,d XP", skill.getName(), s.xp)
				: skill.getName());
			if (ic.skills != null)
			{
				cell.setIcon(new ImageIcon(ic.skills.getSkillImage(skill, true)));
			}
			else
			{
				cell.setText(abbrev(skill.getName()) + " " + level);
			}
			grid.add(cell);
		}
		block.add(grid);
		return block;
	}

	/** Every boss with a kill: the game's own sprite and the count, three across. */
	private static JPanel bosses(List<PlayerCard.Boss> rows, Icons ic, Consumer<String> openLogPage)
	{
		JPanel block = block();
		JPanel grid = new JPanel(new GridLayout(0, 3, 2, 2));
		grid.setOpaque(false);
		grid.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		for (PlayerCard.Boss boss : rows)
		{
			boolean opens = openLogPage != null && boss.clogPage != null;
			JLabel cell = iconCell(hiscoreSkill(boss.key, boss.label), boss.label, boss.kc,
				String.format(Locale.ROOT, "%s — %,d kills%s", boss.label, boss.kc, opens ? " · click for its log" : ""), ic);
			if (opens)
			{
				hoverable(cell, ColorScheme.DARK_GRAY_COLOR, () -> openLogPage.accept(boss.clogPage));
			}
			grid.add(cell);
		}
		block.add(grid);
		return block;
	}

	/**
	 * Personal bests: one row per boss and mode with its best time — a raid's Challenge Mode and an
	 * awakened boss are rows of their own. A row with several team sizes opens to list them.
	 */
	private static JPanel times(List<PlayerCard.PbEntry> entries, Icons ic)
	{
		JPanel block = block();
		for (PlayerCard.PbEntry e : entries)
		{
			boolean expandable = e.variants != null && !e.variants.isEmpty();
			JPanel row = new JPanel(new BorderLayout(6, 0));
			row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
			row.setBorder(BorderFactory.createEmptyBorder(3, 2, 3, 4));
			JLabel icon = new JLabel();
			icon.setPreferredSize(new Dimension(24, 22));
			icon.setHorizontalAlignment(SwingConstants.CENTER);
			bossIcon(icon, e.base, ic);
			row.add(icon, BorderLayout.WEST);
			row.add(small(e.name, Color.WHITE), BorderLayout.CENTER);
			JPanel right = new JPanel(new BorderLayout(5, 0));
			right.setOpaque(false);
			right.add(small(e.best, ColorScheme.BRAND_ORANGE), BorderLayout.CENTER);
			JLabel chevron = new JLabel(expandable ? new GlyphIcon(GlyphIcon.Shape.CHEVRON_RIGHT, 8, MUTED) : null);
			chevron.setPreferredSize(new Dimension(8, 8));
			right.add(chevron, BorderLayout.EAST);
			row.add(right, BorderLayout.EAST);
			row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
			block.add(row);
			if (!expandable)
			{
				row.setOpaque(false);
				continue;
			}
			JPanel teams = column();
			teams.setOpaque(false);
			teams.setBorder(BorderFactory.createEmptyBorder(0, 32, 4, 16));
			for (PlayerCard.PbVariant v : e.variants)
			{
				JPanel line = new JPanel(new BorderLayout());
				line.setOpaque(false);
				line.setAlignmentX(JComponent.LEFT_ALIGNMENT);
				line.setToolTipText(safe(v.activity));
				line.add(small("Solo".equalsIgnoreCase(v.team) ? "Solo" : v.team + " players", MUTED), BorderLayout.WEST);
				line.add(small(v.time, ColorScheme.BRAND_ORANGE), BorderLayout.EAST);
				line.setMaximumSize(new Dimension(Integer.MAX_VALUE, line.getPreferredSize().height));
				teams.add(line);
			}
			teams.setVisible(false);
			block.add(teams);
			hoverable(row, ColorScheme.DARKER_GRAY_COLOR, () ->
			{
				teams.setVisible(!teams.isVisible());
				chevron.setIcon(new GlyphIcon(teams.isVisible() ? GlyphIcon.Shape.CHEVRON_LEFT : GlyphIcon.Shape.CHEVRON_RIGHT, 8, MUTED));
				block.revalidate();
				block.repaint();
			});
		}
		return block;
	}

	/**
	 * The game's own picture for a boss named the way a personal best names it: the matcher first
	 * (RuneLite's "THE_" names, modes), then a boss whose name it contains ("Demonic Brutus" → Brutus),
	 * then a skill it names (agility courses). Nothing at all leaves the cell empty.
	 */
	static HiscoreSkill bossFor(String name)
	{
		HiscoreSkill direct = hiscoreSkill(name, name);
		if (direct != null)
		{
			return direct;
		}
		String f = fold(name);
		HiscoreSkill best = null;
		for (HiscoreSkill s : HiscoreSkill.values())
		{
			String n = fold(s.getName());
			if (n.length() >= 4 && f.contains(n) && (best == null || n.length() > fold(best.getName()).length()))
			{
				best = s;
			}
		}
		return best;
	}

	private static void bossIcon(JLabel icon, String name, Icons ic)
	{
		HiscoreSkill boss = bossFor(name);
		if (boss != null && boss.getSpriteId() > 0 && ic.sprites != null)
		{
			ic.sprites.addSpriteTo(icon, boss.getSpriteId(), 0);
			return;
		}
		if (ic.skills != null && name != null)
		{
			for (Skill skill : SKILL_GRID)
			{
				if (name.toLowerCase(Locale.ROOT).contains(skill.getName().toLowerCase(Locale.ROOT)))
				{
					icon.setIcon(new ImageIcon(ic.skills.getSkillImage(skill, true)));
					return;
				}
			}
		}
	}

	private static boolean isBlank(String s)
	{
		return s == null || s.trim().isEmpty();
	}

	/** Clues, minigames and the rest with a score — same grid as bosses. */
	private static JPanel activities(List<PlayerCard.Activity> rows, Icons ic)
	{
		JPanel block = block();
		JPanel grid = new JPanel(new GridLayout(0, 3, 2, 2));
		grid.setOpaque(false);
		grid.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		for (PlayerCard.Activity a : rows)
		{
			String name = a.shortLabel != null && !a.shortLabel.isEmpty() ? a.shortLabel : a.label;
			grid.add(iconCell(hiscoreSkill(a.key, a.label), clueItemFor(a.key), name, a.score,
				String.format(Locale.ROOT, "%s — %,d", a.label, a.score), ic));
		}
		block.add(grid);
		return block;
	}

	/** Clue tiers have no hiscore sprite; the game's own clue scroll items stand in. */
	static int clueItemFor(String key)
	{
		switch (key == null ? "" : key)
		{
			case "cluesBeginner":
				return 23182;
			case "cluesEasy":
				return 2677;
			case "cluesMedium":
				return 2801;
			case "cluesHard":
				return 2722;
			case "cluesElite":
				return 12073;
			case "cluesMaster":
				return 19835;
			default:
				return -1;
		}
	}

	private static JLabel iconCell(HiscoreSkill skill, String name, long count, String tooltip, Icons ic)
	{
		return iconCell(skill, -1, name, count, tooltip, ic);
	}

	private static JLabel iconCell(HiscoreSkill skill, int itemId, String name, long count, String tooltip, Icons ic)
	{
		JLabel cell = new JLabel(compact(count));
		cell.setFont(FontManager.getRunescapeSmallFont());
		cell.setForeground(Color.WHITE);
		cell.setIconTextGap(4);
		cell.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
		cell.setOpaque(true);
		cell.setBackground(ColorScheme.DARK_GRAY_COLOR);
		cell.setToolTipText(safe(tooltip));
		if (itemId > 0 && ic.items != null)
		{
			ic.items.getImage(itemId).addTo(cell);
		}
		else if (skill != null && skill.getSpriteId() > 0 && ic.sprites != null)
		{
			ic.sprites.addSpriteTo(cell, skill.getSpriteId(), 0);
		}
		else
		{
			// No picture for it: the name has to carry it, or it's an unlabelled number.
			cell.setText(safe(abbrev(name) + " " + compact(count)));
		}
		return cell;
	}

	/** Client property on the Log holder: its top level (the category grid), for "back" from a jump. */
	private static final String LOG_ROOT = "anvil.logRoot";

	/** A picture per log category — the thing people think of when they think of that shelf. */
	static int categoryItem(String group)
	{
		switch (group == null ? "" : group)
		{
			case "Bosses":
				return 11832; // Bandos chestplate
			case "Raids":
				return 20997; // Twisted bow
			case "Clues":
				return 19835; // Clue scroll (master)
			case "Minigames":
				return 6570; // Fire cape
			case "Skilling":
				return 13320; // Heron
			default:
				return 995; // Coins
		}
	}

	/**
	 * The log as three levels, each one obviously clickable: categories as tiles → that category's
	 * pages (icon, count, ›) → the page's items, lit and dim. A long list that expanded in place didn't
	 * say it could open, and mixed every shelf into one scroll.
	 */
	/**
	 * Kills behind each log page — a raid's page counts every mode (bosses carry the page their kills
	 * fill), so "Chambers of Xeric" is normal and Challenge Mode together, as the game's log counts it.
	 */
	static Map<String, Integer> pageKills(List<PlayerCard.Boss> bosses)
	{
		Map<String, Integer> out = new java.util.HashMap<>();
		if (bosses != null)
		{
			for (PlayerCard.Boss b : bosses)
			{
				if (b.clogPage != null && b.kc > 0)
				{
					out.merge(b.clogPage.toLowerCase(Locale.ROOT), b.kc, Integer::sum);
				}
			}
		}
		return out;
	}

	private static final String PAGE_KC = "anvil.pageKc";

	private static Integer killsFor(JPanel holder, String page)
	{
		@SuppressWarnings("unchecked")
		Map<String, Integer> kc = (Map<String, Integer>) holder.getClientProperty(PAGE_KC);
		return kc == null || page == null ? null : kc.get(page.toLowerCase(Locale.ROOT));
	}

	private static JPanel logPages(String rsn, List<PlayerCard.Page> pages, Icons ic, Map<String, Integer> kills)
	{
		JPanel holder = new JPanel(new BorderLayout());
		holder.setOpaque(false);
		holder.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		holder.putClientProperty(PAGE_KC, kills);
		JPanel root = categoryGrid(rsn, pages, ic, holder);
		holder.putClientProperty(LOG_ROOT, root);
		holder.add(root, BorderLayout.CENTER);
		return holder;
	}

	private static JPanel categoryGrid(String rsn, List<PlayerCard.Page> pages, Icons ic, JPanel holder)
	{
		Map<String, List<PlayerCard.Page>> byGroup = new LinkedHashMap<>();
		for (PlayerCard.Page p : pages)
		{
			byGroup.computeIfAbsent(p.group == null ? "Other" : p.group, k -> new ArrayList<>()).add(p);
		}
		JPanel block = block();
		JPanel grid = new JPanel(new GridLayout(0, 2, 6, 6));
		grid.setOpaque(false);
		grid.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		for (Map.Entry<String, List<PlayerCard.Page>> e : byGroup.entrySet())
		{
			int got = 0;
			int of = 0;
			for (PlayerCard.Page p : e.getValue())
			{
				got += p.obtained;
				of += p.total;
			}
			JPanel tile = new JPanel(new BorderLayout(6, 3));
			tile.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
			JLabel icon = new JLabel();
			icon.setPreferredSize(new Dimension(28, 26));
			icon.setHorizontalAlignment(SwingConstants.CENTER);
			if (ic.items != null)
			{
				ic.items.getImage(categoryItem(e.getKey())).addTo(icon);
			}
			tile.add(icon, BorderLayout.WEST);
			JPanel text = column();
			text.setOpaque(false);
			JLabel name = small(e.getKey() + "  ›", Color.WHITE);
			text.add(name);
			text.add(small(got + "/" + of + " · " + percent(got, of) + "%", MUTED));
			tile.add(text, BorderLayout.CENTER);
			tile.add(thinBar(got, of), BorderLayout.SOUTH);
			List<PlayerCard.Page> list = e.getValue();
			String group = e.getKey();
			int g = got;
			int o = of;
			hoverable(tile, ColorScheme.DARK_GRAY_COLOR, () -> swap(holder, categoryView(rsn, group, g, o, list, ic, holder, block)));
			grid.add(tile);
		}
		block.add(grid);
		return block;
	}

	private static JPanel categoryView(String rsn, String group, int got, int of, List<PlayerCard.Page> pages, Icons ic,
		JPanel holder, JComponent back)
	{
		JPanel view = block();
		view.add(backHeader(group, got + "/" + of, got >= of, () -> swap(holder, back), null));
		view.add(gap(6));
		for (PlayerCard.Page p : pages)
		{
			view.add(pageRow(rsn, p, ic, holder, view));
			view.add(gap(2));
		}
		return view;
	}

	private static JPanel pageRow(String rsn, PlayerCard.Page p, Icons ic, JPanel holder, JComponent back)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		row.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(26, 24));
		icon.setHorizontalAlignment(SwingConstants.CENTER);
		if (ic.items != null && p.iconItemId != null)
		{
			ic.items.getImage(p.iconItemId).addTo(icon);
		}
		row.add(icon, BorderLayout.WEST);
		boolean complete = p.total > 0 && p.obtained >= p.total;
		Integer kc = killsFor(holder, p.name);
		if (kc != null)
		{
			JPanel text = column();
			text.setOpaque(false);
			text.add(small(p.name, complete ? ColorScheme.PROGRESS_COMPLETE_COLOR : Color.WHITE));
			text.add(small(String.format(Locale.ROOT, "%,d kc", kc), MUTED));
			row.add(text, BorderLayout.CENTER);
		}
		else
		{
			row.add(small(p.name, complete ? ColorScheme.PROGRESS_COMPLETE_COLOR : Color.WHITE), BorderLayout.CENTER);
		}
		JPanel right = new JPanel(new BorderLayout(6, 0));
		right.setOpaque(false);
		right.add(small(p.obtained + "/" + p.total, MUTED), BorderLayout.CENTER);
		if (ic.pageLoader != null)
		{
			right.add(new JLabel(new GlyphIcon(GlyphIcon.Shape.CHEVRON_RIGHT, 9, MUTED)), BorderLayout.EAST);
		}
		row.add(right, BorderLayout.EAST);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		if (ic.pageLoader != null)
		{
			row.setToolTipText("See the items on this page");
			hoverable(row, ColorScheme.DARKER_GRAY_COLOR, () -> showPage(rsn, p, ic, holder, back));
		}
		else
		{
			row.setOpaque(false);
		}
		return row;
	}

	/** Lights up under the mouse and does {@code onClick} — so a row or tile says it can be opened. */
	private static void hoverable(JComponent c, Color base, Runnable onClick)
	{
		Color hover = ColorScheme.DARK_GRAY_HOVER_COLOR;
		c.setOpaque(true);
		c.setBackground(base);
		c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		c.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				onClick.run();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				c.setBackground(hover);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				c.setBackground(base);
			}
		});
	}

	private static JPanel thinBar(int obtained, int total)
	{
		int pct = percent(obtained, total);
		JPanel bar = new JPanel()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				g.setColor(ColorScheme.DARKER_GRAY_COLOR);
				g.fillRect(0, 0, getWidth(), getHeight());
				g.setColor(pct >= 100 ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.BRAND_ORANGE);
				g.fillRect(0, 0, getWidth() * pct / 100, getHeight());
			}
		};
		bar.setPreferredSize(new Dimension(0, 3));
		return bar;
	}

	private static void showPage(String rsn, PlayerCard.Page p, Icons ic, JPanel holder, JComponent back)
	{
		JPanel page = block();
		Integer kc = killsFor(holder, p.name);
		page.add(backHeader(p.name, p.obtained + "/" + p.total + (kc != null ? String.format(Locale.ROOT, " · %,d kc", kc) : ""),
			p.total > 0 && p.obtained >= p.total,
			() -> swap(holder, back == null ? (JComponent) holder.getClientProperty(LOG_ROOT) : back), rsn));
		page.add(gap(8));
		page.add(small("Loading…", MUTED));
		swap(holder, page);
		ic.pageLoader.accept(p.name, view ->
		{
			if (holder.getComponentCount() == 0 || holder.getComponent(0) != page)
			{
				return; // they went Back (or elsewhere) before it landed
			}
			page.remove(page.getComponentCount() - 1);
			if (view == null || view.items == null)
			{
				page.add(small("Couldn't load this page — try again in a moment.", MUTED));
			}
			else
			{
				page.add(itemGrid(view.items, ic));
			}
			page.revalidate();
			page.repaint();
		});
	}

	/**
	 * "‹ Name   12/30  ↗": back on the left, the count, and — for a page, when {@code rsn} is given —
	 * the same page on the website.
	 */
	private static JPanel backHeader(String name, String count, boolean complete, Runnable back, String rsn)
	{
		JPanel head = new JPanel(new BorderLayout(6, 0));
		head.setOpaque(false);
		head.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		JLabel left = new JLabel(new GlyphIcon(GlyphIcon.Shape.CHEVRON_LEFT, 10, Color.WHITE));
		left.setText(safe(name));
		left.setFont(FontManager.getRunescapeBoldFont());
		left.setForeground(complete ? ColorScheme.PROGRESS_COMPLETE_COLOR : Color.WHITE);
		left.setIconTextGap(6);
		left.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		left.setToolTipText("Back");
		left.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				back.run();
			}
		});
		head.add(left, BorderLayout.CENTER);

		JPanel right = new JPanel(new GridLayout(1, 0, 6, 0));
		right.setOpaque(false);
		right.add(small(count, Color.WHITE));
		if (rsn != null)
		{
			JLabel web = new JLabel(new GlyphIcon(GlyphIcon.Shape.EXTERNAL, 11, MUTED));
			web.setToolTipText("Open this page on the Anvil website");
			web.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			web.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					String url = ProfileLookup.profileUrl(rsn);
					if (url != null)
					{
						net.runelite.client.util.LinkBrowser.browse(url + "?tab=collection&page="
							+ java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"));
					}
				}
			});
			right.add(web);
		}
		head.add(right, BorderLayout.EAST);
		head.setMaximumSize(new Dimension(Integer.MAX_VALUE, head.getPreferredSize().height));
		return head;
	}

	/** The page's slots in the game's order: owned in full colour, the rest dim — as the log draws them. */
	private static JPanel itemGrid(List<BingoApiClient.ClogPageItem> items, Icons ic)
	{
		JPanel grid = new JPanel(new GridLayout(0, 5, 3, 3));
		grid.setOpaque(false);
		grid.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		for (BingoApiClient.ClogPageItem item : items)
		{
			JLabel cell = new JLabel()
			{
				@Override
				protected void paintComponent(Graphics g)
				{
					if (!item.owned)
					{
						java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
						g2.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.25f));
						super.paintComponent(g2);
						g2.dispose();
						return;
					}
					super.paintComponent(g);
				}
			};
			cell.setHorizontalAlignment(SwingConstants.CENTER);
			cell.setPreferredSize(new Dimension(36, 32));
			cell.setOpaque(false);
			cell.setToolTipText(safe(item.owned && item.quantity > 1
				? String.format(Locale.ROOT, "%s × %,d", item.name, item.quantity)
				: item.owned ? item.name : item.name + " — not yet"));
			if (ic.items != null)
			{
				ic.items.getImage(item.id, Math.max(1, item.quantity), item.quantity > 1).addTo(cell);
			}
			else
			{
				cell.setText(item.owned ? "■" : "□");
			}
			grid.add(cell);
		}
		return grid;
	}

	private static void swap(JPanel holder, JComponent next)
	{
		holder.removeAll();
		holder.add(next, BorderLayout.CENTER);
		holder.revalidate();
		holder.repaint();
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

	/** "Bosses  62%" over a 3px bar — a share of the log at a glance. */
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
		return Box.createVerticalStrut(h);
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

	/**
	 * The site's account types (lib/accountType: the game's varbit, reported by the plugin — the
	 * hiscores can't tell the group modes apart) in the short form players use. Null type never gets
	 * here: an account the plugin hasn't seen shows no type rather than a guessed "Main".
	 */
	static String accountTypeLabel(String type)
	{
		switch (type)
		{
			case "normal":
			case "main":
				return "Main";
			case "ironman":
				return "Ironman";
			case "ultimate":
				return "UIM";
			case "hardcore":
				return "HCIM";
			case "group":
				return "GIM";
			case "hardcore_group":
				return "HCGIM";
			case "unranked_group":
				return "UGIM";
			default:
				return Character.toUpperCase(type.charAt(0)) + type.substring(1).replace('_', ' ');
		}
	}

	private static String fold(String s)
	{
		return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}

	/**
	 * RuneLite's hiscore entry for a boss or activity the site names, so the card can use the game's
	 * own icon. The site's key ("alchemicalHydra") and RuneLite's enum (ALCHEMICAL_HYDRA) fold to the
	 * same letters; the label catches the rest, and a prefix catches renames like "barrows" → BARROWS_CHESTS.
	 */
	static HiscoreSkill hiscoreSkill(String key, String label)
	{
		// The site says "cluesBeginner", RuneLite says CLUE_SCROLL_BEGINNER.
		String k = fold(key).replaceFirst("^clues", "cluescroll");
		String l = fold(label);
		for (HiscoreSkill s : HiscoreSkill.values())
		{
			String e = fold(s.name());
			if (!k.isEmpty() && e.equals(k) || !l.isEmpty() && (e.equals(l) || fold(s.getName()).equals(l)))
			{
				return s;
			}
		}
		if (k.isEmpty())
		{
			return null;
		}
		// RuneLite says "THE_" where we don't (corruptedGauntlet ↔ THE_CORRUPTED_GAUNTLET), says more
		// than we do (barrows ↔ BARROWS_CHESTS), or less (tombsOfAmascutExpertMode ↔ TOMBS_OF_AMASCUT_EXPERT).
		// Longest match wins, so the expert raid doesn't settle for the plain one.
		HiscoreSkill best = null;
		int bestLen = 0;
		for (HiscoreSkill s : HiscoreSkill.values())
		{
			String e = fold(s.name());
			if (e.equals("the" + k))
			{
				return s;
			}
			boolean fits = e.startsWith(k) || (e.length() >= 6 && k.startsWith(e));
			if (fits && e.length() > bestLen)
			{
				best = s;
				bestLen = e.length();
			}
		}
		return best;
	}

	/** Label-only lookup — kept for the summary's older callers and tests. */
	static HiscoreSkill hiscoreSkill(String label)
	{
		return hiscoreSkill(null, label);
	}

	private static String abbrev(String label)
	{
		return label.length() <= 8 ? label : label.substring(0, 7) + "…";
	}

	private static StringBuilder sep(StringBuilder sb)
	{
		return sb.length() > 0 ? sb.append(" · ") : sb;
	}
}
