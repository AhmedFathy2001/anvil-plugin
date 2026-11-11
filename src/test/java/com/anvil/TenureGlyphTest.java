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
			assertEquals(15, img.getWidth());
			assertEquals(13, img.getHeight());
			java.util.Set<Integer> tones = new java.util.HashSet<>();
			StringBuilder px = new StringBuilder();
			for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < 15; x++)
			{
				int p = img.getRGB(x, y), a = p >>> 24;
				// The game keeps solid pixels only: every pixel is all or nothing.
				assertTrue(a == 0 || a == 255);
				if (x >= 13) assertEquals("the gap before the name", 0, a);
				if (a > 0) tones.add(p);
				px.append(Integer.toHexString(p)).append(',');
			}
			assertTrue("shaded: outline, body and highlights", tones.size() >= 3);
			assertTrue(seen.add(px.toString()));
		}
		// The Legend's glint moves a highlight across it.
		boolean differs = false;
		for (int y = 0; y < 13; y++) for (int x = 0; x < 15; x++)
			differs |= AnvilPlugin.tenureSprite(6, 0x4da3ff, 1).getRGB(x, y) != AnvilPlugin.tenureSprite(6, 0x4da3ff, -1).getRGB(x, y);
		assertTrue(differs);
		assertNotEquals(AnvilPlugin.tenureSprite(6, 0x4da3ff, -1).getRGB(6, 4), AnvilPlugin.tenureSprite(6, 0xe05ad0, -1).getRGB(6, 4));
	}

	@Test
	public void neverAModsGoldOrSilver()
	{
		for (int ink : new int[]{0xffd35c, 0xffff00, 0xffffff, 0xc0c0c0, 0x808080})
		{
			int c = AnvilPlugin.tenureInk(ink);
			float[] h = java.awt.Color.RGBtoHSB(c >> 16 & 255, c >> 8 & 255, c & 255, null);
			assertTrue(Integer.toHexString(ink), h[1] > 0.3f && !(h[0] > 0.115f && h[0] < 0.19f));
		}
		// Smooth: neighbouring colours (a moving gradient's frames) stay neighbours.
		for (int v = 0; v < 255; v++)
		{
			int a = AnvilPlugin.tenureInk(0x808080 + v / 4 * 0x010000), b = AnvilPlugin.tenureInk(0x808080 + (v / 4 + 1) * 0x010000);
			assertTrue(Math.abs((a >> 16 & 255) - (b >> 16 & 255)) + Math.abs((a >> 8 & 255) - (b >> 8 & 255)) + Math.abs((a & 255) - (b & 255)) < 24);
		}
		assertEquals(0xe05ad0, AnvilPlugin.tenureInk(0xe05ad0));
	}
}
