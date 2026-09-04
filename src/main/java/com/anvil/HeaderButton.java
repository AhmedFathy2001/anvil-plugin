package com.anvil;

import java.util.function.BooleanSupplier;

import net.runelite.api.Client;
import net.runelite.api.FontID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;

/**
 * An "Anvil" button in a game interface's title bar — the collection log's, beside WikiSync and
 * RuneProfile, and the clan window's, beside Wise Old Man's.
 *
 * <p>WHY BUTTONS AND NOT THE TAB WE DELETED. The in-game clog TAB went in 5d2e425 — four thousand
 * lines of hand-drawn widgets nobody read, sitting between the side panel (progress at a glance) and
 * the site (real search). These are not that: one control each, in the place the player already is,
 * doing what the sidebar already does, for somebody whose hands are in the game interface rather
 * than in RuneLite's.
 *
 * <p>DRAWN AS A BUTTON, NOT AS A WORD. The first version was a bare text widget, which read as a
 * stray label rather than something to click — its neighbours have a raised stone frame and a hover
 * state, and beside them a floating "Anvil" looks like a rendering fault. So this is the game's own
 * button: the V2 stone button's nine sprites (a backing, four corners, four edges) with the text
 * laid over the whole area carrying the listeners, swapping to the pressed-in set on hover. There is
 * essentially one right answer here and every plugin with a button in these bars has found it — the
 * sprites are the same ids whether a codebase spells them the legacy way (WORLD_MAP_BUTTON_*,
 * EQUIPMENT_BUTTON_*_HOVERED) or the generated way used here.
 *
 * <p>POSITIONED FROM THE RIGHT EDGE, not off a measured neighbour. Everything in these bars is
 * anchored right — the close button, then each plugin's button walking left — so an absolute-right
 * offset lands in the same slot at any window size, where arithmetic off another plugin's
 * coordinates moves the moment they move theirs.
 *
 * <p>SURVIVES A NEIGHBOUR REBUILDING THE BAR. WikiSync clears every dynamic child of this container
 * before adding its own, on the same script we draw on, so whether our button exists comes down to
 * who ran last — which is plugin load order, and nobody's to rely on. The answer is not to fight it:
 * we keep hold of what we made, notice when it is no longer attached, and build it again.
 */
final class HeaderButton
{
	/** Matches the native header buttons: small, and tall enough to hit comfortably. */
	private static final int WIDTH = 71;
	private static final int FALLBACK_HEIGHT = 20;
	/** The dimensions the nine-slice sprites are cut at. Stretching a corner is what looks wrong. */
	private static final int CORNER = 9;
	private static final int EDGE = 4;

	/** The stone button as it sits: raised, on the light backing. */
	private static final int[] SPRITES_IDLE = {
		SpriteID.TRADEBACKING,
		SpriteID.V2StoneButtonOut.A_TOP_LEFT,
		SpriteID.V2StoneButtonOut.A_TOP_RIGHT,
		SpriteID.V2StoneButtonOut.A_BOTTOM_LEFT,
		SpriteID.V2StoneButtonOut.A_BOTTOM_RIGHT,
		SpriteID.V2StoneButtonOut.A_MAP_EDGE_LEFT,
		SpriteID.V2StoneButtonOut.A_MAP_EDGE_TOP,
		SpriteID.V2StoneButtonOut.A_MAP_EDGE_RIGHT,
		SpriteID.V2StoneButtonOut.A_MAP_EDGE_BOTTOM,
	};

	/** The same button pressed in, on the dark backing — the game's own hover state for it. */
	private static final int[] SPRITES_HOVERED = {
		SpriteID.TRADEBACKING_DARK,
		SpriteID.V2StoneButtonIn.A_TOP_LEFT,
		SpriteID.V2StoneButtonIn.A_TOP_RIGHT,
		SpriteID.V2StoneButtonIn.A_BOTTOM_LEFT,
		SpriteID.V2StoneButtonIn.A_BOTTOM_RIGHT,
		SpriteID.V2StoneButtonIn.A_LEFT,
		SpriteID.V2StoneButtonIn.A_TOP,
		SpriteID.V2StoneButtonIn.A_RIGHT,
		SpriteID.V2StoneButtonIn.A_BOTTOM,
	};

	/** The frame's nine pieces, then the text on top. */
	private static final int PARTS = 10;
	private static final int TEXT = 9;

	private static final int TEXT_IDLE = 0xd6d6d6;
	private static final int TEXT_HOVERED = 0xffffff;

	/**
	 * The menu TARGET. The game builds an entry as "&lt;action&gt; &lt;name&gt;", which is why the
	 * action passed in is a verb alone — spelling it in both produced "Sync to Anvil Anvil".
	 */
	private static final String NAME = "Anvil";

	private final Client client;
	/** The container to draw in — the one the neighbouring buttons use, so we share its space. */
	private final int parentId;
	/** A native control in that bar, read for the row's height and vertical anchoring. */
	private final int anchorId;
	/** Distance from the bar's right edge to our left edge, in the slot left of our neighbours. */
	private final int rightOffset;
	private final String label;
	/** The verb alone; the game appends {@link #NAME} to it. */
	private final String action;
	/** Whether syncing is possible right now — no site or no token means no button. */
	private final BooleanSupplier enabled;
	private final Runnable onClick;

	/** What we drew last time. Null until the first render, stale after a neighbour clears the bar. */
	private Widget[] parts;

