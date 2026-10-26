package com.anvil;

import com.google.gson.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import javax.swing.*;
import net.runelite.api.Skill;
import net.runelite.client.game.*;
import net.runelite.client.hiscore.*;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.components.ProgressBar;

import static com.anvil.Ui.*;

/**
 * A panel view the site built ({@code GET /api/plugin/ui}, capability {@code plugin-ui}): the site
 * decides every line, colour and click, and this draws it from a dozen building blocks (site
 * lib/pluginUi/nodes has the shape). Pictures arrive as game ids drawn with RuneLite's own managers —
 * never a URL — and a click is one of the fixed actions in {@link #act}; the ones that reach the site
 * go back to the same route through {@link Host} and nowhere else.
 *
 * <p>One of these per tab body. {@link #show} replaces everything it holds; a {@code slot} inside it
 * can be refilled on its own (a log page, the CA filters, the goals form) without redrawing the rest.
 * Answers that arrive after a newer request for the same place are dropped.</p>
 */
final class UiView extends JPanel
{
	/** Where this view's fetches and outward clicks go: the panel. Calls arrive on the EDT. */
	interface Host
	{
		/** {@code GET /api/plugin/ui?v=view&args}; {@code done} gets the view (null on failure) on the EDT. */
		void load(String view, Map<String, String> args, Consumer<JsonObject> done);

		/** {@code POST /api/plugin/ui}; same answer as {@link #load}. */
		void post(String act, Map<String, String> args, Map<String, String> fields, Consumer<JsonObject> done);

		void site(String path);

		void lookup(String rsn);

		void clan(String slug);
	}

	/** Toggle faces and section choices outlive a redraw (and the card), like the old virtual-levels switch. */
	private static final Map<String, Boolean> FLIPS = new HashMap<>();
	private static final Map<String, String> TABS = new HashMap<>();
	private static final int WRAP_PT = 170;

	private final Host host;
	private final ItemManager items;
	private final SpriteManager sprites;
	private final SkillIconManager skills;

	private final Map<String, Slot> slots = new HashMap<>();
	private final Map<String, List<Runnable>> toggles = new HashMap<>();
	private final Map<String, Supplier<String>> inputs = new LinkedHashMap<>();
	/**
	 * The latest request for each place (a slot's id, "null" for the whole view). An answer carrying
	 * any other number is for something no longer there; numbers are never reused.
	 */
	private final Map<String, Integer> seq = new HashMap<>();
	private int requests;

	private static final class Slot
	{
		final JPanel holder;
		final JsonObject panes;

		Slot(JPanel holder, JsonObject panes)
		{
			this.holder = holder;
			this.panes = panes;
		}
	}

