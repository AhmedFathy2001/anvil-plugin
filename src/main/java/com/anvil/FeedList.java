package com.anvil;

import static javax.swing.SwingUtilities.invokeLater;
import static net.runelite.client.ui.ColorScheme.*;
import static com.anvil.UiView.strings;
import static com.anvil.UiView.color;
import static com.anvil.UiView.str;
import lombok.RequiredArgsConstructor;
import static com.anvil.Ui.BOLD;
import static com.anvil.Ui.REGULAR;
import static com.anvil.Ui.SMALL;
import static com.anvil.Ui.MUTED;
import static com.anvil.Ui.esc;
import static com.anvil.Ui.plainText;
import static com.anvil.Ui.styleSearch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.AbstractListModel;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;
import java.awt.FontMetrics;
import javax.swing.SwingUtilities;
import net.runelite.api.Skill;
import net.runelite.api.SpriteID;
import javax.swing.JComponent;

/**
 * The server-driven UI's live list (node {@code feed}): drawn once from the
 * view, then grown by push rows that name its {@link #id} — never redrawn per row.
 *
 * Built to stay smooth under a thousand-player board: ONE {@link JList} with ONE reused row renderer
 * (Swing paints only the rows in view, so 300 rows cost what 20 do); arriving rows wait in a list and
 * a single timer adds them in one model change at most every {@link #FLUSH_MS}; the list is capped;
 * and a reader who has scrolled down is never pushed around — the rows go in above them and a
 * "↑ N new" line offers the way back up.
 *
 * With a {@code send} block it is the event chat: a box under the list posts the text and the line
 * comes back through the push like everybody else's. Clicks never redraw the view: a GZ (or one of a
 * row's {@code reacts} chips, a death's Sad / Sit) flips at once and is posted, a mod's menu action is
 * posted, and only an error line comes back.
 */

final class FeedList extends JPanel {
	/** "...+02:00" at the end of a timestamp. Compiled once: it runs for every row on each paint. */

	static final int FLUSH_MS = 400;
	/** How long after our own press's post answers before held reaction pushes are laid over it. */
	static final int SETTLE_MS = 1500;
	/** Reaction pushes held per pressed row, at most (the newest kept). */
	static final int HELD_CAP = 16;
	static final int ICON_W = 24;
	static final int ICON_H = 21;
	static final Color GOLD = new Color(0xFF_D3_5C);
	static final Color MINE_BG = new Color(0x2E_2A_22);
	static final Color CORAL = new Color(0xFF_7A_6B);
	static final Color SEG_BG = new Color(0x1E_1E_1E);
	static final Color SEG_ON = new Color(0x3A_3A_3A);

	final String id;
	final UiView.Host host;
	final ItemManager items;
	final SpriteManager sprites;
	final SkillIconManager skills;
	final Consumer<JsonObject> act;
	final int cap;
	final RowModel model = new RowModel();
	final JList<JsonObject> list = new JList<JsonObject>(model) {
		@Override
		public boolean getScrollableTracksViewportWidth() {
			return true;
		}

		/** A row's whole text, for when the width cut it short. */
		@Override
		public String getToolTipText(MouseEvent e) {
			int i = locationToIndex(e.getPoint());
			var b = i < 0 ? null : getCellBounds(i, i);
			if (b == null || !b.contains(e.getPoint())) return null;
			var r = model.getElementAt(i);
			// Over a reaction chip: who gave it, as the site put it ("GZ \u00b7 Mossy and 3 others").
			var chip = chipAt(r, getFontMetrics(SMALL), b.x + b.width - 6 - e.getX());
			var who = chip == null ? "" : str(chip, "who");
			if (!who.isEmpty()) return Ui.wrapHtml(esc(Ui.fontSafe(who)), 200);
			var title = str(r, "title");
			var line = title.isEmpty() ? (str(r, "who") + " " + str(r, "text")).trim()
				: title + " — " + str(r, "who") + (str(r, "tag").isEmpty() ? "" : " · " + str(r, "tag"));
			var value = str(r, "value");
			return line.isEmpty() ? null : Ui.wrapHtml(esc(Ui.fontSafe(line + (value.isEmpty() ? "" : " · " + value))), 200);
		}
	};
	final JScrollPane scroll;
	final JLabel empty;
	final JLabel newPill = new ClipLabel("", SwingConstants.CENTER);
	final JLabel error = new ClipLabel();
	final JLabel lockLine = new ClipLabel();
	final JLabel lockSub = new ClipLabel();
	/** In place of the message box while locked: the reason, coral when it's the viewer's own timeout. */
	final JPanel lockBox = new JPanel(new BorderLayout(0, 2));
	JPanel boxPanel;
	/** Chat: the channel switch over the list (My team | All teams | Announcements). */
	final List<Seg> segs = new ArrayList<>();
	JTextField input;
	JsonObject send;
	/** A chat (it has a message box): oldest at the top, newest at the bottom, pinned to the latest. */
	final boolean chat;
	/** Pinned to the newest line: true until the reader scrolls away from it. */
	boolean following = true;
	/** Whose lines are the viewer's own, re-read at each flush. */
	String me;
	/** Chat: every name the viewer's own lines carry (the site's `mine`: their characters and host name), per person. */
	final Set<String> mineNames = new HashSet<>();
	/** Arrived since the last flush, oldest first. */
	final List<JsonObject> pending = new ArrayList<>();
	final Set<String> pendingDel = new HashSet<>();
	/** Rows whose reaction chip this viewer just pressed: their pushes wait (see {@link #reaction}). */
	final Map<String, Held> held = new HashMap<>();
	final Timer flush = new Timer(FLUSH_MS, e -> flush());
	/** Repaints only the visible rows with a sliding name, ~25 times a second, and only while there are any. */
	final Timer slide = new Timer(40, e -> slideVisible());
	int unseen;
	final Map<Integer, Icon> icons = new HashMap<>();
	final Map<Integer, Icon> spriteIcons = new HashMap<>();
	final Map<String, Icon> skillIcons = new HashMap<>();