	HeaderButton(Client client, int parentId, int anchorId, int rightOffset, String label, String action,
				 BooleanSupplier enabled, Runnable onClick)
	{
		this.client = client;
		this.parentId = parentId;
		this.anchorId = anchorId;
		this.rightOffset = rightOffset;
		this.label = label;
		this.action = action;
		this.enabled = enabled;
		this.onClick = onClick;
	}

	/**
	 * Draw the button, or leave alone the one already there.
	 *
	 * <p>Safe to call on every draw of the interface. Must run on the client thread — and, because of
	 * the neighbour that clears this container, wants to run AFTER every other subscriber to the
	 * script has had its turn.
	 */
	void render()
	{
		Widget parent = client.getWidget(parentId);
		Widget anchor = client.getWidget(anchorId);
		if (parent == null || anchor == null || !enabled.getAsBoolean())
		{
			return;
		}
		if (stillAttached(parent))
		{
			return; // ours, intact, in place — drawing again would only make a second one
		}

		final int h = anchor.getOriginalHeight() > 0 ? anchor.getOriginalHeight() : FALLBACK_HEIGHT;
		final int y = anchor.getOriginalY();
		final int x = rightOffset;
		final int yMode = anchor.getYPositionMode();
		final int span = WIDTH - (CORNER * 2);
		final Widget[] made = new Widget[PARTS];

		// Background first so every edge draws over it, then the corners, then the edges between.
		made[0] = piece(parent, 0, x, y, WIDTH, h, yMode);
		made[1] = piece(parent, 1, x + WIDTH - CORNER, y, CORNER, CORNER, yMode);
		made[2] = piece(parent, 2, x, y, CORNER, CORNER, yMode);
		made[3] = piece(parent, 3, x + WIDTH - CORNER, y + h - CORNER, CORNER, CORNER, yMode);
		made[4] = piece(parent, 4, x, y + h - CORNER, CORNER, CORNER, yMode);
		made[5] = piece(parent, 5, x + WIDTH - CORNER, y + CORNER, CORNER, EDGE, yMode);
		made[7] = piece(parent, 7, x, y + CORNER, CORNER, EDGE, yMode);
		made[6] = piece(parent, 6, x + CORNER, y, span, CORNER, yMode);
		made[8] = piece(parent, 8, x + CORNER, y + h - CORNER, span, CORNER, yMode);

		// The text covers the whole button, so it is what the mouse actually meets: one layer owns
		// the click, the hover and the label, and the nine underneath are decoration.
		Widget text = parent.createChild(-1, WidgetType.TEXT);
		text.setName(NAME);
		text.setText(label);
		text.setTextColor(TEXT_IDLE);
		text.setFontId(FontID.PLAIN_11);
		text.setTextShadowed(true);
		text.setXPositionMode(WidgetPositionMode.ABSOLUTE_RIGHT);
		text.setYPositionMode(yMode);
		text.setXTextAlignment(WidgetTextAlignment.CENTER);
		text.setYTextAlignment(WidgetTextAlignment.CENTER);
		text.setOriginalX(x);
		text.setOriginalY(y);
		text.setOriginalWidth(WIDTH);
		text.setOriginalHeight(h);
		text.setHasListener(true);
		text.setAction(0, action);
		text.setOnOpListener((JavaScriptCallback) e -> onClick.run());
		text.setOnMouseOverListener((JavaScriptCallback) e -> paint(made, SPRITES_HOVERED, TEXT_HOVERED));
		text.setOnMouseLeaveListener((JavaScriptCallback) e -> paint(made, SPRITES_IDLE, TEXT_IDLE));
		text.revalidate();
		made[TEXT] = text;

		parts = made;
		parent.revalidate();
	}

	/** One piece of the frame, by its index into the sprite sets. */
	private Widget piece(Widget parent, int index, int x, int y, int w, int h, int yMode)
	{
		Widget piece = parent.createChild(-1, WidgetType.GRAPHIC);
		piece.setName(NAME);
		piece.setSpriteId(SPRITES_IDLE[index]);
		piece.setXPositionMode(WidgetPositionMode.ABSOLUTE_RIGHT);
		piece.setYPositionMode(yMode);
		piece.setOriginalX(x);
		piece.setOriginalY(y);
		piece.setOriginalWidth(w);
		piece.setOriginalHeight(h);
		piece.revalidate();
		return piece;
	}

	/** Swap the frame between its idle and hovered sprite sets. */
	private static void paint(Widget[] made, int[] sprites, int textColour)
	{
		for (int i = 0; i < TEXT; i++)
		{
			if (made[i] != null)
			{
				made[i].setSpriteId(sprites[i]);
			}
		}
		if (made[TEXT] != null)
		{
			made[TEXT].setTextColor(textColour);
		}
	}

	/**
	 * Is the button we last drew still a child of this bar?
	 *
	 * <p>Identity, not a name lookup. After a neighbour clears the container our widgets are still
	 * perfectly good objects — just no longer attached to anything — so the question is whether the
	 * bar still holds the one we made, which answers both "were we wiped" and "did the interface
	 * rebuild underneath us".
	 */
	private boolean stillAttached(Widget parent)
	{
		if (parts == null || parts[TEXT] == null)
		{
			return false;
		}
		Widget[] children = parent.getDynamicChildren();
		if (children == null)
		{
			return false;
		}
		for (Widget w : children)
		{
			if (w == parts[TEXT])
			{
				return true;
			}
		}
		return false;
	}
}
