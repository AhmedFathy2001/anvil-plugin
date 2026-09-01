package com.anvil;

import java.util.function.BooleanSupplier;

import net.runelite.api.Client;
import net.runelite.api.FontID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;

/**
 * An "Anvil" button injected into a game interface's header — the collection log's, beside WikiSync
 * and RuneProfile, and the clan window's, beside Wise Old Man's.
 *
 * WHY BUTTONS AND NOT THE TAB WE DELETED. The in-game clog TAB went in 5d2e425 — four thousand lines
 * of hand-drawn widgets nobody read, sitting between the side panel (progress at a glance) and the
 * site (real search). These are not that: one control each, in the place the player already is,
 * doing what the sidebar already does, for somebody whose hands are in the game interface rather
 * than in RuneLite's.
 *
 * PLACED RELATIVE TO WHAT IS ALREADY THERE, never at a fixed offset. Two other hub plugins put
 * buttons in this exact header, and Jagex rearranges interfaces on game updates. So the position is
 * computed at draw time from the header's existing dynamic children — everybody's injected buttons,
 * ours included — and we sit to the LEFT of the leftmost. If the header is missing, the button
 * simply does not appear. A missing button is a small disappointment; one drawn on top of WikiSync's
 * is a bug report from somebody else's users.
 */
final class HeaderButton
{
	/** Matches the native header buttons: small, and tall enough to hit comfortably. */
	private static final int WIDTH = 65;
	private static final int HEIGHT = 20;
	/** Gap between neighbouring injected buttons. */
	private static final int GAP = 4;
	/** Inset from the header's right edge when we are the first button in it. */
	private static final int RIGHT_INSET = 12;

	/** Identifies our own child across redraws, so a re-render moves it instead of adding a second. */
	private static final String NAME = "Anvil";

	private final Client client;
	/** The header to inject into — a gameval component, so a client update carries it with us. */
	private final int componentId;
	private final String label;
	private final String action;
	/** Whether syncing is possible right now — no site or no token means no button. */
	private final BooleanSupplier enabled;
	private final Runnable onClick;

	HeaderButton(Client client, int componentId, String label, String action, BooleanSupplier enabled, Runnable onClick)
	{
		this.client = client;
		this.componentId = componentId;
		this.label = label;
		this.action = action;
		this.enabled = enabled;
		this.onClick = onClick;
	}

	/**
	 * Draw (or re-place) the button. Safe to call on every clog draw — it reuses its own child.
	 *
	 * Must run on the client thread; the caller is already there (onScriptPostFired).
	 */
	void render()
	{
		Widget header = client.getWidget(componentId);
		if (header == null || !enabled.getAsBoolean())
		{
			return;
		}

		Widget button = find(header);
		if (button == null)
		{
			button = header.createChild(-1, WidgetType.TEXT);
			button.setName(NAME);
			button.setHasListener(true);
			button.setAction(0, action);
			button.setOnOpListener((JavaScriptCallback) e -> onClick.run());
		}

		button.setText(label);
		button.setFontId(FontID.PLAIN_12);
		button.setTextColor(0xff981f); // RuneLite's brand orange, so it reads as a plugin control
		button.setTextShadowed(true);
		button.setXTextAlignment(WidgetTextAlignment.CENTER);
		button.setYTextAlignment(WidgetTextAlignment.CENTER);
		button.setOriginalWidth(WIDTH);
		button.setOriginalHeight(HEIGHT);
		button.setOriginalX(placeClearOf(header, button));
		button.setOriginalY(0);
		button.revalidate();
	}

	/** Our child, if we already made one. */
	private Widget find(Widget header)
	{
		Widget[] children = header.getDynamicChildren();
		if (children == null)
		{
			return null;
		}
		for (Widget w : children)
		{
			if (w != null && NAME.equals(w.getName()))
			{
				return w;
			}
		}
		return null;
	}

	/**
	 * An x that does not land on somebody else's button.
	 *
	 * Every injected button in these headers is a DYNAMIC child — the interface's own parts are
	 * static — so the dynamic children are exactly the set to avoid, whoever put them there.
	 *
	 * TWO LAYOUTS, ONE RULE. The collection log's plugins sit at the RIGHT of its header; Wise Old
	 * Man's sits at the LEFT of the clan window's. A rule that only stepped one direction worked for
	 * one of them and drew straight through the other, so this tries right of the rightmost first and
	 * falls back to left of the leftmost when that would overflow the header. Load order stops
	 * mattering either way: whoever draws second sees the first and steps around it.
	 */
	private int placeClearOf(Widget header, Widget self)
	{
		int leftmost = Integer.MAX_VALUE;
		int rightmostEdge = Integer.MIN_VALUE;
		Widget[] children = header.getDynamicChildren();
		if (children != null)
		{
			for (Widget w : children)
			{
				if (w == null || w == self || w.isSelfHidden())
				{
					continue;
				}
				leftmost = Math.min(leftmost, w.getOriginalX());
				rightmostEdge = Math.max(rightmostEdge, w.getOriginalX() + w.getWidth());
			}
		}

		int headerWidth = header.getWidth();
		if (leftmost == Integer.MAX_VALUE)
		{
			// First one in. Sit inside the header's right edge rather than at a guessed coordinate.
			return Math.max(0, headerWidth - WIDTH - RIGHT_INSET);
		}

		int toTheRight = rightmostEdge + GAP;
		if (toTheRight + WIDTH <= headerWidth)
		{
			return toTheRight;
		}

		int toTheLeft = leftmost - WIDTH - GAP;
		// Only if it actually fits. Clamping a negative to 0 would put us back on top of the
		// leftmost button, which is the collision this whole method exists to avoid — better to
		// stack at the right edge and be slightly cramped than to overlap somebody.
		return toTheLeft >= 0 ? toTheLeft : Math.max(0, headerWidth - WIDTH - RIGHT_INSET);
	}
}