	FeedList(JsonObject n, UiView.Host host, ItemManager items, SpriteManager sprites, SkillIconManager skills, Consumer<JsonObject> act) {
		super(new BorderLayout(0, 4));
		id = str(n, "id");
		this.host = host;
		this.items = items;
		this.sprites = sprites;
		this.skills = skills;
		this.act = act;
		cap = n.has("cap") ? Math.max(20, n.get("cap").getAsInt()) : 200;
		int h = n.has("h") ? Math.max(120, n.get("h").getAsInt()) : 420;
		flush.setRepeats(false);
		setOpaque(false);
		chat = n.has("send") && n.get("send").isJsonObject();
		me = host == null ? null : host.myName();
		if (n.has("mine") && n.get("mine").isJsonArray()) {
			for (JsonElement e : n.getAsJsonArray("mine")) {
				if (e.isJsonPrimitive()) mineNames.add(ChatFlair.normalize(e.getAsString()));
			}
		}

		List<JsonObject> first = new ArrayList<>();
		if (n.has("rows") && n.get("rows").isJsonArray()) {
			for (JsonElement e : n.getAsJsonArray("rows")) {
				if (e.isJsonObject()) first.add(e.getAsJsonObject());
			}
		}
		List<JsonObject> kept = new ArrayList<>(first.size() > cap ? first.subList(0, cap) : first);
		// The site sends newest first; a chat reads top to bottom.
		if (chat) Collections.reverse(kept);
		model.reset(kept);

		var renderer = new RowRenderer();
		list.setCellRenderer(renderer);
		if (!chat) {
			// Every feed row is the same shape: measure one and never measure again.
			var probe = new JsonObject();
			probe.addProperty("id", "probe");
			probe.addProperty("title", "Probe");
			probe.addProperty("who", "Probe");
			probe.addProperty("value", "1m");
			probe.addProperty("glyph", "check");
			// With a reaction pill, so the fixed height has room for one.
			var probeGz = new JsonObject();
			probeGz.addProperty("n", 1);
			probe.add("gz", probeGz);
			list.setFixedCellHeight(renderer.getListCellRendererComponent(list, probe, 0, false, false).getPreferredSize().height);
		}
		list.setBackground(DARKER_GRAY_COLOR);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setFocusable(false);
		javax.swing.ToolTipManager.sharedInstance().registerComponent(list);
		list.addMouseListener(new Clicks());
		// Over a pill you can press: a hand cursor and the pill lights, so reactions read as buttons.
		MouseAdapter hover = new MouseAdapter() {
			@Override
			public void mouseMoved(MouseEvent e) {
				int i = list.locationToIndex(e.getPoint());
				var b = i < 0 ? null : list.getCellBounds(i, i);
				int chip = -1;
				if (b != null && b.contains(e.getPoint())) {
					var c = chipsOf(model.getElementAt(i));
					int at = c == null ? -1 : reactAt(c, list.getFontMetrics(SMALL), b.x + b.width - 6 - e.getX());
					if (at >= 0 && c.get(at).getAsJsonObject().has("on")) chip = at;
				}
				setHover(chip >= 0 ? i : -1, chip);
			}

			@Override
			public void mouseExited(MouseEvent e) {
				setHover(-1, -1);
			}
		};
		list.addMouseMotionListener(hover);
		list.addMouseListener(hover);
		// Chat rows wrap to the width: a new width means new heights, so drop JList's cached ones.
		list.addComponentListener(new ComponentAdapter() {
			int lastW = -1;

			@Override
			public void componentResized(ComponentEvent e) {
				if (list.getWidth() != lastW) {
					lastW = list.getWidth();
					list.setFixedCellHeight(10);
					list.setFixedCellHeight(-1);
				}
				if (chat && following) toLatest();
			}
		});

		scroll = new JScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.getViewport().addChangeListener(e -> {
			var atLatest = atLatest();
			if (chat) following = atLatest;
			if (unseen > 0 && atLatest) {
				unseen = 0;
				newPill.setVisible(false);
			}
		});
		SlimScrollBarUI.applyTo(scroll);
		scroll.setPreferredSize(new Dimension(100, h));
		scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));

		newPill.setFont(SMALL);
		newPill.setOpaque(true);
		newPill.setBackground(BRAND_ORANGE);
		newPill.setForeground(Color.BLACK);
		newPill.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
		newPill.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		newPill.setVisible(false);
		newPill.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				toLatest();
			}
		});

		// Wrapped, not cut: the empty line is the only thing in the list.
		empty = new ClipLabel(Ui.wrapHtml(esc(Ui.fontSafe(str(n, "empty"))), 170));
		empty.setFont(SMALL);
		empty.setForeground(MUTED);

		var top = new JPanel(new BorderLayout(0, 4));
		top.setOpaque(false);
		if (!chat) top.add(newPill, BorderLayout.NORTH);
		else if (n.has("chans") && n.get("chans").isJsonArray()) top.add(switchOf(n.getAsJsonArray("chans")), BorderLayout.NORTH);
		top.add(empty, BorderLayout.SOUTH);
		add(top, BorderLayout.NORTH);
		add(scroll, BorderLayout.CENTER);

		if (chat) {
			send = n.getAsJsonObject("send");
			var bottom = new JPanel(new BorderLayout(0, 4));
			bottom.setOpaque(false);
			bottom.add(newPill, BorderLayout.NORTH);
			bottom.add(boxPanel = sendBox(), BorderLayout.CENTER);
			bottom.add(lockBox, BorderLayout.SOUTH);
			add(bottom, BorderLayout.SOUTH);
			lock(str(n, "locked"), str(n, "lockSub"), n.has("lockWarn"));
			invokeLater(this::toLatest);
		}
		syncEmpty();
		setMaximumSize(new Dimension(Integer.MAX_VALUE, getPreferredSize().height));
	}

	// ---- Push --------------------------------------------------------------------------------------

	/** Rows for this list, newest first as sent. Added on the next flush. */
	void add(JsonArray rows) {
		for (int i = rows.size() - 1; i >= 0; i--) {
			if (rows.get(i).isJsonObject()) pending.add(rows.get(i).getAsJsonObject());
		}
		schedule();
	}

	void remove(JsonArray ids) {
		for (JsonElement e : ids) pendingDel.add(e.getAsString());
		schedule();
	}

	/**
	 * A row's reactions as they now stand, pushed after anyone toggles one (site lib/panelRows
	 * emitReactionRows): {@code {t:"rowsreact", feeds, id:"m<moment>", gz, gold, sad, sit}}, and
	 * {@code who:{kind:line}} when the site says who gave them. Counts are absolute; this viewer's
	 * highlight only moves when the message carries it ({@code mineGz}, {@code mineSad}, ...: the copy
	 * sent to the reactor's own clients, so their other client follows). In place, never reordered.
	 *
	 * A chip this viewer just pressed is already showing where it's going: pushes for that row wait
	 * until the press settles (its post answered, plus {@link #SETTLE_MS}) and are then replayed in
	 * order, so the confirmation of our own toggle changes nothing and a quick on-off never flickers.
	 */
	void reaction(JsonObject update) {
		var rowId = str(update, "id");
		if (rowId.isEmpty()) return;
		var h = held.get(rowId);
		if (h != null) {
			if (h.queued.size() >= HELD_CAP) h.queued.remove(0);
			h.queued.add(update);
			return;
		}
		applyReactionToList(rowId, update);
	}

	void applyReactionToList(String rowId, JsonObject update) {
		for (JsonObject row : pending) {
			if (rowId.equals(str(row, "id"))) applyReaction(row, update);
		}
		int i = model.indexOf(rowId);
		if (i >= 0 && applyReaction(model.getElementAt(i), update)) model.changed(i);
	}

	/** Lays one push over a row's chips; true when any chip changed. Kinds the row has no chip for are ignored. */
	static boolean applyReaction(JsonObject row, JsonObject update) {
		List<JsonObject> chips = new ArrayList<>();
		if (row.has("reacts") && row.get("reacts").isJsonArray()) {
			for (JsonElement e : row.getAsJsonArray("reacts")) {
				if (e.isJsonObject()) chips.add(e.getAsJsonObject());
			}
		} else if (row.has("gz") && row.get("gz").isJsonObject()) {
			// The plain GZ chip carries no kind of its own.
			return applyChip(row.getAsJsonObject("gz"), "gz", update);
		}
		var changed = false;
		for (JsonObject chip : chips) changed |= applyChip(chip, str(chip, "kind"), update);
		return changed;
	}

	static boolean applyChip(JsonObject chip, String kind, JsonObject update) {
		if (kind.isEmpty()) return false;
		var changed = false;
		if (update.has(kind) && update.get(kind).isJsonPrimitive()) {
			int n = Math.max(0, update.get(kind).getAsInt());
			if (!chip.has("n") || chip.get("n").getAsInt() != n) {
				chip.addProperty("n", n);
				changed = true;
			}
		}
		var mineKey = "mine" + Character.toUpperCase(kind.charAt(0)) + kind.substring(1);
		if (update.has(mineKey) && update.get(mineKey).isJsonPrimitive()) {
			var mine = update.get(mineKey).getAsBoolean();
			if (!chip.has("mine") || chip.get("mine").getAsBoolean() != mine) {
				chip.addProperty("mine", mine);
				changed = true;
			}
		}
		if (update.has("who") && update.get("who").isJsonObject()) {
			var who = update.getAsJsonObject("who");
			if (who.has(kind)) {
				var line = str(who, kind);
				if (line.isEmpty()) {
					changed |= chip.remove("who") != null;
				} else if (!line.equals(str(chip, "who"))) {
					chip.addProperty("who", line);
					changed = true;
				}
			}
		}
		return changed;
	}

	/** A press on this row's chip just went out: hold its pushes until it settles. */
	void hold(String rowId) {
		held.computeIfAbsent(rowId, k -> new Held()).posts++;
	}

	/** One of the row's posts answered: once none are out, wait {@link #SETTLE_MS} and replay what was held. */
	void unhold(String rowId) {
		var h = held.get(rowId);
		if (h == null) return;
		h.posts = Math.max(0, h.posts - 1);
		if (h.posts > 0) return;
		int gen = ++h.gen;
		var t = new Timer(SETTLE_MS, e -> settle(rowId, gen));
		t.setRepeats(false);
		t.start();
	}

	void settle(String rowId, int gen) {
		var h = held.get(rowId);
		if (h == null || h.posts > 0 || h.gen != gen) return;
		held.remove(rowId);
		for (JsonObject update : h.queued) applyReactionToList(rowId, update);
	}

	/** For tests: every press whose post has answered settles now, without waiting out the timer. */
	void settleNow() {
		for (String rowId : new ArrayList<>(held.keySet())) {
			var h = held.get(rowId);
			if (h.posts == 0) settle(rowId, h.gen);
		}
	}

	/** Pushes for a row whose chip this viewer just pressed. */
	static final class Held {
		int posts;
		int gen;
		final List<JsonObject> queued = new ArrayList<>();
	}

	/**
	 * A box with this line (and a muted second one) in place of the message box, or the box back
	 * (empty {@code why}). {@code warn}: the viewer's own timeout or ban, drawn coral.
	 */
	void lock(String why, String sub, boolean warn) {
		if (input == null) return;
		var locked = why != null && !why.isEmpty();
		input.setEnabled(!locked);
		boxPanel.setVisible(!locked);
		lockBox.setVisible(locked);
		lockLine.setText(locked ? plainText(why) : "");
		lockLine.setForeground(warn ? CORAL : Color.WHITE);
		lockSub.setText(sub == null ? "" : plainText(sub));
		lockSub.setVisible(sub != null && !sub.isEmpty());
		lockBox.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(warn ? new Color(0x6A_3A_34) : new Color(0x4A_4A_4A)),
			BorderFactory.createEmptyBorder(6, 8, 6, 8)));
		revalidate();
	}

	boolean locked() {
		return lockBox.isVisible();
	}

	/** Lines arrived for these lists: a channel segment that names one, and isn't open, counts them. */
	void bump(Set<String> feeds, int count) {
		for (Seg g : segs) {
			if (!g.on && feeds.contains(g.feed)) {
				g.n += count;
				g.repaint();
			}
		}
	}

	int unread(String feed) {
		for (Seg g : segs) if (g.feed.equals(feed)) return g.n;
		return -1;
	}

	JPanel switchOf(JsonArray chans) {
		var bar = new JPanel(new java.awt.GridLayout(1, 0, 2, 0));
		bar.setBackground(SEG_BG);
		bar.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
		for (JsonElement e : chans) {
			if (!e.isJsonObject()) continue;
			var c = e.getAsJsonObject();
			var g = new Seg(c);
			segs.add(g);
			if (!g.on && c.has("act") && c.get("act").isJsonObject()) Ui.onClick(g, () -> act.accept(c.getAsJsonObject("act")));
			bar.add(g);
		}
		return bar;
	}

	/** One channel on the switch: its name, lit when open, and an unread pill when not. */
	static final class Seg extends JComponent {
		final String label;
		final String feed;
		final boolean on;
		final boolean blue;
		int n;

		Seg(JsonObject c) {
			label = str(c, "label");
			feed = str(c, "feed");
			on = c.has("on") && c.get("on").getAsBoolean();
			blue = c.has("blue");
			n = c.has("n") ? c.get("n").getAsInt() : 0;
			setFont(SMALL);
			setPreferredSize(new Dimension(10, 22));
			// The label is often cut short at panel width ("Announceme…"): the full name on hover.
			var tip = str(c, "tip");
			setToolTipText(plainText(!tip.isEmpty() ? tip : label + (n > 0 ? " · " + n + " unread" : "")));
		}

		@Override
		protected void paintComponent(Graphics g0) {
			var g = Ui.smooth(g0);
			int w = getWidth(), h = getHeight();
			if (on) {
				g.setColor(SEG_ON);
				g.fillRoundRect(0, 0, w, h, 6, 6);
				g.setColor(BRAND_ORANGE);
				g.fillRect(2, h - 2, w - 4, 2);
			}
			var fm = g.getFontMetrics(getFont());
			g.setFont(getFont());
			var pill = !on && n > 0 ? (n > 99 ? "99+" : String.valueOf(n)) : null;
			int pw = pill == null ? 0 : Math.max(14, fm.stringWidth(pill) + 8);
			var text = label;
			while (text.length() > 1 && fm.stringWidth(text) + pw + (pw > 0 ? 3 : 0) > w - 4) text = text.substring(0, text.length() - 1);
			if (!text.equals(label)) text = text.substring(0, Math.max(1, text.length() - 1)) + "\u2026";
			int tw = fm.stringWidth(text) + (pw > 0 ? pw + 3 : 0);
			int x = (w - tw) / 2, base = (h + fm.getAscent() - fm.getDescent()) / 2;
			g.setColor(on ? Color.WHITE : MUTED);
			g.drawString(text, x, base);
			if (pill != null) {
				int px = x + fm.stringWidth(text) + 3, py = (h - 14) / 2;
				g.setColor(blue ? new Color(0x1B_2A_3A) : BRAND_ORANGE);
				g.fillRoundRect(px, py, pw, 14, 14, 14);
				g.setColor(blue ? new Color(0x8C_C8_FF) : new Color(0x1E_1E_1E));
				g.drawString(pill, px + (pw - fm.stringWidth(pill)) / 2, base);
			}
			g.dispose();
		}
	}

	boolean hasBox() {
		return input != null;
	}

	void stop() {
		flush.stop();
		slide.stop();
	}

	@Override
	public void addNotify() {
		super.addNotify();
		// Chat lines and Feed rows both slide a Patron's gradient; slideVisible repaints only the
		// visible rows that move, and nothing while the list is hidden or "Animated names" is off.
		slide.start();
	}

	@Override
	public void removeNotify() {
		super.removeNotify();
		slide.stop();
	}

	void slideVisible() {
		if (!list.isShowing() || !GradientLabel.animate || !GradientLabel.clientActive()) return;
		int first = list.getFirstVisibleIndex();
		int last = list.getLastVisibleIndex();
		for (int i = Math.max(0, first); i >= 0 && i <= last && i < model.getSize(); i++) {
			if (moves(model.getElementAt(i))) {
				var b = list.getCellBounds(i, i);
				if (b != null) list.repaint(b);
			}
		}
	}

	// For tests: what the list holds, newest first, and its box.
	int rowCount() {
		return model.getSize();
	}

	String rowId(int i) {
		return str(model.getElementAt(i), "id");
	}

	JsonObject reactionChip(int i, String kind) {
		var row = model.getElementAt(i);
		if (row.has("reacts") && row.get("reacts").isJsonArray()) {
			for (JsonElement e : row.getAsJsonArray("reacts")) {
				if (kind.equals(str(e.getAsJsonObject(), "kind"))) return e.getAsJsonObject();
			}
		}
		return "gz".equals(kind) && row.has("gz") ? row.getAsJsonObject("gz") : null;
	}

	JTextField box() {
		return input;
	}

	String errorLine() {
		return error.isVisible() ? error.getText() : null;
	}

	void schedule() {
		if (!flush.isRunning()) flush.start();
	}

	/** Everything that arrived since the last flush, in one model change. */
	void flush() {
		if (!pendingDel.isEmpty()) {
			model.removeIds(pendingDel);
			pending.removeIf(r -> pendingDel.contains(str(r, "id")));
			pendingDel.clear();
		}
		if (pending.isEmpty()) {
			syncEmpty();
			return;
		}
		List<JsonObject> fresh = new ArrayList<>();
		for (int i = pending.size() - 1; i >= 0; i--) {
			var r = pending.get(i);
			if (!model.has(str(r, "id"))) fresh.add(r);
		}
		pending.clear();
		if (fresh.isEmpty()) return;
		me = host == null ? null : host.myName();
		if (chat) {
			// Oldest first at the bottom; follow it down unless the reader has scrolled up.
			var stick = following;
			Collections.reverse(fresh);
			model.append(fresh, cap);
			if (stick) {
				invokeLater(this::toLatest);
			} else {
				unseen += fresh.size();
				newPill.setText("↓ " + unseen + " new");
				newPill.setVisible(true);
			}
			syncEmpty();
			return;
		}
		int y = scroll.getViewport().getViewPosition().y;
		model.prepend(fresh, cap);
		if (y > 2) {
			// Reading further down: keep the same rows under their eyes and say what's above.
			var added = list.getCellBounds(0, fresh.size() - 1);
			scroll.getViewport().setViewPosition(new Point(0, y + (added == null ? 0 : added.height)));
			unseen += fresh.size();
			newPill.setText("↑ " + unseen + " new");
			newPill.setVisible(true);
		}
		syncEmpty();
	}

	/** At the newest row: the bottom for a chat, the top for a feed. */
	boolean atLatest() {
		javax.swing.JViewport vp = scroll.getViewport();
		if (!chat) return vp.getViewPosition().y <= 2;
		return vp.getViewPosition().y + vp.getExtentSize().height >= list.getHeight() - 4;
	}

	void toLatest() {
		following = true;
		unseen = 0;
		newPill.setVisible(false);
		if (chat) {
			int last = model.getSize() - 1;
			if (last >= 0) {
				list.ensureIndexIsVisible(last);
				var b = list.getCellBounds(last, last);
				if (b != null) list.scrollRectToVisible(b);
			}
		} else scroll.getViewport().setViewPosition(new Point(0, 0));
	}

	void syncEmpty() {
		var none = model.getSize() == 0;
		if (empty.isVisible() != (none && !empty.getText().isEmpty())) {
			empty.setVisible(none && !empty.getText().isEmpty());
			revalidate();
		}
	}

	// ---- The message box ---------------------------------------------------------------------------

	JPanel sendBox() {
		var box = new JPanel(new BorderLayout(0, 2));
		box.setOpaque(false);
		input = new JTextField();
		styleSearch(input);
		input.setToolTipText(plainText(str(send, "hint")));
		int max = send.has("max") ? send.get("max").getAsInt() : 200;
		((AbstractDocument) input.getDocument()).setDocumentFilter(new MaxLength(max));
		input.addActionListener(e -> sendNow());
		// Escape hands the keyboard back to the game without sending.
		input.getInputMap().put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "anvil-game");
		input.getActionMap().put("anvil-game", new javax.swing.AbstractAction() {
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				host.gameFocus();
			}
		});
		JLabel hint = new ClipLabel(plainText(str(send, "hint") + " — Enter to send"));
		hint.setFont(SMALL);
		hint.setForeground(MUTED);
		error.setFont(SMALL);
		error.setForeground(PROGRESS_ERROR_COLOR);
		error.setVisible(false);
		lockLine.setFont(BOLD);
		lockSub.setFont(SMALL);
		lockSub.setForeground(MUTED);
		lockBox.setOpaque(false);
		lockBox.add(lockLine, BorderLayout.NORTH);
		lockBox.add(lockSub, BorderLayout.SOUTH);
		lockBox.setVisible(false);
		var lines = new JPanel(new BorderLayout());
		lines.setOpaque(false);
		lines.add(hint, BorderLayout.NORTH);
		lines.add(error, BorderLayout.CENTER);
		box.add(input, BorderLayout.NORTH);
		box.add(lines, BorderLayout.SOUTH);
		return box;
	}

	/** A send on its way: Enter again does nothing (the box is never disabled — that moves focus). */
	boolean sending;

	/**
	 * Post the line. Sent, the keyboard goes straight back to the game, so Key Remapping, WASD and
	 * Enter-to-chat work as before; refused, it stays in the box so the line can be fixed.
	 */
	void sendNow() {
		var text = input.getText().trim();
		if (text.isEmpty() || !input.isEnabled() || sending) return;
		sending = true;
		Map<String, String> fields = new HashMap<>();
		fields.put("msg", text);
		host.post(str(send, "act"), strings(send.get("args")), fields, v -> {
			sending = false;
			var err = errorOf(v);
			showError(err);
			if (err == null) {
				input.setText("");
				host.gameFocus();
			}
		});
	}

	void showError(String err) {
		error.setText(err == null ? "" : plainText(err));
		error.setVisible(err != null);
		revalidate();
	}

	/** An action's answer: null when it went through, else the line to show. */
	static String errorOf(JsonObject v) {
		if (v == null || !v.has("ui") || !v.get("ui").isJsonObject()) return "Couldn't reach Anvil — try again in a moment.";
		var ui = v.getAsJsonObject("ui");
		return "text".equals(str(ui, "t")) && "error".equals(str(ui, "color")) ? str(ui, "s") : null;
	}

	@RequiredArgsConstructor
	static final class MaxLength extends DocumentFilter {
		final int max;

		@Override
		public void insertString(FilterBypass fb, int offset, String s, AttributeSet a) throws BadLocationException {
			replace(fb, offset, 0, s, a);
		}

		@Override
		public void replace(FilterBypass fb, int offset, int length, String s, AttributeSet a) throws BadLocationException {
			var add = s == null ? "" : s;
			int room = max - (fb.getDocument().getLength() - length);
			if (room <= 0) return;
			super.replace(fb, offset, length, add.length() > room ? add.substring(0, room) : add, a);
		}
	}

	// ---- Clicks ------------------------------------------------------------------------------------

	final class Clicks extends MouseAdapter {
		@Override
		public void mousePressed(MouseEvent e) {
			popup(e);
		}

		@Override
		public void mouseReleased(MouseEvent e) {
			if (!popup(e) && SwingUtilities.isLeftMouseButton(e)) click(e);
		}

		int rowAt(MouseEvent e) {
			int i = list.locationToIndex(e.getPoint());
			var b = i < 0 ? null : list.getCellBounds(i, i);
			return b != null && b.contains(e.getPoint()) ? i : -1;
		}

		boolean popup(MouseEvent e) {
			if (!e.isPopupTrigger()) return false;
			int i = rowAt(e);
			var r = i < 0 ? null : model.getElementAt(i);
			if (r == null || !r.has("menu") || !r.get("menu").isJsonArray()) return true;
			var menu = new JPopupMenu();
			for (JsonElement el : r.getAsJsonArray("menu")) {
				var item = el.getAsJsonObject();
				var mi = new JMenuItem(plainText(str(item, "label")));
				var on = item.has("on") && item.get("on").isJsonObject() ? item.getAsJsonObject("on") : null;
				if (on == null) {
					mi.setEnabled(false);
				} else mi.addActionListener(ev -> quietly(on));
				menu.add(mi);
			}
			menu.show(list, e.getX(), e.getY());
			return true;
		}

		void click(MouseEvent e) {
			int i = rowAt(e);
			if (i < 0) return;
			var r = model.getElementAt(i);
			var b = list.getCellBounds(i, i);
			var chip = chipAt(r, list.getFontMetrics(SMALL), b.x + b.width - 6 - e.getX());
			if (chip != null) {
				press(i, chip);
				return;
			}
			if (r.has("on") && r.get("on").isJsonObject()) act.accept(r.getAsJsonObject("on"));
		}
	}

	/** A reaction chip pressed: flipped at once, posted, and the row's pushes held until the post settles. */
	void press(int i, JsonObject chip) {
		var mine = chip.has("mine") && chip.get("mine").getAsBoolean();
		int count = chip.has("n") ? chip.get("n").getAsInt() : 0;
		chip.addProperty("mine", !mine);
		chip.addProperty("n", Math.max(0, count + (mine ? -1 : 1)));
		model.changed(i);
		if (!chip.has("on") || !chip.get("on").isJsonObject()) return;
		var on = chip.getAsJsonObject("on");
		var rowId = str(model.getElementAt(i), "id");
		if (rowId.isEmpty() || !"post".equals(str(on, "a"))) {
			quietly(on);
			return;
		}
		hold(rowId);
		host.post(str(on, "act"), strings(on.get("args")), Collections.emptyMap(), v -> {
			unhold(rowId);
			showError(errorOf(v));
		});
	}

	/** For tests: press row {@code i}'s chip of this kind, as a click on it would. */
	void pressChip(int i, String kind) {
		var chip = reactionChip(i, kind);
		if (chip != null) press(i, chip);
	}

	/** A post that must not redraw the view: only an error line comes back. */
	void quietly(JsonObject on) {
		if (!"post".equals(str(on, "a"))) {
			act.accept(on);
			return;
		}
		host.post(str(on, "act"), strings(on.get("args")), Collections.emptyMap(), v -> showError(errorOf(v)));
	}

	// ---- The model: one list, changed in batches -------------------------------------------------

	static final class RowModel extends AbstractListModel<JsonObject> {
		final List<JsonObject> rows = new ArrayList<>();
		final Set<String> ids = new HashSet<>();

		@Override
		public int getSize() {
			return rows.size();
		}

		@Override
		public JsonObject getElementAt(int index) {
			return rows.get(index);
		}

		boolean has(String id) {
			return ids.contains(id);
		}

		void reset(List<JsonObject> next) {
			int was = rows.size();
			rows.clear();
			ids.clear();
			if (was > 0) fireIntervalRemoved(this, 0, was - 1);
			for (JsonObject r : next) {
				if (ids.add(str(r, "id"))) rows.add(r);
			}
			if (!rows.isEmpty()) fireIntervalAdded(this, 0, rows.size() - 1);
		}

		/** A chat's new lines at the bottom; the oldest past {@code cap} go from the top. */
		void append(List<JsonObject> fresh, int cap) {
			int at = rows.size();
			for (JsonObject r : fresh) ids.add(str(r, "id"));
			rows.addAll(fresh);
			fireIntervalAdded(this, at, rows.size() - 1);
			if (rows.size() > cap) {
				int gone = rows.size() - cap;
				for (JsonObject r : rows.subList(0, gone)) ids.remove(str(r, "id"));
				rows.subList(0, gone).clear();
				fireIntervalRemoved(this, 0, gone - 1);
			}
		}

		/** Newest first; the oldest past {@code cap} go. Two events however many rows. */
		void prepend(List<JsonObject> fresh, int cap) {
			for (JsonObject r : fresh) ids.add(str(r, "id"));
			rows.addAll(0, fresh);
			fireIntervalAdded(this, 0, fresh.size() - 1);
			if (rows.size() > cap) {
				int from = cap;
				int to = rows.size() - 1;
				for (JsonObject r : rows.subList(from, rows.size())) ids.remove(str(r, "id"));
				rows.subList(from, rows.size()).clear();
				fireIntervalRemoved(this, from, to);
			}
		}

		void removeIds(Set<String> gone) {
			for (int i = rows.size() - 1; i >= 0; i--) {
				var id = str(rows.get(i), "id");
				if (gone.contains(id)) {
					rows.remove(i);
					ids.remove(id);
					fireIntervalRemoved(this, i, i);
				}
			}
		}

		int indexOf(String id) {
			for (int i = 0; i < rows.size(); i++) {
				if (id.equals(str(rows.get(i), "id"))) return i;
			}
			return -1;
		}

		void changed(int i) {
			fireContentsChanged(this, i, i);
		}
	}

	// ---- One renderer, reused for every row --------------------------------------------------------

	Icon iconFor(JsonObject r) {
		if (r.has("item") && items != null) {
			int item = r.get("item").getAsInt();
			var cached = icons.get(item);
			if (cached != null) return cached;
			icons.put(item, new BlankIcon());
			var img = items.getImage(item);
			img.onLoaded(() -> invokeLater(() -> {
				icons.put(item, new ImageIcon(ImageUtil.resizeImage(img, ICON_W, ICON_H, true)));
				list.repaint();
			}));
			return icons.get(item);
		}
		if (r.has("sprite") && sprites != null) {
			int sprite = spriteId(str(r, "sprite"));
			if (sprite < 0) return null;
			var cached = spriteIcons.get(sprite);
			if (cached != null) return cached;
			spriteIcons.put(sprite, new BlankIcon());
			sprites.getSpriteAsync(sprite, 0, img -> invokeLater(() -> {
				if (img != null) {
					spriteIcons.put(sprite, new ImageIcon(ImageUtil.resizeImage(img, ICON_W, ICON_H, true)));
					list.repaint();
				}
			}));
			return spriteIcons.get(sprite);
		}
		if (r.has("skill") && skills != null) {
			var name = str(r, "skill").toUpperCase(java.util.Locale.ROOT);
			var cached = skillIcons.get(name);
			if (cached != null) return cached;
			try {
				var skill = Skill.valueOf(name);
				Icon icon = new ImageIcon(ImageUtil.resizeImage(skills.getSkillImage(skill, true), ICON_W, ICON_H, true));
				skillIcons.put(name, icon);
				return icon;
			} catch (IllegalArgumentException ignored) {
				return null;
			}
		}
		if (r.has("glyph")) {
			var g = str(r, "glyph");
			var shape = "star".equals(g) ? GlyphIcon.Shape.STAR : "chevron-right".equals(g) ? GlyphIcon.Shape.CHEVRON_RIGHT : GlyphIcon.Shape.CHECK;
			return new GlyphIcon(shape, 12, color(str(r, "glyphColor"), MUTED));
		}
		return null;
	}

	static int spriteId(String name) {
		switch (name) {
			case "quest":
				return SpriteID.QUESTS_PAGE_ICON_BLUE_QUESTS;
			case "diary":
				return SpriteID.QUESTS_PAGE_ICON_GREEN_ACHIEVEMENT_DIARIES;
			case "combat":
				return SpriteID.TAB_COMBAT;
			case "collection-log":
				return SpriteID.HISCORE_COLLECTIONS_LOGGED;
			case "total":
				return SpriteID.SKILL_TOTAL;
			default:
				return -1;
		}
	}

	/** A row's picture on a rounded tile of its kind's colour (purple rare, green done, coral death). */
	@RequiredArgsConstructor
	static final class Tinted implements Icon {
		final Icon in;
		final Color tint;

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y) {
			var g2 = Ui.smooth(g);
			g2.setColor(tint);
			g2.fillRoundRect(x, y, ICON_W, ICON_W - 2, 6, 6);
			g2.dispose();
			in.paintIcon(c, g, x + (ICON_W - in.getIconWidth()) / 2, y + (ICON_W - 2 - in.getIconHeight()) / 2);
		}

		@Override
		public int getIconWidth() {
			return ICON_W;
		}

		@Override
		public int getIconHeight() {
			return ICON_W - 2;
		}
	}

		static final class BlankIcon implements Icon {
		@Override
		public void paintIcon(Component c, Graphics g, int x, int y) {
		}

		@Override
		public int getIconWidth() {
			return ICON_W;
		}

		@Override
		public int getIconHeight() {
			return ICON_H;
		}
	}

	final ChatCell chatCell = new ChatCell();

	/**
	 * One chat line, painted straight onto the list: the name (and team, or HOST / MOD / TEAM) with
	 * the time, the message wrapped under it, and a run of lines from one person showing the name once.
	 *
	 * No HTML: a Swing HTML label parses its markup every time a row is measured or painted, and a list
	 * re-measures every row whenever one is added — 300 HTML rows cost ~100ms a flush on a fast PC. Here
	 * the wrapping is done once per row and width with plain font metrics and kept, so a flush is cheap
	 * however long the chat is, and nothing a player typed can ever become markup.
	 */
	final class ChatCell extends JComponent {
		static final int PAD_X = 8;
		final Map<String, List<String>> wraps = new HashMap<>();
		int wrapWidth = -1;
		JsonObject r;
		boolean grouped;
		boolean mine;
		List<String> lines = Collections.emptyList();
		int height;

		ChatCell() {
			setOpaque(true);
		}

		ChatCell show(JsonObject row, int index, int width, boolean mine) {
			this.r = row;
			this.mine = mine;
			var prev = index > 0 ? model.getElementAt(index - 1) : null;
			grouped = prev != null && sameRun(prev, row);
			// A few px spare: lines are measured off-screen and painted on it, and the two can round apart.
			int w = Math.max(80, width - PAD_X * 2 - 6);
			// Rows trimmed off the list leave their wrapping behind: start over once it holds well more
			// than the list does (the rows on screen re-wrap as they paint).
			if (w != wrapWidth || wraps.size() > 2 * Math.max(model.getSize(), 100)) {
				wraps.clear();
				wrapWidth = w;
			}
			lines = wraps.computeIfAbsent(str(row, "id"), k -> wrap(Ui.fontSafe(str(row, "text")), list.getFontMetrics(REGULAR), w));
			var fm = getFontMetrics(REGULAR);
			height = (grouped ? 0 : 6 + fm.getHeight()) + lines.size() * fm.getHeight() + 2;
			return this;
		}

		@Override
		public Dimension getPreferredSize() {
			return new Dimension(wrapWidth + PAD_X * 2, height);
		}

		@Override
		protected void paintComponent(Graphics g0) {
			var g = (Graphics2D) g0;
			g.setColor(mine ? MINE_BG : DARKER_GRAY_COLOR);
			g.fillRect(0, 0, getWidth(), getHeight());
			var fm = g.getFontMetrics(REGULAR);
			int y = 0;
			if (!grouped) {
				y = 6;
				int x = PAD_X;
				// A name in its team's colour; a Supporter keeps their own colour or gradient (their perk) with
				// a dot of their team's colour in front. Only a role (HOST / MOD / TEAM-only) is spelled out.
				var tag = Ui.fontSafe(str(r, "tag"));
				var role = isRole(tag);
				var team = !role && !tag.isEmpty();
				var grad = stops(r);
				var whoColor = str(r, "whoColor");
				var supporter = grad != null || (!whoColor.isEmpty() && !"white".equals(whoColor));
				if (supporter && team) {
					g.setColor(color(str(r, "tagColor"), MUTED));
					g.fillOval(x, y + fm.getAscent() - 7, 6, 6);
					x += 10;
				}
				g.setFont(BOLD);
				var who = Ui.fontSafe(str(r, "who"));
				int nameW = g.getFontMetrics().stringWidth(who);
				if (grad != null) {
					var at = new float[grad.length];
					for (int i = 0; i < grad.length; i++) at[i] = (float) i / (grad.length - 1);
					// A Patron's gradient slides along (reflecting back), like everywhere else, unless the
					// player turned "Animated names" off.
					var shift = moves(r) ? (System.currentTimeMillis() % 6000) / 6000f * 2 * Math.max(1, nameW) : 0;
					g.setPaint(new java.awt.LinearGradientPaint(x - shift, 0, x - shift + Math.max(1, nameW), 0, at, grad,
						java.awt.MultipleGradientPaint.CycleMethod.REFLECT));
				} else g.setColor(supporter || !team ? color(whoColor, Color.WHITE) : color(str(r, "tagColor"), Color.WHITE));
				g.drawString(who, x, y + fm.getAscent());
				x += nameW + 6;
				// A Supporter's star after the name, in their tenure shape and their colour.
				var shape = UiView.glyph(str(r, "whoGlyph"));
				if (shape != null && supporter) {
					new GlyphIcon(shape, 11, grad != null ? grad[0] : color(whoColor, Color.WHITE)).paintIcon(this, g, x - 3, y + fm.getAscent() - 10);
					x += 12;
				}
				g.setFont(SMALL);
				if (role) {
					g.setColor(color(str(r, "tagColor"), MUTED));
					g.drawString(tag, x, y + fm.getAscent());
				}
				var when = Ui.ago(str(r, "at")).replace(" ago", "");
				g.setColor(MUTED);
				g.drawString(when, getWidth() - PAD_X - g.getFontMetrics().stringWidth(when), y + fm.getAscent());
				y += fm.getHeight();
			}
			g.setFont(REGULAR);
			g.setColor(color(str(r, "textColor"), TEXT_COLOR));
			for (String line : lines) {
				g.drawString(line, PAD_X, y + fm.getAscent());
				y += fm.getHeight();
			}
		}
	}

	/**
	 * A moment's name line: the name in a Supporter's gradient (letter by letter; the panel's static
	 * form of it) or in {@code whoColor}, and "· Team" after it in the team's colour. A site that only
	 * sent the team (whoColor "muted") gets the name in the team's colour, as before.
	 */
	static String whoHtml(JsonObject r, String who, String tag, Color whoInk, Color tagInk) {
		var sb = new StringBuilder("<html>");
		whoRuns(r, who, tag, whoInk, tagInk, (text, ink) -> sb.append(font(ink, text)));
		return sb.toString();
	}

	/**
	 * The name line as coloured runs, in order: one per letter for a gradient (sliding like the chat
	 * lines: the same 6s cycle, reflecting back), else the whole name; then "· Team". Shared by
	 * {@link #whoHtml} and {@link WhoLine}, which paints the runs straight onto the row.
	 */
	static void whoRuns(JsonObject r, String who, String tag, Color whoInk, Color tagInk, java.util.function.BiConsumer<String, Color> out) {
		var grad = stops(r);
		var whoColor = str(r, "whoColor");
		var nameInk = !tag.isEmpty() && (whoColor.isEmpty() || "muted".equals(whoColor)) ? tagInk : whoInk;
		var name = Ui.fontSafe(who);
		if (grad != null && name.length() > 1) {
			var phase = moves(r) ? (System.currentTimeMillis() % 6000) / 6000f * 2 : 0;
			for (int i = 0; i < name.length(); i++) {
				var t = reflect((float) i / (name.length() - 1) + phase) * (grad.length - 1);
				int k = Math.min((int) t, grad.length - 2);
				out.accept(String.valueOf(name.charAt(i)), mix(grad[k], grad[k + 1], t - k));
			}
		} else out.accept(name, nameInk);
		if (!tag.isEmpty()) out.accept(" \u00b7 " + Ui.fontSafe(tag), tagInk);
	}

	/**
	 * A moment's name line, painted directly. It was an HTML label, and a sliding gradient changes
	 * its text every 40ms frame, so Swing re-parsed the markup 25 times a second for each visible
	 * moving row; drawing the same letters in the same colours costs only the drawString calls.
	 */
	static final class WhoLine extends JComponent {
		JsonObject r;
		String who = "";
		String tag = "";
		Color whoInk = Color.WHITE;
		Color tagInk = MUTED;

		void show(JsonObject r, String who, String tag, Color whoInk, Color tagInk) {
			this.r = r;
			this.who = who;
			this.tag = tag;
			this.whoInk = whoInk;
			this.tagInk = tagInk;
		}

		@Override
		public Dimension getPreferredSize() {
			var fm = getFontMetrics(SMALL);
			return new Dimension(0, fm.getHeight());
		}

		@Override
		protected void paintComponent(Graphics g0) {
			if (r == null) return;
			var g = (Graphics2D) g0;
			g.setFont(SMALL);
			var fm = g.getFontMetrics();
			int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
			int[] x = {0};
			int max = getWidth();
			whoRuns(r, who, tag, whoInk, tagInk, (text, ink) -> {
				if (x[0] >= max) return;
				g.setColor(ink);
				g.drawString(text, x[0], y);
				x[0] += fm.stringWidth(text);
			});
		}
	}

	static String font(Color c, String text) {
		return String.format("<font color='#%06x'>%s</font>", c.getRGB() & 0xffffff, Ui.esc(text));
	}

	static Color mix(Color a, Color b, float t) {
		return new Color(Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
				Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
				Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
	}

	/** A Supporter's name gradient ({@code grad}: 2+ "#rrggbb"), or null. */
	static Color[] stops(JsonObject r) {
		if (!r.has("grad") || !r.get("grad").isJsonArray() || r.getAsJsonArray("grad").size() < 2) return null;
		List<Color> out = new ArrayList<>();
		for (JsonElement e : r.getAsJsonArray("grad")) {
			var hex = ChatPalette.nameColor(e.isJsonPrimitive() ? e.getAsString() : null);
			if (hex == null) return null;
			out.add(new Color(Integer.parseInt(hex, 16)));
		}
		return out.toArray(new Color[0]);
	}

	/** A position along a reflecting gradient (0→1→0 every 2), as CycleMethod.REFLECT paints it. */
	static float reflect(float p) {
		var m = p % 2f;
		return m > 1f ? 2f - m : m;
	}

	/** A row whose name gradient should be sliding right now. */
	static boolean moves(JsonObject r) {
		return GradientLabel.animate && r.has("move") && r.get("move").getAsBoolean() && stops(r) != null;
	}

	/** HOST / MOD / TEAM: a role worth spelling out. Anything else in a row's tag is its team's name. */
	static boolean isRole(String tag) {
		return "HOST".equals(tag) || "MOD".equals(tag) || "TEAM".equals(tag);
	}

	/** Words into lines no wider than {@code width}; a word longer than a line is broken where it must be. */
	static List<String> wrap(String text, FontMetrics fm, int width) {
		List<String> out = new ArrayList<>();
		var line = new StringBuilder();
		for (String word : text.split(" ")) {
			if (word.isEmpty()) continue;
			var candidate = line.length() == 0 ? word : line + " " + word;
			if (fm.stringWidth(candidate) <= width) {
				line.setLength(0);
				line.append(candidate);
				continue;
			}
			if (line.length() > 0) {
				out.add(line.toString());
				line.setLength(0);
			}
			while (fm.stringWidth(word) > width) {
				int cut = 1;
				while (cut < word.length() && fm.stringWidth(word.substring(0, cut + 1)) <= width) cut++;
				out.add(word.substring(0, cut));
				word = word.substring(cut);
			}
			line.append(word);
		}
		if (line.length() > 0 || out.isEmpty()) out.add(line.toString());
		return out;
	}

	final class RowRenderer extends JPanel implements ListCellRenderer<JsonObject> {
		final JLabel icon = new ClipLabel();
		final JLabel line1 = new ClipLabel();
		final WhoLine line2 = new WhoLine();
		final JLabel value = new ClipLabel();
		final JLabel gz = new ClipLabel();
		final Chips chips = new Chips();

		RowRenderer() {
			super(new BorderLayout(6, 0));
			setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 0, 1, 0, DARK_GRAY_COLOR),
				BorderFactory.createEmptyBorder(4, 6, 4, 6)));
			icon.setVerticalAlignment(SwingConstants.TOP);
			icon.setPreferredSize(new Dimension(ICON_W, ICON_H));
			line1.setFont(REGULAR);
			value.setFont(SMALL);
			value.setForeground(GOLD);
			value.setHorizontalAlignment(SwingConstants.RIGHT);
			gz.setFont(SMALL);
			gz.setHorizontalAlignment(SwingConstants.RIGHT);
			var middle = new JPanel(new BorderLayout());
			middle.setOpaque(false);
			middle.add(line1, BorderLayout.NORTH);
			middle.add(line2, BorderLayout.SOUTH);
			var right = new JPanel(new BorderLayout());
			right.setOpaque(false);
			right.add(value, BorderLayout.NORTH);
			// The second line: reaction pills when the row has them, else the plain gz/time text.
			var bottom = new JPanel(new BorderLayout());
			bottom.setOpaque(false);
			bottom.add(gz, BorderLayout.CENTER);
			bottom.add(chips, BorderLayout.EAST);
			right.add(bottom, BorderLayout.SOUTH);
			add(icon, BorderLayout.WEST);
			add(middle, BorderLayout.CENTER);
			add(right, BorderLayout.EAST);
		}

		final javax.swing.border.Border feedBorder = BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, DARK_GRAY_COLOR),
			BorderFactory.createEmptyBorder(4, 6, 4, 6));

		boolean mine(JsonObject r) {
			return (r.has("mine") && r.get("mine").getAsBoolean()) || (me != null && !me.isEmpty() && Rsn.same(me, str(r, "who")))
					|| (!mineNames.isEmpty() && mineNames.contains(ChatFlair.normalize(str(r, "who"))));
		}

		@Override
		public Component getListCellRendererComponent(JList<? extends JsonObject> l, JsonObject r, int index, boolean selected, boolean focus) {
			if (chat) return chatCell.show(r, index, l.getWidth(), mine(r));
			setBorder(feedBorder);
			line1.setVisible(true);
			line2.setFont(SMALL);
			value.setVisible(true);
			setBackground(mine(r) ? MINE_BG : DARKER_GRAY_COLOR);
			var tint = color(str(r, "tint"), null);
			var ic = iconFor(r);
			if (tint != null) ic = new Tinted(ic != null ? ic : new BlankIcon(), tint);
			icon.setIcon(ic);
			icon.setVisible(ic != null);

			var who = str(r, "who");
			var whoInk = color(str(r, "whoColor"), Color.WHITE);
			var tag = str(r, "tag");
			var tagInk = color(str(r, "tagColor"), MUTED);
			var when = Ui.ago(str(r, "at")).replace(" ago", "");
			var title = str(r, "title");
			if (!title.isEmpty()) {
				// A moment: the item on top, who got it (and their team) under it.
				line1.setText(plainText(title));
				line1.setForeground(color(str(r, "textColor"), Color.WHITE));
				// Who got it, in the colours the site picked (a Supporter's own, else their team's), then
				// "· Team" in the team's colour on a board.
				line2.show(r, who, tag, whoInk, tagInk);
				line2.setVisible(true);
			} else {
				// A roll-up line ("+214 smaller drops…"): one plain line.
				line1.setText(plainText(str(r, "text")));
				line1.setForeground(color(str(r, "textColor"), MUTED));
				line2.setVisible(false);
			}
			value.setText(plainText(r.has("value") ? str(r, "value") : when));
			value.setForeground(r.has("value") ? GOLD : MUTED);
			var rowChips = chipsOf(r);
			if (rowChips != null) {
				// Reactions as pills (GZ / Sad / Sit), so they read as buttons rather than labels.
				chips.chips = rowChips;
				chips.hover = index == hoverRow ? hoverChip : -1;
				chips.setVisible(true);
				gz.setVisible(false);
			} else {
				chips.chips = null;
				chips.setVisible(false);
				gz.setText(r.has("value") ? plainText(when) : "");
				gz.setForeground(MUTED);
				gz.setVisible(r.has("value"));
			}
			return this;
		}
	}

	/** The row and pill under the mouse, lit by the renderer (-1: none). EDT. */
	int hoverRow = -1;
	int hoverChip = -1;

	void setHover(int row, int chip) {
		if (row == hoverRow && chip == hoverChip) return;
		int was = hoverRow;
		hoverRow = row;
		hoverChip = chip;
		list.setCursor(Cursor.getPredefinedCursor(row >= 0 ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
		for (int i : new int[]{was, row}) {
			var b = i < 0 || i >= model.getSize() ? null : list.getCellBounds(i, i);
			if (b != null) list.repaint(b);
		}
	}

	/** Inside a reaction pill: the text's side padding; and the space between two pills. */
	static final int CHIP_PAD = 6;
	static final int CHIP_SPACE = 3;
	/** Soft fills, no outline: quiet when idle, a little lighter under the mouse, tinted when yours. */
	static final Color CHIP_FILL = new Color(0x303030);
	static final Color CHIP_FILL_HOVER = new Color(0x404040);
	static final Color CHIP_MINE = new Color(255, 152, 31, 52);
	static final Color CHIP_MINE_HOVER = new Color(255, 152, 31, 84);
	static final Color CHIP_INK = new Color(0xb4b4b4);

	/** "Sit 3", or just "Sit" before anyone has. */
	static String chipText(JsonObject c) {
		int n = c.has("n") ? c.get("n").getAsInt() : 0;
		var label = str(c, "label");
		return n > 0 ? label + " " + n : label;
	}

	/** A row's reaction chips in drawing order: a death's {@code reacts}, else its one {@code gz}. */
	static JsonArray chipsOf(JsonObject r) {
		if (r.has("reacts") && r.get("reacts").isJsonArray()) return r.getAsJsonArray("reacts");
		if (r.has("gz") && r.get("gz").isJsonObject()) {
			var g = r.getAsJsonObject("gz");
			if (!g.has("label")) g.addProperty("label", "GZ");
			var one = new JsonArray();
			one.add(g);
			return one;
		}
		return null;
	}

	static int chipWidth(FontMetrics fm, JsonObject c) {
		return fm.stringWidth(chipText(c)) + 2 * CHIP_PAD;
	}

	/**
	 * The reaction chip {@code fromRight} px left of the row's right edge (inside its 6px inset), or
	 * null. The pills' own geometry ({@link Chips}), so a click or hover lands on exactly what is drawn.
	 */
	static JsonObject chipAt(JsonObject r, FontMetrics fm, int fromRight) {
		var c = chipsOf(r);
		int at = c == null ? -1 : reactAt(c, fm, fromRight);
		return at < 0 ? null : c.get(at).getAsJsonObject();
	}

	/** Which pill {@code fromRight} px left of the row's right edge falls on (2px of slack), or -1. */
	static int reactAt(JsonArray reacts, FontMetrics fm, int fromRight) {
		int edge = 0;
		for (int k = reacts.size() - 1; k >= 0; k--) {
			int w = chipWidth(fm, reacts.get(k).getAsJsonObject());
			// The rightmost pill also takes the row's 6px inset beside it, as the plain chips did.
			if (fromRight >= (edge == 0 ? -6 : edge - 2) && fromRight <= edge + w + 2) return k;
			edge += w + CHIP_SPACE;
		}
		return -1;
	}

	/** A row's reactions as small rounded buttons: outlined, filled when they're yours, lit on hover. */
	static final class Chips extends JComponent {
		JsonArray chips;
		int hover = -1;

		Chips() {
			setFont(SMALL);
			setOpaque(false);
		}

		@Override
		public Dimension getPreferredSize() {
			var fm = getFontMetrics(getFont());
			int w = 0;
			for (int k = 0; chips != null && k < chips.size(); k++) w += (k > 0 ? CHIP_SPACE : 0) + chipWidth(fm, chips.get(k).getAsJsonObject());
			return new Dimension(w, fm.getHeight() + 2);
		}

		@Override
		protected void paintComponent(Graphics g) {
			if (chips == null) return;
			var g2 = (Graphics2D) g.create();
			try {
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setFont(getFont());
				var fm = g2.getFontMetrics();
				int h = getHeight() - 1;
				int x = getWidth();
				for (int k = chips.size() - 1; k >= 0; k--) {
					var c = chips.get(k).getAsJsonObject();
					int w = chipWidth(fm, c);
					x -= w;
					var mine = c.has("mine") && c.get("mine").getAsBoolean();
					var live = c.has("on") && c.get("on").isJsonObject();
					var lit = live && k == hover;
					g2.setColor(mine ? (lit ? CHIP_MINE_HOVER : CHIP_MINE) : live ? (lit ? CHIP_FILL_HOVER : CHIP_FILL) : CHIP_FILL);
					g2.fillRoundRect(x, 0, w, h + 1, 7, 7);
					g2.setColor(mine ? BRAND_ORANGE : lit ? Color.WHITE : live ? CHIP_INK : MUTED);
					g2.drawString(chipText(c), x + CHIP_PAD, (h - fm.getHeight()) / 2 + fm.getAscent() + 1);
					x -= CHIP_SPACE;
				}
			} finally {
				g2.dispose();
			}
		}
	}

	/** A line joins the one before it (no name or time of its own) only from the same person within this. */
	static final long RUN_GAP_MS = 5 * 60_000L;

	/** Same person, same team tag, and close in time: a run shows its name and time once. */
	static boolean sameRun(JsonObject prev, JsonObject row) {
		if (!str(row, "who").equals(str(prev, "who")) || !str(row, "tag").equals(str(prev, "tag"))) return false;
		var a = millis(str(prev, "at"));
		var b = millis(str(row, "at"));
		return a > 0 && b > 0 && Math.abs(a - b) <= RUN_GAP_MS;
	}

	static long millis(String at) {
		if (at == null || at.isEmpty()) return 0;
		var iso = at.trim().replace(' ', 'T');
		if (!iso.endsWith("Z") && !Ui.HAS_OFFSET.matcher(iso).matches()) iso = iso + "Z";
		try {
			return java.time.Instant.parse(iso).toEpochMilli();
		} catch (RuntimeException e) {
			return 0;
		}
	}
}
