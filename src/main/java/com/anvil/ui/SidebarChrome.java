package com.anvil.ui;

import net.runelite.client.util.LinkBrowser;
import java.awt.Font;
import java.net.URISyntaxException;
import java.net.URI;
import com.anvil.ui.view.BoardChoices;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.SwingConstants;
import javax.swing.plaf.basic.BasicArrowButton;
import javax.swing.plaf.basic.BasicComboBoxUI;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * What the sidebar is made of: its colours, its buttons, its labels, and its one safety rule.
 *
 * <p>Default Swing chrome sticks out badly beside RuneLite's own panels, so everything here is flat,
 * dark and drawn in the same two greys with gold for anything you can press. The type never shrinks
 * to mean "secondary" — RuneScape's small font is a bitmap face, and asked to render smaller still it
 * does not get daintier, it gets illegible. Secondary text keeps the same font and drops to
 * {@link #VALUE_COLOR} instead.</p>
 *
 * <p>{@link #plainText} is the safety rule, and it is why several rows here are built out of two real
 * labels rather than one HTML one: a clan name or a tile label is somebody else's text, and Swing
 * renders a label starting with {@code <html>} as markup.</p>
 */
public final class SidebarChrome
{
	/** Secondary text — the same size as everything else, just quieter. */
	public static final Color VALUE_COLOR = new Color(0x98_98_98);

	public static final Color WIDGET_BG = ColorScheme.DARKER_GRAY_COLOR;
	public static final Color WIDGET_BG_HOVER = ColorScheme.DARK_GRAY_HOVER_COLOR;
	public static final Color WIDGET_BORDER = new Color(0x47_47_47);

	/** Where a wrapped status line has to break — the panel minus its padding. */
	public static final int STATUS_WRAP_PX = net.runelite.client.ui.PluginPanel.PANEL_WIDTH - 40;

	private SidebarChrome()
	{
	}

	/** A small gold section label, matching the panel's header style. */
	public static JLabel sectionHeader(String text)
	{
		JLabel header = new JLabel(text);
		header.setFont(FontManager.getRunescapeSmallFont());
		header.setForeground(ColorScheme.BRAND_ORANGE);
		header.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		return header;
	}
	public static JLabel warningLabel(String message)
	{
		JLabel warn = new JLabel(plainText(message));
		warn.setFont(FontManager.getRunescapeSmallFont());
		warn.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		warn.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		return warn;
	}
	public static Component gap(int height)
	{
		return Box.createVerticalStrut(height);
	}
	/**
	 * Neutralize a site-supplied string for Swing. A {@link JLabel}/{@link JToolTip} renders as
	 * HTML when its text begins (ignoring leading whitespace, case-insensitive) with {@code <html}, so an
	 * untrusted clan/tile/activity name could inject markup. Such strings get their markup chars escaped so they
	 * render only as literal text; ordinary strings pass through. An explicit sanitize rather than reliance on
	 * {@code html.disable}; every server-supplied field the panel renders routes through here.
	 *
	 * <p>It does NOT help when the string is being placed INSIDE markup we are building: there the
	 * leading-{@code <html} test does not apply and every occurrence has to be escaped. Build the row
	 * out of real components instead — several rows here do.</p>
	 */
	public static String plainText(String s)
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
		if (s.regionMatches(true, i, "<html", 0, 5))
		{
			return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		}
		return s;
	}

	/** Clip an over-long label to width with an ellipsis so a feed/spotlight row never overflows the panel. */
	public static String ellipsize(String s, int max)
	{
		if (s == null)
		{
			return "";
		}
		return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)).trim() + "…";
	}

	/**
	 * Drop the held ladder-card refs. Only one card renders at a time, so every render path that isn't
	 * building a ladder card must clear them — otherwise the 1 s tick keeps updating labels that are no
	 * longer on screen.
	 */
	/** Renders a {@link ConnectionView} in the clan dropdown by name, flagging an unreachable home. */
	// ---- Anvil-themed widget chrome ---------------------------------------------------------------

	/** Flat dark button matching the sidebar theme: dark surface, thin border, hover lift, no L&F chrome. */
	/** A themed action button in the panel's orange, wired to one thing it does. */
	public static JButton actionButton(String label, String tooltip, Runnable onClick)
	{
		JButton b = new JButton(label);
		styleFlatButton(b, ColorScheme.BRAND_ORANGE);
		b.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		b.setToolTipText(tooltip);
		b.addActionListener(e -> onClick.run());
		return b;
	}

	/**
	 * Two buttons on one line, equal halves.
	 *
	 * <p>The height cap matters: a grid inside a vertical BoxLayout will happily stretch to whatever
	 * space is left, which turns a pair of buttons into a pair of slabs.
	 */
	public static JPanel buttonRow(JButton left, JButton right)
	{
		JPanel row = new JPanel(new GridLayout(1, 2, 4, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		row.add(left);
		row.add(right);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	/** One button holding a whole row, so a lone action doesn't sit in half a line. */
	public static JPanel fullWidth(JButton button)
	{
		JPanel row = new JPanel(new GridLayout(1, 1));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		row.add(button);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	public static void styleFlatButton(JButton b, Color foreground)
	{
		b.setFocusPainted(false);
		b.setForeground(foreground);
		b.setBackground(WIDGET_BG);
		b.setFont(FontManager.getRunescapeSmallFont());
		b.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(WIDGET_BORDER),
			BorderFactory.createEmptyBorder(4, 10, 4, 10)));
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		b.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				if (b.isEnabled())
				{
					b.setBackground(WIDGET_BG_HOVER);
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				b.setBackground(WIDGET_BG);
			}
		});
	}

	/** Theme the clan-filter combo: dark flat field, gold arrow, dark popup — default Swing sticks out. */
	public static void styleClanPicker(JComboBox<BoardChoices.ClanChoice> combo)
	{
		combo.setBackground(WIDGET_BG);
		combo.setForeground(Color.WHITE);
		combo.setFont(FontManager.getRunescapeSmallFont());
		combo.setBorder(BorderFactory.createLineBorder(WIDGET_BORDER));
		combo.setUI(new BasicComboBoxUI()
		{
			@Override
			protected JButton createArrowButton()
			{
				BasicArrowButton arrow = new BasicArrowButton(
					SwingConstants.SOUTH, WIDGET_BG, WIDGET_BG, ColorScheme.BRAND_ORANGE, WIDGET_BG);
				arrow.setBorder(BorderFactory.createEmptyBorder());
				return arrow;
			}
		});
	}

	/**
	 * Two real labels, not one HTML one.
	 *
	 * The second line was <code>&lt;font size='-2'&gt;</code> inside a label already set to
	 * RuneScape's small font. That font is a bitmap face: asked to render smaller still it does not
	 * get daintier, it gets illegible — and the size the browser-ish HTML renderer picks is not one
	 * the face was drawn at. The rest of this panel never shrinks type to mean "secondary"; it keeps
	 * the same small font and drops the colour to {@link #VALUE_COLOR}. This does the same.
	 *
	 * Building the row out of components also means no markup, which means no clan name can smuggle
	 * any: {@link #plainText} was guarding a string we were about to concatenate into HTML ourselves,
	 * which is the one case it does not guard.
	 */
	public static final class ClanChoiceRenderer implements ListCellRenderer<BoardChoices.ClanChoice>
	{
		private final JPanel row = new JPanel(new BorderLayout(0, 1));
		private final JLabel name = new JLabel();
		private final JLabel detail = new JLabel();

		public ClanChoiceRenderer()
		{
			name.setFont(FontManager.getRunescapeSmallFont());
			detail.setFont(FontManager.getRunescapeSmallFont());
			row.setOpaque(true);
			row.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
			row.add(name, BorderLayout.NORTH);
			row.add(detail, BorderLayout.SOUTH);
		}

		@Override
		public Component getListCellRendererComponent(JList<? extends BoardChoices.ClanChoice> list, BoardChoices.ClanChoice value,
			int index, boolean isSelected, boolean cellHasFocus)
		{
			// index >= 0 → a popup row; -1 → the closed field. Dark rows, gold-tinted hover.
			boolean hover = isSelected && index >= 0;
			row.setBackground(hover ? WIDGET_BG_HOVER : WIDGET_BG);
			name.setForeground(hover ? ColorScheme.BRAND_ORANGE : Color.WHITE);
			detail.setForeground(VALUE_COLOR);

			name.setText(value == null ? "" : plainText(value.label));
			// The closed field shows the name alone — a second line there would resize the header on
			// every refresh, and the detail is what you read while choosing, not after.
			boolean showDetail = index >= 0 && value != null && !value.detail.isEmpty();
			detail.setText(showDetail ? plainText(value.detail) : "");
			detail.setVisible(showDetail);
			return row;
		}
	}

	/** A left-aligned JLabel (BoxLayout children default to centre), sanitized for federated text. */
	public static JLabel leftLabel(String text, Font font, Color color)
	{
		JLabel label = new JLabel(plainText(text));
		label.setFont(font);
		label.setForeground(color);
		label.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		return label;
	}
	/** A wrapped grey paragraph at the panel's width — long copy clips rather than wraps without it. */
	public static JLabel note(String text)
	{
		JLabel label = new JLabel("<html><body style='width:" + STATUS_WRAP_PX + "px'>"
			+ text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</body></html>");
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(VALUE_COLOR);
		label.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		return label;
	}
	/** A clickable "View standings" link opening the board's site page in the system browser. */
	public static JLabel boardLink(String url)
	{
		return siteLink("View standings ↗", "Open the full standings on the Anvil site", url);
	}

	/** As {@link #boardLink}, for an event you're not in yet — the site page is where you sign up. */
	public static JLabel eventLink(String url)
	{
		return siteLink("View event ↗", "Open this event on the Anvil site", url);
	}

	public static JLabel siteLink(String text, String tooltip, String url)
	{
		JLabel link = new JLabel(text);
		link.setFont(FontManager.getRunescapeSmallFont());
		link.setForeground(ColorScheme.BRAND_ORANGE);
		link.setToolTipText(tooltip);
		link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		link.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
		link.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (isSafeHttpUrl(url)) // defense-in-depth: never a javascript:/data:/file: or creds@host URL
				{
					LinkBrowser.browse(url);
				}
			}
		});
		return link;
	}

	/** True only for a well-formed absolute {@code http}/{@code https} URL with a host and no embedded
	 *  credentials — refuses {@code javascript:} / {@code data:} / {@code file:} and {@code user@host} tricks. */
	public static boolean isSafeHttpUrl(String url)
	{
		if (url == null || url.isEmpty())
		{
			return false;
		}
		try
		{
			URI u = new URI(url);
			String scheme = u.getScheme();
			return u.getHost() != null && u.getUserInfo() == null
				&& ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme));
		}
		catch (URISyntaxException ex)
		{
			return false;
		}
	}
}
