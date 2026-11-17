package com.anvil;

import java.awt.*;
import javax.swing.JPanel;

/**
 * A panel drawn as a rounded rectangle: a card, a raised row, a track step. {@code fill} null draws no
 * ground until hovered; {@code edge} is a 1px outline (an accent card, a row marked on the way back).
 * Never opaque to Swing, so the corners show what's behind; "opaque" only switches the fill on.
 */
final class Round extends JPanel {
	final int arc;
	final Color hover;
	Color edge;
	boolean filled;

	Round(LayoutManager layout, int arc, Color fill, Color edge, Color hover) {
		super(layout);
		this.arc = arc;
		this.edge = edge;
		this.hover = hover;
		if (fill != null) setBackground(fill);
		filled = fill != null;
		super.setOpaque(false);
	}

	boolean filled() {
		return filled;
	}

	public void setOpaque(boolean on) {
		filled = on;
		super.setOpaque(false);
	}

	protected void paintComponent(Graphics g) {
		var g2 = Ui.smooth(g);
		if (filled) {
			g2.setColor(getBackground());
			g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc * 2, arc * 2);
		}
		if (edge != null) {
			g2.setColor(edge);
			g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc * 2, arc * 2);
		}
		g2.dispose();
	}
}
