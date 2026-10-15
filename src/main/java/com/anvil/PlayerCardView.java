package com.anvil;

import java.awt.BorderLayout;
import java.awt.Color;
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
import net.runelite.client.ui.components.ProgressBar;

import static com.anvil.Ui.*;

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

		Icons(ItemManager items, SpriteManager sprites, SkillIconManager skills,
			java.util.function.BiConsumer<String, Consumer<BingoApiClient.ClogPageView>> pageLoader)
		{
			this.items = items;
			this.sprites = sprites;
			this.skills = skills;
			this.pageLoader = pageLoader;
		}

		static final Icons NONE = new Icons(null, null, null, null);
	}

	private PlayerCardView()
	{
	}

	/**
	 * @param section   which profile section to open on ("Skills", "Bosses", "Clues", "Times"); null or
	 *                  one this card doesn't have → the first it does
	 * @param onSection told when the member switches section, so a re-render can reopen the same one
	 */
	static JPanel render(PlayerCard card, Icons icons, String section, Consumer<String> onSection)
	{
		Icons ic = icons == null ? Icons.NONE : icons;
		JPanel root = column();
		root.add(identity(card));

		// The log lives in its summary block: the total, then the category tiles, which open in place to
		// a category's pages and a page's items. It used to be a second copy under the switcher.
		// A hiscores card has no collection log or Anvil feed; anything the site sends for one is ignored.
		boolean anvil = !card.fromHiscores();
		JPanel log = anvil && card.clogPages != null && !card.clogPages.isEmpty()
			? logPages(card.rsn, card.clogPages, ic, pageKills(card.bosses))
			: null;
		if (anvil && card.clog != null && card.clog.total > 0)
		{
			root.add(gap(BLOCK_GAP));
			root.add(clogSummary(card.clog, log));
		}
		if (anvil && card.latest != null && !card.latest.isEmpty())
		{
			root.add(gap(BLOCK_GAP));
			root.add(latest(card.rsn, card.latest, ic));
		}

		// A boss (or a clue tier) opens the log page it fills, in the log block above.
		Consumer<String> openLogPage = log == null || ic.pageLoader == null ? null : name ->
		{
			for (PlayerCard.Page p : card.clogPages)
			{
				if (p.name.equalsIgnoreCase(name))
				{
					showPage(card.rsn, p, ic, log, (JComponent) log.getClientProperty(LOG_ROOT));
					log.scrollRectToVisible(new java.awt.Rectangle(0, 0, 1, 1));
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
			JPanel grid = grid(3, 2, 2);
			for (PlayerCard.Boss boss : bosses)
			{
				grid.add(iconCell(hiscoreSkill(boss.key, boss.label), -1, boss.label, boss.kc,
					String.format(Locale.ROOT, "%s — %,d kills", boss.label, boss.kc), ic, openLogPage, boss.clogPage));
			}
			sections.put("Bosses", blockOf(grid));
		}
		// Clues, minigames and the rest with a score — same grid as bosses.
		if (card.activities != null && !card.activities.isEmpty())
		{
			JPanel grid = grid(3, 2, 2);
			for (PlayerCard.Activity a : card.activities)
			{
				String name = a.shortLabel != null && !a.shortLabel.isEmpty() ? a.shortLabel : a.label;
				grid.add(iconCell(hiscoreSkill(a.key, a.label), clueItemFor(a.key), name, a.score,
					String.format(Locale.ROOT, "%s — %,d", a.label, a.score), ic, openLogPage, cluePage(a.key)));
			}
			sections.put("Clues", blockOf(grid));
		}
		if (anvil && card.times != null && !card.times.isEmpty())
		{
			sections.put("Times", times(card.times, ic));
		}
		if (!sections.isEmpty())
		{
			root.add(gap(BLOCK_GAP));
			root.add(sectionSwitcher(sections, section, onSection));
		}
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

	// ---- Summary ------------------------------------------------------------------------------------

	private static JPanel identity(PlayerCard card)
	{
		JPanel block = block();

		// Name on the left, the website one small click away on the right — the panel IS the profile
		// now, so the browser is an icon, not the card's main button.
		JPanel nameRow = row(6);
		nameRow.add(title(card.rsn), BorderLayout.CENTER);
		// A hiscores card has no Anvil profile to open.
		if (!card.fromHiscores())
		{
			JLabel web = onClick(new JLabel(new GlyphIcon(GlyphIcon.Shape.EXTERNAL, 12, MUTED)), () -> ProfileLookup.open(card.rsn));
			web.setToolTipText("Open on the Anvil website");
			nameRow.add(web, BorderLayout.EAST);
		}
		block.add(capH(nameRow));
		if (card.fromHiscores())
		{
			String when = ago(card.fetchedAt);
			block.add(gap(2));
			block.add(small("From the hiscores — not on Anvil" + (when.isEmpty() ? "" : " · " + when), MUTED));
		}

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

	private static JPanel clogSummary(PlayerCard.Clog clog, JPanel log)
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
		block.add(left(bar));

		if (log != null)
		{
			// The categories as tiles that open — the bars that sat here only said how far each was.
			block.add(gap(6));
			block.add(left(log));
		}
		else if (clog.groups != null && !clog.groups.isEmpty())
		{
			block.add(gap(6));
			JPanel grid = grid(2, 8, 4);
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
		JLabel all = onClick(small("See all ›", MUTED), () ->
		{
			String url = ProfileLookup.profileUrl(rsn);
			if (url != null)
			{
				net.runelite.client.util.LinkBrowser.browse(url + "?tab=records");
			}
		});
		all.setToolTipText("Everything they've done lately, on the Anvil website");
		all.addMouseListener(new MouseAdapter()
		{
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
			JPanel line = row(6);
			JLabel icon = iconSlot(24, 22);
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

	private static JPanel sectionSwitcher(Map<String, JPanel> sections, String wanted, Consumer<String> onSection)
	{
		String first = sections.containsKey(wanted) ? wanted : sections.keySet().iterator().next();

		JPanel wrap = column();
		JPanel strip = left(new JPanel(new GridLayout(1, sections.size())));
		strip.setBackground(ColorScheme.DARK_GRAY_COLOR);
		// One child at a time, swapped on click — not a CardLayout. A CardLayout has to be told its height,
		// and the boss and clue sprites arrive asynchronously AFTER it's measured, so the grid outgrew the
		// space it was given and the last row was cut off. A plain parent re-lays out when they land.
		JPanel body = row(0);

		List<JLabel> labels = new ArrayList<>();
		for (String name : sections.keySet())
		{
			JLabel l = onClick(new JLabel(name, SwingConstants.CENTER), () ->
			{
				styleSwitch(labels, name);
				swap(body, sections.get(name));
				if (onSection != null)
				{
					onSection.accept(name);
				}
			});
			l.setFont(SMALL);
			labels.add(l);
			strip.add(l);
		}
		styleSwitch(labels, first);
		swap(body, sections.get(first));

		wrap.add(capH(strip));
		wrap.add(gap(4));
		wrap.add(body);
		return wrap;
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
		JPanel grid = grid(3, 2, 2);
		for (Skill skill : SKILL_GRID)
		{
			PlayerCard.Skill s = byKey.get(skill.name().toLowerCase(Locale.ROOT));
			int level = s != null ? s.level : 1;
			JLabel cell = cell(Integer.toString(level), level >= 99 ? ColorScheme.BRAND_ORANGE : Color.WHITE, 6);
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
		return blockOf(grid);
	}

	private static JPanel blockOf(JComponent content)
	{
		JPanel block = block();
		block.add(content);
		return block;
	}

	/** A transparent, left-aligned grid {@code cols} across. */
	private static JPanel grid(int cols, int hgap, int vgap)
	{
		JPanel grid = left(new JPanel(new GridLayout(0, cols, hgap, vgap)));
		grid.setOpaque(false);
		return grid;
	}

	/** A grid cell: small text on the panel background, room for an icon to its left. */
	private static JLabel cell(String text, Color color, int iconGap)
	{
		JLabel cell = small(text, color);
		cell.setIconTextGap(iconGap);
		cell.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
		cell.setOpaque(true);
		cell.setBackground(ColorScheme.DARK_GRAY_COLOR);
		return cell;
	}

	/** An empty, centred icon holder of a fixed size. */
	static JLabel iconSlot(int w, int h)
	{
		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(w, h));
		icon.setHorizontalAlignment(SwingConstants.CENTER);
		return icon;
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
			JPanel row = row(6);
			row.setBorder(BorderFactory.createEmptyBorder(3, 2, 3, 4));
			JLabel icon = iconSlot(24, 22);
			bossIcon(icon, e.base, ic);
			row.add(icon, BorderLayout.WEST);
			row.add(small(e.name, Color.WHITE), BorderLayout.CENTER);
			JPanel right = row(5);
			right.add(small(e.best, ColorScheme.BRAND_ORANGE), BorderLayout.CENTER);
			JLabel chevron = new JLabel(expandable ? new GlyphIcon(GlyphIcon.Shape.CHEVRON_RIGHT, 8, MUTED) : null);
			chevron.setPreferredSize(new Dimension(8, 8));
			right.add(chevron, BorderLayout.EAST);
			row.add(right, BorderLayout.EAST);
			block.add(capH(row));
			if (!expandable)
			{
				continue;
			}
			JPanel teams = column();
			teams.setOpaque(false);
			teams.setBorder(BorderFactory.createEmptyBorder(0, 32, 4, 16));
			for (PlayerCard.PbVariant v : e.variants)
			{
				JPanel line = row(0);
				line.setToolTipText(plainText(v.activity));
				line.add(small("Solo".equalsIgnoreCase(v.team) ? "Solo" : v.team + " players", MUTED), BorderLayout.WEST);
				line.add(small(v.time, ColorScheme.BRAND_ORANGE), BorderLayout.EAST);
				teams.add(capH(line));
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

	static void bossIcon(JLabel icon, String name, Icons ic)
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

	/** The log page a clue tier fills: cluesHard → "Hard Treasure Trails". "All tiers" has none. */
	static String cluePage(String key)
	{
		if (key == null || !key.startsWith("clues") || "cluesAll".equals(key))
		{
			return null;
		}
		String tier = key.substring("clues".length());
		return tier.isEmpty() ? null : tier + " Treasure Trails";
	}

	/** Clue tiers have no hiscore sprite; the game's own clue scroll items stand in. */
	private static final Map<String, Integer> CLUE_ITEMS = Map.of(
		"cluesBeginner", 23182, "cluesEasy", 2677, "cluesMedium", 2801,
		"cluesHard", 2722, "cluesElite", 12073, "cluesMaster", 19835);

	static int clueItemFor(String key)
	{
		return CLUE_ITEMS.getOrDefault(key == null ? "" : key, -1);
	}

	/**
	 * A count with the game's own picture — an item, else a hiscore sprite, else the name in text. A
	 * cell whose {@code page} can be opened lights up and opens it.
	 */
	private static JLabel iconCell(HiscoreSkill skill, int itemId, String name, long count, String tooltip, Icons ic,
		Consumer<String> openLogPage, String page)
	{
		boolean opens = openLogPage != null && page != null;
		JLabel cell = cell(compact(count), Color.WHITE, 4);
		cell.setToolTipText(plainText(tooltip + (opens ? " · click for its log" : "")));
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
			cell.setText(plainText(abbrev(name) + " " + compact(count)));
		}
		if (opens)
		{
			hoverable(cell, ColorScheme.DARK_GRAY_COLOR, () -> openLogPage.accept(page));
		}
		return cell;
	}

	/** Client property on the Log holder: its top level (the category grid), for "back" from a jump. */
	private static final String LOG_ROOT = "anvil.logRoot";

	/** A picture per log category — the thing people think of when they think of that shelf. */
	private static final Map<String, Integer> CATEGORY_ITEMS = Map.of(
		"Bosses", 11832, // Bandos chestplate
		"Raids", 20997, // Twisted bow
		"Clues", 19835, // Clue scroll (master)
		"Minigames", 6570, // Fire cape
		"Skilling", 13320); // Heron

	static int categoryItem(String group)
	{
		return CATEGORY_ITEMS.getOrDefault(group == null ? "" : group, 995); // Coins
	}

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

	/**
	 * The log as three levels, each one obviously clickable: categories as tiles → that category's
	 * pages (icon, count, ›) → the page's items, lit and dim. A long list that expanded in place didn't
	 * say it could open, and mixed every shelf into one scroll.
	 */
	private static JPanel logPages(String rsn, List<PlayerCard.Page> pages, Icons ic, Map<String, Integer> kills)
	{
		JPanel holder = row(0);
		holder.putClientProperty(PAGE_KC, kills);
		JPanel root = categoryGrid(rsn, pages, ic, holder);
		holder.putClientProperty(LOG_ROOT, root);
		holder.add(root, BorderLayout.CENTER);
		return holder;
	}

	/** A log level inside the summary block: no second background or padding of its own. */
	private static JPanel inner()
	{
		JPanel p = column();
		p.setOpaque(false);
		return p;
	}

	private static JPanel categoryGrid(String rsn, List<PlayerCard.Page> pages, Icons ic, JPanel holder)
	{
		Map<String, List<PlayerCard.Page>> byGroup = new LinkedHashMap<>();
		for (PlayerCard.Page p : pages)
		{
			byGroup.computeIfAbsent(p.group == null ? "Other" : p.group, k -> new ArrayList<>()).add(p);
		}
		JPanel block = inner();
		JPanel grid = grid(2, 6, 6);
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
			JLabel icon = iconSlot(28, 26);
			if (ic.items != null)
			{
				ic.items.getImage(categoryItem(e.getKey())).addTo(icon);
			}
			tile.add(icon, BorderLayout.WEST);
			JPanel text = inner();
			text.add(small(e.getKey() + "  ›", Color.WHITE));
			text.add(small(got + "/" + of + " · " + percent(got, of) + "%", MUTED));
			tile.add(text, BorderLayout.CENTER);
			tile.add(thinBar(percent(got, of), ColorScheme.DARKER_GRAY_COLOR), BorderLayout.SOUTH);
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
		JPanel view = inner();
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
		JPanel row = row(6);
		row.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
		JLabel icon = iconSlot(26, 24);
		if (ic.items != null && p.iconItemId != null)
		{
			ic.items.getImage(p.iconItemId).addTo(icon);
		}
		row.add(icon, BorderLayout.WEST);
		boolean complete = p.total > 0 && p.obtained >= p.total;
		Integer kc = killsFor(holder, p.name);
		JLabel name = small(p.name, complete ? ColorScheme.PROGRESS_COMPLETE_COLOR : Color.WHITE);
		if (kc != null)
		{
			JPanel text = inner();
			text.add(name);
			text.add(small(String.format(Locale.ROOT, "%,d kc", kc), MUTED));
			row.add(text, BorderLayout.CENTER);
		}
		else
		{
			row.add(name, BorderLayout.CENTER);
		}
		JPanel right = row(6);
		right.add(small(p.obtained + "/" + p.total, MUTED), BorderLayout.CENTER);
		if (ic.pageLoader != null)
		{
			right.add(new JLabel(new GlyphIcon(GlyphIcon.Shape.CHEVRON_RIGHT, 9, MUTED)), BorderLayout.EAST);
		}
		row.add(right, BorderLayout.EAST);
		capH(row);
		if (ic.pageLoader != null)
		{
			row.setToolTipText("See the items on this page");
			hoverable(row, ColorScheme.DARKER_GRAY_COLOR, () -> showPage(rsn, p, ic, holder, back));
		}
		return row;
	}

	/** A 3px share-of-total bar: orange, green once full, over {@code bg}. */
	private static JPanel thinBar(int pct, Color bg)
	{
		JPanel bar = new JPanel()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				g.setColor(bg);
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
		JPanel page = inner();
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
		// Two lines: the name on its own (it was cut to "Tom…" beside the count), then the count, the
		// kills and the website link under it.
		JPanel head = inner();
		JLabel left = onClick(label(name, BOLD, complete ? ColorScheme.PROGRESS_COMPLETE_COLOR : Color.WHITE), back);
		left.setIcon(new GlyphIcon(GlyphIcon.Shape.CHEVRON_LEFT, 10, Color.WHITE));
		left.setIconTextGap(6);
		left.setToolTipText("Back");
		head.add(left);

		JPanel sub = row(6);
		sub.setBorder(BorderFactory.createEmptyBorder(1, 16, 0, 0));
		sub.add(small(count, MUTED), BorderLayout.CENTER);
		if (rsn != null)
		{
			JLabel web = onClick(new JLabel(new GlyphIcon(GlyphIcon.Shape.EXTERNAL, 11, MUTED)), () ->
			{
				String url = ProfileLookup.profileUrl(rsn);
				if (url != null)
				{
					net.runelite.client.util.LinkBrowser.browse(url + "?tab=collection&page="
						+ java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"));
				}
			});
			web.setToolTipText("Open this page on the Anvil website");
			sub.add(web, BorderLayout.EAST);
		}
		head.add(capH(sub));
		return capH(head);
	}

	/** The page's slots in the game's order: owned in full colour, the rest dim — as the log draws them. */
	private static JPanel itemGrid(List<BingoApiClient.ClogPageItem> items, Icons ic)
	{
		JPanel grid = grid(5, 3, 3);
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
			cell.setToolTipText(plainText(item.owned && item.quantity > 1
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

	/** A section's orange name, with an optional white figure at the right. */
	static JPanel header(String left, String right)
	{
		JPanel row = row(0);
		row.add(small(left, ColorScheme.BRAND_ORANGE), BorderLayout.WEST);
		if (right != null)
		{
			row.add(small(right, Color.WHITE), BorderLayout.EAST);
		}
		return capH(row);
	}

	/** "Bosses  62%" over a 3px bar — a share of the log at a glance. */
	static JPanel miniBar(String name, int obtained, int total)
	{
		int pct = percent(obtained, total);
		JPanel cell = new JPanel(new BorderLayout(0, 2));
		cell.setOpaque(false);
		cell.setToolTipText(plainText(String.format(Locale.ROOT, "%s: %,d / %,d", name, obtained, total)));
		JPanel text = new JPanel(new BorderLayout());
		text.setOpaque(false);
		text.add(small(name, MUTED), BorderLayout.WEST);
		text.add(small(pct + "%", Color.WHITE), BorderLayout.EAST);
		cell.add(text, BorderLayout.CENTER);
		cell.add(thinBar(pct, ColorScheme.DARK_GRAY_COLOR), BorderLayout.SOUTH);
		return cell;
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
	private static final Map<String, String> ACCOUNT_TYPES = Map.of(
		"normal", "Main", "main", "Main", "ironman", "Ironman", "ultimate", "UIM", "hardcore", "HCIM",
		"group", "GIM", "hardcore_group", "HCGIM", "unranked_group", "UGIM");

	static String accountTypeLabel(String type)
	{
		String known = ACCOUNT_TYPES.get(type);
		return known != null ? known : Character.toUpperCase(type.charAt(0)) + type.substring(1).replace('_', ' ');
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

	private static String abbrev(String label)
	{
		return label.length() <= 8 ? label : label.substring(0, 7) + "…";
	}

	private static StringBuilder sep(StringBuilder sb)
	{
		return sb.length() > 0 ? sb.append(" · ") : sb;
	}
}
