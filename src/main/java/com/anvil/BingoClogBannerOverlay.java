package com.anvil;

import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.*;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Collection-log "unlock" banner. 178x100 base panel (source PNG is 3x), three centred lines at
 * text-tops y=10/40/64, all 16px with a 1px black shadow and no font smoothing. Open animation:
 * a 1px line draws out horizontally, the background unsquishes to full height, holds, then reverses.
 */
public class BingoClogBannerOverlay extends Overlay {
	// Design space (matches Skeldoor's .collection-log-panel). 1.0 = original asset size.
	static final int BASE_W = 178;
	static final int BASE_H = 100;

	// Text tops (design px) + size. Tuned for even vertical rhythm on the small panel: the header sits
	// just under the top of the frame (11), the "Tile complete!/Progress:" line clears the divider (36),
	// and the label sits close under it (52) so the middle→label gap matches the ~15px gap between the
	// two wrapped label lines — otherwise the body reads as two disconnected zones.
	static final int TOP_Y = 11;
	static final int MID_Y = 36;
	static final int BOTTOM_Y = 52;
	static final float FONT_PX = 16f;
	// The bottom label wraps onto a second line at full size when it's too wide — the RS font is a
	// bitmap face that pixelates when scaled, so we never shrink glyphs. WRAP_LINE_H is the vertical
	// advance (design px) between the two lines, kept tight so both clear the 100px panel.
	static final int WRAP_LINE_H = 15;
	static final int BOTTOM_MAX_LINES = 2;

	// Horizontal padding (design px) kept clear inside the panel; text wider than the inner width wraps
	// onto up to BOTTOM_MAX_LINES lines (bottom label) and is ellipsised only if it overflows the last.
	static final int TEXT_MARGIN_X = 12;

	// Animation (matches the reference gif): a thin line grows to full WIDTH, then the whole
	// background unsquishes to full HEIGHT; close reverses it. Linear motion, no fade.
	static final long H_EXPAND_MS = 800;    // line grows to full width
	static final long V_EXPAND_MS = 800;    // unsquish to full height
	static final long HOLD_MS = 2500;
	static final long V_COLLAPSE_MS = 650;  // squish back down to the line
	static final long H_COLLAPSE_MS = 650;  // line shrinks to nothing
	static final int LINE_H = 1;            // design-px height of the thin opening line

	static final Color ORANGE = new Color(255, 152, 31);
	static final Color WHITE = new Color(255, 255, 255);
	static final Color SHADOW = new Color(0, 0, 0, 255);
	static final Color LINE_COLOR = Color.BLACK; // the thin opening/closing line

	final ConcurrentLinkedQueue<String[]> queue = new ConcurrentLinkedQueue<>();
	final BufferedImage background;
	final Font headerFont;
	final Font bodyFont;
	String[] active; // {top, middle, bottom}
	/** {@link #active} wrapped to the panel, once per banner rather than every frame; null until first drawn. */
	List<List<String>> wrapped;
	long startedAt;

	@Inject
	Client client;

	@Inject
	public BingoClogBannerOverlay(AnvilPlugin plugin) {
		super(plugin);
		setPosition(OverlayPosition.TOP_CENTER);
		setPriority(OverlayPriority.HIGHEST);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		background = loadBackground();
		headerFont = FontManager.getRunescapeBoldFont().deriveFont(FONT_PX);
		// Detail lines use the natively-smaller RS bitmap (the XP-counter/minimap face) at its own
		// crisp size — the standard chat font read too large on the small panel. No deriveFont: scaling
		// a bitmap font off its native grid pixelates (see the wrap-not-shrink note above).
		bodyFont = FontManager.getRunescapeSmallFont();
	}

	static BufferedImage loadBackground() {
		try (InputStream in = BingoClogBannerOverlay.class.getResourceAsStream("/com/anvil/clog_banner.png")) {
			return in == null ? null : ImageIO.read(in);
		} catch (IOException e) {
			return null;
		}
	}

	/** Backwards-compatible entry point used by the existing drop call site. */
	public boolean show(String label, int current, int required) {
		var complete = current >= required;
		var middle = complete ? "Tile complete!" : "Progress:";
		var bottom = required > 1 ? label + " (" + current + "/" + required + ")" : label;
		return show("Anvil Bingo", middle, bottom);
	}

