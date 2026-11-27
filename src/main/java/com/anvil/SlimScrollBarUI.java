package com.anvil;

import static net.runelite.client.ui.ColorScheme.*;
import java.awt.*;
import javax.swing.*;
import javax.swing.plaf.basic.*;

/**
 * A thin, flat scrollbar for the side panel: no arrow buttons, a rounded thumb that lightens under
 * the mouse, and a track the colour of the panel. The default bar is 17px of a 225px-wide panel,
 * which on a small client is a lot of width spent on chrome.
 */
class SlimScrollBarUI extends BasicScrollBarUI {
	static final int WIDTH = 7;

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
		Ui.sizeTo(b, new Dimension(0, 0));
		return b;
	}

	protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
		g.setColor(DARK_GRAY_COLOR);
		g.fillRect(r.x, r.y, r.width, r.height);
	}

	protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
		if (r.isEmpty() || !scrollbar.isEnabled()) return;
		var g2 = Ui.smooth(g);
		g2.setColor(isThumbRollover() || isDragging ? LIGHT_GRAY_COLOR : MEDIUM_GRAY_COLOR);
		g2.fillRoundRect(r.x + 1, r.y + 1, r.width - 2, r.height - 2, WIDTH, WIDTH); // 1px in
		g2.dispose();
	}

	protected Dimension getMinimumThumbSize() {
		return new Dimension(WIDTH, 24);
	}
}
