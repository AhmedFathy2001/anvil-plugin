package com.anvil;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.function.BooleanSupplier;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;

/**
 * Hover a Supporter's name in the chatbox: "Anvil Patron" (Champion, Supporter). Draws nothing; per
 * frame it only reads the mouse, and walks the chat lines only while the mouse is over them.
 */
final class ChatFlairTooltip extends Overlay {
	final Client client;
	final TooltipManager tooltips;
	final ChatFlair flair;
	final BooleanSupplier on;
	/** One chat line's height. */
	static final int LINE_H = 14;

	ChatFlairTooltip(Client client, TooltipManager tooltips, ChatFlair flair, BooleanSupplier on) {
		this.client = client;
		this.tooltips = tooltips;
		this.flair = flair;
		this.on = on;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g) {
		if (flair.isEmpty() || !on.getAsBoolean()) return null;
		var area = client.getWidget(InterfaceID.Chatbox.SCROLLAREA);
		var mouse = client.getMouseCanvasPosition();
		if (area == null || area.isHidden() || mouse == null || !area.getBounds().contains(mouse.getX(), mouse.getY())) return null;
		var lines = area.getDynamicChildren();
		if (lines == null) return null;
		for (Widget w : lines) {
			if (w != null && !w.isHidden() && w.getBounds().contains(mouse.getX(), mouse.getY())) {
				var label = flair.tierOfSender(w.getText());
				if (label == null && w.getFont() != null && w.getCanvasLocation() != null
					&& mouse.getY() - w.getCanvasLocation().getY() < LINE_H) {
					// A name inside the line (an Anvil line naming its player): only over the name itself, and
					// only on the first line of a wrapped one, where the measured position holds.
					net.runelite.api.FontTypeFace font = w.getFont();
					label = flair.tierInLine(w.getText(), mouse.getX() - w.getCanvasLocation().getX(),
						raw -> ChatFlair.measure(raw, font::getTextWidth, this::iconWidth));
				}
				if (label != null) tooltips.add(new Tooltip(label));
				return null;
			}
		}
		return null;
	}

	/** An {@code <img=n>}'s drawn width: the chat icon's own (the star included), the usual 13 when unknown. */
	int iconWidth(int n) {
		net.runelite.api.IndexedSprite[] icons = client.getModIcons();
		net.runelite.api.IndexedSprite icon = icons != null && n >= 0 && n < icons.length ? icons[n] : null;
		return icon == null ? 13 : icon.getOriginalWidth();
	}
}
