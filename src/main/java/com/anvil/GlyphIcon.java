package com.anvil;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import javax.swing.Icon;

/**
 * The sidebar's small symbols — chevrons, the external-link arrow, a tick, a cross, an ellipsis —
 * drawn as vector shapes instead of typed as characters.
 *
 * <p>Typed, they depended on the font: the RuneScape font has none of ‹ › ↗ ✓ ⋯, so each platform
 * substituted whatever its own fallback had. On macOS that was often nothing (an empty box or a
 * blank), and where a glyph did appear it sat on the text baseline, below the middle of the words
 * beside it. Drawn, they look the same everywhere, and Swing centres an icon against its label.
 *
 * <p>The colour follows the component's foreground (a hovered button's orange, a done tick's green)
 * unless one is fixed at construction.
 */
final class GlyphIcon implements Icon
{
	enum Shape
	{
		CHEVRON_LEFT, CHEVRON_RIGHT, EXTERNAL, CHECK, CLOSE, ELLIPSIS, GEAR
	}

	private final Shape shape;
	private final int size;
	private final Color color;

	GlyphIcon(Shape shape, int size)
	{
		this(shape, size, null);
	}

	GlyphIcon(Shape shape, int size, Color color)
	{
		this.shape = shape;
		this.size = size;
		this.color = color;
	}

	@Override
	public int getIconWidth()
	{
		return size;
	}

	@Override
	public int getIconHeight()
	{
		return size;
	}

	@Override
	public void paintIcon(Component c, Graphics graphics, int x, int y)
	{
		Graphics2D g = (Graphics2D) graphics.create();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			Color ink = color != null ? color : (c != null ? c.getForeground() : Color.WHITE);
			if (c != null && !c.isEnabled())
			{
				ink = ink.darker();
			}
			g.setColor(ink);
			float s = size;
			g.setStroke(new BasicStroke(Math.max(1.4f, s / 7f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.translate(x, y);
			Path2D p = new Path2D.Float();
			switch (shape)
			{
				case CHEVRON_LEFT:
					g.draw(stroke(p, s, 0.62, 0.22, 0.34, 0.5, 0.62, 0.78));
					break;
				case CHEVRON_RIGHT:
					g.draw(stroke(p, s, 0.38, 0.22, 0.66, 0.5, 0.38, 0.78));
					break;
				case EXTERNAL:
					stroke(p, s, 0.28, 0.72, 0.72, 0.28);
					g.draw(stroke(p, s, 0.4, 0.28, 0.72, 0.28, 0.72, 0.6));
					break;
				case CHECK:
					g.draw(stroke(p, s, 0.2, 0.52, 0.42, 0.74, 0.8, 0.28));
					break;
				case CLOSE:
					stroke(p, s, 0.28, 0.28, 0.72, 0.72);
					g.draw(stroke(p, s, 0.72, 0.28, 0.28, 0.72));
					break;
				case ELLIPSIS:
					float d = Math.max(2f, s / 6f);
					for (int i = 0; i < 3; i++)
					{
						float cx = s * (0.25f + 0.25f * i);
						g.fill(new java.awt.geom.Ellipse2D.Float(cx - d / 2, s * 0.5f - d / 2, d, d));
					}
					break;
				case GEAR:
					// A ring with eight short teeth — the settings mark everyone reads without a label.
					float cx = s / 2f;
					float r = s * 0.24f;
					g.draw(new java.awt.geom.Ellipse2D.Float(cx - r, cx - r, r * 2, r * 2));
					for (int i = 0; i < 8; i++)
					{
						double a = Math.PI / 4 * i;
						p.moveTo(cx + Math.cos(a) * s * 0.33, cx + Math.sin(a) * s * 0.33);
						p.lineTo(cx + Math.cos(a) * s * 0.44, cx + Math.sin(a) * s * 0.44);
					}
					g.draw(p);
					break;
				default:
					break;
			}
		}
		finally
		{
			g.dispose();
		}
	}

	/** Adds one polyline to {@code p}: x,y pairs as fractions of the icon's size {@code s}. */
	private static Path2D stroke(Path2D p, float s, double... xy)
	{
		p.moveTo(s * xy[0], s * xy[1]);
		for (int i = 2; i < xy.length; i += 2)
		{
			p.lineTo(s * xy[i], s * xy[i + 1]);
		}
		return p;
	}
}
