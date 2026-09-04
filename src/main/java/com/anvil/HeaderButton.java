package com.anvil;

import java.util.function.BooleanSupplier;

import net.runelite.api.Client;
import net.runelite.api.FontID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;

/**
 * An "Anvil" button injected into a game interface's TITLE BAR — the collection log's, beside
 * WikiSync and RuneProfile, and the clan window's, beside Wise Old Man's.
 *
 * WHY BUTTONS AND NOT THE TAB WE DELETED. The in-game clog TAB went in 5d2e425 — four thousand lines
 * of hand-drawn widgets nobody read, sitting between the side panel (progress at a glance) and the
 * site (real search). These are not that: one control each, in the place the player already is,
 * doing what the sidebar already does, for somebody whose hands are in the game interface rather
 * than in RuneLite's.
 *
 * PLACED RELATIVE TO WHAT IS ALREADY THERE, never at a fixed offset. Two other hub plugins put
 * buttons in this exact bar, and Jagex rearranges interfaces on game updates. So the position is
 * computed at draw time: start just left of the window's CLOSE button — the one part of a title bar
 * that is always at its right edge — then keep stepping left past any dynamic child we would land
 * on. Dynamic children are exactly everybody's injected buttons, ours included; the interface's own
 * parts are static. If the bar is missing, the button simply does not appear. A missing button is a
 * small disappointment; one drawn on top of WikiSync's — or on the close button — is a bug report
 * from somebody else's users.
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

	/**
	 * The menu TARGET, and how we identify our own child across redraws so a re-render moves it
	 * instead of adding a second.
	 *
	 * <p>The game builds a menu entry as "&lt;action&gt; &lt;name&gt;", which is why the action reads
	 * "Sync to" rather than "Sync to Anvil" — the two used to be concatenated into "Sync to Anvil
	 * Anvil".
	 */
	private static final String NAME = "Anvil";

	private final Client client;
	/** The title bar to inject into — a gameval component, so a client update carries it with us. */
	private final int componentId;
	/** That bar's close button, the anchor we place ourselves to the left of. */
	private final int closeComponentId;
	private final String label;
	/** The verb alone; the game appends {@link #NAME} to it. */
	private final String action;
	/** Whether syncing is possible right now — no site or no token means no button. */
	private final BooleanSupplier enabled;
	private final Runnable onClick;

	HeaderButton(Client client, int componentId, int closeComponentId, String label, String action,
				 BooleanSupplier enabled, Runnable onClick)
	{
		this.client = client;
		this.componentId = componentId;
		this.closeComponentId = closeComponentId;
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
	 * An x that lands on nobody: not the close button, not another plugin's.
	 *
	 * <p>The close button is the fixed point. Every one of these title bars has one, it is always at
	 * the right end, and it is the thing a misplaced button covers most expensively — a player who
	 * cannot shut the collection log will remember which plugin did that. So we start immediately to
	 * its left and walk further left past anything we would overlap.
	 *
	 * <p>What we step past is the bar's DYNAMIC children, which is exactly the set of injected
	 * buttons — WikiSync's, RuneProfile's, Wise Old Man's, ours — because an interface's own parts
	 * are static. Load order stops mattering: whoever draws second sees the first and steps around
	 * it. The walk is bounded, and gives up by returning the initial slot rather than looping on a
	 * pathological layout; being slightly cramped beats not drawing at all.
	 */
	private int placeClearOf(Widget bar, Widget self)
	{
		Widget close = client.getWidget(closeComponentId);
		int rightEdge = close != null && !close.isSelfHidden()
			? close.getOriginalX()
			: Math.max(0, bar.getWidth() - RIGHT_INSET);

		Widget[] children = bar.getDynamicChildren();
		int count = 0;
		int[] lefts = new int[children == null ? 0 : children.length];
		int[] widths = new int[lefts.length];
		if (children != null)
		{
			for (Widget w : children)
			{
				if (w == null || w == self || w.isSelfHidden())
				{
					continue;
				}
				lefts[count] = w.getOriginalX();
				widths[count] = w.getWidth();
				count++;
			}
		}
		return slotLeftOf(rightEdge, lefts, widths, count);
	}

	/**
	 * The x of the first free slot walking LEFT from {@code rightEdge}, given the neighbours to
	 * avoid. Pure arithmetic, so the placement rule is unit-tested without a game client
	 * (HeaderButtonTest) — the part that reads widgets is the caller's.
	 *
	 * <p>Each step clears at least one neighbour, so the walk terminates in at most one pass per
	 * neighbour. Running out of room to the left returns the starting slot rather than a negative x:
	 * being slightly cramped beats being pinned to the bar's left edge, straight through its title.
	 */
	static int slotLeftOf(int rightEdge, int[] lefts, int[] widths, int count)
	{
		int start = rightEdge - WIDTH - GAP;
		int x = start;
		for (int guard = count; guard >= 0; guard--)
		{
			int blockedBy = -1;
			for (int i = 0; i < count; i++)
			{
				if (x < lefts[i] + widths[i] && lefts[i] < x + WIDTH)
				{
					blockedBy = i;
					break;
				}
			}
			if (blockedBy < 0)
			{
				return Math.max(0, x);
			}
			x = lefts[blockedBy] - WIDTH - GAP;
			if (x < 0)
			{
				break;
			}
		}
		return Math.max(0, start);
	}
}
