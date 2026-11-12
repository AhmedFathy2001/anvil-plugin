package com.anvil;

import static javax.swing.BorderFactory.*;
import static java.awt.BorderLayout.*;
import static java.lang.Math.*;
import static net.runelite.client.ui.ColorScheme.*;
import lombok.RequiredArgsConstructor;
import com.google.gson.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import javax.swing.*;
import net.runelite.api.Skill;
import net.runelite.client.game.*;
import net.runelite.client.hiscore.*;
import net.runelite.client.ui.components.ProgressBar;
import net.runelite.client.util.ImageUtil;

import static com.anvil.Ui.*;
import static com.anvil.GlyphIcon.Shape.*;
import java.awt.image.BufferedImage;
import javax.swing.Timer;

/**
 * A panel view the site built ({@code GET /api/plugin/ui}): the site
 * decides every line, colour and click, and this draws it from a dozen building blocks (site
 * lib/pluginUi/nodes has the shape). Pictures arrive as game ids drawn with RuneLite's own managers —
 * never a URL — and a click is one of the fixed actions in {@link #act}; the ones that reach the site
 * go back to the same route through {@link Host} and nowhere else.
 *
 * <p>One of these per tab body. {@link #show} replaces everything it holds; a {@code slot} inside it
 * can be refilled on its own (a log page, the CA filters, the goals form) without redrawing the rest.
 * Answers that arrive after a newer request for the same place are dropped.</p>
 */

final class UiView extends JPanel {
	/** Where this view's fetches and outward clicks go: the panel. Calls arrive on the EDT. */
	interface Host {
		/** {@code GET /api/plugin/ui?v=view&args}; {@code done} gets the view (null on failure) on the EDT. */
		void load(String view, Map<String, String> args, Consumer<JsonObject> done);

		/** {@code POST /api/plugin/ui}; same answer as {@link #load}. */
		void post(String act, Map<String, String> args, Map<String, String> fields, Consumer<JsonObject> done);

		void site(String path);

		void lookup(String rsn);

		void clan(String slug);

		/** Pin (or unpin) a tile's in-game tracker on this client; tile 0 unpins every tile on the event. */
		void pin(int event, int tile, boolean on);

		/** Point the plugin at another of the member's clans. */
		void switchClan(String slug);

		/** Open one of the panel's own tabs by name ("feed"). Unknown names do nothing. */
		default void openTab(String name, Map<String, String> args) {
		}

		/** A part only the plugin can draw (it reads the game), or null for none. */
		JComponent nativePart(String name);

		/** This client's newest count for a tile (its own progress, the live push's), or -1 for none. */
		/** Give the keyboard back to the game (after a chat line is sent from the panel). */
		default void gameFocus() {
		}

		/** The signed-in character's name, so a live list can mark the viewer's own lines. */
		default String myName() {
			return null;
		}

		default long count(int tile) {
			return -1;
		}

		/** A picture by site path (node {@code img}); motion may be frozen by client preference. */
		default void image(String path, int w, int h, boolean motion, Consumer<Icon> done) {
		}
	}

	/** Toggle faces and section choices outlive a redraw (and the card), like the old virtual-levels switch. */
	static final Map<String, Boolean> FLIPS = new HashMap<>();
	static final Map<String, String> TABS = new HashMap<>();
	static final int WRAP_PT = 170;
	static final String OFFLINE = "Couldn't reach Anvil — try again in a moment.";
	static final Color DONE_DOT = new Color(0x5fd35f);

	final Host host;
	final ItemManager items;
	final SpriteManager sprites;
	final SkillIconManager skills;

	final Map<String, Slot> slots = new HashMap<>();
	final Map<String, List<Runnable>> toggles = new HashMap<>();
	final Map<String, Supplier<String>> inputs = new LinkedHashMap<>();
	/** The last request for each place, for {@link #refresh}. */
	final Map<String, Object[]> lastLoads = new LinkedHashMap<>();
	/** When each warmed load (view and args) was last fetched ahead, so a redraw doesn't ask again. */
	final Map<String, Long> warmed = new HashMap<>();
	/** A clickable part's marker, so the row around it leaves its clicks to it. */
	/** How long after a click a background refresh waits before redrawing. */
	static final long CLICK_SETTLE_MS = 400;
	boolean pressing;
	long pressedAt;
	static final long PRESS_STALE_MS = 2_000;
	static final long WARM_EVERY_MS = 60_000;

	/** What each place last drew, so an unchanged answer leaves it alone (its scroll, its open folds). */
	final Map<String, JsonElement> drawn = new HashMap<>();
	/**
	 * The pane each slot was switched to, kept while the view refreshes so a new answer doesn't throw
	 * the reader back to the first page. Going somewhere (a load, a new view) forgets them.
	 */
	final Map<String, String> openPanes = new HashMap<>();
	/** Countdowns on screen, run once a second while there are any. */
	final List<Runnable> tickers = new ArrayList<>();
	/** The counts on screen that move without asking the site again ({@code live}), see {@link #tickLive}. */
	final List<Runnable> lives = new ArrayList<>();
	final Timer ticker = new Timer(1000, e -> tickers.forEach(Runnable::run));
	/** Live lists on screen (node {@code feed}): push rows find them by id, see {@link #feedPush}. */
	final List<FeedList> feeds = new ArrayList<>();
	/**
	 * The latest request for each place (a slot's id, "null" for the whole view). An answer carrying
	 * any other number is for something no longer there; numbers are never reused.
	 */
	final Map<String, Integer> seq = new HashMap<>();
	int requests;

	@RequiredArgsConstructor
	static final class Slot {
		final JPanel holder;
		final JsonObject panes;
		/** {@code nav}: a pane replaces the page; the panes left keep their build and the row clicked to leave. */
		final String first;
		final Map<String, JComponent> built = new HashMap<>();
		final Map<String, JComponent> from = new HashMap<>();
		Round marked;
	}

	/** The last thing clicked, so a nav slot can mark it when the reader comes back. */
	JComponent lastClicked;
	/** Where each nav pane was scrolled to ("slot:pane"), kept across redraws. */
	final Map<String, Integer> scrolls = new HashMap<>();
	static final Color RAISED = new Color(0x3a3a3a);
	static final Color RAISED_HOVER = new Color(0x474747);

	UiView(Host host, ItemManager items, SpriteManager sprites, SkillIconManager skills) {
		super(new BorderLayout());
		setBackground(DARK_GRAY_COLOR);
		setAlignmentX(LEFT_ALIGNMENT);
		this.host = host;
		this.items = items;
		this.sprites = sprites;
		this.skills = skills;
	}

	/** Fetch a view and show it in place of everything; {@code waiting} shows meanwhile. */
	void show(String view, Map<String, String> args, String waiting) {
		openPanes.clear();
		if (waiting != null) {
			drawn.remove("null");
			replace(this, message(waiting));
		}
		fetch(null, view, args);
	}

	/**
	 * Ask again for what's on screen — the board moved. Each slot that was loaded since the view was
	 * drawn asks for its latest; with none, the whole view does (nothing typed into it can be lost).
	 * What's drawn stays until the answer lands, then swaps once. False when nothing was loaded yet.
	 */
	boolean refresh() {
		return refresh(false);
	}

	/**
	 * {@code whole}: the page itself asks again (a tile just completed — the race road, "Up next" and
	 * the standings all live on the page, not in a slot). A slot no longer on screen (a nav pane left
	 * behind, Back from a tile's detail) is never what a refresh asks for: it has nowhere to land, and
	 * it used to stand in for the page, so the page stayed stale until something else redrew it.
	 */
	boolean refresh(boolean whole) {
		lastLoads.keySet().removeIf(k -> {
			if ("null".equals(k)) return false;
			var s = slots.get(k);
			return s == null || !SwingUtilities.isDescendingFrom(s.holder, this);
		});
		var loads = new LinkedHashMap<>(lastLoads);
		var root = loads.remove("null");
		if (root != null && (whole || loads.isEmpty())) {
			refetch(null, root);
			return true;
		}
		loads.forEach(this::refetch);
		return root != null || !loads.isEmpty();
	}

