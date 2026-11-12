package com.anvil;

import java.awt.Font;
import java.awt.event.MouseEvent;
import javax.swing.*;

/**
 * A label that says the rest of itself: when its text is cut short ("…") it shows the whole text as
 * its tooltip. A tooltip set on purpose still wins. Every label the panel draws is one of these, so
 * nothing truncated is ever unreadable.
 */
class ClipLabel extends JLabel {
	ClipLabel() {
		this("");
	}

	ClipLabel(String text) {
		this(text, LEADING);
	}


	ClipLabel(String text, int align) {
		super(text, align);
		ToolTipManager.sharedInstance().registerComponent(this);
	}

	/** Site text with a symbol the RS font lacks is drawn in the system font (see {@link Ui#fit}). */
	public Font getFont() {
		return Ui.fit(super.getFont(), getText());
	}

	public String getToolTipText(MouseEvent e) {
		var own = super.getToolTipText(e);
		if (own != null) return own;
		var text = getText();
		if (text == null || text.isEmpty() || getWidth() <= 0 || getPreferredSize().width <= getWidth()) return null;
		// Shown as plain text: HTML labels wrap rather than clip, so a clipped one is plain already.
		return text.regionMatches(true, 0, "<html", 0, 5) ? null : Ui.wrapHtml(Ui.esc(text), 200);
	}
}
