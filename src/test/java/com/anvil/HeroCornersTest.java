package com.anvil;

import com.google.gson.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.Test;

import static org.junit.Assert.*;

/** A hero card with a real banner and badge picture keeps its rounded corners (build/hero-corners-1x.png, -2x.png). */
public class HeroCornersTest {
	private static final Color PIC = new Color(255, 0, 255);

	private static final class Host implements UiView.Host {
		@Override public void load(String v, Map<String, String> a, Consumer<JsonObject> d) { }
		@Override public void post(String a, Map<String, String> g, Map<String, String> f, Consumer<JsonObject> d) { }
		@Override public void site(String p) { }
		@Override public void lookup(String r) { }
		@Override public void clan(String s) { }
		@Override public void pin(int e, int t, boolean on) { }
		@Override public void switchClan(String s) { }
		@Override public JComponent nativePart(String n) { return null; }
		@Override public void image(String path, int w, int h, boolean motion, Consumer<Icon> done) {
			BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = im.createGraphics();
			g.setColor(PIC);
			g.fillRect(0, 0, w, h);
			g.dispose();
			done.accept(new ImageIcon(im));
		}
	}

	private static void layOut(Component c) {
		if (c instanceof Container) {
			((Container) c).doLayout();
			for (Component k : ((Container) c).getComponents()) layOut(k);
		}
	}

	private static BufferedImage render(int scale, int width) throws Exception {
		JsonObject root = new JsonParser().parse("{\"ui\":{\"t\":\"col\",\"c\":[{\"t\":\"hero\",\"src\":\"b\",\"c\":["
			+ "{\"t\":\"col\",\"card\":true,\"inset\":2,\"rad\":10,\"c\":[{\"t\":\"img\",\"src\":\"l\",\"w\":32,\"h\":32,\"rad\":8}]}]}]}}").getAsJsonObject();
		BufferedImage[] out = new BufferedImage[1];
		SwingUtilities.invokeAndWait(() -> {
			UiView view = new UiView(new Host(), null, null, null);
			view.render(root);
			view.setSize(width, view.getPreferredSize().height);
			layOut(view);
			out[0] = new BufferedImage(width * scale, view.getHeight() * scale, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = out[0].createGraphics();
			g.scale(scale, scale);
			view.paint(g);
			g.dispose();
		});
		return out[0];
	}

	@Test
	public void realImagesKeepRoundedCorners() throws Exception {
		for (int scale = 1; scale <= 2; scale++) {
			BufferedImage im = render(scale, 225);
			ImageIO.write(im, "png", new File("build/hero-corners-" + scale + "x.png"));
			// The banner's outermost corner pixel must not be the picture, and the strip's middle must be.
			assertNotEquals("banner corner square at " + scale + "x", PIC.getRGB(), im.getRGB(0, 0) | 0xff000000);
			// The badge picture's own corner (8px radius) is cut: the ring shows there.
			int bx = 12 * scale, by = 89 * scale; // bottom-left of the 32px picture (body at x10, y56: banner 74 less the 18 pull-up; ring 2px)
			assertNotEquals("badge corner square at " + scale + "x", PIC.getRGB(), im.getRGB(bx, by) | 0xff000000);
			assertEquals("banner body", PIC.getRGB(), im.getRGB(im.getWidth() / 2, 10 * scale) | 0xff000000);
		}
	}

	/** On a panel wider than the picture the banner still reaches both edges (cover, not centred). */
	@Test
	public void bannerCoversAWidePanel() throws Exception {
		BufferedImage im = render(1, 400);
		ImageIO.write(im, "png", new File("build/hero-corners-wide.png"));
		assertEquals("left edge", PIC.getRGB(), im.getRGB(2, 28) | 0xff000000);
		assertEquals("right edge", PIC.getRGB(), im.getRGB(397, 28) | 0xff000000);
	}
}
