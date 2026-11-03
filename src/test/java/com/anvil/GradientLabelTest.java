package com.anvil;

import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.swing.SwingConstants;
import org.junit.Test;

public class GradientLabelTest
{
	/** The colours painted in the label's right-most quarter, where the star sits after the name. */
	private static boolean starIsYellow(boolean tint)
	{
		GradientLabel l = new GradientLabel(new Color[]{Color.RED, Color.BLUE}, false);
		l.setText("Abc");
		l.setForeground(Color.RED);
		l.setIcon(new GlyphIcon(GlyphIcon.Shape.STAR, 10, new Color(0xff, 0xd3, 0x5c)));
		l.setHorizontalTextPosition(SwingConstants.LEADING);
		l.setIconTextGap(4);
		l.setTintIcon(tint);
		Dimension d = l.getPreferredSize();
		l.setSize(d);
		BufferedImage img = new BufferedImage(d.width, d.height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		l.paint(g);
		g.dispose();
		boolean yellow = false;
		for (int x = d.width - 10; x < d.width; x++)
		{
			for (int y = 0; y < d.height; y++)
			{
				Color c = new Color(img.getRGB(x, y), true);
				if (c.getAlpha() > 200 && c.getRed() > 200 && c.getGreen() > 150 && c.getBlue() < 140)
				{
					yellow = true;
				}
			}
		}
		return yellow;
	}

	@Test
	public void theStarTakesTheGradientOnlyWhenAsked()
	{
		assertTrue("an ordinary icon keeps its colours", starIsYellow(false));
		assertTrue("the supporter star is painted by the gradient", !starIsYellow(true));
	}
}
