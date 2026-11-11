package com.anvil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import org.junit.Test;

public class TenureGlyphTest
{
	@Test
	public void panelGlyphNamesMapToTheSixSteps()
	{
		assertEquals(1, UiView.tenureStep("tenure-1"));
		assertEquals(6, UiView.tenureStep("tenure-6"));
		assertEquals(0, UiView.tenureStep("tenure-7"));
		assertEquals(0, UiView.tenureStep("star"));
		assertEquals(0, UiView.tenureStep(null));
	}

	@Test
	public void chatSpritesAreSolidShadedAndAllDifferent()
	{
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (int t = 1; t <= AnvilPlugin.TENURE_STEPS; t++)
		{
			BufferedImage img = AnvilPlugin.tenureSprite(t, 0x4da3ff, -1);
			assertEquals(13, img.getWidth());
			java.util.Set<Integer> tones = new java.util.HashSet<>();
			StringBuilder px = new StringBuilder();
			for (int y = 0; y < 12; y++) for (int x = 0; x < 13; x++)
			{
				int p = img.getRGB(x, y), a = p >>> 24;
				// The game keeps solid pixels only: every pixel is all or nothing.
				assertTrue(a == 0 || a == 255);
				if (x >= 11 || y == 0) assertEquals("the gap before the name, the row above", 0, a);
				if (a > 0) tones.add(p);
				px.append(Integer.toHexString(p)).append(',');
			}
			assertEquals("outline, body and highlight", 3, tones.size());
			assertTrue(seen.add(px.toString()));
		}
		// The Legend's glint moves a highlight across it.
		boolean differs = false;
		for (int y = 0; y < 12; y++) for (int x = 0; x < 13; x++)
			differs |= AnvilPlugin.tenureSprite(6, 0x4da3ff, 1).getRGB(x, y) != AnvilPlugin.tenureSprite(6, 0x4da3ff, -1).getRGB(x, y);
		assertTrue(differs);
		assertNotEquals(AnvilPlugin.tenureSprite(6, 0x4da3ff, -1).getRGB(5, 5), AnvilPlugin.tenureSprite(6, 0xffd35c, -1).getRGB(5, 5));
	}
}
