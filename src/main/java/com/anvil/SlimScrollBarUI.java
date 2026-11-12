package com.anvil;

import static net.runelite.client.ui.ColorScheme.*;
import java.awt.*;
import javax.swing.*;
import javax.swing.plaf.basic.BasicScrollBarUI;

/**
 * A thin, flat scrollbar for the side panel: no arrow buttons, a rounded thumb that lightens under
 * the mouse, and a track the colour of the panel. The default bar is 17px of a 225px-wide panel,
 * which on a small client is a lot of width spent on chrome.
 */
final class SlimScrollBarUI extends BasicScrollBarUI {
	static final int WIDTH = 7;

	static final Color THUMB = MEDIUM_GRAY_COLOR;
	static final Color THUMB_HOVER = LIGHT_GRAY_COLOR;

	/** Restyle every scroll pane directly under {@code root} (the PluginPanel's wrapper). */
	static void applyTo(Container root) {
		if (root == null) return;
		for (var c : root.getComponents()) {
			if (c instanceof JScrollPane) style(((JScrollPane) c).getVerticalScrollBar());
		}
	}

	static void style(JScrollBar bar) {
		bar.setUI(new SlimScrollBarUI());
		bar.setPreferredSize(new Dimension(WIDTH, 0));
		bar.setOpaque(true);
		bar.setBackground(DARK_GRAY_COLOR);
		bar.setBorder(null);
		bar.setUnitIncrement(16);
	}

	protected JButton createDecreaseButton(int orientation) {
		return zeroButton();
	}

	protected JButton createIncreaseButton(int orientation) {
		return zeroButton();
	}

	static JButton zeroButton() {
		var b = new JButton();
		var none = new Dimension(0, 0);
		b.setPreferredSize(none);
		b.setMinimumSize(none);
		b.setMaximumSize(none);
		return b;
	}

	protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
		g.setColor(DARK_GRAY_COLOR);
		g.fillRect(r.x, r.y, r.width, r.height);
	}

	protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
		if (r.isEmpty() || !scrollbar.isEnabled()) return;
		var g2 = Ui.smooth(g);
		g2.setColor(isThumbRollover() || isDragging ? THUMB_HOVER : THUMB);
		int inset = 1;
		g2.fillRoundRect(r.x + inset, r.y + inset, r.width - inset * 2, r.height - inset * 2, WIDTH, WIDTH);
		g2.dispose();
	}

	protected Dimension getMinimumThumbSize() {
		return new Dimension(WIDTH, 24);
	}
}
