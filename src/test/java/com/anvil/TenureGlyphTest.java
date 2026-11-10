package com.anvil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import org.junit.Test;

public class TenureGlyphTest
{
	private static BufferedImage draw(GlyphIcon.Shape shape, int glint)
	{
		BufferedImage img = new BufferedImage(11, 11, BufferedImage.TYPE_INT_ARGB);
		GlyphIcon icon = new GlyphIcon(shape, 11, new Color(0xffd35c));
		icon.glint = glint;
		java.awt.Graphics2D g = img.createGraphics();
		icon.paintIcon(null, g, 0, 0);
		g.dispose();
		return img;
	}

	@Test
	public void eachTenureShapeDrawsSomethingDifferent()
	{
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (GlyphIcon.Shape s : new GlyphIcon.Shape[]{GlyphIcon.Shape.STAR, GlyphIcon.Shape.STAR_RING, GlyphIcon.Shape.STAR_HEX, GlyphIcon.Shape.HEX})
		{
			BufferedImage img = draw(s, -1);
			StringBuilder px = new StringBuilder();
			for (int y = 0; y < 11; y++) for (int x = 0; x < 11; x++) px.append(img.getRGB(x, y)).append(',');
			assertTrue(s + " is blank", px.toString().replace("0,", "").length() > 0);
			assertTrue(s + " looks like another shape", seen.add(px.toString()));
		}
		assertEquals(GlyphIcon.Shape.STAR_RING, UiView.glyph("star-ring"));
		assertEquals(GlyphIcon.Shape.STAR_HEX, UiView.glyph("star-hex"));
		assertEquals(GlyphIcon.Shape.HEX, UiView.glyph("hex"));
	}

	@Test
	public void legendHexHasARealHoleAndGlints()
	{
		BufferedImage plain = draw(GlyphIcon.Shape.HEX, -1);
		// The star in the middle is cut out (transparent), not painted dark: the panel tint keeps alpha.
		assertTrue((plain.getRGB(5, 5) >>> 24) < 80);
		assertTrue((plain.getRGB(5, 1) >>> 24) > 200);
		boolean differs = false;
		for (int y = 0; y < 11; y++) for (int x = 0; x < 11; x++) differs |= draw(GlyphIcon.Shape.HEX, 1).getRGB(x, y) != plain.getRGB(x, y);
		assertTrue(differs);
		assertNotEquals(draw(GlyphIcon.Shape.HEX, 0).getRGB(1, 3), draw(GlyphIcon.Shape.HEX, 3).getRGB(1, 3));
	}

	@Test
	public void chatSpritesAreSolidPixelArtWithAGapAndAGlint()
	{
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (int t = 1; t <= 4; t++)
		{
			BufferedImage img = AnvilPlugin.tenureSprite(t, 0x4da3ff, -1);
			assertEquals(13, img.getWidth());
			StringBuilder px = new StringBuilder();
			for (int y = 0; y < 11; y++) for (int x = 0; x < 13; x++)
			{
				int a = img.getRGB(x, y) >>> 24;
				// The game keeps solid pixels only: every pixel is all or nothing.
				assertTrue(a == 0 || a == 255);
				if (x >= 11) assertEquals("the gap before the name", 0, a);
				px.append(a == 0 ? '.' : '#');
			}
			assertTrue(seen.add(px.toString()));
		}
		assertNotEquals(AnvilPlugin.tenureSprite(4, 0x4da3ff, 1).getRGB(5, 2), AnvilPlugin.tenureSprite(4, 0x4da3ff, -1).getRGB(5, 2));
	}
}
