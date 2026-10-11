package com.anvil;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.plaf.basic.BasicScrollBarUI;
import net.runelite.client.ui.ColorScheme;

/**
 * A thin, flat scrollbar for the side panel: no arrow buttons, a rounded thumb that lightens under
 * the mouse, and a track the colour of the panel. The default bar is 17px of a 225px-wide panel,
 * which on a small client is a lot of width spent on chrome.
 */
final class SlimScrollBarUI extends BasicScrollBarUI
{
	static final int WIDTH = 7;

	private static final Color THUMB = ColorScheme.MEDIUM_GRAY_COLOR;
	private static final Color THUMB_HOVER = ColorScheme.LIGHT_GRAY_COLOR;

	/** Restyle every scroll pane directly under {@code root} (the PluginPanel's wrapper). */
	static void applyTo(Container root)
	{
		if (root == null)
		{
			return;
		}
		for (Component c : root.getComponents())
		{
			if (c instanceof JScrollPane)
			{
				style(((JScrollPane) c).getVerticalScrollBar());
			}
		}
	}

	static void style(JScrollBar bar)
	{
		bar.setUI(new SlimScrollBarUI());
		bar.setPreferredSize(new Dimension(WIDTH, 0));
		bar.setOpaque(true);
		bar.setBackground(ColorScheme.DARK_GRAY_COLOR);
		bar.setBorder(null);
		bar.setUnitIncrement(16);
	}

	@Override
	protected JButton createDecreaseButton(int orientation)
	{
		return zeroButton();
	}

	@Override
	protected JButton createIncreaseButton(int orientation)
	{
		return zeroButton();
	}

	private static JButton zeroButton()
	{
		JButton b = new JButton();
		Dimension none = new Dimension(0, 0);
		b.setPreferredSize(none);
		b.setMinimumSize(none);
		b.setMaximumSize(none);
		return b;
	}

	@Override
	protected void paintTrack(Graphics g, JComponent c, Rectangle r)
	{
		g.setColor(ColorScheme.DARK_GRAY_COLOR);
		g.fillRect(r.x, r.y, r.width, r.height);
	}

	@Override
	protected void paintThumb(Graphics g, JComponent c, Rectangle r)
	{
		if (r.isEmpty() || !scrollbar.isEnabled())
		{
			return;
		}
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setColor(isThumbRollover() || isDragging ? THUMB_HOVER : THUMB);
		int inset = 1;
		g2.fillRoundRect(r.x + inset, r.y + inset, r.width - inset * 2, r.height - inset * 2, WIDTH, WIDTH);
		g2.dispose();
	}

	@Override
	protected Dimension getMinimumThumbSize()
	{
		return new Dimension(WIDTH, 24);
	}
}
