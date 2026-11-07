package com.anvil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import org.junit.Test;

public class MotionIconTest
{
	/** An animated GIF of {@code frames} solid frames, each {@code delayCs} centiseconds. */
	static byte[] gif(int w, int h, int frames, int delayCs) throws Exception
	{
		ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ImageOutputStream ios = ImageIO.createImageOutputStream(out))
		{
			writer.setOutput(ios);
			writer.prepareWriteSequence(null);
			for (int i = 0; i < frames; i++)
			{
				BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
				img.setRGB(0, 0, 0x00ff00 * (i + 1));
				IIOMetadata meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(img), null);
				IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree("javax_imageio_gif_image_1.0");
				IIOMetadataNode gce = new IIOMetadataNode("GraphicControlExtension");
				gce.setAttribute("disposalMethod", "none");
				gce.setAttribute("userInputFlag", "FALSE");
				gce.setAttribute("transparentColorFlag", "FALSE");
				gce.setAttribute("delayTime", Integer.toString(delayCs));
				gce.setAttribute("transparentColorIndex", "0");
				root.appendChild(gce);
				meta.setFromTree("javax_imageio_gif_image_1.0", root);
				writer.writeToSequence(new IIOImage(img, null, meta), null);
			}
			writer.endWriteSequence();
		}
		finally
		{
			writer.dispose();
		}
		return out.toByteArray();
	}

	@Test
	public void anAnimatedGifDecodesEveryFrameAndLoopsForever() throws Exception
	{
		Icon icon = BingoApiClient.animatedIcon(gif(48, 48, 3, 10), 48, 48);
		assertTrue(icon instanceof GifIcon);
		GifIcon g = (GifIcon) icon;
		assertEquals(3, g.frameCount());
		assertEquals(48, g.getIconWidth());
		// 100 ms a frame: past the end it wraps to the first frame again, whatever the file's loop count.
		assertEquals(0, g.frameAt(50));
		assertEquals(1, g.frameAt(150));
		assertEquals(2, g.frameAt(250));
		g.advance(250);
		assertEquals(2, g.shownFrame());
		g.advance(100);
		assertEquals("looped", 0, g.shownFrame());
	}

	@Test
	public void eachLabelGetsItsOwnClockOverSharedFrames() throws Exception
	{
		GifIcon first = (GifIcon) BingoApiClient.animatedIcon(gif(48, 48, 2, 10), 48, 48);
		GifIcon second = first.copy();
		assertNotSame(first, second);
		first.advance(150);
		assertEquals(1, first.shownFrame());
		assertEquals("paused/other labels keep their own frame", 0, second.shownFrame());
	}

	@Test
	public void aOneFrameGifIsAPlainStill() throws Exception
	{
		assertTrue(BingoApiClient.animatedIcon(gif(48, 48, 1, 0), 48, 48) instanceof ImageIcon);
	}

	@Test
	public void unsafeOrForeignFilesAreRefused() throws Exception
	{
		assertNull("too big for the slot", BingoApiClient.animatedIcon(gif(96, 96, 2, 10), 48, 48));
		byte[] huge = gif(48, 48, 2, 10);
		// Logical screen 4096x4096 behind 48x48 frames.
		huge[6] = 0; huge[7] = 0x10; huge[8] = 0; huge[9] = 0x10;
		assertNull(BingoApiClient.animatedIcon(huge, 48, 48));
		ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(48, 48, BufferedImage.TYPE_INT_RGB), "jpg", jpeg);
		assertNull("a still answer is decoded as a still by the caller", BingoApiClient.animatedIcon(jpeg.toByteArray(), 48, 48));
		assertNotNull(BingoApiClient.decodeSmall(jpeg.toByteArray(), 48, 48));
	}
}