	UiView(Host host, ItemManager items, SpriteManager sprites, SkillIconManager skills)
	{
		super(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setAlignmentX(LEFT_ALIGNMENT);
		this.host = host;
		this.items = items;
		this.sprites = sprites;
		this.skills = skills;
	}

	/** Fetch a view and show it in place of everything; {@code waiting} shows meanwhile. */
	void show(String view, Map<String, String> args, String waiting)
	{
		if (waiting != null)
		{
			replace(this, message(waiting));
		}
		fetch(null, view, args);
	}

	/** Show a line of text in place of everything, dropping any answer still on its way. */
	void say(String text)
	{
		bump(null);
		replace(this, message(text));
	}

	/** Draw a view already in hand. */
	void render(JsonObject view)
	{
		slots.clear();
		toggles.clear();
		inputs.clear();
		// The slots are gone, so whatever they were waiting for is too.
		seq.keySet().removeIf(k -> !"null".equals(k));
		replace(this, view == null || !view.has("ui") ? message("Couldn't reach Anvil — try again in a moment.") : node(view.getAsJsonObject("ui")));
	}

	private int bump(String place)
	{
		seq.put(String.valueOf(place), ++requests);
		return requests;
	}

	private void fetch(String into, String view, Map<String, String> args)
	{
		int n = bump(into);
		host.load(view, args, v -> land(into, n, v));
	}

	private void land(String into, int n, JsonObject view)
	{
		if (seq.getOrDefault(String.valueOf(into), 0) != n)
		{
			return;
		}
		Slot slot = into == null ? null : slots.get(into);
		if (into == null || slot == null)
		{
			render(view);
		}
		else
		{
			replace(slot.holder, view == null || !view.has("ui") ? small("Couldn't reach Anvil — try again in a moment.", MUTED) : node(view.getAsJsonObject("ui")));
		}
	}

	private static void replace(JPanel holder, Component c)
	{
		holder.removeAll();
		holder.add(c, BorderLayout.CENTER);
		holder.revalidate();
		holder.repaint();
	}

	private static JPanel message(String text)
	{
		JPanel p = block();
		JLabel l = small("", MUTED);
		l.setText(wrapHtml(esc(text), WRAP_PT));
		p.add(l);
		return capH(p);
	}

	// ---- Actions -----------------------------------------------------------------------------------

	private void act(JsonObject a)
	{
		String into = str(a, "into");
		switch (str(a, "a"))
		{
			case "site":
				String path = str(a, "path");
				if (path.startsWith("/") && !path.startsWith("//"))
				{
					host.site(path);
				}
				break;
			case "lookup":
				host.lookup(str(a, "rsn"));
				break;
			case "clan":
				host.clan(str(a, "slug"));
				break;
			case "load":
				fetch(into.isEmpty() ? null : into, str(a, "v"), strings(a.get("args")));
				break;
			case "pane":
				Slot slot = slots.get(into);
				JsonElement pane = slot == null || slot.panes == null ? null : slot.panes.get(str(a, "pane"));
				if (pane != null && pane.isJsonObject())
				{
					replace(slot.holder, node(pane.getAsJsonObject()));
				}
				break;
			case "flip":
				String key = str(a, "key");
				FLIPS.merge(key, true, (x, y) -> !x);
				toggles.getOrDefault(key, Collections.emptyList()).forEach(Runnable::run);
				break;
			case "post":
				Map<String, String> fields = new HashMap<>();
				inputs.forEach((k, v) -> fields.put(k, v.get()));
				String place = into.isEmpty() ? null : into;
				int n = bump(place);
				host.post(str(a, "act"), strings(a.get("args")), fields, v -> land(place, n, v));
				break;
			default:
				// An action this version doesn't know does nothing.
		}
	}

	// ---- Nodes -------------------------------------------------------------------------------------

	private JComponent node(JsonObject n)
	{
		JComponent c;
		switch (str(n, "t"))
		{
			case "col":
				c = col(n);
				break;
			case "row":
				c = row(n);
				break;
			case "grid":
				c = grid(n);
				break;
			case "text":
				c = text(n);
				break;
			case "icon":
				c = icon(n);
				break;
			case "bar":
				c = bar(n);
				break;
			case "tabs":
				c = tabs(n);
				break;
			case "fold":
				c = fold(n);
				break;
			case "filter":
				c = filter(n);
				break;
			case "slot":
				c = slot(n);
				break;
			case "toggle":
				c = toggle(n);
				break;
			case "input":
				c = input(n);
				break;
			case "button":
				JsonObject on = n.has("on") ? n.getAsJsonObject("on") : new JsonObject();
				return AnvilSidebarPanel.fullWidthButton(str(n, "label"), tip(n), () -> act(on));
			case "gap":
				c = (JComponent) gap(num(n, "h", 0));
				break;
			default:
				return new JPanel(); // a block this version doesn't know is left out
		}
		String tip = tip(n);
		if (tip != null)
		{
			c.setToolTipText(tip);
		}
		if (n.has("on") && n.get("on").isJsonObject())
		{
			JsonObject on = n.getAsJsonObject("on");
			clickable(c, () -> act(on));
		}
		return left(c);
	}

	private List<JComponent> children(JsonObject n, String key)
	{
		List<JComponent> out = new ArrayList<>();
		if (n.has(key) && n.get(key).isJsonArray())
		{
			for (JsonElement e : n.getAsJsonArray(key))
			{
				if (e.isJsonObject())
				{
					out.add(node(e.getAsJsonObject()));
				}
			}
		}
		return out;
	}

	private JComponent col(JsonObject n)
	{
		JPanel p = bool(n, "card") ? block() : column();
		if (!bool(n, "card"))
		{
			p.setOpaque(false);
			background(p, str(n, "bg"));
		}
		int inset = num(n, "inset", 0);
		if (inset > 0)
		{
			p.setBorder(BorderFactory.createEmptyBorder(inset, inset, inset, inset));
		}
		int gap = num(n, "gap", 0);
		for (JComponent c : children(n, "c"))
		{
			if (gap > 0 && p.getComponentCount() > 0)
			{
				p.add(gap(gap));
			}
			p.add(c);
		}
		return p;
	}

	private JComponent row(JsonObject n)
	{
		JPanel p = Ui.row(num(n, "gap", 6));
		background(p, str(n, "bg"));
		int pad = num(n, "pad", 0);
		int inset = num(n, "inset", pad);
		p.setBorder(BorderFactory.createEmptyBorder(pad, inset, pad, pad));
		String[][] parts = {{"l", BorderLayout.WEST}, {"c", BorderLayout.CENTER}, {"r", BorderLayout.EAST}};
		for (String[] part : parts)
		{
			if (n.has(part[0]) && n.get(part[0]).isJsonObject())
			{
				p.add(node(n.getAsJsonObject(part[0])), part[1]);
			}
		}
		return capH(p);
	}

	private JComponent grid(JsonObject n)
	{
		JPanel g = Ui.grid(Math.max(1, num(n, "cols", 1)), num(n, "gap", 0), num(n, "vgap", 0));
		children(n, "c").forEach(g::add);
		return g;
	}

	private JComponent text(JsonObject n)
	{
		String s = n.has("at") ? ago(str(n, "at")) : str(n, "s");
		JLabel l = label("", font(str(n, "font")), color(str(n, "color"), Color.WHITE));
		String align = str(n, "align");
		l.setHorizontalAlignment("r".equals(align) ? SwingConstants.RIGHT : "c".equals(align) ? SwingConstants.CENTER : SwingConstants.LEADING);
		if (n.has("icon") && n.get("icon").isJsonObject())
		{
			JsonObject spec = n.getAsJsonObject("icon");
			l.setIconTextGap(4);
			if (!draw(l, spec) && !str(spec, "alt").isEmpty())
			{
				// No picture for it: the name has to carry it, or it's an unlabelled number.
				s = str(spec, "alt") + " " + s;
			}
		}
		l.setText(bool(n, "wrap") ? wrapHtml(esc(s), WRAP_PT) : plainText(s));
		if (bool(n, "cell"))
		{
			l.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
			l.setOpaque(true);
			l.setBackground(ColorScheme.DARK_GRAY_COLOR);
		}
		return l;
	}

	private JComponent icon(JsonObject n)
	{
		boolean dim = bool(n, "dim");
		JLabel l = new JLabel()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				if (!dim)
				{
					super.paintComponent(g);
					return;
				}
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.25f));
				super.paintComponent(g2);
				g2.dispose();
			}
		};
		l.setHorizontalAlignment(SwingConstants.CENTER);
		int w = num(n, "w", 0);
		if (w > 0)
		{
			l.setPreferredSize(new Dimension(w, num(n, "h", w)));
		}
		if (!draw(l, n) && n.has("or") && n.get("or").isJsonObject())
		{
			draw(l, n.getAsJsonObject("or"));
		}
		return l;
	}

	/** Puts {@code spec}'s picture on {@code l}; false when there is none to draw. */
	private boolean draw(JLabel l, JsonObject spec)
	{
		int size = num(spec, "w", 12);
		if (spec.has("glyph"))
		{
			GlyphIcon.Shape shape = glyph(str(spec, "glyph"));
			if (shape == null)
			{
				return false;
			}
			l.setIcon(new GlyphIcon(shape, size, color(str(spec, "color"), MUTED)));
			return true;
		}
		if (spec.has("item") && items != null)
		{
			int qty = num(spec, "qty", 0);
			items.getImage(num(spec, "item", 0), Math.max(1, qty), qty > 1).addTo(l);
			return true;
		}
		if (spec.has("sprite") && sprites != null)
		{
			sprites.addSpriteTo(l, num(spec, "sprite", 0), 0);
			return true;
		}
		Skill skill = spec.has("skill") ? skill(str(spec, "skill")) : null;
		if (skill != null && skills != null)
		{
			l.setIcon(new ImageIcon(skills.getSkillImage(skill, true)));
			return true;
		}
		if (spec.has("boss"))
		{
			return bossIcon(l, str(spec, "boss"));
		}
		return false;
	}

	private JComponent bar(JsonObject n)
	{
		int pct = Math.max(0, Math.min(100, num(n, "pct", 0)));
		Color bg = "card".equals(str(n, "bg")) ? ColorScheme.DARKER_GRAY_COLOR : ColorScheme.DARK_GRAY_COLOR;
		Color fg = color(str(n, "color"), pct >= 100 ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.BRAND_ORANGE);
		int h = num(n, "h", 3);
		if (n.has("label"))
		{
			ProgressBar bar = new ProgressBar();
			bar.setMaximumValue(100);
			bar.setValue(pct);
			bar.setCenterLabel(str(n, "label"));
			bar.setLeftLabel("");
			bar.setRightLabel("");
			bar.setForeground(fg);
			bar.setBackground(bg);
			bar.setPreferredSize(new Dimension(0, h));
			bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
			return bar;
		}
		JPanel bar = new JPanel()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				g.setColor(bg);
				g.fillRect(0, 0, getWidth(), getHeight());
				g.setColor(fg);
				g.fillRect(0, 0, getWidth() * pct / 100, getHeight());
			}
		};
		bar.setPreferredSize(new Dimension(0, h));
		return capH(bar);
	}

	/**
	 * The section switcher: one body at a time, built when first picked, so a section that fetches
	 * (CAs) fetches only once someone opens it. Not a CardLayout: the sprites arrive after it would
	 * have measured, and the last row got cut off.
	 */
	private JComponent tabs(JsonObject n)
	{
		String key = str(n, "key");
		JsonArray list = n.has("tabs") ? n.getAsJsonArray("tabs") : new JsonArray();
		if (list.size() == 0)
		{
			return new JPanel();
		}
		Map<String, JsonObject> bodies = new LinkedHashMap<>();
		for (JsonElement e : list)
		{
			bodies.put(str(e.getAsJsonObject(), "label"), e.getAsJsonObject().getAsJsonObject("c"));
		}
		String wanted = TABS.getOrDefault(key, str(n, "open"));
		String first = bodies.containsKey(wanted) ? wanted : bodies.keySet().iterator().next();

		JPanel wrap = column();
		JPanel strip = left(new JPanel(new GridLayout(1, bodies.size())));
		strip.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JPanel body = Ui.row(0);
		Map<String, JComponent> built = new HashMap<>();
		List<JLabel> labels = new ArrayList<>();
		Consumer<String> pick = name ->
		{
			TABS.put(key, name);
			for (JLabel l : labels)
			{
				boolean on = l.getText().equals(name);
				l.setForeground(on ? Color.WHITE : MUTED);
				l.setBackground(on ? ColorScheme.DARKER_GRAY_COLOR : ColorScheme.DARK_GRAY_COLOR);
			}
			replace(body, built.computeIfAbsent(name, k -> node(bodies.get(k))));
		};
		for (String name : bodies.keySet())
		{
			JLabel l = onClick(new JLabel(name, SwingConstants.CENTER), () -> pick.accept(name));
			l.setFont(SMALL);
			l.setOpaque(true);
			l.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
			labels.add(l);
			strip.add(l);
		}
		pick.accept(first);
		wrap.add(capH(strip));
		wrap.add(gap(4));
		wrap.add(body);
		return wrap;
	}

	private JComponent fold(JsonObject n)
	{
		JPanel wrap = column();
		wrap.setOpaque(false);
		JPanel head = Ui.row(5);
		head.add(node(n.getAsJsonObject("head")), BorderLayout.CENTER);
		JLabel chevron = new JLabel();
		head.add(chevron, BorderLayout.EAST);
		JComponent body = node(n.getAsJsonObject("c"));
		boolean chev = bool(n, "chev");
		Runnable paint = () -> chevron.setIcon(chev
			? new GlyphIcon(body.isVisible() ? GlyphIcon.Shape.CHEVRON_LEFT : GlyphIcon.Shape.CHEVRON_RIGHT, 8, MUTED) : null);
		body.setVisible(bool(n, "open"));
		paint.run();
		clickable(capH(head), () ->
		{
			body.setVisible(!body.isVisible());
			paint.run();
			wrap.revalidate();
			wrap.repaint();
		});
		wrap.add(head);
		wrap.add(body);
		return wrap;
	}

	/**
	 * A search box over the rows of its children (a grid's cells, a column's rows). Each row is found
	 * by its {@code k}, else its own text; a {@code q} row shows only while something is typed.
	 */
	private JComponent filter(JsonObject n)
	{
		JPanel wrap = column();
		wrap.setOpaque(false);
		JLabel none = small(str(n, "none"), MUTED);
		none.setVisible(false);
		JLabel more = small(str(n, "more"), MUTED);
		more.setVisible(false);
		int max = num(n, "max", Integer.MAX_VALUE);
		List<Runnable> refills = new ArrayList<>();
		String[] query = {""};
		int[] shown = {0};

		JPanel lists = column();
		lists.setOpaque(false);
		for (JsonElement e : n.has("c") ? n.getAsJsonArray("c") : new JsonArray())
		{
			JsonObject list = e.getAsJsonObject();
			JComponent box = node(list);
			List<Component> rows = new ArrayList<>(Arrays.asList(box.getComponents()));
			JsonArray specs = list.has("c") ? list.getAsJsonArray("c") : new JsonArray();
			if (rows.size() != specs.size())
			{
				lists.add(box); // a list with gaps or extras between rows isn't searched
				continue;
			}
			refills.add(() ->
			{
				box.removeAll();
				for (int i = 0; i < rows.size(); i++)
				{
					JsonObject spec = specs.get(i).getAsJsonObject();
					boolean typed = !query[0].isEmpty();
					if ((!bool(spec, "q") || typed) && matches(query[0], spec.has("k") ? str(spec, "k") : words(spec)) && shown[0] < max)
					{
						box.add(rows.get(i));
						shown[0]++;
					}
				}
				box.revalidate();
				box.repaint();
			});
			lists.add(box);
		}
		Runnable refill = () ->
		{
			shown[0] = 0;
			refills.forEach(Runnable::run);
			none.setVisible(shown[0] == 0 && !refills.isEmpty());
			more.setVisible(shown[0] >= max);
		};
		refill.run();
		if (bool(n, "box"))
		{
			SearchField search = new SearchField(str(n, "hint"), "");
			styleSearch(search);
			search.setFont(SMALL);
			search.setBackground(ColorScheme.DARK_GRAY_COLOR);
			search.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
			onEdit(search, () ->
			{
				query[0] = search.getText().trim();
				refill.run();
				wrap.revalidate();
				wrap.repaint();
			});
			wrap.add(capH(left(search)));
			wrap.add(gap(6));
		}
		wrap.add(none);
		wrap.add(lists);
		wrap.add(more);
		return wrap;
	}

	private JComponent slot(JsonObject n)
	{
		JPanel holder = Ui.row(0);
		String id = str(n, "id");
		slots.put(id, new Slot(holder, n.has("panes") ? n.getAsJsonObject("panes") : null));
		holder.add(node(n.getAsJsonObject("c")), BorderLayout.CENTER);
		if (n.has("load") && n.get("load").isJsonObject())
		{
			JsonObject load = n.getAsJsonObject("load");
			fetch(id, str(load, "v"), strings(load.get("args")));
		}
		return holder;
	}

	private JComponent toggle(JsonObject n)
	{
		JPanel holder = Ui.row(0);
		String key = str(n, "key");
		Runnable paint = () -> replace(holder, node(n.getAsJsonObject(FLIPS.getOrDefault(key, false) ? "lit" : "off")));
		toggles.computeIfAbsent(key, k -> new ArrayList<>()).add(paint);
		paint.run();
		return holder;
	}

	private JComponent input(JsonObject n)
	{
		String name = str(n, "name");
		String value = str(n, "value");
		switch (str(n, "kind"))
		{
			case "select":
				List<String[]> options = new ArrayList<>();
				for (JsonElement e : n.has("options") ? n.getAsJsonArray("options") : new JsonArray())
				{
					options.add(new String[]{str(e.getAsJsonObject(), "v"), str(e.getAsJsonObject(), "label")});
				}
				JComboBox<String> combo = new JComboBox<>(options.stream().map(o -> o[1]).toArray(String[]::new));
				AnvilSidebarPanel.styleCombo(combo);
				for (int i = 0; i < options.size(); i++)
				{
					if (options.get(i)[0].equals(value))
					{
						combo.setSelectedIndex(i);
					}
				}
				inputs.put(name, () -> combo.getSelectedIndex() < 0 ? "" : options.get(combo.getSelectedIndex())[0]);
				return capH(combo);
			case "number":
				int min = num(n, "min", 0);
				int max = num(n, "max", Integer.MAX_VALUE);
				int start = Math.max(min, Math.min(max, parseInt(value, min)));
				JSpinner spinner = new JSpinner(new SpinnerNumberModel(start, min, max, Math.max(1, num(n, "step", 1))));
				spinner.setFont(SMALL);
				JTextField field = ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField();
				field.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				field.setForeground(Color.WHITE);
				field.setCaretColor(Color.WHITE);
				inputs.put(name, () -> String.valueOf(spinner.getValue()));
				JPanel row = Ui.row(6);
				row.add(small(str(n, "label"), MUTED), BorderLayout.WEST);
				row.add(spinner, BorderLayout.CENTER);
				return capH(row);
			case "check":
				JCheckBox check = new JCheckBox(str(n, "label"), "1".equals(value));
				check.setFont(SMALL);
				check.setOpaque(false);
				inputs.put(name, () -> check.isSelected() ? "1" : "");
				return check;
			default:
				return new JPanel();
		}
	}

	// ---- Pieces ------------------------------------------------------------------------------------

	/** Lights up under the mouse and runs {@code action} on a click. */
	private static void clickable(JComponent c, Runnable action)
	{
		boolean opaque = c.isOpaque();
		Color base = c.getBackground();
		onClick(c, action).addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				c.setOpaque(true);
				c.setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
				c.repaint();
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				c.setOpaque(opaque);
				c.setBackground(base);
				c.repaint();
			}
		});
	}

	private static void background(JPanel p, String bg)
	{
		if (!bg.isEmpty())
		{
			p.setOpaque(true);
			p.setBackground("card".equals(bg) ? ColorScheme.DARKER_GRAY_COLOR : ColorScheme.DARK_GRAY_COLOR);
		}
	}

	private static String tip(JsonObject n)
	{
		String tip = str(n, "tip");
		return tip.isEmpty() ? null : wrapHtml(esc(tip).replace("\n", "<br>"), 180);
	}

	private static Font font(String f)
	{
		return "bold".equals(f) ? BOLD : "regular".equals(f) ? REGULAR : SMALL;
	}

	static Color color(String c, Color fallback)
	{
		switch (c)
		{
			case "white":
				return Color.WHITE;
			case "muted":
				return MUTED;
			case "brand":
				return ColorScheme.BRAND_ORANGE;
			case "done":
				return ColorScheme.PROGRESS_COMPLETE_COLOR;
			case "error":
				return ColorScheme.PROGRESS_ERROR_COLOR;
			case "claim":
				return new Color(0x7D_C4_F0);
			default:
				return teamColor(c, fallback);
		}
	}

	private static GlyphIcon.Shape glyph(String g)
	{
		switch (g)
		{
			case "check":
				return GlyphIcon.Shape.CHECK;
			case "chevron-right":
				return GlyphIcon.Shape.CHEVRON_RIGHT;
			case "chevron-left":
				return GlyphIcon.Shape.CHEVRON_LEFT;
			case "external":
				return GlyphIcon.Shape.EXTERNAL;
			case "close":
				return GlyphIcon.Shape.CLOSE;
			default:
				return null;
		}
	}

	private static Skill skill(String key)
	{
		for (Skill s : Skill.values())
		{
			if (s.name().equalsIgnoreCase(key))
			{
				return s;
			}
		}
		return null;
	}

	/** Every text in {@code n}, for a row a filter searches without a {@code k}. */
	private static String words(JsonObject n)
	{
		StringBuilder sb = new StringBuilder(str(n, "s")).append(' ').append(str(n, "alt"));
		for (Map.Entry<String, JsonElement> e : n.entrySet())
		{
			if (e.getValue().isJsonObject())
			{
				sb.append(' ').append(words(e.getValue().getAsJsonObject()));
			}
			else if (e.getValue().isJsonArray())
			{
				for (JsonElement x : e.getValue().getAsJsonArray())
				{
					if (x.isJsonObject())
					{
						sb.append(' ').append(words(x.getAsJsonObject()));
					}
				}
			}
		}
		return sb.toString();
	}

	/** Every word of the query somewhere in the name, case and punctuation aside ("kril" finds K'ril). */
	static boolean matches(String query, String name)
	{
		String n = fold(SearchAliases.augment(name));
		for (String word : query.split("\\s+"))
		{
			if (!n.contains(fold(word)))
			{
				return false;
			}
		}
		return true;
	}

	private static String fold(String s)
	{
		return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}

	static String str(JsonObject o, String key)
	{
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
	}

	private static int num(JsonObject o, String key, int fallback)
	{
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsInt() : fallback;
	}

	private static boolean bool(JsonObject o, String key)
	{
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
	}

	private static int parseInt(String s, int fallback)
	{
		try
		{
			return Integer.parseInt(s);
		}
		catch (NumberFormatException e)
		{
			return fallback;
		}
	}

	static Map<String, String> strings(JsonElement e)
	{
		Map<String, String> out = new LinkedHashMap<>();
		if (e != null && e.isJsonObject())
		{
			for (Map.Entry<String, JsonElement> x : e.getAsJsonObject().entrySet())
			{
				if (x.getValue().isJsonPrimitive())
				{
					out.put(x.getKey(), x.getValue().getAsString());
				}
			}
		}
		return out;
	}

	// ---- The game's picture for a boss --------------------------------------------------------------

	/**
	 * RuneLite's hiscore entry for a boss or activity the site names. The site's key
	 * ("alchemicalHydra") and RuneLite's enum (ALCHEMICAL_HYDRA) fold to the same letters; the label
	 * catches the rest, and a prefix catches renames like "barrows" → BARROWS_CHESTS.
	 */
	static HiscoreSkill hiscoreSkill(String key)
	{
		// The site says "cluesBeginner", RuneLite says CLUE_SCROLL_BEGINNER.
		String k = fold(key).replaceFirst("^clues", "cluescroll");
		if (k.isEmpty())
		{
			return null;
		}
		HiscoreSkill best = null;
		int bestLen = 0;
		for (HiscoreSkill s : HiscoreSkill.values())
		{
			String e = fold(s.name());
			if (e.equals(k) || fold(s.getName()).equals(k) || e.equals("the" + k))
			{
				return s;
			}
			// RuneLite says more than we do (barrows ↔ BARROWS_CHESTS) or less (…ExpertMode ↔ …_EXPERT);
			// longest match wins, so the expert raid doesn't settle for the plain one.
			boolean fits = e.startsWith(k) || (e.length() >= 6 && k.startsWith(e));
			if (fits && e.length() > bestLen)
			{
				best = s;
				bestLen = e.length();
			}
		}
		if (best != null)
		{
			return best;
		}
		// A personal best's name: a boss whose name it contains ("Demonic Brutus" → Brutus).
		for (HiscoreSkill s : HiscoreSkill.values())
		{
			String n = fold(s.getName());
			if (n.length() >= 4 && k.contains(n) && (best == null || n.length() > fold(best.getName()).length()))
			{
				best = s;
			}
		}
		return best;
	}

	/** A boss's sprite, else a skill it names (agility courses, "99 Attack"); false when neither. */
	private boolean bossIcon(JLabel l, String name)
	{
		HiscoreSkill boss = hiscoreSkill(name);
		if (boss != null && boss.getSpriteId() > 0 && sprites != null)
		{
			sprites.addSpriteTo(l, boss.getSpriteId(), 0);
			return true;
		}
		if (skills != null)
		{
			for (Skill s : Skill.values())
			{
				if (name.toLowerCase(Locale.ROOT).contains(s.getName().toLowerCase(Locale.ROOT)))
				{
					l.setIcon(new ImageIcon(skills.getSkillImage(s, true)));
					return true;
				}
			}
		}
		return false;
	}
}
