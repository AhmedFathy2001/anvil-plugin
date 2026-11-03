package com.anvil;

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
final class GradientLabel extends JLabel
{
	/** One full reflect cycle (there and back) across the name. */
	private static final long CYCLE_MS = 6000;
	private static final Set<GradientLabel> MOVING = Collections.newSetFromMap(new WeakHashMap<>());
	private static final Timer TICK = new Timer(40, e -> MOVING.forEach(l -> { if (l.isShowing()) l.repaint(); }));

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
	void setTintIcon(boolean tintIcon)
	{
		this.tintIcon = tintIcon;
		baseKey = null;
		repaint();
	}

	GradientLabel(Color[] stops, boolean moving)
	{
		this.stops = stops;
		this.moving = moving;
		this.fractions = new float[stops.length];
		for (int i = 0; i < stops.length; i++)
		{
			fractions[i] = stops.length == 1 ? 0f : (float) i / (stops.length - 1);
		}
	}

	@Override
	public void addNotify()
	{
		super.addNotify();
		if (moving)
		{
			MOVING.add(this);
			TICK.start();
		}
	}

	@Override
	public void removeNotify()
	{
		super.removeNotify();
		MOVING.remove(this);
		if (MOVING.isEmpty())
		{
			TICK.stop();
		}
		base = null;
		frame = null;
		baseKey = null;
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		int w = getWidth();
		int h = getHeight();
		if (stops.length < 2 || w <= 0 || h <= 0 || isOpaque() || getText() == null || getText().isEmpty())
		{
			super.paintComponent(g);
			return;
		}
		Graphics2D g2d = (Graphics2D) g;
		AffineTransform at = g2d.getTransform();
		double sx = Math.max(1, at.getScaleX());
		double sy = Math.max(1, at.getScaleY());
		int iw = (int) Math.ceil(w * sx);
		int ih = (int) Math.ceil(h * sy);

		String key = w + "x" + h + "@" + sx + "," + sy + "|" + getText() + "|" + getFont() + "|" + getForeground()
			+ "|" + System.identityHashCode(getIcon()) + "|" + getInsets();
		if (base == null || !key.equals(baseKey))
		{
			drawBase(g2d, w, h, iw, ih, sx, sy);
			baseKey = key;
		}

		Graphics2D fg = frame.createGraphics();
		try
		{
			fg.setComposite(AlphaComposite.Src);
			fg.drawImage(base, 0, 0, null);
			if (fillR.width > 0)
			{
				fg.scale(sx, sy);
				fg.setComposite(AlphaComposite.SrcIn);
				// REFLECT repeats every two widths, so a shift over that whole period wraps seamlessly.
				float period = 2f * fillR.width;
				float shift = moving ? (System.currentTimeMillis() % CYCLE_MS) / (float) CYCLE_MS * period : 0;
				fg.setPaint(new LinearGradientPaint(fillR.x - shift, 0, fillR.x - shift + fillR.width, 0, fractions, stops,
					MultipleGradientPaint.CycleMethod.REFLECT));
				fg.fillRect(fillR.x, fillR.y, fillR.width, fillR.height);
			}
		}
		finally
		{
			fg.dispose();
		}

		Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			g2.scale(1 / sx, 1 / sy);
			g2.drawImage(frame, 0, 0, null);
		}
		finally
		{
			g2.dispose();
		}
	}

	private void drawBase(Graphics2D g, int w, int h, int iw, int ih, double sx, double sy)
	{
		if (base == null || base.getWidth() != iw || base.getHeight() != ih)
		{
			base = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB);
			frame = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB);
		}
		Graphics2D ig = base.createGraphics();
		try
		{
			ig.setComposite(AlphaComposite.Clear);
			ig.fillRect(0, 0, iw, ih);
			ig.setComposite(AlphaComposite.SrcOver);
			// Swing hands paintComponent a graphics already set to the label's font, colour and hints.
			ig.setRenderingHints(g.getRenderingHints());
			ig.scale(sx, sy);
			ig.setFont(getFont());
			ig.setColor(getForeground());
			super.paintComponent(ig);
		}
		finally
		{
			ig.dispose();
		}
		Insets in = getInsets();
		Rectangle view = new Rectangle(in.left, in.top, w - in.left - in.right, h - in.top - in.bottom);
		textR.setBounds(0, 0, 0, 0);
		iconR.setBounds(0, 0, 0, 0);
		SwingUtilities.layoutCompoundLabel(this, getFontMetrics(getFont()), getText(), getIcon(),
			getVerticalAlignment(), getHorizontalAlignment(), getVerticalTextPosition(), getHorizontalTextPosition(),
			view, iconR, textR, getIconTextGap());
		fillR.setBounds(tintIcon && getIcon() != null && !iconR.isEmpty() ? textR.union(iconR) : textR);
	}
}
