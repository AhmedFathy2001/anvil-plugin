package com.anvil;

import java.awt.*;
import java.awt.event.*;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import javax.swing.*;
import net.runelite.client.ui.*;
import javax.swing.event.DocumentEvent;

/**
 * The side panel's shared Swing pieces — the labels, rows, cards and click wiring every view builds
 * the same way, so the sidebar and the card views can't drift apart in how they look.
 */
final class Ui
{
	// FontManager hands out the same static instances every call, so holding them changes nothing.
	static final Font SMALL = FontManager.getRunescapeSmallFont();
	static final Font REGULAR = FontManager.getRunescapeFont();
	static final Font BOLD = FontManager.getRunescapeBoldFont();

	/** Secondary text: counts, hints, the quieter half of a row. */
	static final Color MUTED = new Color(0x98_98_98);

	private Ui()
	{
	}

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
	static String plainText(String s)
	{
		if (s == null)
		{
			return "";
		}
		int i = 0;
		while (i < s.length() && Character.isWhitespace(s.charAt(i)))
		{
			i++;
		}
		return s.regionMatches(true, i, "<html", 0, 5) ? esc(s) : s;
	}

	/** Escape markup characters for text placed inside HTML we build ourselves. */
	static String esc(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/** {@code html} wrapped to {@code widthPt} so a long line breaks instead of clipping at the panel's edge. */
	static String wrapHtml(String html, int widthPt)
	{
		return "<html><body style='width:" + widthPt + "pt'>" + html + "</body></html>";
	}

	/** A label in {@code font} and {@code color}, its text sanitized ({@link #plainText}). */
	static JLabel label(String text, Font font, Color color)
	{
		JLabel l = new JLabel(plainText(text));
		l.setFont(font);
		l.setForeground(color);
		return l;
	}

	static JLabel small(String text, Color color)
	{
		return label(text, SMALL, color);
	}

	static JLabel title(String text)
	{
		return label(text, BOLD, Color.WHITE);
	}

	/**
	 * Cap a component's height at what it wants right now, so a vertical BoxLayout stops stretching it.
	 * Call once it's filled — the cap is the preferred height at the time of the call.
	 */
	static <T extends JComponent> T capH(T c)
	{
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
		return c;
	}

	static <T extends JComponent> T left(T c)
	{
		c.setAlignmentX(Component.LEFT_ALIGNMENT);
		return c;
	}

	/** A hand cursor and a click that runs {@code action}. */
	static <T extends JComponent> T onClick(T c, Runnable action)
	{
		c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		c.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				action.run();
			}
		});
		return c;
	}

	/** Lights up under the mouse and does {@code onClick} — so a row or tile says it can be opened. */
	static void hoverable(JComponent c, Color base, Runnable onClick)
	{
		c.setOpaque(true);
		c.setBackground(base);
		onClick(c, onClick).addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				c.setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				c.setBackground(base);
			}
		});
	}

	/** A left-aligned vertical stack on the panel background. */
	static JPanel column()
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		return left(p);
	}

	/** A {@link #column} as a card: the darker surface with an 8px inset. */
	static JPanel block()
	{
		JPanel p = column();
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		return p;
	}

	/** A transparent, left-aligned BorderLayout row with {@code hgap} between its parts. */
	static JPanel row(int hgap)
	{
		JPanel p = new JPanel(new BorderLayout(hgap, 0));
		p.setOpaque(false);
		return left(p);
	}

	static Component gap(int h)
	{
		return Box.createVerticalStrut(h);
	}

	/** The search fields' look: white text and caret on the darker surface, an orange selection. */
	static void styleSearch(JTextField f)
	{
		f.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		f.setForeground(Color.WHITE);
		f.setCaretColor(Color.WHITE);
		f.setSelectionColor(ColorScheme.BRAND_ORANGE);
		f.setSelectedTextColor(Color.BLACK);
		f.setFont(REGULAR);
	}

	/** Runs {@code changed} on every edit of the field's text. */
	static void onEdit(javax.swing.text.JTextComponent f, Runnable changed)
	{
		f.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				changed.run();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				changed.run();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				changed.run();
			}
		});
	}

	/** The panel's flat 0–100 progress bar: no text, no border, {@code h} px tall. */
	static JProgressBar bar(int percent, Color fg, Color bg, int h)
	{
		JProgressBar bar = new JProgressBar(0, 100);
		bar.setValue(percent);
		bar.setStringPainted(false);
		bar.setBorderPainted(false);
		bar.setForeground(fg);
		bar.setBackground(bg);
		bar.setPreferredSize(new Dimension(0, h));
		return bar;
	}

	/** A transparent, left-aligned grid {@code cols} across. */
	static JPanel grid(int cols, int hgap, int vgap)
	{
		JPanel grid = left(new JPanel(new GridLayout(0, cols, hgap, vgap)));
		grid.setOpaque(false);
		return grid;
	}

	/** An empty, centred icon holder of a fixed size. */
	static JLabel iconSlot(int w, int h)
	{
		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(w, h));
		icon.setHorizontalAlignment(SwingConstants.CENTER);
		return icon;
	}

	/** A 3px share-of-total bar: orange, green once full, over {@code bg}. */
	static JPanel thinBar(int pct, Color bg)
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

	static int percent(long part, long whole)
	{
		return whole <= 0 ? 0 : (int) Math.floor(part * 100.0 / whole);
	}

	/**
	 * The one way a count is shown, panel and overlays alike: "9,999", "12K", "4.1M", "4.6B" — the game's
	 * own capital suffixes, rounded down so a goal never reads as reached before it is.
	 */
	static String count(long n)
	{
		if (n < 10_000)
		{
			return String.format(Locale.ROOT, "%,d", n);
		}
		if (n < 1_000_000)
		{
			return n / 1000 + "K";
		}
		boolean b = n >= 1_000_000_000;
		long tenths = n / (b ? 100_000_000 : 100_000);
		return tenths / 10 + (tenths % 10 == 0 ? "" : "." + tenths % 10) + (b ? "B" : "M");
	}

	/** "1 player", "1,204 players". */
	static String plural(long n, String one)
	{
		return plural(n, one, one + "s");
	}

	static String plural(long n, String one, String many)
	{
		return String.format(Locale.ROOT, "%,d %s", n, n == 1 ? one : many);
	}

	/** A team's colour; the server stores "#rrggbb", and anything else reads as {@code fallback}. */
	static Color teamColor(String hex, Color fallback)
	{
		return hex != null && hex.matches("#[0-9a-fA-F]{6}") ? new Color(Integer.parseInt(hex.substring(1), 16)) : fallback;
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

	/** JTextField has no placeholder API; paint one without putting fake text into the query. */
	static final class SearchField extends JTextField
	{
		private final String placeholder;

		SearchField(String placeholder, String value)
		{
			super(value == null ? "" : value);
			this.placeholder = placeholder;
			addFocusListener(new FocusAdapter()
			{
				@Override public void focusGained(FocusEvent e) { repaint(); }
				@Override public void focusLost(FocusEvent e) { repaint(); }
			});
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			super.paintComponent(graphics);
			if (!getText().isEmpty() || isFocusOwner())
			{
				return;
			}
			Graphics2D g = (Graphics2D) graphics.create();
			g.setColor(MUTED);
			g.setFont(getFont());
			FontMetrics metrics = g.getFontMetrics();
			Insets insets = getInsets();
			int y = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
			g.drawString(placeholder, insets.left, y);
			g.dispose();
		}
	}
}
