package com.anvil;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import javax.swing.*;
import javax.swing.Timer;
import org.w3c.dom.*;

/**
 * A panel GIF decoded by us and played on our own clock, instead of handing raw bytes to AWT's
 * Toolkit decoder. That decoder stopped when its observers detached (an idle or hidden panel never
 * resumed), restarted blank when labels swapped, and sized its canvas from whatever the file
 * declared. Here every frame is composited once under a fixed pixel budget, it always loops, and it
 * advances only while a label showing it is on screen and the client window is in front — so after
 * idling it carries on from the frame it stopped at.
 */
final class GifIcon implements Icon {
	/** Decoded canvas pixels across all frames (~24 MB of ARGB): a 220x73 banner of 60 frames is ~1M. */
	static final long MAX_PIXELS = 6_000_000L;
	static final int MAX_FRAMES = 300;
	/** GIF delays are centiseconds; 0/1 means "as fast as possible", which browsers show as 100 ms. */
	static final int MIN_DELAY_MS = 20;
	static final int FALLBACK_DELAY_MS = 100;

	static final Set<GifIcon> LIVE = Collections.newSetFromMap(new WeakHashMap<>());
	static long lastTick;
	static final Timer TICK = new Timer(20, e -> tick());

	final BufferedImage[] frames;
	final int[] ends;
	final int total;
	final Map<Component, Boolean> hosts = new WeakHashMap<>();
	long playedMs;
	int shown;

	GifIcon(BufferedImage[] frames, int[] ends) {
		this.frames = frames;
		this.ends = ends;
		total = ends[ends.length - 1];
	}

	/** Its own clock for another label; the decoded frames are shared. */
	GifIcon copy() {
		return new GifIcon(frames, ends);
	}

	int frameCount() {
		return frames.length;
	}

	public int getIconWidth() {
		return frames[0].getWidth();
	}

	public int getIconHeight() {
		return frames[0].getHeight();
	}

	public void paintIcon(Component c, Graphics g, int x, int y) {
		if (c != null && frames.length > 1) {
			hosts.put(c, Boolean.TRUE);
			if (LIVE.add(this) && !TICK.isRunning()) {
				lastTick = System.currentTimeMillis();
				TICK.start();
			}
		}
		g.drawImage(frames[shown], x, y, null);
	}

	/** EDT: advance every icon some showing label holds, while the client window is in front. */
	static void tick() {
		var now = System.currentTimeMillis();
		// Clamped, so a stalled EDT or a long idle never fast-forwards: playback resumes where it was.
		var dt = Math.max(0, Math.min(100, now - lastTick));
		lastTick = now;
		if (LIVE.isEmpty()) {
			TICK.stop();
			return;
		}
		var active = GradientLabel.clientActive();
		for (Iterator<GifIcon> it = LIVE.iterator(); it.hasNext(); ) {
			var icon = it.next();
			if (icon.hosts.isEmpty()) {
				it.remove();
				continue;
			}
			if (active && icon.anyShowing()) icon.advance(dt);
		}
	}

	boolean anyShowing() {
		for (Component c : hosts.keySet()) {
			if (c.isShowing()) return true;
		}
		return false;
	}

	void advance(long dt) {
		playedMs = (playedMs + dt) % total;
		int next = frameAt(playedMs);
		if (next != shown) {
			shown = next;
			for (Component c : hosts.keySet()) {
				if (c.isShowing()) c.repaint();
			}
		}
	}

	int frameAt(long t) {
		for (int i = 0; i < ends.length; i++) {
			if (t < ends[i]) return i;
		}
		return ends.length - 1;
	}

	int shownFrame() {
		return shown;
	}

	/**
	 * A GIF the site already sized to fit {@code w}x{@code h}: a {@link GifIcon} when animated, a still
	 * ImageIcon when it has one frame, null when it isn't a GIF or breaks a bound (canvas, any frame's
	 * size or position, frame count, total pixels).
	 */
	static Icon decode(byte[] bytes, int w, int h) {
		if (bytes == null || bytes.length < 10 || bytes[0] != 'G' || bytes[1] != 'I' || bytes[2] != 'F') return null;
		int lw = (bytes[6] & 0xff) | (bytes[7] & 0xff) << 8;
		int lh = (bytes[8] & 0xff) | (bytes[9] & 0xff) << 8;
		if (lw <= 0 || lh <= 0 || lw > w || lh > h) return null;
		try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
			var readers = in == null ? null : ImageIO.getImageReaders(in);
			if (readers == null || !readers.hasNext()) return null;
			var reader = readers.next();
			try {
				reader.setInput(in, false, false);
				int n = reader.getNumImages(true);
				if (n <= 0 || n > MAX_FRAMES || (long) lw * lh * n > MAX_PIXELS) return null;
				var frames = new BufferedImage[n];
				int[] ends = new int[n];
				var canvas = new BufferedImage(lw, lh, BufferedImage.TYPE_INT_ARGB);
				int at = 0;
				for (int i = 0; i < n; i++) {
					int fw = reader.getWidth(i), fh = reader.getHeight(i);
					var f = frameInfo(reader.getImageMetadata(i));
					if (f == null || fw <= 0 || fh <= 0 || f.x < 0 || f.y < 0 || f.x + fw > lw || f.y + fh > lh) return null;
					var before = "restoreToPrevious".equals(f.disposal) ? copyOf(canvas) : null;
					var raw = reader.read(i);
					var g = canvas.createGraphics();
					g.drawImage(raw, f.x, f.y, null);
					g.dispose();
					frames[i] = copyOf(canvas);
					at += f.delayMs;
					ends[i] = at;
					if ("restoreToBackgroundColor".equals(f.disposal)) {
						var clear = canvas.createGraphics();
						clear.setComposite(AlphaComposite.Clear);
						clear.fillRect(f.x, f.y, fw, fh);
						clear.dispose();
					} else if (before != null) canvas = before;
				}
				return n == 1 ? new ImageIcon(frames[0]) : new GifIcon(frames, ends);
			} finally {
				reader.dispose();
			}
		} catch (IOException | RuntimeException | OutOfMemoryError e) {
			return null;
		}
	}

	static final class Frame {
		int x;
		int y;
		int delayMs = FALLBACK_DELAY_MS;
		String disposal = "none";
	}

	static Frame frameInfo(IIOMetadata meta) {
		var root = meta == null ? null : meta.getAsTree("javax_imageio_gif_image_1.0");
		if (root == null) return null;
		var f = new Frame();
		var placed = false;
		for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
			var a = n.getAttributes();
			if ("ImageDescriptor".equals(n.getNodeName())) {
				f.x = Integer.parseInt(a.getNamedItem("imageLeftPosition").getNodeValue());
				f.y = Integer.parseInt(a.getNamedItem("imageTopPosition").getNodeValue());
				placed = true;
			} else if ("GraphicControlExtension".equals(n.getNodeName())) {
				int cs = Integer.parseInt(a.getNamedItem("delayTime").getNodeValue());
				f.delayMs = cs <= 1 ? FALLBACK_DELAY_MS : Math.max(MIN_DELAY_MS, cs * 10);
				f.disposal = a.getNamedItem("disposalMethod").getNodeValue();
			}
		}
		return placed ? f : null;
	}

	static BufferedImage copyOf(BufferedImage src) {
		var out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
		var g = out.createGraphics();
		g.setComposite(AlphaComposite.Src);
		g.drawImage(src, 0, 0, null);
		g.dispose();
		return out;
	}
}
