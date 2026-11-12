package com.anvil;

import static net.runelite.client.ui.ColorScheme.*;
import java.awt.*;
import java.awt.event.*;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.swing.*;
import net.runelite.client.ui.*;
import javax.swing.event.*;
import javax.swing.text.JTextComponent;

/**
 * The side panel's shared Swing pieces — the labels, rows, cards and click wiring every view builds
 * the same way, so the sidebar and the card views can't drift apart in how they look.
 */
final class Ui {
	/** A copy of {@code g} that draws antialiased; the caller disposes it. */
	static Graphics2D smooth(Graphics g) {
		var g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		return g2;
	}

	/** "...+02:00" at the end of a timestamp. Compiled once: it runs for every row on each paint. */
	static final Pattern HAS_OFFSET = Pattern.compile(".*[+-]\\d\\d:\\d\\d$");

	// FontManager hands out the same static instances every call, so holding them changes nothing.
	static final Font SMALL = FontManager.getRunescapeSmallFont();
	static final Font REGULAR = FontManager.getRunescapeFont();
	static final Font BOLD = FontManager.getRunescapeBoldFont();

	/** Secondary text: counts, hints, the quieter half of a row. */
	static final Color MUTED = new Color(0xa8_a8_a8);

	/**
	 * Neutralize a site-supplied string for Swing. A {@link JLabel}/{@link javax.swing.JToolTip} renders as
	 * HTML when its text begins (ignoring leading whitespace, case-insensitive) with {@code <html}, so an
	 * untrusted clan/tile/activity name could inject markup. Such strings get their markup chars escaped so they
	 * render only as literal text; ordinary strings pass through. An explicit sanitize rather than reliance on
	 * {@code html.disable}; every server-supplied field the panel renders routes through here.
	 *
	 * <p>Use {@link #esc} instead when the string is being placed INSIDE markup we are building: there the
	 * leading-{@code <html} test does not apply and every occurrence has to be escaped.</p>
	 */
	static String plainText(String s) {
		if (s == null) return "";
		s = fontSafe(s);
		int i = 0;
		while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
		return s.regionMatches(true, i, "<html", 0, 5) ? esc(s) : s;
	}

	/**
	 * The RuneScape fonts have no curly quotes or long dashes: on macOS they drew as a box ("You?re").
	 * The site writes proper typography, so it is folded to the plain ASCII the font does have.
	 */
	static String fontSafe(String s) {
		return s == null ? null : s.replace('\u2019', '\'').replace('\u2018', '\'')
			.replace('\u201C', '"').replace('\u201D', '"')
			.replace('\u2013', '-').replace('\u2014', '-')
			.replace("\u2026", "...");
	}

