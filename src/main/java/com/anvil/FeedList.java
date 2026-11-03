package com.anvil;

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
 * comes back through the push like everybody else's. Clicks never redraw the view: a GZ flips at
 * once and is posted, a mod's menu action is posted, and only an error line comes back.
 */
final class FeedList extends JPanel
{
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

		list.setCellRenderer(new RowRenderer());
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
			if (r.has("gz") && r.get("gz").isJsonObject() && e.getX() > b.x + b.width - 44)
			{
				JsonObject gz = r.getAsJsonObject("gz");
				boolean mine = gz.has("mine") && gz.get("mine").getAsBoolean();
				int count = gz.has("n") ? gz.get("n").getAsInt() : 0;
				gz.addProperty("mine", !mine);
				gz.addProperty("n", Math.max(0, count + (mine ? -1 : 1)));
				model.changed(i);
				if (gz.has("on") && gz.get("on").isJsonObject())
				{
					quietly(gz.getAsJsonObject("on"));
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
		private final javax.swing.border.Border chatFirst = BorderFactory.createEmptyBorder(6, 8, 2, 8);
		private final javax.swing.border.Border chatMore = BorderFactory.createEmptyBorder(0, 8, 2, 8);

		private boolean mine(JsonObject r)
		{
			return (r.has("mine") && r.get("mine").getAsBoolean()) || (me != null && !me.isEmpty() && Rsn.same(me, UiView.str(r, "who")));
		}

		/**
		 * A chat line: the name (and team, or HOST / MOD / TEAM) with the time on the first line, the
		 * message wrapped under it. A run of lines from one person shows the name once, like any chat.
		 */
		private Component chatLine(JList<? extends JsonObject> l, JsonObject r, int index)
		{
			JsonObject prev = index > 0 ? model.getElementAt(index - 1) : null;
			String who = UiView.str(r, "who");
			String tag = UiView.str(r, "tag");
			boolean grouped = prev != null && who.equals(UiView.str(prev, "who")) && tag.equals(UiView.str(prev, "tag"));
			setBorder(grouped ? chatMore : chatFirst);
			setBackground(mine(r) ? MINE_BG : ColorScheme.DARKER_GRAY_COLOR);
			icon.setVisible(false);
			gz.setVisible(false);

			Color whoInk = mine(r) ? ColorScheme.BRAND_ORANGE : UiView.color(UiView.str(r, "whoColor"), Color.WHITE);
			Color tagInk = UiView.color(UiView.str(r, "tagColor"), MUTED);
			line1.setFont(REGULAR);
			line1.setForeground(Color.WHITE);
			line1.setText("<html><b>" + span(who, whoInk) + "</b>" + (tag.isEmpty() ? "" : "&nbsp;&nbsp;" + span(tag, tagInk)) + "</html>");
			line1.setVisible(!grouped);
			value.setText(plainText(Ui.ago(UiView.str(r, "at")).replace(" ago", "")));
			value.setForeground(MUTED);
			value.setVisible(!grouped);

			int wrap = Math.max(80, l.getWidth() - 20 - (grouped ? 0 : 40));
			Color textInk = UiView.color(UiView.str(r, "textColor"), ColorScheme.TEXT_COLOR);
			line2.setFont(REGULAR);
			line2.setText("<html><div style='width:" + wrap + "px'>" + span(UiView.str(r, "text"), textInk) + "</div></html>");
			line2.setVisible(true);
			return this;
		}

		@Override
		public Component getListCellRendererComponent(JList<? extends JsonObject> l, JsonObject r, int index, boolean selected, boolean focus)
		{
			if (chat)
			{
				return chatLine(l, r, index);
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
				line2.setText("<html>" + span(who, whoInk) + (tag.isEmpty() ? "" : " " + span("· " + tag, tagInk)) + "</html>");
				line2.setVisible(true);
			}
			else
			{
				// A chat line (or a roll-up): one wrapped paragraph, the name leading it.
				int wrap = Math.max(80, l.getWidth() - (ic != null ? ICON_W + 6 : 0) - 70);
				String text = esc(Ui.fontSafe(UiView.str(r, "text")));
				Color textInk = UiView.color(UiView.str(r, "textColor"), ColorScheme.TEXT_COLOR);
				line1.setText("<html><div style='width:" + wrap + "px'>"
					+ (tag.isEmpty() ? "" : "<b>" + span(tag, tagInk) + "</b> ")
					+ (who.isEmpty() ? "" : "<b>" + span(who, whoInk) + "</b> ")
					+ span(text, textInk, true) + "</div></html>");
				line2.setVisible(false);
			}
			value.setText(plainText(r.has("value") ? UiView.str(r, "value") : when));
			value.setForeground(r.has("value") ? GOLD : MUTED);
			if (r.has("gz") && r.get("gz").isJsonObject())
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

		private String span(String s, Color c)
		{
			return span(esc(Ui.fontSafe(s)), c, true);
		}

		private String span(String escaped, Color c, boolean already)
		{
			return String.format("<span style='color:#%06x'>%s</span>", c.getRGB() & 0xFFFFFF, escaped);
		}
	}
}
