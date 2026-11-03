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
 * is drawn to an image at the screen's scale and the text box is filled through its own pixels.
 * A moving one (Patron) slides its gradient along: one shared timer repaints only those on screen.
 */
final class GradientLabel extends JLabel
{
	private static final Set<GradientLabel> MOVING = Collections.newSetFromMap(new WeakHashMap<>());
	private static final Timer TICK = new Timer(66, e -> MOVING.forEach(l -> { if (l.isShowing()) l.repaint(); }));

	private final Color[] stops;
	private final float[] fractions;
	private final boolean moving;
	private boolean tintIcon;

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

	/** Paint the icon with the gradient too (the supporter star), not only the text. */
	void setTintIcon(boolean tintIcon)
	{
		this.tintIcon = tintIcon;
		repaint();
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
		AffineTransform at = ((Graphics2D) g).getTransform();
		double sx = Math.max(1, at.getScaleX());
		double sy = Math.max(1, at.getScaleY());
		BufferedImage img = new BufferedImage((int) Math.ceil(w * sx), (int) Math.ceil(h * sy), BufferedImage.TYPE_INT_ARGB);
		Graphics2D ig = img.createGraphics();
		try
		{
			// Swing hands paintComponent a graphics already set to the label's font, colour and hints.
			ig.setRenderingHints(((Graphics2D) g).getRenderingHints());
			ig.scale(sx, sy);
			ig.setFont(getFont());
			ig.setColor(getForeground());
			super.paintComponent(ig);

			Insets in = getInsets();
			Rectangle view = new Rectangle(in.left, in.top, w - in.left - in.right, h - in.top - in.bottom);
			Rectangle iconR = new Rectangle();
			Rectangle textR = new Rectangle();
			SwingUtilities.layoutCompoundLabel(this, getFontMetrics(getFont()), getText(), getIcon(),
				getVerticalAlignment(), getHorizontalAlignment(), getVerticalTextPosition(), getHorizontalTextPosition(),
				view, iconR, textR, getIconTextGap());
			Rectangle fill = tintIcon && getIcon() != null && !iconR.isEmpty() ? textR.union(iconR) : textR;
			if (fill.width > 0)
			{
				ig.setComposite(AlphaComposite.SrcIn);
				// Moving: one pass across the name every 4s, wrapping (the stops repeat).
				float shift = moving ? (System.currentTimeMillis() % 4000) / 4000f * fill.width : 0;
				ig.setPaint(new LinearGradientPaint(fill.x - shift, 0, fill.x - shift + fill.width, 0, fractions, stops,
					MultipleGradientPaint.CycleMethod.REFLECT));
				ig.fillRect(fill.x, fill.y, fill.width, fill.height);
			}
		}
		finally
		{
			ig.dispose();
		}
		Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			g2.scale(1 / sx, 1 / sy);
			g2.drawImage(img, 0, 0, null);
		}
		finally
		{
			g2.dispose();
		}
	}
}