	void refetch(String place, Object[] l) {
		fetch(place, (String) l[0], (Map<String, String>) l[1], true);
	}

	/** Force the next answers to rebuild their nodes, even when their JSON is structurally unchanged. */
	void invalidateDrawn() {
		drawn.clear();
	}

	/** Show a line of text in place of everything, dropping any answer still on its way. */
	void say(String text) {
		bump(null);
		drawn.remove("null");
		replace(this, message(text));
	}

	/** Draw a view already in hand. */
	void render(JsonObject view) {
		feeds.forEach(FeedList::stop);
		feeds.clear();
		slots.clear();
		toggles.clear();
		inputs.clear();
		tickers.clear();
		lives.clear();
		// The slots are gone, so whatever they were waiting for is too.
		for (var m : List.of(drawn, seq, lastLoads)) m.keySet().removeIf(k -> !"null".equals(k));
		warmed.clear();
		replace(this, view == null || !view.has("ui") ? message(OFFLINE) : node(view.getAsJsonObject("ui")));
		if (tickers.isEmpty()) ticker.stop();
		else ticker.start();
	}

	/**
	 * Push rows for the live lists ({@code {t:'rows'|'rowsdel'|'rowslock'|'rowsreact', feeds:[…]}}): each list
	 * named takes them; nothing is redrawn. A lock names id prefixes (a board's every chat list).
	 */
	void feedPush(JsonObject m) {
		// A list whose slot was redrawn is no longer under this view: forget it.
		feeds.removeIf(f -> !SwingUtilities.isDescendingFrom(f, this));
		var fs = arr(m, "feeds");
		if (feeds.isEmpty() || fs == null) return;
		Set<String> named = new HashSet<>();
		for (var e : fs) named.add(e.getAsString());
		var t = str(m, "t");
		var rows = "rows".equals(t) ? arr(m, "rows") : null;
		var ids = "rowsdel".equals(t) ? arr(m, "ids") : null;
		for (var f : feeds) {
			if ("rowslock".equals(t)) {
				if (f.hasBox() && named.stream().anyMatch(p -> f.id.equals(p) || f.id.startsWith(p + ":"))) f.lock(str(m, "locked"), str(m, "sub"), m.has("warn"));
			} else if (!named.contains(f.id)) {
				// Another channel's line: its segment on the chat's switch counts it.
				if (rows != null) f.bump(named, rows.size());
			} else if (rows != null) {
				f.add(rows);
			} else if (ids != null) {
				f.remove(ids);
			} else if ("rowsreact".equals(t)) f.reaction(m);
		}
	}

	/** The panel went off screen (or the plugin stopped): no countdowns tick unseen. */
	void stop() {
		ticker.stop();
	}

	/** Back on screen: the countdowns drawn before resume. */
	void resume() {
		if (!tickers.isEmpty()) ticker.start();
	}

	int bump(String place) {
		seq.put(String.valueOf(place), ++requests);
		return requests;
	}

	void fetch(String into, String view, Map<String, String> args) {
		fetch(into, view, args, false);
	}

	/** {@code background}: a refresh nobody clicked for, which waits for a click in progress to finish. */
	void fetch(String into, String view, Map<String, String> args, boolean background) {
		lastLoads.put(String.valueOf(into), new Object[]{view, args});
		int n = bump(into);
		host.load(view, args, v -> land(into, n, v, background));
	}

	/** The slot node with this id inside an answer (not inside panes or other slots' loads), or null. */
	static JsonObject ownSlot(JsonElement e, String id) {
		if (e == null) return null;
		if (e.isJsonArray()) {
			for (var x : e.getAsJsonArray()) {
				var hit = ownSlot(x, id);
				if (hit != null) return hit;
			}
			return null;
		}
		if (!e.isJsonObject()) return null;
		var o = e.getAsJsonObject();
		if ("slot".equals(str(o, "t")) && id.equals(str(o, "id"))) return o;
		for (var kv : o.entrySet()) {
			if (List.of("panes", "on", "menu", "load").contains(kv.getKey())) continue;
			var hit = ownSlot(kv.getValue(), id);
			if (hit != null) return hit;
		}
		return null;
	}

	void land(String into, int n, JsonObject answer, boolean background) {
		var view = answer;
		var key = String.valueOf(into);
		if (seq.getOrDefault(key, 0) != n) return;
		// A press older than PRESS_STALE_MS is over: a redraw under it can swallow its release, and the
		// flag would otherwise hold every later background refresh off for good.
		var sincePress = System.currentTimeMillis() - pressedAt;
		if (background && ((pressing && sincePress < PRESS_STALE_MS) || sincePress < CLICK_SETTLE_MS)) {
			// Redrawing now would swap the row out from under the press, and the click would be lost.
			once((int) CLICK_SETTLE_MS, e -> land(into, n, answer, true));
			return;
		}
		// A slot only ever takes its own inner content: a whole page answered into it (one that carries
		// this same slot) would stack the page's header again on every answer, so keep just that slot's part.
		var own = into == null || view == null ? null : ownSlot(obj(view, "ui"), into);
		if (obj(own, "c") != null) {
			view = view.deepCopy();
			view.add("ui", own.get("c"));
		}
		var ui = view == null ? null : view.get("ui");
		if (ui != null && ui.equals(drawn.get(key))) return; // the same answer: leave what's on screen alone
		drawn.put(key, ui);
		var slot = slots.get(into);
		if (slot == null) {
			render(view);
		} else if (slot.first != null && view != null && view.has("ui")) {
			// A page fetched into a nav slot is a page of its own: the pane left is kept for Back.
			var vp = leave(slot, into, "~" + n);
			replace(slot.holder, node(view.getAsJsonObject("ui")));
			if (vp != null) vp.setViewPosition(new Point(0, 0));
		} else replace(slot.holder, view == null || !view.has("ui") ? small(OFFLINE, MUTED) : node(view.getAsJsonObject("ui")));
	}

	/** Runs {@code a} once on the EDT after {@code ms}. */
	static void once(int ms, ActionListener a) {
		var t = new Timer(ms, a);
		t.setRepeats(false);
		t.start();
	}

	static void replace(JPanel holder, Component c) {
		holder.removeAll();
		holder.add(c, CENTER);
		holder.revalidate();
		holder.repaint();
	}

	static JPanel message(String text) {
		var p = block();
		var l = small("", MUTED);
		l.setText(wrapHtml(esc(text), WRAP_PT));
		p.add(l);
		return capH(p);
	}

	// ---- Actions -----------------------------------------------------------------------------------

	void act(JsonObject a) {
		var into = str(a, "into");
		var place = into.isEmpty() ? null : into;
		switch (str(a, "a")) {
			case "site":
				var path = str(a, "path");
				// A path on the site, never a host: "//" and a backslash ("/\\evil.example") both resolve
				// to another host.
				if (path.startsWith("/") && !path.startsWith("//") && path.indexOf('\\') < 0) host.site(path);
				break;
			case "lookup":
				host.lookup(str(a, "rsn"));
				break;
			case "clan":
				host.clan(str(a, "slug"));
				break;
			case "load":
				openPanes.clear();
				var open = obj(a, "open");
				if (open != null) openPanes.put(str(open, "into"), str(open, "pane"));
				var busy = slots.get(place);
				if (busy != null && busy.first == null) {
					// The click registers at once: the slot says it is loading until the answer lands.
					drawn.remove(into);
					replace(busy.holder, small("Loading…", MUTED));
				}
				fetch(place, str(a, "v"), strings(a.get("args")));
				break;
			case "pane":
				lastLoads.remove(into); // the reader is on a pane now, not on the page last fetched: a refresh must not replay it
				var slot = slots.get(into);
				var name = str(a, "pane");
				var pane = slot == null ? null : obj(slot.panes, name);
				if (pane != null && slot.first != null) {
					go(slot, into, name, pane);
				} else if (pane != null) {
					openPanes.put(into, name);
					drawn.remove(into);
					replace(slot.holder, node(pane));
				}
				break;
			case "flip":
				var key = str(a, "key");
				FLIPS.merge(key, true, (x, y) -> !x);
				toggles.getOrDefault(key, List.of()).forEach(Runnable::run);
				break;
			case "post":
				Map<String, String> fields = new HashMap<>();
				inputs.forEach((k, v) -> fields.put(k, v.get()));
				if (a.has("ask")) {
					var answer = JOptionPane.showInputDialog(this, plainText(str(a, "ask")), "Anvil", JOptionPane.PLAIN_MESSAGE, null, null, str(a, "askValue"));
					if (answer == null) return; // cancelled
					fields.put("note", answer.toString());
				}
				int n = bump(place);
				host.post(str(a, "act"), strings(a.get("args")), fields, v -> land(place, n, v, false));
				break;
			case "switch":
				host.switchClan(str(a, "slug"));
				break;
			case "tab":
				// With args: that list on the tab (a board's feed or chat), not the tab's own default.
				host.openTab(str(a, "tab"), strings(a.get("args")));
				break;
			case "pin":
				host.pin(num(a, "event", 0), num(a, "tile", 0), bool(a, "on"));
				var then = obj(a, "then");
				if (then != null) act(then);
				break;
			default:
				// An action this version doesn't know does nothing.
		}
	}

