package com.anvil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.JLabel;
import org.junit.Test;

public class MotionIconTest
{
	private static byte[] gif(int w, int h) throws Exception
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "gif", out);
		return out.toByteArray();
	}

	@Test
	public void eachConsumerOfACachedGifGetsItsOwnImage() throws Exception
	{
		Icon icon = BingoApiClient.animatedIcon(gif(48, 48), 48, 48);
		assertTrue(icon instanceof BingoApiClient.MotionIcon);
		BingoApiClient.MotionIcon first = (BingoApiClient.MotionIcon) icon;
		BingoApiClient.MotionIcon second = first.copy();
		// A shared Toolkit image restarts blank when another label takes it over.
		assertNotSame(first.getImage(), second.getImage());
		assertEquals(48, second.getIconWidth());
		assertEquals(48, second.getIconHeight());
	}

	@Test
	public void oversizedGifIsStillRefused() throws Exception
	{
		assertNull(BingoApiClient.animatedIcon(gif(96, 96), 48, 48));
	}

	@Test
	public void frameRepainterDetachesFromHiddenLabels()
	{
		ImageObserver observer = UiView.frameRepainter(new JLabel());
		assertNotNull(observer);
		assertFalse(observer.imageUpdate(null, ImageObserver.FRAMEBITS, 0, 0, 1, 1));
	}
}
