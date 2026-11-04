package com.anvil;

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
import java.awt.Point;
import java.awt.Rectangle;
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
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;

/**
 * The server-driven UI's live list (node {@code feed}, capability 'panel-rows'): drawn once from the
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
final class FeedList extends JPanel
{
	/** "...+02:00" at the end of a timestamp. Compiled once: it runs for every row on each paint. */
	private static final java.util.regex.Pattern HAS_OFFSET = java.util.regex.Pattern.compile(".*[+-]\\d\\d:\\d\\d$");

	static final int FLUSH_MS = 400;
	private static final int ICON_W = 24;
	private static final int ICON_H = 21;
	private static final Color GOLD = new Color(0xFF_D3_5C);
	private static final Color MINE_BG = new Color(0x2E_2A_22);

	final String id;
	private final UiView.Host host;
	private final ItemManager items;
	private final Consumer<JsonObject> act;
	private final int cap;
	private final RowModel model = new RowModel();
	private final JList<JsonObject> list = new JList<JsonObject>(model)
	{
		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		/** A row's whole text, for when the width cut it short. */
		@Override
		public String getToolTipText(MouseEvent e)
		{
			int i = locationToIndex(e.getPoint());
			Rectangle b = i < 0 ? null : getCellBounds(i, i);
			if (b == null || !b.contains(e.getPoint()))
			{
				return null;
			}
			JsonObject r = model.getElementAt(i);
			String title = UiView.str(r, "title");
			String line = title.isEmpty() ? (UiView.str(r, "who") + " " + UiView.str(r, "text")).trim()
				: title + " — " + UiView.str(r, "who") + (UiView.str(r, "tag").isEmpty() ? "" : " · " + UiView.str(r, "tag"));
			String value = UiView.str(r, "value");
			return line.isEmpty() ? null : Ui.wrapHtml(esc(Ui.fontSafe(line + (value.isEmpty() ? "" : " · " + value))), 200);
		}
	};
	private final JScrollPane scroll;
	private final JLabel empty;
	private final JLabel newPill = new ClipLabel("", SwingConstants.CENTER);
	private final JLabel error = new ClipLabel();
	private final JLabel lockLine = new ClipLabel();
	private JTextField input;
	private JsonObject send;
	/** A chat (it has a message box): oldest at the top, newest at the bottom, pinned to the latest. */
	private final boolean chat;
	/** Pinned to the newest line: true until the reader scrolls away from it. */
	private boolean following = true;
	/** Whose lines are the viewer's own, re-read at each flush. */
	private String me;
	/** Arrived since the last flush, oldest first. */
	private final List<JsonObject> pending = new ArrayList<>();
	private final Set<String> pendingDel = new HashSet<>();
	private final Timer flush = new Timer(FLUSH_MS, e -> flush());
	/** Repaints only the visible rows with a sliding name, ~25 times a second, and only while there are any. */
	private final Timer slide = new Timer(40, e -> slideVisible());
	private int unseen;
	private final Map<Integer, Icon> icons = new HashMap<>();

	FeedList(JsonObject n, UiView.Host host, ItemManager items, Consumer<JsonObject> act)
	{
		super(new BorderLayout(0, 4));
		this.id = UiView.str(n, "id");
		this.host = host;
		this.items = items;
		this.act = act;
		this.cap = n.has("cap") ? Math.max(20, n.get("cap").getAsInt()) : 200;
		int h = n.has("h") ? Math.max(120, n.get("h").getAsInt()) : 420;
		flush.setRepeats(false);
		setOpaque(false);
		chat = n.has("send") && n.get("send").isJsonObject();
		me = host == null ? null : host.myName();

		List<JsonObject> first = new ArrayList<>();
		if (n.has("rows") && n.get("rows").isJsonArray())
		{
			for (JsonElement e : n.getAsJsonArray("rows"))
			{
				if (e.isJsonObject())
				{
					first.add(e.getAsJsonObject());
				}
			}
		}
		List<JsonObject> kept = new ArrayList<>(first.size() > cap ? first.subList(0, cap) : first);
		if (chat)
		{
			// The site sends newest first; a chat reads top to bottom.
			Collections.reverse(kept);
		}
		model.reset(kept);

		RowRenderer renderer = new RowRenderer();
		list.setCellRenderer(renderer);
		if (!chat)
		{
			// Every feed row is the same shape: measure one and never measure again.
			JsonObject probe = new JsonObject();
			probe.addProperty("id", "probe");
			probe.addProperty("title", "Probe");
			probe.addProperty("who", "Probe");
			probe.addProperty("value", "1m");
			probe.addProperty("glyph", "check");
			list.setFixedCellHeight(renderer.getListCellRendererComponent(list, probe, 0, false, false).getPreferredSize().height);
		}
		list.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setFocusable(false);
		javax.swing.ToolTipManager.sharedInstance().registerComponent(list);
		list.addMouseListener(new Clicks());
		// Chat rows wrap to the width: a new width means new heights, so drop JList's cached ones.
		list.addComponentListener(new ComponentAdapter()
		{
			private int lastW = -1;

			@Override
			public void componentResized(ComponentEvent e)
			{
				if (list.getWidth() != lastW)
				{
					lastW = list.getWidth();
					list.setFixedCellHeight(10);
					list.setFixedCellHeight(-1);
				}
				if (chat && following)
				{
					toLatest();
				}
			}
		});

		scroll = new JScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.getViewport().addChangeListener(e -> {
			boolean atLatest = atLatest();
			if (chat)
			{
				following = atLatest;
			}
			if (unseen > 0 && atLatest)
			{
				unseen = 0;
				newPill.setVisible(false);
			}
		});
		SlimScrollBarUI.applyTo(scroll);
		scroll.setPreferredSize(new Dimension(100, h));
		scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));

		newPill.setFont(SMALL);
		newPill.setOpaque(true);
		newPill.setBackground(ColorScheme.BRAND_ORANGE);
		newPill.setForeground(Color.BLACK);
		newPill.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
		newPill.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		newPill.setVisible(false);
		newPill.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				toLatest();
			}
		});

		// Wrapped, not cut: the empty line is the only thing in the list.
		empty = new ClipLabel(Ui.wrapHtml(esc(Ui.fontSafe(UiView.str(n, "empty"))), 170));
		empty.setFont(SMALL);
		empty.setForeground(MUTED);

		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		if (!chat)
		{
			top.add(newPill, BorderLayout.NORTH);
		}
		top.add(empty, BorderLayout.SOUTH);
		add(top, BorderLayout.NORTH);
		add(scroll, BorderLayout.CENTER);

		if (chat)
		{
			send = n.getAsJsonObject("send");
			JPanel bottom = new JPanel(new BorderLayout(0, 4));
			bottom.setOpaque(false);
			bottom.add(newPill, BorderLayout.NORTH);
			bottom.add(sendBox(), BorderLayout.CENTER);
			add(bottom, BorderLayout.SOUTH);
			lock(n.has("locked") && !n.get("locked").isJsonNull() ? UiView.str(n, "locked") : null);
			javax.swing.SwingUtilities.invokeLater(this::toLatest);
		}
		syncEmpty();
		setMaximumSize(new Dimension(Integer.MAX_VALUE, getPreferredSize().height));
	}

	// ---- Push --------------------------------------------------------------------------------------

	/** Rows for this list, newest first as sent. Added on the next flush. */
	void add(JsonArray rows)
	{
		for (int i = rows.size() - 1; i >= 0; i--)
		{
			if (rows.get(i).isJsonObject())
			{
				pending.add(rows.get(i).getAsJsonObject());
			}
		}
		schedule();
	}

	void remove(JsonArray ids)
	{
		for (JsonElement e : ids)
		{
			pendingDel.add(e.getAsString());
		}
		schedule();
	}

	/** Grey the message box out with this line, or reopen it (null). Only a list with a box has one. */
	void lock(String why)
	{
		if (input == null)
		{
			return;
		}
		boolean locked = why != null && !why.isEmpty();
		input.setEnabled(!locked);
		lockLine.setText(locked ? plainText(why) : "");
		lockLine.setVisible(locked);
		revalidate();
	}

	boolean hasBox()
	{
		return input != null;
	}

	void stop()
	{
		flush.stop();
		slide.stop();
	}

	@Override
	public void addNotify()
	{
		super.addNotify();
		if (chat)
		{
			slide.start();
		}
	}

	@Override
	public void removeNotify()
	{
		super.removeNotify();
		slide.stop();
	}

	private void slideVisible()
	{
		if (!list.isShowing() || !GradientLabel.animate)
		{
			return;
		}
		int first = list.getFirstVisibleIndex();
		int last = list.getLastVisibleIndex();
		for (int i = Math.max(0, first); i >= 0 && i <= last && i < model.getSize(); i++)
		{
			if (moves(model.getElementAt(i)))
			{
				Rectangle b = list.getCellBounds(i, i);
				if (b != null)
				{
					list.repaint(b);
				}
			}
		}
	}

	// For tests: what the list holds, newest first, and its box.
	int rowCount()
	{
		return model.getSize();
	}

	String rowId(int i)
	{
		return UiView.str(model.getElementAt(i), "id");
	}

	JTextField box()
	{
		return input;
	}

	String errorLine()
	{
		return error.isVisible() ? error.getText() : null;
	}

	private void schedule()
	{
		if (!flush.isRunning())
		{
			flush.start();
		}
	}

	/** Everything that arrived since the last flush, in one model change. */
	void flush()
	{
		if (!pendingDel.isEmpty())
		{
			model.removeIds(pendingDel);
			pending.removeIf(r -> pendingDel.contains(UiView.str(r, "id")));
			pendingDel.clear();
		}
		if (pending.isEmpty())
		{
			syncEmpty();
			return;
		}
		List<JsonObject> fresh = new ArrayList<>();
		for (int i = pending.size() - 1; i >= 0; i--)
		{
			JsonObject r = pending.get(i);
			if (!model.has(UiView.str(r, "id")))
			{
				fresh.add(r);
			}
		}
		pending.clear();
		if (fresh.isEmpty())
		{
			return;
		}
		me = host == null ? null : host.myName();
		if (chat)
		{
			// Oldest first at the bottom; follow it down unless the reader has scrolled up.
			boolean stick = following;
			Collections.reverse(fresh);
			model.append(fresh, cap);
			if (stick)
			{
				javax.swing.SwingUtilities.invokeLater(this::toLatest);
			}
			else
			{
				unseen += fresh.size();
				newPill.setText("↓ " + unseen + " new");
				newPill.setVisible(true);
			}
			syncEmpty();
			return;
		}
		int y = scroll.getViewport().getViewPosition().y;
		model.prepend(fresh, cap);
		if (y > 2)
		{
			// Reading further down: keep the same rows under their eyes and say what's above.
			Rectangle added = list.getCellBounds(0, fresh.size() - 1);
			scroll.getViewport().setViewPosition(new Point(0, y + (added == null ? 0 : added.height)));
			unseen += fresh.size();
			newPill.setText("↑ " + unseen + " new");
			newPill.setVisible(true);
		}
		syncEmpty();
	}

	/** At the newest row: the bottom for a chat, the top for a feed. */
	private boolean atLatest()
	{
		javax.swing.JViewport vp = scroll.getViewport();
		if (!chat)
		{
			return vp.getViewPosition().y <= 2;
		}
		return vp.getViewPosition().y + vp.getExtentSize().height >= list.getHeight() - 4;
	}

	private void toLatest()
	{
		following = true;
		unseen = 0;
		newPill.setVisible(false);
		if (chat)
		{
			int last = model.getSize() - 1;
			if (last >= 0)
			{
				list.ensureIndexIsVisible(last);
				Rectangle b = list.getCellBounds(last, last);
				if (b != null)
				{
					list.scrollRectToVisible(b);
				}
			}
		}
		else
		{
			scroll.getViewport().setViewPosition(new Point(0, 0));
		}
	}

	private void syncEmpty()
	{
		boolean none = model.getSize() == 0;
		if (empty.isVisible() != (none && !empty.getText().isEmpty()))
		{
			empty.setVisible(none && !empty.getText().isEmpty());
			revalidate();
		}
	}

	// ---- The message box ---------------------------------------------------------------------------

	private JPanel sendBox()
	{
		JPanel box = new JPanel(new BorderLayout(0, 2));
		box.setOpaque(false);
		input = new JTextField();
		styleSearch(input);
		input.setToolTipText(plainText(UiView.str(send, "hint")));
		int max = send.has("max") ? send.get("max").getAsInt() : 200;
		((AbstractDocument) input.getDocument()).setDocumentFilter(new MaxLength(max));
		input.addActionListener(e -> sendNow());
		// Escape hands the keyboard back to the game without sending.
		input.getInputMap().put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "anvil-game");
		input.getActionMap().put("anvil-game", new javax.swing.AbstractAction()
		{
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e)
			{
				host.gameFocus();
			}
		});
		JLabel hint = new ClipLabel(plainText(UiView.str(send, "hint") + " — Enter to send"));
		hint.setFont(SMALL);
		hint.setForeground(MUTED);
		error.setFont(SMALL);
		error.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		error.setVisible(false);
		lockLine.setFont(SMALL);
		lockLine.setForeground(MUTED);
		lockLine.setVisible(false);
		JPanel lines = new JPanel(new BorderLayout());
		lines.setOpaque(false);
		lines.add(hint, BorderLayout.NORTH);
		lines.add(error, BorderLayout.CENTER);
		lines.add(lockLine, BorderLayout.SOUTH);
		box.add(input, BorderLayout.NORTH);
		box.add(lines, BorderLayout.SOUTH);
		return box;
	}

	/** A send on its way: Enter again does nothing (the box is never disabled — that moves focus). */
	private boolean sending;

	/**
	 * Post the line. Sent, the keyboard goes straight back to the game, so Key Remapping, WASD and
	 * Enter-to-chat work as before; refused, it stays in the box so the line can be fixed.
	 */
	private void sendNow()
	{
		String text = input.getText().trim();
		if (text.isEmpty() || !input.isEnabled() || sending)
		{
			return;
		}
		sending = true;
		Map<String, String> fields = new HashMap<>();
		fields.put("msg", text);
		host.post(UiView.str(send, "act"), UiView.strings(send.get("args")), fields, v -> {
			sending = false;
			String err = errorOf(v);
			showError(err);
			if (err == null)
			{
				input.setText("");
				host.gameFocus();
			}
		});
	}

	private void showError(String err)
	{
		error.setText(err == null ? "" : plainText(err));
		error.setVisible(err != null);
		revalidate();
	}

	/** An action's answer: null when it went through, else the line to show. */
	static String errorOf(JsonObject v)
	{
		if (v == null || !v.has("ui") || !v.get("ui").isJsonObject())
		{
			return "Couldn't reach Anvil — try again in a moment.";
		}
		JsonObject ui = v.getAsJsonObject("ui");
		return "text".equals(UiView.str(ui, "t")) && "error".equals(UiView.str(ui, "color")) ? UiView.str(ui, "s") : null;
	}

	private static final class MaxLength extends DocumentFilter
	{
		private final int max;

		MaxLength(int max)
		{
			this.max = max;
		}

		@Override
		public void insertString(FilterBypass fb, int offset, String s, AttributeSet a) throws BadLocationException
		{
			replace(fb, offset, 0, s, a);
		}

		@Override
		public void replace(FilterBypass fb, int offset, int length, String s, AttributeSet a) throws BadLocationException
		{
			String add = s == null ? "" : s;
			int room = max - (fb.getDocument().getLength() - length);
			if (room <= 0)
			{
				return;
			}
			super.replace(fb, offset, length, add.length() > room ? add.substring(0, room) : add, a);
		}
	}

	// ---- Clicks ------------------------------------------------------------------------------------

	private final class Clicks extends MouseAdapter
	{
		@Override
		public void mousePressed(MouseEvent e)
		{
			popup(e);
		}

		@Override
		public void mouseReleased(MouseEvent e)
		{
			if (!popup(e) && javax.swing.SwingUtilities.isLeftMouseButton(e))
			{
				click(e);
			}
		}

		private int rowAt(MouseEvent e)
		{
			int i = list.locationToIndex(e.getPoint());
			Rectangle b = i < 0 ? null : list.getCellBounds(i, i);
			return b != null && b.contains(e.getPoint()) ? i : -1;
		}

		private boolean popup(MouseEvent e)
		{
			if (!e.isPopupTrigger())
			{
				return false;
			}
			int i = rowAt(e);
			JsonObject r = i < 0 ? null : model.getElementAt(i);
			if (r == null || !r.has("menu") || !r.get("menu").isJsonArray())
			{
				return true;
			}
			JPopupMenu menu = new JPopupMenu();
			for (JsonElement el : r.getAsJsonArray("menu"))
			{
				JsonObject item = el.getAsJsonObject();
				JMenuItem mi = new JMenuItem(plainText(UiView.str(item, "label")));
				JsonObject on = item.has("on") && item.get("on").isJsonObject() ? item.getAsJsonObject("on") : null;
				if (on == null)
				{
					mi.setEnabled(false);
				}
				else
				{
					mi.addActionListener(ev -> quietly(on));
				}
				menu.add(mi);
			}
			menu.show(list, e.getX(), e.getY());
			return true;
		}

		private void click(MouseEvent e)
		{
			int i = rowAt(e);
			if (i < 0)
			{
				return;
			}
			JsonObject r = model.getElementAt(i);
			Rectangle b = list.getCellBounds(i, i);
			JsonObject chip = null;
			if (r.has("reacts") && r.get("reacts").isJsonArray())
			{
				JsonArray reacts = r.getAsJsonArray("reacts");
				int at = reactAt(reacts, list.getFontMetrics(SMALL), b.x + b.width - 6 - e.getX());
				chip = at < 0 ? null : reacts.get(at).getAsJsonObject();
			}
			else if (r.has("gz") && r.get("gz").isJsonObject() && e.getX() > b.x + b.width - 44)
			{
				chip = r.getAsJsonObject("gz");
			}
			if (chip != null)
			{
				boolean mine = chip.has("mine") && chip.get("mine").getAsBoolean();
				int count = chip.has("n") ? chip.get("n").getAsInt() : 0;
				chip.addProperty("mine", !mine);
				chip.addProperty("n", Math.max(0, count + (mine ? -1 : 1)));
				model.changed(i);
				if (chip.has("on") && chip.get("on").isJsonObject())
				{
					quietly(chip.getAsJsonObject("on"));
				}
				return;
			}
			if (r.has("on") && r.get("on").isJsonObject())
			{
				act.accept(r.getAsJsonObject("on"));
			}
		}
	}

	/** A post that must not redraw the view: only an error line comes back. */
	private void quietly(JsonObject on)
	{
		if (!"post".equals(UiView.str(on, "a")))
		{
			act.accept(on);
			return;
		}
		host.post(UiView.str(on, "act"), UiView.strings(on.get("args")), Collections.emptyMap(), v -> showError(errorOf(v)));
	}

	// ---- The model: one list, changed in batches -------------------------------------------------

	private static final class RowModel extends AbstractListModel<JsonObject>
	{
		private final List<JsonObject> rows = new ArrayList<>();
		private final Set<String> ids = new HashSet<>();

		@Override
		public int getSize()
		{
			return rows.size();
		}

		@Override
		public JsonObject getElementAt(int index)
		{
			return rows.get(index);
		}

		boolean has(String id)
		{
			return ids.contains(id);
		}

		void reset(List<JsonObject> next)
		{
			int was = rows.size();
			rows.clear();
			ids.clear();
			if (was > 0)
			{
				fireIntervalRemoved(this, 0, was - 1);
			}
			for (JsonObject r : next)
			{
				if (ids.add(UiView.str(r, "id")))
				{
					rows.add(r);
				}
			}
			if (!rows.isEmpty())
			{
				fireIntervalAdded(this, 0, rows.size() - 1);
			}
		}

		/** A chat's new lines at the bottom; the oldest past {@code cap} go from the top. */
		void append(List<JsonObject> fresh, int cap)
		{
			int at = rows.size();
			for (JsonObject r : fresh)
			{
				ids.add(UiView.str(r, "id"));
			}
			rows.addAll(fresh);
			fireIntervalAdded(this, at, rows.size() - 1);
			if (rows.size() > cap)
			{
				int gone = rows.size() - cap;
				for (JsonObject r : rows.subList(0, gone))
				{
					ids.remove(UiView.str(r, "id"));
				}
				rows.subList(0, gone).clear();
				fireIntervalRemoved(this, 0, gone - 1);
			}
		}

		/** Newest first; the oldest past {@code cap} go. Two events however many rows. */
		void prepend(List<JsonObject> fresh, int cap)
		{
			for (JsonObject r : fresh)
			{
				ids.add(UiView.str(r, "id"));
			}
			rows.addAll(0, fresh);
			fireIntervalAdded(this, 0, fresh.size() - 1);
			if (rows.size() > cap)
			{
				int from = cap;
				int to = rows.size() - 1;
				for (JsonObject r : rows.subList(from, rows.size()))
				{
					ids.remove(UiView.str(r, "id"));
				}
				rows.subList(from, rows.size()).clear();
				fireIntervalRemoved(this, from, to);
			}
		}

		void removeIds(Set<String> gone)
		{
			for (int i = rows.size() - 1; i >= 0; i--)
			{
				String id = UiView.str(rows.get(i), "id");
				if (gone.contains(id))
				{
					rows.remove(i);
					ids.remove(id);
					fireIntervalRemoved(this, i, i);
				}
			}
		}

		void changed(int i)
		{
			fireContentsChanged(this, i, i);
		}
	}

	// ---- One renderer, reused for every row --------------------------------------------------------

	private Icon iconFor(JsonObject r)
	{
		if (r.has("item") && items != null)
		{
			int item = r.get("item").getAsInt();
			Icon cached = icons.get(item);
			if (cached != null)
			{
				return cached;
			}
			icons.put(item, new BlankIcon());
			AsyncBufferedImage img = items.getImage(item);
			img.onLoaded(() -> javax.swing.SwingUtilities.invokeLater(() -> {
				icons.put(item, new ImageIcon(ImageUtil.resizeImage(img, ICON_W, ICON_H, true)));
				list.repaint();
			}));
			return icons.get(item);
		}
		if (r.has("glyph"))
		{
			String g = UiView.str(r, "glyph");
			GlyphIcon.Shape shape = "star".equals(g) ? GlyphIcon.Shape.STAR : "chevron-right".equals(g) ? GlyphIcon.Shape.CHEVRON_RIGHT : GlyphIcon.Shape.CHECK;
			return new GlyphIcon(shape, 12, UiView.color(UiView.str(r, "glyphColor"), MUTED));
		}
		return null;
	}

	private static final class BlankIcon implements Icon
	{
		@Override
		public void paintIcon(Component c, java.awt.Graphics g, int x, int y)
		{
		}

		@Override
		public int getIconWidth()
		{
			return ICON_W;
		}

		@Override
		public int getIconHeight()
		{
			return ICON_H;
		}
	}

	private final ChatCell chatCell = new ChatCell();

	/**
	 * One chat line, painted straight onto the list: the name (and team, or HOST / MOD / TEAM) with
	 * the time, the message wrapped under it, and a run of lines from one person showing the name once.
	 *
	 * No HTML: a Swing HTML label parses its markup every time a row is measured or painted, and a list
	 * re-measures every row whenever one is added — 300 HTML rows cost ~100ms a flush on a fast PC. Here
	 * the wrapping is done once per row and width with plain font metrics and kept, so a flush is cheap
	 * however long the chat is, and nothing a player typed can ever become markup.
	 */
	private final class ChatCell extends javax.swing.JComponent
	{
		private static final int PAD_X = 8;
		private static final int TIME_W = 34;
		private final Map<String, List<String>> wraps = new HashMap<>();
		private int wrapWidth = -1;
		private JsonObject r;
		private boolean grouped;
		private boolean mine;
		private List<String> lines = Collections.emptyList();
		private int height;

		ChatCell()
		{
			setOpaque(true);
		}

		ChatCell show(JsonObject row, int index, int width, boolean mine)
		{
			this.r = row;
			this.mine = mine;
			JsonObject prev = index > 0 ? model.getElementAt(index - 1) : null;
			grouped = prev != null && sameRun(prev, row);
			// A few px spare: lines are measured off-screen and painted on it, and the two can round apart.
			int w = Math.max(80, width - PAD_X * 2 - 6);
			if (w != wrapWidth)
			{
				wraps.clear();
				wrapWidth = w;
			}
			lines = wraps.computeIfAbsent(UiView.str(row, "id"), k -> wrap(Ui.fontSafe(UiView.str(row, "text")), list.getFontMetrics(REGULAR), w));
			java.awt.FontMetrics fm = getFontMetrics(REGULAR);
			height = (grouped ? 0 : 6 + fm.getHeight()) + lines.size() * fm.getHeight() + 2;
			return this;
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(wrapWidth + PAD_X * 2, height);
		}

		@Override
		protected void paintComponent(java.awt.Graphics g0)
		{
			java.awt.Graphics2D g = (java.awt.Graphics2D) g0;
			g.setColor(mine ? MINE_BG : ColorScheme.DARKER_GRAY_COLOR);
			g.fillRect(0, 0, getWidth(), getHeight());
			java.awt.FontMetrics fm = g.getFontMetrics(REGULAR);
			int y = 0;
			if (!grouped)
			{
				y = 6;
				int x = PAD_X;
				// A name in its team's colour; a Supporter keeps their own colour or gradient (their perk) with
				// a dot of their team's colour in front. Only a role (HOST / MOD / TEAM-only) is spelled out.
				String tag = Ui.fontSafe(UiView.str(r, "tag"));
				boolean role = isRole(tag);
				boolean team = !role && !tag.isEmpty();
				Color[] grad = stops(r);
				String whoColor = UiView.str(r, "whoColor");
				boolean supporter = grad != null || (!whoColor.isEmpty() && !"white".equals(whoColor));
				if (supporter && team)
				{
					g.setColor(UiView.color(UiView.str(r, "tagColor"), MUTED));
					g.fillOval(x, y + fm.getAscent() - 7, 6, 6);
					x += 10;
				}
				g.setFont(BOLD);
				String who = Ui.fontSafe(UiView.str(r, "who"));
				int nameW = g.getFontMetrics().stringWidth(who);
				if (grad != null)
				{
					float[] at = new float[grad.length];
					for (int i = 0; i < grad.length; i++)
					{
						at[i] = (float) i / (grad.length - 1);
					}
					// A Patron's gradient slides along (reflecting back), like everywhere else, unless the
					// player turned "Animated names" off.
					float shift = moves(r) ? (System.currentTimeMillis() % 6000) / 6000f * 2 * Math.max(1, nameW) : 0;
					g.setPaint(new java.awt.LinearGradientPaint(x - shift, 0, x - shift + Math.max(1, nameW), 0, at, grad,
						java.awt.MultipleGradientPaint.CycleMethod.REFLECT));
				}
				else
				{
					g.setColor(supporter || !team ? UiView.color(whoColor, Color.WHITE) : UiView.color(UiView.str(r, "tagColor"), Color.WHITE));
				}
				g.drawString(who, x, y + fm.getAscent());
				x += nameW + 6;
				g.setFont(SMALL);
				if (role)
				{
					g.setColor(UiView.color(UiView.str(r, "tagColor"), MUTED));
					g.drawString(tag, x, y + fm.getAscent());
				}
				String when = Ui.ago(UiView.str(r, "at")).replace(" ago", "");
				g.setColor(MUTED);
				g.drawString(when, getWidth() - PAD_X - g.getFontMetrics().stringWidth(when), y + fm.getAscent());
				y += fm.getHeight();
			}
			g.setFont(REGULAR);
			g.setColor(UiView.color(UiView.str(r, "textColor"), ColorScheme.TEXT_COLOR));
			for (String line : lines)
			{
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
	static String whoHtml(JsonObject r, String who, String tag, Color whoInk, Color tagInk)
	{
		Color[] grad = stops(r);
		String whoColor = UiView.str(r, "whoColor");
		Color nameInk = !tag.isEmpty() && (whoColor.isEmpty() || "muted".equals(whoColor)) ? tagInk : whoInk;
		String name = Ui.fontSafe(who);
		StringBuilder sb = new StringBuilder("<html>");
		if (grad != null && name.length() > 1)
		{
			for (int i = 0; i < name.length(); i++)
			{
				float t = (float) i / (name.length() - 1) * (grad.length - 1);
				int k = Math.min((int) t, grad.length - 2);
				sb.append(font(mix(grad[k], grad[k + 1], t - k), String.valueOf(name.charAt(i))));
			}
		}
		else
		{
			sb.append(font(nameInk, name));
		}
		if (!tag.isEmpty())
		{
			sb.append(font(tagInk, " \u00b7 " + Ui.fontSafe(tag)));
		}
		return sb.toString();
	}

	private static String font(Color c, String text)
	{
		return String.format("<font color='#%06x'>%s</font>", c.getRGB() & 0xffffff, Ui.esc(text));
	}

	private static Color mix(Color a, Color b, float t)
	{
		return new Color(Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
				Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
				Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
	}

	/** A Supporter's name gradient ({@code grad}: 2+ "#rrggbb"), or null. */
	static Color[] stops(JsonObject r)
	{
		if (!r.has("grad") || !r.get("grad").isJsonArray() || r.getAsJsonArray("grad").size() < 2)
		{
			return null;
		}
		List<Color> out = new ArrayList<>();
		for (JsonElement e : r.getAsJsonArray("grad"))
		{
			String hex = ChatPalette.nameColor(e.isJsonPrimitive() ? e.getAsString() : null);
			if (hex == null)
			{
				return null;
			}
			out.add(new Color(Integer.parseInt(hex, 16)));
		}
		return out.toArray(new Color[0]);
	}

	/** A row whose name gradient should be sliding right now. */
	static boolean moves(JsonObject r)
	{
		return GradientLabel.animate && r.has("move") && r.get("move").getAsBoolean() && stops(r) != null;
	}

	/** HOST / MOD / TEAM: a role worth spelling out. Anything else in a row's tag is its team's name. */
	static boolean isRole(String tag)
	{
		return "HOST".equals(tag) || "MOD".equals(tag) || "TEAM".equals(tag);
	}

	/** Words into lines no wider than {@code width}; a word longer than a line is broken where it must be. */
	static List<String> wrap(String text, java.awt.FontMetrics fm, int width)
	{
		List<String> out = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" "))
		{
			if (word.isEmpty())
			{
				continue;
			}
			String candidate = line.length() == 0 ? word : line + " " + word;
			if (fm.stringWidth(candidate) <= width)
			{
				line.setLength(0);
				line.append(candidate);
				continue;
			}
			if (line.length() > 0)
			{
				out.add(line.toString());
				line.setLength(0);
			}
			while (fm.stringWidth(word) > width)
			{
				int cut = 1;
				while (cut < word.length() && fm.stringWidth(word.substring(0, cut + 1)) <= width)
				{
					cut++;
				}
				out.add(word.substring(0, cut));
				word = word.substring(cut);
			}
			line.append(word);
		}
		if (line.length() > 0 || out.isEmpty())
		{
			out.add(line.toString());
		}
		return out;
	}

	private final class RowRenderer extends JPanel implements ListCellRenderer<JsonObject>
	{
		private final JLabel icon = new ClipLabel();
		private final JLabel line1 = new ClipLabel();
		private final JLabel line2 = new ClipLabel();
		private final JLabel value = new ClipLabel();
		private final JLabel gz = new ClipLabel();

		RowRenderer()
		{
			super(new BorderLayout(6, 0));
			setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
				BorderFactory.createEmptyBorder(4, 6, 4, 6)));
			icon.setVerticalAlignment(SwingConstants.TOP);
			icon.setPreferredSize(new Dimension(ICON_W, ICON_H));
			line1.setFont(REGULAR);
			line2.setFont(SMALL);
			line2.setForeground(MUTED);
			value.setFont(SMALL);
			value.setForeground(GOLD);
			value.setHorizontalAlignment(SwingConstants.RIGHT);
			gz.setFont(SMALL);
			gz.setHorizontalAlignment(SwingConstants.RIGHT);
			JPanel middle = new JPanel(new BorderLayout());
			middle.setOpaque(false);
			middle.add(line1, BorderLayout.NORTH);
			middle.add(line2, BorderLayout.SOUTH);
			JPanel right = new JPanel(new BorderLayout());
			right.setOpaque(false);
			right.add(value, BorderLayout.NORTH);
			right.add(gz, BorderLayout.SOUTH);
			add(icon, BorderLayout.WEST);
			add(middle, BorderLayout.CENTER);
			add(right, BorderLayout.EAST);
		}

		private final javax.swing.border.Border feedBorder = BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
			BorderFactory.createEmptyBorder(4, 6, 4, 6));

		private boolean mine(JsonObject r)
		{
			return (r.has("mine") && r.get("mine").getAsBoolean()) || (me != null && !me.isEmpty() && Rsn.same(me, UiView.str(r, "who")));
		}

		@Override
		public Component getListCellRendererComponent(JList<? extends JsonObject> l, JsonObject r, int index, boolean selected, boolean focus)
		{
			if (chat)
			{
				return chatCell.show(r, index, l.getWidth(), mine(r));
			}
			setBorder(feedBorder);
			line1.setVisible(true);
			line2.setFont(SMALL);
			value.setVisible(true);
			setBackground(mine(r) ? MINE_BG : ColorScheme.DARKER_GRAY_COLOR);
			Icon ic = iconFor(r);
			icon.setIcon(ic);
			icon.setVisible(ic != null);

			String who = UiView.str(r, "who");
			Color whoInk = UiView.color(UiView.str(r, "whoColor"), Color.WHITE);
			String tag = UiView.str(r, "tag");
			Color tagInk = UiView.color(UiView.str(r, "tagColor"), MUTED);
			String when = Ui.ago(UiView.str(r, "at")).replace(" ago", "");
			String title = UiView.str(r, "title");
			if (!title.isEmpty())
			{
				// A moment: the item on top, who got it (and their team) under it.
				line1.setText(plainText(title));
				line1.setForeground(UiView.color(UiView.str(r, "textColor"), Color.WHITE));
				// Who got it, in the colours the site picked (a Supporter's own, else their team's), then
				// "· Team" in the team's colour on a board.
				line2.setText(whoHtml(r, who, tag, whoInk, tagInk));
				line2.setForeground(whoInk);
				line2.setVisible(true);
			}
			else
			{
				// A roll-up line ("+214 smaller drops…"): one plain line.
				line1.setText(plainText(UiView.str(r, "text")));
				line1.setForeground(UiView.color(UiView.str(r, "textColor"), MUTED));
				line2.setVisible(false);
			}
			value.setText(plainText(r.has("value") ? UiView.str(r, "value") : when));
			value.setForeground(r.has("value") ? GOLD : MUTED);
			if (r.has("reacts") && r.get("reacts").isJsonArray())
			{
				gz.setText(reactsHtml(r.getAsJsonArray("reacts")));
				gz.setToolTipText(null);
				gz.setVisible(true);
			}
			else if (r.has("gz") && r.get("gz").isJsonObject())
			{
				JsonObject g = r.getAsJsonObject("gz");
				int n = g.has("n") ? g.get("n").getAsInt() : 0;
				boolean mine = g.has("mine") && g.get("mine").getAsBoolean();
				gz.setText(n > 0 ? "GZ " + n : "GZ");
				gz.setForeground(mine ? ColorScheme.BRAND_ORANGE : MUTED);
				gz.setToolTipText(null);
				gz.setVisible(true);
			}
			else
			{
				gz.setText(r.has("value") ? plainText(when) : "");
				gz.setForeground(MUTED);
				gz.setVisible(r.has("value"));
			}
			return this;
		}
	}

	/** Between a row's reaction chips. */
	private static final String CHIP_GAP = "   ";

	/** "Sit 3", or just "Sit" before anyone has. */
	static String chipText(JsonObject c)
	{
		int n = c.has("n") ? c.get("n").getAsInt() : 0;
		String label = UiView.str(c, "label");
		return n > 0 ? label + " " + n : label;
	}

	/** A row's chips on one right-aligned line, the viewer's own lit. */
	static String reactsHtml(JsonArray reacts)
	{
		StringBuilder sb = new StringBuilder("<html>");
		for (int k = 0; k < reacts.size(); k++)
		{
			JsonObject c = reacts.get(k).getAsJsonObject();
			boolean mine = c.has("mine") && c.get("mine").getAsBoolean();
			Color ink = mine ? ColorScheme.BRAND_ORANGE : MUTED;
			sb.append(k > 0 ? CHIP_GAP.replace(" ", "&nbsp;") : "")
				.append(String.format("<font color='#%06x'>", ink.getRGB() & 0xFFFFFF))
				.append(esc(plainText(chipText(c))))
				.append("</font>");
		}
		return sb.append("</html>").toString();
	}

	/** Which chip a click {@code fromRight} px left of the row's right edge lands on, or -1. */
	static int reactAt(JsonArray reacts, java.awt.FontMetrics fm, int fromRight)
	{
		int gap = fm.stringWidth(CHIP_GAP);
		int edge = 0;
		for (int k = reacts.size() - 1; k >= 0 && fromRight >= -6; k--)
		{
			edge += fm.stringWidth(chipText(reacts.get(k).getAsJsonObject()));
			if (fromRight <= edge + gap / 2)
			{
				return k;
			}
			edge += gap;
		}
		return -1;
	}

	/** A line joins the one before it (no name or time of its own) only from the same person within this. */
	static final long RUN_GAP_MS = 5 * 60_000L;

	/** Same person, same team tag, and close in time: a run shows its name and time once. */
	static boolean sameRun(JsonObject prev, JsonObject row)
	{
		if (!UiView.str(row, "who").equals(UiView.str(prev, "who")) || !UiView.str(row, "tag").equals(UiView.str(prev, "tag")))
		{
			return false;
		}
		long a = millis(UiView.str(prev, "at"));
		long b = millis(UiView.str(row, "at"));
		return a > 0 && b > 0 && Math.abs(a - b) <= RUN_GAP_MS;
	}

	private static long millis(String at)
	{
		if (at == null || at.isEmpty())
		{
			return 0;
		}
		String iso = at.trim().replace(' ', 'T');
		if (!iso.endsWith("Z") && !HAS_OFFSET.matcher(iso).matches())
		{
			iso = iso + "Z";
		}
		try
		{
			return java.time.Instant.parse(iso).toEpochMilli();
		}
		catch (RuntimeException e)
		{
			return 0;
		}
	}
}