	/**
	 * A nav slot's pane replaces the page. The pane left keeps its build, its scroll and the row that
	 * was clicked to leave it, so going back lands where the reader was, that row outlined.
	 */
	/** Keeps the pane being left (its build, scroll, clicked row) and names the one entered; returns the viewport. */
	JViewport leave(Slot s, String into, String name) {
		var from = openPanes.getOrDefault(into, s.first);
		var vp = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, s.holder);
		if (s.holder.getComponentCount() > 0) s.built.put(from, (JComponent) s.holder.getComponent(0));
		if (lastClicked != null && SwingUtilities.isDescendingFrom(lastClicked, s.holder)) s.from.put(from, lastClicked);
		if (vp != null) scrolls.put(into + ":" + from, vp.getViewPosition().y);
		openPanes.put(into, name);
		return vp;
	}

	void go(Slot s, String into, String name, JsonObject pane) {
		var vp = leave(s, into, name);
		drawn.remove(into);
		var back = s.built.containsKey(name);
		replace(s.holder, back ? s.built.get(name) : node(pane));
		if (s.marked != null) s.marked.edge = (Color) s.marked.getClientProperty("edge");
		var was = s.from.get(name);
		s.marked = back && was instanceof Round ? (Round) was : null;
		if (s.marked != null) {
			s.marked.putClientProperty("edge", s.marked.edge);
			s.marked.edge = BRAND_ORANGE;
			// A brief "you were here", not a stuck focus ring: the outline lets go after a moment.
			var m = s.marked;
			once(1500, e -> {
				if (s.marked != m) return;
				m.edge = (Color) m.getClientProperty("edge");
				s.marked = null;
				m.repaint();
			});
		}
		int y = back ? scrolls.getOrDefault(into + ":" + name, 0) : 0;
		if (vp != null) {
			vp.setViewPosition(new Point(0, y));
			SwingUtilities.invokeLater(() -> vp.setViewPosition(new Point(0, y)));
		}
	}

	/** An input's own action: its args plus every input's current value, so a search keeps its filter. */
	void fire(JsonObject on) {
		var a = on.deepCopy();
		var args = Objects.requireNonNullElseGet(obj(a, "args"), JsonObject::new);
		inputs.forEach((k, v) -> args.addProperty(k, v.get()));
		a.add("args", args);
		act(a);
	}

	// ---- Nodes -------------------------------------------------------------------------------------

	/** The kinds drawn as they are: no tip, click or menu laid over them. */
	static final List<String> BARE = List.of("button", "warm", "native", "feed");

	JComponent node(JsonObject n) {
		var t = str(n, "t");
		var c = part(n, t);
		// A block this version doesn't know (null) is left out.
		if (c == null || BARE.contains(t)) return left(c == null ? new JPanel() : c);
		var tip = tip(n);
		if (tip != null) {
			c.setToolTipText(tip);
			// Swing shows the tooltip of the component under the mouse: a card's picture and text are
			// their own components, so the card's tip only showed in the gaps. They share it now (a
			// child with a tip of its own keeps it).
			inheritTip(c, tip);
		}
		// An input's action fires on a change, not on a click of the box.
		var on = obj(n, "on");
		if (on != null && !"input".equals(t)) clickable(c, () -> act(on));
		var menu = arr(n, "menu");
		if (menu != null) menu(c, menu);
		return left(c);
	}

	JComponent part(JsonObject n, String t) {
		switch (t) {
			case "col":
				return col(n);
			case "row":
				return row(n);
			case "grid":
				return grid(n);
			case "text":
				return text(n);
			case "icon":
				return icon(n);
			case "img":
				return img(n);
			case "hero":
				return hero(n);
			case "bar":
				return bar(n);
			case "tabs":
				return tabs(n);
			case "fold":
				return fold(n);
			case "filter":
				return filter(n);
			case "slot":
				return slot(n);
			case "toggle":
				return toggle(n);
			case "input":
				return input(n);
			case "button":
				var on = n.has("on") ? n.getAsJsonObject("on") : new JsonObject();
				var button = AnvilSidebarPanel.fullWidthButton(plainText(str(n, "label")), tip(n), () -> act(on));
				button.getComponent(0).setEnabled(!bool(n, "off"));
				return button;
			case "gap":
				return (JComponent) gap(num(n, "h", 0));
			case "countdown":
				return countdown(n);
			case "segs":
				return segs(n);
			case "step":
				return step(n);
			case "warm":
				warm(obj(n, "on"));
				return new JPanel();
			case "native":
				// Left-aligned like every other node: one centred child in a BoxLayout column shifts all
				// of its siblings sideways (an absent start-proof card indented a whole board page).
				return host.nativePart(str(n, "name"));
			case "feed":
				var f = new FeedList(n, host, items, sprites, skills, this::act);
				feeds.add(f);
				return f;
			default:
				return null;
		}
	}

	static void inheritTip(Container parent, String tip) {
		for (var child : parent.getComponents()) {
			if (child instanceof JComponent && ((JComponent) child).getToolTipText() == null) {
				((JComponent) child).setToolTipText(tip);
				inheritTip((JComponent) child, tip);
			}
		}
	}

	List<JComponent> children(JsonObject n, String key) {
		List<JComponent> out = new ArrayList<>();
		var a = arr(n, key);
		if (a != null) for (var e : a) if (e.isJsonObject()) out.add(node(e.getAsJsonObject()));
		return out;
	}

	/**
	 * A col's or row's ground: a rounded card, a raised row ({@code bg:'raised'}), a 1px outline
	 * ({@code edge}), or none. Anything clickable is a {@link Round} so its hover is rounded too.
	 */
	static JPanel surface(JsonObject n, LayoutManager layout, boolean card) {
		var bg = str(n, "bg");
		var edge = color(str(n, "edge"), null);
		var fill = card || "card".equals(bg) ? DARKER_GRAY_COLOR
			: "raised".equals(bg) ? RAISED : "panel".equals(bg) ? DARK_GRAY_COLOR : null;
		if (fill == null && edge == null && !n.has("on")) {
			var p = new JPanel(layout);
			p.setOpaque(false);
			return left(p);
		}
		return left(new Round(layout, card ? num(n, "rad", 6) : 4, fill, edge, "raised".equals(bg) ? RAISED_HOVER : null));
	}

	JComponent col(JsonObject n) {
		var card = bool(n, "card");
		var p = surface(n, null, card);
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		int inset = num(n, "inset", card ? 8 : 0);
		if (inset > 0) p.setBorder(createEmptyBorder(inset, inset, inset, inset));
		int gap = num(n, "gap", 0);
		for (var c : children(n, "c")) {
			if (gap > 0 && p.getComponentCount() > 0) p.add(gap(gap));
			p.add(c);
		}
		return p;
	}

	JComponent row(JsonObject n) {
		var p = surface(n, new BorderLayout(num(n, "gap", 6), 0), false);
		int pad = num(n, "pad", 0);
		int inset = num(n, "inset", pad);
		p.setBorder(createEmptyBorder(pad, inset, pad, pad));
		for (var part : new String[][]{{"l", WEST}, {"c", CENTER}, {"r", EAST}}) {
			var o = obj(n, part[0]);
			if (o != null) p.add(node(o), part[1]);
		}
		return capH(p);
	}

	JComponent grid(JsonObject n) {
		var g = Ui.grid(max(1, num(n, "cols", 1)), num(n, "gap", 0), num(n, "vgap", 0));
		children(n, "c").forEach(g::add);
		return g;
	}

	JComponent text(JsonObject n) {
		var s = n.has("at") ? ago(str(n, "at")) : str(n, "s");
		var ink = color(str(n, "color"), Color.WHITE);
		var chip = color(str(n, "chip"), null);
		var grad = arr(n, "grad");
		JLabel l = grad != null ? gradient(grad, ink, bool(n, "move"))
			: chip != null ? new ClipLabel() {
				protected void paintComponent(Graphics g) {
					var g2 = smooth(g);
					g2.setColor(chip);
					g2.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
					g2.dispose();
					super.paintComponent(g);
				}
			} : new ClipLabel();
		if (chip != null) l.setBorder(createEmptyBorder(1, 6, 1, 6));
		l.setFont(font(str(n, "font")));
		l.setForeground(ink);
		var align = str(n, "align");
		l.setHorizontalAlignment("r".equals(align) ? JLabel.RIGHT : "c".equals(align) ? JLabel.CENTER : JLabel.LEADING);
		var spec = obj(n, "icon");
		if (spec != null) {
			l.setIconTextGap(4);
			if (bool(n, "iconAfter")) l.setHorizontalTextPosition(JLabel.LEADING);
			if (!draw(l, spec) && !str(spec, "alt").isEmpty()) {
				// No picture for it: the name has to carry it, or it's an unlabelled number.
				s = str(spec, "alt") + " " + s;
			} else if (l instanceof GradientLabel && "star".equals(str(spec, "glyph"))) {
				// A supporter's star is part of their name: the gradient runs through it.
				((GradientLabel) l).setTintIcon(true);
			}
		}
		// wrap: true, or the width in pt where the text sits deeper in than a card.
		int ww = num(n, "wrap", 0);
		int width = ww > 0 ? ww : WRAP_PT;
		var runs = arr(n, "runs");
		if (runs != null) {
			// Runs in their own colours (names in team colours), always wrapped.
			var h = new StringBuilder();
			for (var e : runs) {
				var r = e.getAsJsonObject();
				h.append(String.format("<font color='#%06x'>", color(str(r, "color"), ink).getRGB() & 0xffffff)).append(esc(fontSafe(str(r, "s")))).append("</font>");
			}
			l.setText(wrapHtml(h.toString(), width));
		} else l.setText(bool(n, "wrap") || ww > 0 ? wrapHtml(esc(s), width) : plainText(s));
		live(n, (v, goal) -> l.setText(liveText(str(obj(n, "live"), "fmt"), v, goal)));
		int w = num(n, "w", 0);
		if (w > 0 && chip != null) {
			// A badge: a square that size, its letter centred.
			l.setBorder(null);
			var d = new Dimension(w, w);
			l.setPreferredSize(d);
			l.setMaximumSize(d);
		} else if (w > 0) l.setPreferredSize(new Dimension(w, l.getPreferredSize().height));
		if (bool(n, "cell")) {
			l.setBorder(createEmptyBorder(3, 4, 3, 4));
			l.setOpaque(true);
			l.setBackground(DARK_GRAY_COLOR);
		}
		return l;
	}

	/** A Supporter's name: up to four "#rrggbb" stops ({@code move}: sliding); else the plain colour. */
	static JLabel gradient(JsonArray grad, Color fallback, boolean move) {
		List<Color> stops = new ArrayList<>();
		for (var e : grad) {
			if (stops.size() < 4 && e.isJsonPrimitive()) stops.add(ChatPalette.readableColor(e.getAsString(), false, fallback));
		}
		return stops.size() < 2 ? new ClipLabel() : new GradientLabel(stops.toArray(new Color[0]), move);
	}

	JComponent icon(JsonObject n) {
		var dim = bool(n, "dim");
		JLabel l = !dim ? new ClipLabel() : new ClipLabel() {
			protected void paintComponent(Graphics g) {
				var g2 = (Graphics2D) g.create();
				g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.25f));
				super.paintComponent(g2);
				g2.dispose();
			}
		};
		l.setHorizontalAlignment(JLabel.CENTER);
		int w = num(n, "w", 0);
		if (w > 0) l.setPreferredSize(new Dimension(w, num(n, "h", w)));
		var or = obj(n, "or");
		if (!draw(l, n) && or != null) draw(l, or);
		return l;
	}

	/**
	 * A header card: a 56px banner (picture cover-fitted, else the gradient) full-bleed under the card's
	 * rounded top corners; the body starts 18px up over it, so its first row's badge overlaps the edge.
	 */
	JComponent hero(JsonObject n) {
		int bannerH = 74, up = 18; // about 3:1 at panel width, the shape banners are uploaded in
		var b = new JsonObject();
		b.add("c", n.get("c"));
		b.addProperty("gap", 6);
		var body = (JPanel) col(b);
		body.setBorder(createEmptyBorder(0, 10, 10, 10));
		var pic = new Icon[1];
		var g = arr(n, "grad");
		var two = g != null && g.size() > 1;
		var from = two ? color(g.get(0).getAsString(), DARK_GRAY_COLOR) : DARK_GRAY_COLOR;
		var to = two ? color(g.get(1).getAsString(), from) : from;
		var strip = new JComponent() {
			protected void paintComponent(Graphics gr) {
				var g2 = (Graphics2D) gr.create();
				g2.setPaint(new GradientPaint(0, 0, from, getWidth(), 0, to));
				g2.fillRect(0, 0, getWidth(), getHeight());
				if (pic[0] != null) {
					g2.clipRect(0, 0, getWidth(), getHeight()); // intersect: setClip would drop the card's rounded clip
					// Cover: scale to fill the strip at its real size, crop the overflow (a GIF scales as it plays).
					int iw = max(1, pic[0].getIconWidth()), ih = max(1, pic[0].getIconHeight());
					var k = max((double) getWidth() / iw, (double) getHeight() / ih);
					g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
					g2.translate((getWidth() - iw * k) / 2, (getHeight() - ih * k) / 2);
					g2.scale(k, k);
					pic[0].paintIcon(this, g2, 0, 0);
				}
				g2.dispose();
			}
		};
		if (n.has("src")) {
			host.image(str(n, "src"), 480, 160, bool(n, "motion"), image -> {
				pic[0] = image;
				strip.repaint();
			});
		}
		var card = new JPanel(null) {
			protected void paintComponent(Graphics gr) {
				var g2 = smooth(gr);
				g2.setColor(DARKER_GRAY_COLOR);
				g2.fillRoundRect(0, 0, getWidth(), getHeight(), 22, 22);
				g2.dispose();
			}

			protected void paintChildren(Graphics gr) {
				var g2 = (Graphics2D) gr.create();
				g2.setClip(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 22, 22));
				super.paintChildren(g2);
				g2.dispose();
			}

			public Dimension getPreferredSize() {
				return new Dimension(0, bannerH - up + body.getPreferredSize().height);
			}

			public Dimension getMinimumSize() {
				return getPreferredSize();
			}

			public void doLayout() {
				strip.setBounds(0, 0, getWidth(), bannerH);
				body.setBounds(0, bannerH - up, getWidth(), getHeight() - bannerH + up);
			}
		};
		card.setOpaque(false);
		card.add(body); // first = painted last, over the strip
		card.add(strip);
		return capH(left(card));
	}

	/** A site picture: it takes no room until a valid image has decoded. */
	JComponent img(JsonObject n) {
		int w = max(1, min(240, num(n, "w", 64)));
		int h = max(1, min(240, num(n, "h", 40)));
		var l = new JLabel("", JLabel.CENTER);
		l.setOpaque(false);
		l.setBackground(DARKER_GRAY_COLOR);
		var empty = new Dimension(0, 0);
		sizeTo(l, empty);
		l.setVisible(false);
		host.image(str(n, "src"), w, h, bool(n, "motion"), image -> {
			// A missing/invalid banner never flashes or leaves a permanent black hole above a card.
			var got = image != null;
			if (got) l.setIcon(bool(n, "round") || num(n, "rad", 0) > 0 ? roundIcon(image, w, h, num(n, "rad", 0)) : image);
			sizeTo(l, got ? new Dimension(w, h) : empty);
			l.setVisible(got);
			if (got) growCappedAncestors(l);
			l.revalidate();
		});
		return l;
	}

	static void sizeTo(JComponent c, Dimension d) {
		c.setPreferredSize(d);
		c.setMinimumSize(d);
		c.setMaximumSize(d);
	}

	/**
	 * A row is height-capped ({@link Ui#capH}) when built, while its picture is still hidden at 0x0; once
	 * the picture decodes, lift each capped ancestor to its new preferred height or the icon is cropped
	 * into a squashed sliver.
	 */
	static void growCappedAncestors(Component c) {
		for (Container p = c.getParent(); p instanceof JComponent && !(p instanceof JViewport); p = p.getParent()) {
			var max = p.getMaximumSize();
			int want = p.getPreferredSize().height;
			if (max.width == Integer.MAX_VALUE && max.height < want) ((JComponent) p).setMaximumSize(new Dimension(Integer.MAX_VALUE, want));
		}
	}

	/**
	 * Mask avatars and clan marks to a smooth circle; an animated GifIcon keeps playing through it.
	 * The masked picture is kept and redone only when the GIF moves to another frame (or another
	 * label paints it), so a repaint is one drawImage rather than a fresh buffer and mask each time.
	 */
	static Icon roundIcon(Icon image, int w, int h, int rad) {
		return new Icon() {
			BufferedImage masked;
			int maskedFrame = -1;
			Component maskedFor;

			public int getIconWidth() { return w; }
			public int getIconHeight() { return h; }

			public void paintIcon(Component c, Graphics g, int x, int y) {
				int frame = image instanceof GifIcon ? ((GifIcon) image).shownFrame() : 0;
				if (masked == null || frame != maskedFrame || c != maskedFor) {
					if (masked == null) masked = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
					// Clipping a GIF directly leaves a visibly jagged Swing edge. Paint the current frame into
					// a tiny ARGB buffer, then apply an antialiased alpha mask; ImageIcon still advances normally.
					// A DstIn fill only touches pixels inside the shape, so it can't cut the corners away: the
					// frame goes into a buffer and is painted through the shape instead.
					var raw = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
					var rg = raw.createGraphics();
					try {
						image.paintIcon(c, rg, 0, 0); // a GifIcon registers c and repaints it per frame itself
					} finally {
						rg.dispose();
					}
					var copy = masked.createGraphics();
					try {
						copy.setComposite(AlphaComposite.Clear);
						copy.fillRect(0, 0, w, h);
						copy.setComposite(AlphaComposite.SrcOver);
						copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
						copy.setPaint(new TexturePaint(raw, new Rectangle(0, 0, w, h)));
						copy.fill(rad > 0 ? new RoundRectangle2D.Float(0, 0, w, h, rad * 2f, rad * 2f) : new Ellipse2D.Float(0, 0, w, h));
					} finally {
						copy.dispose();
					}
					maskedFrame = frame;
					maskedFor = c;
				}
				g.drawImage(masked, x, y, null);
			}
		};
	}

	/** Puts {@code spec}'s picture on {@code l}; false when there is none to draw. */
	boolean draw(JLabel l, JsonObject spec) {
		int size = num(spec, "w", 12);
		if ("swatch".equals(str(spec, "glyph"))) {
			l.setIcon(swatch(color(str(spec, "color"), MUTED), size));
			return true;
		}
		var tenure = tenureStep(str(spec, "glyph"));
		if (tenure > 0) {
			// The Supporter tenure badge: the chat's pixel sprite, shaded in the name's colour.
			// The site picks the size: a whole multiple of the 11px art keeps it crisp.
			l.setIcon(new ImageIcon(AnvilPlugin.tenureSprite(tenure, color(str(spec, "color"), Color.WHITE).getRGB() & 0xffffff, -1, max(AnvilPlugin.TENURE_PX, min(39, size)))));
			return true;
		}
		if (spec.has("glyph")) {
			var shape = glyph(str(spec, "glyph"));
			if (shape == null) return false;
			l.setIcon(new GlyphIcon(shape, size, color(str(spec, "color"), MUTED)));
			return true;
		}
		if (spec.has("item") && items != null) {
			int qty = num(spec, "qty", 0);
			items.getImage(num(spec, "item", 0), max(1, qty), qty > 1).addTo(l);
			return true;
		}
		if (spec.has("sprite") && sprites != null) {
			sprites.addSpriteTo(l, num(spec, "sprite", 0), 0);
			return true;
		}
		var skill = spec.has("skill") ? skill(str(spec, "skill")) : null;
		if (skill != null && skills != null) {
			l.setIcon(new ImageIcon(skills.getSkillImage(skill, true)));
			return true;
		}
		if (spec.has("boss")) return bossIcon(l, str(spec, "boss"), min(size, l.getFontMetrics(l.getFont()).getHeight()));
		return false;
	}

	JComponent bar(JsonObject n) {
		int pct = max(0, min(100, num(n, "pct", 0)));
		var bg = "card".equals(str(n, "bg")) ? DARKER_GRAY_COLOR : DARK_GRAY_COLOR;
		var fg = color(str(n, "color"), pct >= 100 ? PROGRESS_COMPLETE_COLOR : BRAND_ORANGE);
		int h = num(n, "h", 3);
		if (n.has("label")) {
			var bar = new ProgressBar();
			bar.setMaximumValue(100);
			bar.setValue(pct);
			bar.setCenterLabel(plainText(str(n, "label")));
			bar.setLeftLabel("");
			bar.setRightLabel("");
			bar.setForeground(fg);
			bar.setBackground(bg);
			bar.setPreferredSize(new Dimension(0, h));
			bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
			return bar;
		}
		int[] fill = {pct};
		var bar = new JPanel() {
			protected void paintComponent(Graphics g) {
				g.setColor(bg);
				g.fillRect(0, 0, getWidth(), getHeight());
				g.setColor(fg);
				g.fillRect(0, 0, getWidth() * fill[0] / 100, getHeight());
			}
		};
		live(n, (v, goal) -> {
			fill[0] = percent(v, goal);
			bar.repaint();
		});
		bar.setPreferredSize(new Dimension(0, h));
		return capH(bar);
	}

	/** A bar in segments, one colour each (a race's steps: done, here, still to come). */
	static JComponent segs(JsonObject n) {
		List<Color> cs = new ArrayList<>();
		var a = arr(n, "c");
		if (a != null) for (var e : a) cs.add(color(e.getAsString(), MUTED));
		int h = num(n, "h", 6);
		var bar = new JPanel() {
			protected void paintComponent(Graphics g) {
				var g2 = smooth(g);
				int k = cs.size();
				var w = (getWidth() - 3f * (k - 1)) / max(1, k);
				for (int i = 0; i < k; i++) {
					g2.setColor(cs.get(i));
					g2.fillRoundRect(round(i * (w + 3)), 0, round(w), h, 4, 4);
				}
				g2.dispose();
			}
		};
		bar.setOpaque(false);
		bar.setPreferredSize(new Dimension(0, h));
		return capH(bar);
	}

	/**
	 * One stop on a vertical track: a dot ({@code done} ticked green, {@code now} gold with a glow,
	 * {@code end} a gold ring, else a hollow ring), a {@code line} down to the next stop in that colour
	 * (none on the last), and the stop's content beside it.
	 */
	JComponent step(JsonObject n) {
		var dot = str(n, "dot");
		var line = color(str(n, "line"), null);
		var gutter = new JPanel() {
			protected void paintComponent(Graphics g) {
				var g2 = smooth(g);
				if (line != null) {
					g2.setColor(line);
					g2.fillRect(8, 15, 2, getHeight() - 15);
				}
				g2.setStroke(new BasicStroke(2f));
				var gold = BRAND_ORANGE;
				if ("done".equals(dot)) {
					g2.setColor(DONE_DOT);
					g2.fillOval(3, 3, 12, 12);
					g2.setColor(DARKER_GRAY_COLOR);
					g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
					g2.drawPolyline(new int[]{6, 8, 12}, new int[]{9, 11, 7}, 3);
				} else if ("now".equals(dot)) {
					g2.setColor(new Color(64 << 24 | gold.getRGB() & 0xffffff, true));
					g2.fillOval(0, 0, 18, 18);
					g2.setColor(gold);
					g2.fillOval(3, 3, 12, 12);
				} else if ("end".equals(dot)) {
					g2.setColor(RAISED);
					g2.fillOval(3, 3, 12, 12);
					g2.setColor(gold);
					g2.drawOval(4, 4, 10, 10);
				} else {
					g2.setColor(new Color(0x666666));
					if ("lock".equals(dot)) { // not reached yet: a small padlock
						g2.setStroke(new BasicStroke(1.4f));
						g2.drawArc(6, 3, 6, 7, 0, 180);
						g2.fillRoundRect(5, 8, 8, 7, 2, 2);
					} else g2.drawOval(5, 5, 8, 8);
				}
				g2.dispose();
			}
		};
		gutter.setOpaque(false);
		gutter.setPreferredSize(new Dimension(18, 18));
		var body = new JPanel(new BorderLayout());
		body.setOpaque(false);
		body.setBorder(createEmptyBorder(0, 0, line != null ? 8 : 0, 0));
		var c = obj(n, "c");
		if (c != null) body.add(node(c), CENTER);
		var p = new Round(new BorderLayout(6, 0), 4, null, null, null);
		p.add(gutter, WEST);
		p.add(body, CENTER);
		return capH(left(p));
	}

	/**
	 * The section switcher: one body at a time, built when first picked, so a section that fetches
	 * (CAs) fetches only once someone opens it. Not a CardLayout: the sprites arrive after it would
	 * have measured, and the last row got cut off.
	 */
	JComponent tabs(JsonObject n) {
		var key = str(n, "key");
		var list = n.has("tabs") ? n.getAsJsonArray("tabs") : new JsonArray();
		if (list.size() == 0) return new JPanel();
		// A tab with {@code on} and no body is a link (Feed | Chat): picking it runs the action, and the
		// one marked {@code sel} is lit.
		Map<String, JsonObject> bodies = new LinkedHashMap<>();
		Map<String, JsonObject> links = new HashMap<>();
		var wanted = TABS.getOrDefault(key, str(n, "open"));
		for (var e : list) {
			var t = e.getAsJsonObject();
			var label = str(t, "label");
			bodies.put(label, t.has("c") ? t.getAsJsonObject("c") : null);
			if (t.has("on") && !t.has("c")) links.put(label, t.getAsJsonObject("on"));
			if (bool(t, "sel")) wanted = label;
		}
		var first = bodies.containsKey(wanted) ? wanted : bodies.keySet().iterator().next();
		var wrap = column();
		var body = Ui.row(0);
		Map<String, JComponent> built = new HashMap<>();
		Consumer<String> open = name -> {
			if (bodies.get(name) != null) replace(body, built.computeIfAbsent(name, k -> node(bodies.get(k))));
		};
		wrap.add(segments(new ArrayList<>(bodies.keySet()), first, name -> {
			if (links.containsKey(name)) act(links.get(name));
			else {
				TABS.put(key, name);
				open.accept(name);
			}
		}));
		open.accept(first);
		if (links.size() < bodies.size()) {
			wrap.add(gap(4));
			wrap.add(body);
		}
		return wrap;
	}

	/** A segmented switch: a dark rounded track, the picked segment raised with an orange underline. */
	static JComponent segments(List<String> labels, String first, Consumer<String> picked) {
		var strip = new Round(new GridLayout(1, labels.size(), 2, 0), 4, DARKER_GRAY_COLOR, null, null);
		strip.setBorder(createEmptyBorder(2, 2, 2, 2));
		Map<String, Round> segs = new LinkedHashMap<>();
		Consumer<String> paint = name -> segs.forEach((k, seg) -> {
			var on = k.equals(name);
			seg.setOpaque(on);
			var l = (JLabel) seg.getComponent(0);
			l.setForeground(on ? Color.WHITE : MUTED);
			l.setBorder(createCompoundBorder(
				createMatteBorder(0, 0, 2, 0, on ? BRAND_ORANGE : new Color(0, 0, 0, 0)),
				createEmptyBorder(3, 2, 2, 2)));
			seg.repaint();
		});
		for (var name : labels) {
			var seg = new Round(new BorderLayout(), 3, RAISED, null, null);
			var l = new ClipLabel(plainText(name), JLabel.CENTER);
			l.setFont(SMALL);
			seg.add(l, CENTER);
			onClick(seg, () -> {
				paint.accept(name);
				picked.accept(name);
			});
			segs.put(name, seg);
			strip.add(seg);
		}
		paint.accept(first);
		return capH(left(strip));
	}

	JComponent fold(JsonObject n) {
		var wrap = column();
		wrap.setOpaque(false);
		var head = Ui.row(5);
		head.add(node(n.getAsJsonObject("head")), CENTER);
		var chevron = new ClipLabel();
		head.add(chevron, EAST);
		var body = node(n.getAsJsonObject("c"));
		var chev = bool(n, "chev");
		Runnable paint = () -> chevron.setIcon(chev
			? new GlyphIcon(body.isVisible() ? CHEVRON_LEFT : CHEVRON_RIGHT, 8, MUTED) : null);
		body.setVisible(bool(n, "open"));
		paint.run();
		clickable(capH(head), () -> {
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
	JComponent filter(JsonObject n) {
		var wrap = column();
		wrap.setOpaque(false);
		var none = small(str(n, "none"), MUTED);
		none.setVisible(false);
		var more = small(str(n, "more"), MUTED);
		more.setVisible(false);
		int max = num(n, "max", Integer.MAX_VALUE);
		List<Runnable> refills = new ArrayList<>();
		String[] query = {""};
		int[] shown = {0};

		var lists = column();
		lists.setOpaque(false);
		for (var e : n.has("c") ? n.getAsJsonArray("c") : new JsonArray()) {
			var list = e.getAsJsonObject();
			var box = node(list);
			var rows = List.of(box.getComponents());
			var specs = list.has("c") ? list.getAsJsonArray("c") : new JsonArray();
			if (rows.size() != specs.size()) {
				lists.add(box); // a list with gaps or extras between rows isn't searched
				continue;
			}
			refills.add(() -> {
				box.removeAll();
				for (int i = 0; i < rows.size(); i++) {
					var spec = specs.get(i).getAsJsonObject();
					if ((!bool(spec, "q") || !query[0].isEmpty()) && matches(query[0], spec.has("k") ? str(spec, "k") : words(spec)) && shown[0] < max) {
						box.add(rows.get(i));
						shown[0]++;
					}
				}
				box.revalidate();
				box.repaint();
			});
			lists.add(box);
		}
		Runnable refill = () -> {
			shown[0] = 0;
			refills.forEach(Runnable::run);
			none.setVisible(shown[0] == 0 && !refills.isEmpty());
			more.setVisible(shown[0] >= max);
		};
		refill.run();
		if (bool(n, "box")) {
			var search = new SearchField(str(n, "hint"), "");
			styleSearch(search);
			search.setFont(SMALL);
			search.setBackground(DARK_GRAY_COLOR);
			search.setBorder(createEmptyBorder(4, 6, 4, 6));
			onEdit(search, () -> {
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

	/**
	 * A search box that asks again as the member pauses typing (or presses Enter), its action carrying
	 * every input's value. The results belong in a slot, so the box itself — and its caret — stays put.
	 */
	JComponent search(JsonObject n, String name, String value) {
		var field = new SearchField(str(n, "label"), value);
		styleSearch(field);
		field.setFont(SMALL);
		field.setBorder(createCompoundBorder(
			createLineBorder(RAISED_HOVER), createEmptyBorder(4, 6, 4, 6)));
		inputs.put(name, () -> field.getText().trim());
		var on = obj(n, "on");
		if (on != null) {
			var pause = new Timer(350, e -> fire(on));
			pause.setRepeats(false);
			onEdit(field, pause::restart);
			field.addActionListener(e -> {
				pause.stop();
				fire(on);
			});
		}
		return capH(field);
	}

	/**
	 * Redraw every {@code live} count from what this client now knows: its own progress the moment it
	 * happens, a teammate's from the live push. A count only ever moves up from the site's {@code cur}.
	 */
	void tickLive() {
		lives.forEach(Runnable::run);
	}

	void live(JsonObject n, BiConsumer<Long, Long> show) {
		var live = obj(n, "live");
		if (live == null) return;
		int tile = num(live, "tile", 0);
		var cur = live.has("cur") ? live.get("cur").getAsLong() : 0;
		var goal = live.has("goal") ? live.get("goal").getAsLong() : 0;
		long[] shown = {cur};
		lives.add(() -> {
			// Never past the goal: a local count can run ahead of the site (a jump in KC, a lab
			// session's held-back pushes), and "810 / 10" says nothing a full bar doesn't.
			var v = max(cur, host.count(tile));
			if (goal > 0) v = min(v, goal);
			if (v != shown[0]) {
				shown[0] = v;
				show.accept(v, goal);
			}
		});
	}

	/** A live count as the site words it (lib/pluginUi/nodes {@code Live}): of, raw, pct or near. */
	static String liveText(String fmt, long v, long goal) {
		var pct = percent(v, goal) + "%";
		var raw = v + " / " + goal;
		return "pct".equals(fmt) || "near".equals(fmt) && goal >= 100_000 ? pct : "raw".equals(fmt) || "near".equals(fmt) ? raw : count(v) + " / " + count(goal);
	}

	/** Fetch a load ahead of its click; the host keeps the answer, so the click draws it at once. */
	void warm(JsonObject on) {
		if (on == null || !"load".equals(str(on, "a"))) return;
		var key = str(on, "v") + on.get("args");
		var now = System.currentTimeMillis();
		var last = warmed.get(key);
		if (last == null || now - last >= WARM_EVERY_MS) {
			warmed.put(key, now);
			host.load(str(on, "v"), strings(on.get("args")), v -> { });
		}
	}

	JComponent slot(JsonObject n) {
		var holder = Ui.row(0);
		var id = str(n, "id");
		var panes = obj(n, "panes");
		// nav: the slot is a page of its own; `open` names the pane it shows first (no `c` needed).
		var first = bool(n, "nav") ? str(n, "open") : null;
		slots.put(id, new Slot(holder, panes, first));
		var want = openPanes.getOrDefault(id, first);
		var open = panes == null || want == null ? null : panes.get(want);
		if (open == null && first != null && panes != null) open = panes.get(first); // a pane that was a loaded page: back to the first
		holder.add(node(open != null && open.isJsonObject() ? open.getAsJsonObject() : n.getAsJsonObject("c")), CENTER);
		var load = obj(n, "load");
		if (load != null) {
			drawn.remove(id); // a new slot shows its placeholder: whatever answer comes must replace it
			fetch(id, str(load, "v"), strings(load.get("args")));
		}
		return holder;
	}

	JComponent toggle(JsonObject n) {
		var holder = Ui.row(0);
		var key = str(n, "key");
		Runnable paint = () -> replace(holder, node(n.getAsJsonObject(FLIPS.getOrDefault(key, false) ? "lit" : "off")));
		toggles.computeIfAbsent(key, k -> new ArrayList<>()).add(paint);
		paint.run();
		return holder;
	}

	JComponent input(JsonObject n) {
		var name = str(n, "name");
		var value = str(n, "value");
		switch (str(n, "kind")) {
			case "select":
				List<String[]> options = new ArrayList<>();
				for (var e : n.has("options") ? n.getAsJsonArray("options") : new JsonArray()) {
					var o = e.getAsJsonObject();
					options.add(new String[]{str(o, "v"), plainText(str(o, "label"))});
				}
				var combo = new JComboBox<>(options.stream().map(o -> o[1]).toArray(String[]::new));
				AnvilSidebarPanel.styleCombo(combo);
				for (int i = 0; i < options.size(); i++) {
					if (options.get(i)[0].equals(value)) combo.setSelectedIndex(i);
				}
				inputs.put(name, () -> combo.getSelectedIndex() < 0 ? "" : options.get(combo.getSelectedIndex())[0]);
				var on = obj(n, "on");
				if (on != null) combo.addActionListener(e -> fire(on));
				// The widest option must not set the panel's width: the combo takes the row it's given.
				combo.setPrototypeDisplayValue("");
				int ch = combo.getPreferredSize().height;
				combo.setPreferredSize(new Dimension(40, ch));
				combo.setMinimumSize(new Dimension(0, ch));
				return capH(combo);
			case "search":
				return search(n, name, value);
			case "number":
				int min = num(n, "min", 0);
				int max = num(n, "max", Integer.MAX_VALUE);
				int start = max(min, min(max, parseInt(value, min)));
				var spinner = new JSpinner(new SpinnerNumberModel(start, min, max, max(1, num(n, "step", 1))));
				spinner.setFont(SMALL);
				var field = ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField();
				field.setBackground(DARKER_GRAY_COLOR);
				field.setForeground(Color.WHITE);
				field.setCaretColor(Color.WHITE);
				inputs.put(name, () -> String.valueOf(spinner.getValue()));
				var row = Ui.row(6);
				row.add(small(str(n, "label"), MUTED), WEST);
				row.add(spinner, CENTER);
				return capH(row);
			case "check":
				var check = new JCheckBox(plainText(str(n, "label")), "1".equals(value));
				check.setFont(SMALL);
				check.setOpaque(false);
				inputs.put(name, () -> check.isSelected() ? "1" : "");
				return check;
			default:
				return new JPanel();
		}
	}

	// ---- Pieces ------------------------------------------------------------------------------------

	/** "Next mission in 12:34", moving every second; {@code due} once it's reached, {@code none} with no clock. */
	JComponent countdown(JsonObject n) {
		var l = label("", font(str(n, "font")), color(str(n, "color"), Color.WHITE));
		var at = utcMillis(str(n, "at"));
		Runnable paint = () -> {
			var secs = max(0, (at - System.currentTimeMillis()) / 1000);
			// Past a day, "4d 4h" — "100:15:48" reads as a clock, not as four days.
			var clock = secs >= 86400
				? String.format(Locale.ROOT, "%dd %dh", secs / 86400, secs % 86400 / 3600)
				: secs >= 3600
				? String.format(Locale.ROOT, "%d:%02d:%02d", secs / 3600, secs % 3600 / 60, secs % 60)
				: String.format(Locale.ROOT, "%d:%02d", secs / 60, secs % 60);
			l.setText(plainText(at < 0 ? str(n, "none") : secs == 0 ? str(n, "due") : str(n, "prefix") + clock));
		};
		paint.run();
		if (at >= 0) tickers.add(paint);
		return l;
	}

	/** A right-click menu on {@code c} and everything in it (Swing doesn't bubble a click up from a label). */
	void menu(JComponent c, JsonArray items) {
		AnvilSidebarPanel.addMouseListenerToTree(c, new MouseAdapter() {
			public void mousePressed(MouseEvent e) {
				show(e);
			}

			public void mouseReleased(MouseEvent e) {
				show(e);
			}

			void show(MouseEvent e) {
				if (!e.isPopupTrigger()) return;
				var menu = new JPopupMenu();
				for (var el : items) {
					var item = el.getAsJsonObject();
					var mi = new JMenuItem(plainText(str(item, "label")));
					var on = obj(item, "on");
					if (on != null) mi.addActionListener(ev -> act(on));
					else mi.setEnabled(false);
					menu.add(mi);
				}
				menu.show(e.getComponent(), e.getX(), e.getY());
			}
		});
	}

	/**
	 * Lights up under the mouse and runs {@code action} on a left click ({@link Ui#onClick}), noting the
	 * press so a background refresh waits for it.
	 */
	void clickable(JComponent c, Runnable action) {
		var r = c instanceof Round ? (Round) c : null;
		var opaque = r != null ? r.filled() : c.isOpaque();
		var base = c.getBackground();
		var hover = r != null && r.hover != null ? r.hover : DARK_GRAY_HOVER_COLOR;
		AnvilSidebarPanel.addMouseListenerToTree(c, new MouseAdapter() {
			public void mousePressed(MouseEvent e) {
				if (SwingUtilities.isLeftMouseButton(e) && ownClick(c, e)) pressing = true;
			}

			public void mouseReleased(MouseEvent e) {
				pressing = false;
				pressedAt = System.currentTimeMillis();
			}

			public void mouseEntered(MouseEvent e) {
				c.setOpaque(true);
				c.setBackground(hover);
				c.repaint();
			}

			public void mouseExited(MouseEvent e) {
				if (c.getMousePosition(true) == null) { // into a child is still inside
					c.setOpaque(opaque);
					c.setBackground(base);
					c.repaint();
				}
			}
		});
		onClick(c, () -> {
			lastClicked = c;
			action.run();
		});
	}

	static String tip(JsonObject n) {
		var tip = str(n, "tip");
		return tip.isEmpty() ? null : wrapHtml(esc(tip).replace("\n", "<br>"), 180);
	}

	static Font font(String f) {
		return "bold".equals(f) ? BOLD : "regular".equals(f) ? REGULAR : SMALL;
	}

	static final Map<String, Color> COLORS = Map.of("white", Color.WHITE, "text", TEXT_COLOR, "muted", MUTED, "brand", BRAND_ORANGE,
		"done", PROGRESS_COMPLETE_COLOR, "error", PROGRESS_ERROR_COLOR, "claim", new Color(0x7D_C4_F0), "progress", PROGRESS_INPROGRESS_COLOR);

	static Color color(String c, Color fallback) {
		var k = COLORS.get(c);
		return k != null ? k : teamColor(c, fallback);
	}

	/** A team's colour as a square. */
	static Icon swatch(Color c, int size) {
		return new Icon() {
			public void paintIcon(Component comp, Graphics g, int x, int y) {
				g.setColor(c);
				g.fillRect(x, y, size, size);
			}

			public int getIconWidth() {
				return size;
			}

			public int getIconHeight() {
				return size;
			}
		};
	}

	/** "tenure-1".."tenure-6" (a Supporter's tenure badge), else 0. */
	static int tenureStep(String g) {
		return g != null && g.length() == 8 && g.startsWith("tenure-") && g.charAt(7) >= '1' && g.charAt(7) <= '6' ? g.charAt(7) - '0' : 0;
	}

	static final Map<String, GlyphIcon.Shape> GLYPHS = Map.of("check", CHECK, "chevron-right", CHEVRON_RIGHT, "chevron-left", CHEVRON_LEFT,
		"external", EXTERNAL, "close", CLOSE, "star", STAR, "up", UP, "down", DOWN);

	static GlyphIcon.Shape glyph(String g) {
		return GLYPHS.get(g);
	}

	static Skill skill(String key) {
		for (var s : Skill.values()) if (s.name().equalsIgnoreCase(key)) return s;
		return null;
	}

	/** Every text in {@code n}, for a row a filter searches without a {@code k}. */
	static String words(JsonObject n) {
		var sb = new StringBuilder(str(n, "s")).append(' ').append(str(n, "alt"));
		for (var e : n.entrySet()) {
			var v = e.getValue();
			if (v.isJsonObject()) {
				sb.append(' ').append(words(v.getAsJsonObject()));
			} else if (v.isJsonArray()) {
				for (var x : v.getAsJsonArray()) if (x.isJsonObject()) sb.append(' ').append(words(x.getAsJsonObject()));
			}
		}
		return sb.toString();
	}

	/** Every word of the query somewhere in the name, case and punctuation aside ("kril" finds K'ril). */
	static boolean matches(String query, String name) {
		var n = fold(SearchAliases.augment(name));
		for (var word : query.split("\\s+")) if (!n.contains(fold(word))) return false;
		return true;
	}

	static String fold(String s) {
		return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}

	static String str(JsonObject o, String key) {
		var e = o == null ? null : o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
	}

	static JsonObject obj(JsonObject o, String key) {
		var e = o == null ? null : o.get(key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	static JsonArray arr(JsonObject o, String key) {
		var e = o.get(key);
		return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
	}

	static int num(JsonObject o, String key, int fallback) {
		var e = o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsInt() : fallback;
	}

	static boolean bool(JsonObject o, String key) {
		var e = o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
	}

	static int parseInt(String s, int fallback) {
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	static Map<String, String> strings(JsonElement e) {
		Map<String, String> out = new LinkedHashMap<>();
		if (e != null && e.isJsonObject()) {
			for (var x : e.getAsJsonObject().entrySet()) if (x.getValue().isJsonPrimitive()) out.put(x.getKey(), x.getValue().getAsString());
		}
		return out;
	}

	// ---- The game's picture for a boss --------------------------------------------------------------

	/**
	 * RuneLite's hiscore entry for a boss or activity the site names. The site's key
	 * ("alchemicalHydra") and RuneLite's enum (ALCHEMICAL_HYDRA) fold to the same letters; the label
	 * catches the rest, and a prefix catches renames like "barrows" → BARROWS_CHESTS.
	 */
	static HiscoreSkill hiscoreSkill(String key) {
		// The site says "cluesBeginner", RuneLite says CLUE_SCROLL_BEGINNER.
		var k = fold(key).replaceFirst("^clues", "cluescroll");
		if (k.isEmpty()) return null;
		HiscoreSkill best = null;
		int bestLen = 0;
		for (var s : HiscoreSkill.values()) {
			var e = fold(s.name());
			if (e.equals(k) || fold(s.getName()).equals(k) || e.equals("the" + k)) return s;
			// RuneLite says more than we do (barrows ↔ BARROWS_CHESTS) or less (…ExpertMode ↔ …_EXPERT);
			// longest match wins, so the expert raid doesn't settle for the plain one.
			var fits = e.startsWith(k) || (e.length() >= 6 && k.startsWith(e));
			if (fits && e.length() > bestLen) {
				best = s;
				bestLen = e.length();
			}
		}
		if (best != null) return best;
		// A personal best's name: a boss whose name it contains ("Demonic Brutus" → Brutus).
		for (var s : HiscoreSkill.values()) {
			var n = fold(s.getName());
			if (n.length() >= 4 && k.contains(n) && (best == null || n.length() > fold(best.getName()).length())) best = s;
		}
		return best;
	}

	/** A boss's sprite, else a skill it names (agility courses, "99 Attack"); false when neither. */
	/** A boss's hiscore sprite, no taller than {@code max} (the row's text): at full size it pushed the card's last line out. */
	boolean bossIcon(JLabel l, String name, int max) {
		var boss = hiscoreSkill(name);
		if (boss != null && boss.getSpriteId() > 0 && sprites != null) {
			sprites.getSpriteAsync(boss.getSpriteId(), 0, img -> SwingUtilities.invokeLater(() -> {
				if (img != null) l.setIcon(new ImageIcon(img.getHeight() > max ? ImageUtil.resizeImage(img, max, max, true) : img));
			}));
			return true;
		}
		if (skills != null) {
			var lower = name.toLowerCase(Locale.ROOT);
			for (var s : Skill.values()) {
				if (lower.contains(s.getName().toLowerCase(Locale.ROOT))) {
					l.setIcon(new ImageIcon(skills.getSkillImage(s, true)));
					return true;
				}
			}
		}
		return false;
	}
}
