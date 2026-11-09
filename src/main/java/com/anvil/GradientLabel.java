package com.anvil;

import lombok.Setter;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.Collections;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * A label whose text is painted with a left-to-right gradient (an Anvil Supporter's name). The icon
 * keeps its own colours (a team swatch is information) unless it's the supporter star, which the
 * gradient runs on through as the name's last letter. Swing has no gradient text, so the label
 * is drawn once to an image at the screen's scale (redrawn only when its size, text, font or scale
 * change) and each frame fills the text box through that image's pixels into a reused buffer.
 * A moving one (Patron) slides its gradient along: one shared timer repaints only those on screen.
 */
final class GradientLabel extends JLabel {
	/** Side-panel labels look faster at the same rate because they are compact; keep them at five seconds. */
	static final long PANEL_CYCLE_MS = 5000;
	/** RuneScape chat and widget names use the same calm five-second cycle as the side panel. */
	static final long GAME_CYCLE_MS = 5000;
	private static final Set<GradientLabel> MOVING = Collections.newSetFromMap(new WeakHashMap<>());
	/** The player's "Animated names" setting: off, a moving gradient holds still. */
	@Setter static volatile boolean animate = true;
	private static final Timer TICK = new Timer(40, e -> { if (animate && clientActive()) MOVING.forEach(l -> { if (l.isShowing()) l.repaint(); }); });

	/**
	 * Whether the client window is in front. WINDOW focus on purpose: RuneLite's FocusChanged follows
	 * the game canvas, so clicking the side panel would pause the panel's own gradients. Behind
	 * another app or minimised, moving names hold still to spare weak PCs; the phase stays on the
	 * wall clock, so on refocus they pick up where they'd be with no reset.
	 */
	static boolean clientActive() {
		// A field read, never the focus manager itself: its getters lock KeyboardFocusManager.class,
		// which the EDT holds while it handles focus/window events, and this is asked several times a
		// frame from the client thread — that wait showed up as a 2-3 frame hitch every few seconds.
		return ACTIVE.active;
	}

	/** The active-window state, kept by a focus-manager listener on the EDT. */
	private static final class ActiveWindow {
		volatile boolean active = true;

		ActiveWindow() {
			SwingUtilities.invokeLater(() -> {
				var kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager();
				active = kfm.getActiveWindow() != null || GraphicsEnvironment.isHeadless();
				kfm.addPropertyChangeListener("activeWindow", e -> active = e.getNewValue() != null);
			});
		}
	}

	private static final ActiveWindow ACTIVE = new ActiveWindow();

	/** Played animation time: starts at the wall clock (clients begin in step) and runs only while in front. */
	private static long playedMs = System.currentTimeMillis();
	private static long lastWallMs = playedMs;

	/**
	 * Shared phase clock for Swing labels and game widgets (EDT and client thread). It never resets on
	 * a panel rebuild, and it stands still while the client window is in the background, so names
	 * carry on from where they stopped instead of jumping to a new spot in the cycle.
	 */
	static synchronized long animationTimeMillis() {
		if (!animate) return 0;
		var now = System.currentTimeMillis();
		var dt = now - lastWallMs;
		lastWallMs = now;
		if (dt > 0 && clientActive()) {
			// Clamped: a long gap between frames (a stall, a hidden panel) never fast-forwards.
			playedMs += Math.min(dt, 250);
		}
		return playedMs;
	}

	private final Color[] stops;
	private final float[] fractions;
	private final boolean moving;

	// The label as Swing draws it, plus a same-size buffer each frame is composed in.
	private BufferedImage base;
	private BufferedImage frame;
	private final Rectangle textR = new Rectangle();
	private final Rectangle iconR = new Rectangle();
	/** The supporter star takes the gradient too; any other icon keeps its colours. */
	private boolean tintIcon;
	/** What the gradient fills: the text, or the text and the star. */
	private final Rectangle fillR = new Rectangle();
	private String baseKey;

	/** Paint the icon with the gradient too (the supporter star), not only the text. */
	void setTintIcon(boolean tintIcon) {
		this.tintIcon = tintIcon;
		baseKey = null;
		repaint();
	}