	/** Full three-line control. False when it folded into the "+N more" banner (play no sound). */
	public boolean show(String top, String middle, String bottom) {
		if (queue.size() >= 3) {
			folded.incrementAndGet();
			return false;
		}
		queue.add(new String[]{top, middle, bottom});
		return true;
	}

	/** Banners past the three waiting: one "+N more" closes the run, the rest is in chat and the panel. */
	final AtomicInteger folded = new AtomicInteger();

	@Override
	public Dimension render(Graphics2D g) {
		if (background == null || AnvilPlugin.capturing) return null;

		var now = System.currentTimeMillis();
		var hEnd = H_EXPAND_MS;
		var vEnd = hEnd + V_EXPAND_MS;
		var holdEnd = vEnd + HOLD_MS;
		long vCollapseEnd = holdEnd + V_COLLAPSE_MS;
		var total = vCollapseEnd + H_COLLAPSE_MS;

		if (active == null || now - startedAt >= total) {
			active = queue.poll();
			int n = active == null ? folded.getAndSet(0) : 0;
			if (n > 0) active = new String[]{"Anvil Bingo", "+" + n + " more", "See chat and the panel"};
			if (active == null) return null;
			startedAt = now;
			wrapped = null;
			now = System.currentTimeMillis();
		}

		long e = now - startedAt;

		// w/h = current panel size; line = just the thin opening/closing line (no bg/text yet).
		var line = e < hEnd || e >= vCollapseEnd;
		float w = BASE_W;
		float h = line ? LINE_H : BASE_H;
		if (e < hEnd) {
			// P1: thin line grows to full width.
			w = BASE_W * (e / (float) H_EXPAND_MS);
		} else if (e < vEnd) {
			// P2: whole background unsquishes from the line up to full height.
			h = LINE_H + (BASE_H - LINE_H) * ((e - hEnd) / (float) V_EXPAND_MS);
		} else if (e >= holdEnd && e < vCollapseEnd) {
			// P4: squish back down to the line.
			h = BASE_H - (BASE_H - LINE_H) * ((e - holdEnd) / (float) V_COLLAPSE_MS);
		} else if (line) {
			// P5: line shrinks horizontally to nothing.
			w = BASE_W * (1f - (e - vCollapseEnd) / (float) H_COLLAPSE_MS);
		}

		int iw = Math.max(1, Math.round(w));
		int ih = Math.max(1, Math.round(h));
		int x = (BASE_W - iw) / 2; // centre the (partly-open) panel within the reserved area
		int y = (BASE_H - ih) / 2;

		var g2 = (Graphics2D) g.create();
		var off = dodge();
		if (off != null) g2.translate(off.x, off.y);
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);

		if (line) {
			// Opening/closing line: a plain strip in the banner's interior tone (not the bg, no text).
			g2.setColor(LINE_COLOR);
			g2.fillRect(x, y, iw, ih);
		} else {
			// Squish the WHOLE background into the current height (full width); it unsquishes to full.
			g2.drawImage(background, x, y, iw, ih, null);

			// Text only while opening/holding (e < holdEnd). On close it's dropped immediately so the
			// panel collapses cleanly instead of a centre sliver of text lingering. Full-size, fixed
			// in place, clipped to the opened region so it's revealed centre-out as the bg unsquishes.
			if (e < holdEnd) {
				g2.clipRect(x, y, iw, ih);
				if (wrapped == null) {
					int maxTextW = BASE_W - 2 * TEXT_MARGIN_X;
					wrapped = Arrays.asList(
						wrap(active[0], g2.getFontMetrics(headerFont), maxTextW, 1),
						wrap(active[1], g2.getFontMetrics(bodyFont), maxTextW, 1),
						wrap(active[2], g2.getFontMetrics(bodyFont), maxTextW, BOTTOM_MAX_LINES));
				}
				drawLines(g2, wrapped.get(0), headerFont, ORANGE, TOP_Y);
				drawLines(g2, wrapped.get(1), bodyFont, ORANGE, MID_Y);
				drawLines(g2, wrapped.get(2), bodyFont, WHITE, BOTTOM_Y);
			}
		}

		g2.dispose();