	/** Escape markup characters for text placed inside HTML we build ourselves. */
	static String esc(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/** {@code html} wrapped to {@code widthPt} so a long line breaks instead of clipping at the panel's edge. */
	static String wrapHtml(String html, int widthPt) {
		return "<html><body style='width:" + widthPt + "pt'>" + html + "</body></html>";
	}

	/** A label in {@code font} and {@code color}, its text sanitized ({@link #plainText}). */
	static JLabel label(String text, Font font, Color color) {
		var l = new ClipLabel(plainText(text));
		l.setFont(font);
		l.setForeground(color);
		return l;
	}

	static JLabel small(String text, Color color) {
		return label(text, SMALL, color);
	}

	/**
	 * Cap a component's height at what it wants right now, so a vertical BoxLayout stops stretching it.
	 * Call once it's filled — the cap is the preferred height at the time of the call.
	 */
	static <T extends JComponent> T capH(T c) {
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
		return c;
	}

	static <T extends JComponent> T left(T c) {
		c.setAlignmentX(Component.LEFT_ALIGNMENT);
		return c;
	}

	static final String CLICKS = "anvil.clicks";

	/**
	 * A hand cursor and a left click (a press and a release inside: Swing's mouseClicked drops a click
	 * whose mouse moved a pixel) that runs {@code action}. Its whole tree listens: a label with a tooltip
	 * (every ClipLabel) takes the mouse for itself and Swing doesn't hand the click up. A part inside
	 * with a click of its own keeps it.
	 */
	static <T extends JComponent> T onClick(T c, Runnable action) {
		c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		c.putClientProperty(CLICKS, Boolean.TRUE);
		AnvilSidebarPanel.addMouseListenerToTree(c, new MouseAdapter() {
			boolean down;

			public void mousePressed(MouseEvent e) {
				down = SwingUtilities.isLeftMouseButton(e) && ownClick(c, e);
			}

			public void mouseReleased(MouseEvent e) {
				if (down && SwingUtilities.isLeftMouseButton(e) && c.contains(SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), c))) action.run();
				down = false;
			}
		});
		return c;
	}

	/** Whether the press is {@code c}'s: no clickable part inside it is nearer the pointer. */
	static boolean ownClick(JComponent c, MouseEvent e) {
		for (Component at = e.getComponent(); at != null && at != c; at = at.getParent())
			if (at instanceof JComponent && ((JComponent) at).getClientProperty(CLICKS) != null) return false;
		return true;
	}

	/** Lights up under the mouse and does {@code onClick} — so a row or tile says it can be opened. */
	static void hoverable(JComponent c, Color base, Runnable onClick) {
		c.setOpaque(true);
		c.setBackground(base);
		onClick(c, onClick).addMouseListener(new MouseAdapter() {
			public void mouseEntered(MouseEvent e) {
				c.setBackground(DARK_GRAY_HOVER_COLOR);
			}

			public void mouseExited(MouseEvent e) {
				if (c.getMousePosition(true) == null) c.setBackground(base); // into a child is still inside
			}
		});
	}

	/** A left-aligned vertical stack on the panel background. */
	static JPanel column() {
		var p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(DARK_GRAY_COLOR);
		return left(p);
	}

	/** A {@link #column} as a card: the darker surface with an 8px inset. */
	static JPanel block() {
		var p = column();
		p.setBackground(DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		return p;
	}

	/** A transparent, left-aligned BorderLayout row with {@code hgap} between its parts. */
	static JPanel row(int hgap) {
		var p = new JPanel(new BorderLayout(hgap, 0));
		p.setOpaque(false);
		return left(p);
	}

	static Component gap(int h) {
		return Box.createVerticalStrut(h);
	}

	/** The search fields' look: white text and caret on the darker surface, an orange selection. */
	static void styleSearch(JTextField f) {
		f.setBackground(DARKER_GRAY_COLOR);
		f.setForeground(Color.WHITE);
		f.setCaretColor(Color.WHITE);
		f.setSelectionColor(BRAND_ORANGE);
		f.setSelectedTextColor(Color.BLACK);
		f.setFont(REGULAR);
	}

	/** Runs {@code changed} on every edit of the field's text. */
	static void onEdit(JTextComponent f, Runnable changed) {
		f.getDocument().addDocumentListener(new DocumentListener() {
			public void insertUpdate(DocumentEvent e) {
				changed.run();
			}

			public void removeUpdate(DocumentEvent e) {
				changed.run();
			}

			public void changedUpdate(DocumentEvent e) {
				changed.run();
			}
		});
	}

	/** A transparent, left-aligned grid {@code cols} across. */
	static JPanel grid(int cols, int hgap, int vgap) {
		var grid = left(new JPanel(new GridLayout(0, cols, hgap, vgap)));
		grid.setOpaque(false);
		return grid;
	}

	/** {@code part} of {@code whole} as 0..100, rounded down so a goal never reads as reached before it is. */
	static int percent(long part, long whole) {
		return whole <= 0 ? 0 : (int) Math.max(0, Math.min(100, Math.floor(part * 100.0 / whole)));
	}

	/**
	 * The one way a count is shown, panel and overlays alike: "9,999", "12K", "4.1M", "4.6B" — the game's
	 * own capital suffixes, rounded down so a goal never reads as reached before it is.
	 */
	static String count(long n) {
		if (n < 10_000) return String.format(Locale.ROOT, "%,d", n);
		if (n < 1_000_000) return n / 1000 + "K";
		var b = n >= 1_000_000_000;
		var tenths = n / (b ? 100_000_000 : 100_000);
		return tenths / 10 + (tenths % 10 == 0 ? "" : "." + tenths % 10) + (b ? "B" : "M");
	}

	/** "1 player", "1,204 players". */
	static String plural(long n, String one) {
		return plural(n, one, one + "s");
	}

	static String plural(long n, String one, String many) {
		return String.format(Locale.ROOT, "%,d %s", n, n == 1 ? one : many);
	}

	/** A team's colour; the server stores "#rrggbb", and anything else reads as {@code fallback}. */
	static Color teamColor(String hex, Color fallback) {
		return hex != null && hex.matches("#[0-9a-fA-F]{6}") ? new Color(Integer.parseInt(hex.substring(1), 16)) : fallback;
	}

	/** "3d ago" from either timestamp format the site sends ("2026-10-12 08:00:00" or ISO). */
	static String ago(String at) {
		return ago(at, Instant.now());
	}

	static String ago(String at, Instant now) {
		if (at == null || at.isEmpty()) return "";
		var iso = at.trim().replace(' ', 'T');
		if (!iso.endsWith("Z") && !HAS_OFFSET.matcher(iso).matches()) iso += "Z";
		try {
			var minutes = Math.max(0, Duration.between(Instant.parse(iso), now).toMinutes());
			if (minutes < 60) return minutes <= 1 ? "just now" : minutes + "m ago";
			var hours = minutes / 60;
			if (hours < 24) return hours + "h ago";
			var days = hours / 24;
			if (days < 14) return days + "d ago";
			if (days < 60) return days / 7 + "w ago";
			if (days < 730) return days / 30 + "mo ago";
			return days / 365 + "y ago";
		} catch (DateTimeParseException e) {
			return "";
		}
	}

	/** JTextField has no placeholder API; paint one without putting fake text into the query. */
	static final class SearchField extends JTextField {
		final String placeholder;

		SearchField(String placeholder, String value) {
			super(value == null ? "" : value);
			this.placeholder = placeholder;
			addFocusListener(new FocusAdapter() {
				public void focusGained(FocusEvent e) { repaint(); }
				public void focusLost(FocusEvent e) { repaint(); }
			});
		}

		protected void paintComponent(Graphics graphics) {
			super.paintComponent(graphics);
			if (!getText().isEmpty() || isFocusOwner()) return;
			var g = (Graphics2D) graphics.create();
			g.setColor(MUTED);
			g.setFont(getFont());
			var metrics = g.getFontMetrics();
			g.drawString(placeholder, getInsets().left, (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent());
			g.dispose();
		}
	}

	/**
	 * The server's UTC stamp — ISO ({@code 2026-07-29T12:00:00.000Z}), the space-separated SQLite
	 * {@code yyyy-MM-dd HH:mm:ss} form, or a bare date (midnight UTC) — as epoch millis; -1 if unparseable.
	 */
	static long utcMillis(String ts) {
		var s = ts == null ? "" : ts.trim().replace(' ', 'T');
		try {
			return s.length() == 10
				? LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
				: Instant.parse(s.endsWith("Z") ? s : s + "Z").toEpochMilli();
		} catch (RuntimeException e) {
			return -1;
		}
	}

}