	GradientLabel(Color[] stops, boolean moving) {
		this.stops = stops;
		this.moving = moving;
		this.fractions = new float[stops.length];
		for (int i = 0; i < stops.length; i++) fractions[i] = stops.length == 1 ? 0f : (float) i / (stops.length - 1);
	}

	@Override
	public void addNotify() {
		super.addNotify();
		if (moving) {
			MOVING.add(this);
			TICK.start();
		}
	}

	@Override
	public void removeNotify() {
		super.removeNotify();
		MOVING.remove(this);
		if (MOVING.isEmpty()) TICK.stop();
		base = null;
		frame = null;
		baseKey = null;
	}

	@Override
	protected void paintComponent(Graphics g) {
		int w = getWidth();
		int h = getHeight();
		if (stops.length < 2 || w <= 0 || h <= 0 || isOpaque() || getText() == null || getText().isEmpty()) {
			super.paintComponent(g);
			return;
		}
		var g2d = (Graphics2D) g;
		var at = g2d.getTransform();
		var sx = Math.max(1, at.getScaleX());
		var sy = Math.max(1, at.getScaleY());
		int iw = (int) Math.ceil(w * sx);
		int ih = (int) Math.ceil(h * sy);

		var key = w + "x" + h + "@" + sx + "," + sy + "|" + getText() + "|" + getFont() + "|" + getForeground()
			+ "|" + System.identityHashCode(getIcon()) + "|" + getInsets();
		if (base == null || !key.equals(baseKey)) {
			drawBase(g2d, w, h, iw, ih, sx, sy);
			baseKey = key;
		}

		var fg = frame.createGraphics();
		try {
			fg.setComposite(AlphaComposite.Src);
			fg.drawImage(base, 0, 0, null);
			if (fillR.width > 0) {
				fg.scale(sx, sy);
				fg.setComposite(AlphaComposite.SrcIn);
				// REFLECT repeats every two widths, so a shift over that whole period wraps seamlessly.
				var period = 2f * fillR.width;
				var shift = moving && animate ? (animationTimeMillis() % PANEL_CYCLE_MS) / (float) PANEL_CYCLE_MS * period : 0;
				fg.setPaint(new LinearGradientPaint(fillR.x - shift, 0, fillR.x - shift + fillR.width, 0, fractions, stops,
					MultipleGradientPaint.CycleMethod.REFLECT));
				fg.fillRect(fillR.x, fillR.y, fillR.width, fillR.height);
			}
		} finally {
			fg.dispose();
		}

		var g2 = (Graphics2D) g.create();
		try {
			g2.scale(1 / sx, 1 / sy);
			g2.drawImage(frame, 0, 0, null);
		} finally {
			g2.dispose();
		}
	}

	private void drawBase(Graphics2D g, int w, int h, int iw, int ih, double sx, double sy) {
		if (base == null || base.getWidth() != iw || base.getHeight() != ih) {
			base = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB);
			frame = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB);
		}
		var ig = base.createGraphics();
		try {
			ig.setComposite(AlphaComposite.Clear);
			ig.fillRect(0, 0, iw, ih);
			ig.setComposite(AlphaComposite.SrcOver);
			// Swing hands paintComponent a graphics already set to the label's font, colour and hints.
			ig.setRenderingHints(g.getRenderingHints());
			ig.scale(sx, sy);
			ig.setFont(getFont());
			ig.setColor(getForeground());
			super.paintComponent(ig);
		} finally {
			ig.dispose();
		}
		var in = getInsets();
		var view = new Rectangle(in.left, in.top, w - in.left - in.right, h - in.top - in.bottom);
		textR.setBounds(0, 0, 0, 0);
		iconR.setBounds(0, 0, 0, 0);
		SwingUtilities.layoutCompoundLabel(this, getFontMetrics(getFont()), getText(), getIcon(),
			getVerticalAlignment(), getHorizontalAlignment(), getVerticalTextPosition(), getHorizontalTextPosition(),
			view, iconR, textR, getIconTextGap());
		fillR.setBounds(tintIcon && getIcon() != null && !iconR.isEmpty() ? textR.union(iconR) : textR);
	}
}
