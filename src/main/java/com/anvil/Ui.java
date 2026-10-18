package com.anvil;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

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
	static void styleSearch(javax.swing.JTextField f)
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
			public void insertUpdate(javax.swing.event.DocumentEvent e)
			{
				changed.run();
			}

			@Override
			public void removeUpdate(javax.swing.event.DocumentEvent e)
			{
				changed.run();
			}

			@Override
			public void changedUpdate(javax.swing.event.DocumentEvent e)
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

	/** JTextField has no placeholder API; paint one without putting fake text into the query. */
	static final class SearchField extends javax.swing.JTextField
	{
		private final String placeholder;

		SearchField(String placeholder, String value)
		{
			super(value == null ? "" : value);
			this.placeholder = placeholder;
			addFocusListener(new java.awt.event.FocusAdapter()
			{
				@Override public void focusGained(java.awt.event.FocusEvent e) { repaint(); }
				@Override public void focusLost(java.awt.event.FocusEvent e) { repaint(); }
			});
		}

		@Override
		protected void paintComponent(java.awt.Graphics graphics)
		{
			super.paintComponent(graphics);
			if (!getText().isEmpty() || isFocusOwner())
			{
				return;
			}
			java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
			g.setColor(MUTED);
			g.setFont(getFont());
			java.awt.FontMetrics metrics = g.getFontMetrics();
			java.awt.Insets insets = getInsets();
			int y = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
			g.drawString(placeholder, insets.left, y);
			g.dispose();
		}
	}
}