		return new Dimension(BASE_W, BASE_H);
	}

	/** The game's collection log popup (restyled or not) sits top centre too: when it would cover the
	 *  banner, the banner goes left of it (the right is minimap and orbs), else right, else under it. */
	Point dodge() {
		var w = client.getWidget(InterfaceID.NotificationDisplay.CONTENT);
		var me = getBounds();
		if (w == null || w.isHidden() || me == null) return null;
		var b = w.getBounds();
		// Restyle plugins draw it about twice the widget's size around the same centre: keep clear of that.
		var p = new Rectangle(b.x - b.width / 2, b.y, b.width * 2, b.height * 2);
		if (!p.intersects(me.x, me.y, BASE_W, BASE_H)) return null;
		int gap = 12, vx = client.getViewportXOffset(), right = p.x + p.width + gap;
		if (p.x - gap - vx >= BASE_W) return new Point(p.x - gap - BASE_W - me.x, p.y - me.y);
		if (vx + client.getViewportWidth() - right >= BASE_W) return new Point(right - me.x, p.y - me.y);
		return new Point(0, p.y + p.height + gap - me.y);
	}

	/** Draws {@link #wrap wrapped} lines centred horizontally, {@code topY} being the CSS-style top of the
	 *  first (line-height 1); extra lines advance downward by {@link #WRAP_LINE_H}. */
	static void drawLines(Graphics2D g, List<String> lines, Font font, Color color, int topY) {
		g.setFont(font);
		var fm = g.getFontMetrics();
		for (int i = 0; i < lines.size(); i++) {
			var shown = lines.get(i);
			int x = (BASE_W - fm.stringWidth(shown)) / 2;
			int baseline = topY + i * WRAP_LINE_H + fm.getAscent();
			g.setColor(SHADOW);
			g.drawString(shown, x + 1, baseline + 1);
			g.setColor(color);
			g.drawString(shown, x, baseline);
		}
	}

	/** Greedy word-wrap into at most {@code maxLines} lines that each fit {@code maxWidth}, at the full font
	 *  size (the RS bitmap font pixelates when scaled, so we wrap rather than shrink); any overflow past
	 *  the last line is folded into it, and every kept line is ellipsised if it still overflows (covers a
	 *  lone over-long word too). No text, no lines. */
	static List<String> wrap(String text, FontMetrics fm, int maxWidth, int maxLines) {
		List<String> lines = new ArrayList<>();
		if (text == null || text.isEmpty()) return lines;
		if (maxWidth <= 0 || fm.stringWidth(text) <= maxWidth) {
			lines.add(text);
			return lines;
		}
		var line = new StringBuilder();
		for (String word : text.split("\\s+")) {
			var trial = line.length() == 0 ? word : line + " " + word;
			if (fm.stringWidth(trial) <= maxWidth) {
				line.setLength(0);
				line.append(trial);
				continue;
			}
			if (line.length() > 0) {
				lines.add(line.toString());
				line.setLength(0);
			}
			line.append(word);
		}
		if (line.length() > 0) lines.add(line.toString());
		if (lines.size() > maxLines) {
			// Fold every line past the cap into the last kept line (it gets ellipsised below).
			var tail = new StringBuilder(lines.get(maxLines - 1));
			for (int i = maxLines; i < lines.size(); i++) tail.append(' ').append(lines.get(i));
			lines = new ArrayList<>(lines.subList(0, maxLines - 1));
			lines.add(tail.toString());
		}
		for (int i = 0; i < lines.size(); i++) {
			if (fm.stringWidth(lines.get(i)) > maxWidth) lines.set(i, truncate(lines.get(i), fm, maxWidth));
		}
		return lines;
	}

	/** Trims {@code text} with a trailing ellipsis so it fits within {@code maxWidth} px. */
	static String truncate(String text, FontMetrics fm, int maxWidth) {
		if (maxWidth <= 0 || fm.stringWidth(text) <= maxWidth) return text;
		var ellipsis = "...";
		int ellipsisW = fm.stringWidth(ellipsis);
		int end = text.length();
		while (end > 0 && fm.stringWidth(text.substring(0, end)) + ellipsisW > maxWidth) end--;
		return end <= 0 ? ellipsis : text.substring(0, end).trim() + ellipsis;
	}
}
