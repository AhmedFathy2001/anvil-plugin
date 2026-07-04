package com.anvil;

import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Collection-log "unlock" banner. 178x100 base panel (source PNG is 3x), three centred lines at
 * text-tops y=10/40/64, all 16px with a 1px black shadow and no font smoothing. Open animation:
 * a 1px line draws out horizontally, the background unsquishes to full height, holds, then reverses.
 */
public class BingoClogBannerOverlay extends Overlay
{
	// Design space (matches Skeldoor's .collection-log-panel). 1.0 = original asset size.
	private static final int BASE_W = 178;
	private static final int BASE_H = 100;
	private static final float DISPLAY_SCALE = 1f;

	// Text tops (design px) + size, straight from the CSS.
	private static final float TOP_Y = 10f;
	private static final float MID_Y = 40f;
	private static final float BOTTOM_Y = 64f;
	private static final float FONT_PX = 16f;

	// Horizontal padding (design px) kept clear inside the panel; text wider than the inner
	// width is truncated with an ellipsis so long item/tile names can't overflow the banner.
	private static final int TEXT_MARGIN_X = 12;

	// Animation (matches the reference gif): a thin line grows to full WIDTH, then the whole
	// background unsquishes to full HEIGHT; close reverses it. Linear motion, no fade.
	private static final long H_EXPAND_MS = 800;    // line grows to full width
	private static final long V_EXPAND_MS = 800;    // unsquish to full height
	private static final long HOLD_MS = 2500;
	private static final long V_COLLAPSE_MS = 650;  // squish back down to the line
	private static final long H_COLLAPSE_MS = 650;  // line shrinks to nothing
	private static final float LINE_H = 1f;         // design-px height of the thin opening line

	private static final Color ORANGE = new Color(255, 152, 31);
	private static final Color WHITE = new Color(255, 255, 255);
	private static final Color SHADOW = new Color(0, 0, 0, 255);
	private static final Color LINE_COLOR = Color.BLACK; // the thin opening/closing line

	private static final class Banner
	{
		final String top;
		final String middle;
		final String bottom;

		Banner(String top, String middle, String bottom)
		{
			this.top = top;
			this.middle = middle;
			this.bottom = bottom;
		}
	}

	private final ConcurrentLinkedQueue<Banner> queue = new ConcurrentLinkedQueue<>();
	private final BufferedImage background;
	private final Font headerFont;
	private final Font bodyFont;
	private Banner active;
	private long startedAt;

	@Inject
	public BingoClogBannerOverlay(AnvilPlugin plugin)
	{
		super(plugin);
		setPosition(OverlayPosition.TOP_CENTER);
		setPriority(OverlayPriority.HIGHEST);
		this.background = loadBackground();
		this.headerFont = FontManager.getRunescapeBoldFont().deriveFont(FONT_PX * DISPLAY_SCALE);
		this.bodyFont = FontManager.getRunescapeFont().deriveFont(FONT_PX * DISPLAY_SCALE);
	}

	private static BufferedImage loadBackground()
	{
		try (InputStream in = BingoClogBannerOverlay.class.getResourceAsStream("/com/anvil/clog_banner.png"))
		{
			return in == null ? null : ImageIO.read(in);
		}
		catch (IOException e)
		{
			return null;
		}
	}

	/** Backwards-compatible entry point used by the existing drop call site. */
	public void show(String label, int current, int required)
	{
		boolean complete = current >= required;
		String middle = complete ? "Tile complete!" : "Progress:";
		String bottom = required > 1 ? label + " (" + current + "/" + required + ")" : label;
		show("Anvil Bingo", middle, bottom);
	}

	/** Full three-line control. */
	public void show(String top, String middle, String bottom)
	{
		queue.add(new Banner(top, middle, bottom));
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (background == null)
		{
			return null;
		}

		long now = System.currentTimeMillis();
		long hEnd = H_EXPAND_MS;
		long vEnd = hEnd + V_EXPAND_MS;
		long holdEnd = vEnd + HOLD_MS;
		long vCollapseEnd = holdEnd + V_COLLAPSE_MS;
		long total = vCollapseEnd + H_COLLAPSE_MS;

		if (active == null || now - startedAt >= total)
		{
			active = queue.poll();
			if (active == null)
			{
				return null;
			}
			startedAt = now;
			now = System.currentTimeMillis();
		}

		long e = now - startedAt;

		int fullW = Math.round(BASE_W * DISPLAY_SCALE);
		int fullH = Math.round(BASE_H * DISPLAY_SCALE);
		int lineH = Math.max(1, Math.round(LINE_H * DISPLAY_SCALE));

		// w/h = current panel size; line=true draws just the thin opening line (no bg/text yet).
		float w;
		float h;
		boolean line;
		if (e < hEnd)
		{
			// P1: thin line grows to full width.
			w = fullW * (e / (float) H_EXPAND_MS);
			h = lineH;
			line = true;
		}
		else if (e < vEnd)
		{
			// P2: whole background unsquishes from the line up to full height.
			float t = (e - hEnd) / (float) V_EXPAND_MS;
			w = fullW;
			h = lineH + (fullH - lineH) * t;
			line = false;
		}
		else if (e < holdEnd)
		{
			w = fullW;
			h = fullH;
			line = false;
		}
		else if (e < vCollapseEnd)
		{
			// P4: squish back down to the line.
			float t = (e - holdEnd) / (float) V_COLLAPSE_MS;
			w = fullW;
			h = fullH - (fullH - lineH) * t;
			line = false;
		}
		else
		{
			// P5: line shrinks horizontally to nothing.
			float t = (e - vCollapseEnd) / (float) H_COLLAPSE_MS;
			w = fullW * (1f - t);
			h = lineH;
			line = true;
		}

		int iw = Math.max(1, Math.round(w));
		int ih = Math.max(1, Math.round(h));
		int x = (fullW - iw) / 2; // centre the (partly-open) panel within the reserved area
		int y = (fullH - ih) / 2;

		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);

		if (line)
		{
			// Opening/closing line: a plain strip in the banner's interior tone (not the bg, no text).
			g2.setColor(LINE_COLOR);
			g2.fillRect(x, y, iw, ih);
		}
		else
		{
			// Squish the WHOLE background into the current height (full width); it unsquishes to full.
			g2.drawImage(background, x, y, iw, ih, null);

			// Text only while opening/holding (e < holdEnd). On close it's dropped immediately so the
			// panel collapses cleanly instead of a centre sliver of text lingering. Full-size, fixed
			// in place, clipped to the opened region so it's revealed centre-out as the bg unsquishes.
			if (e < holdEnd)
			{
				g2.clipRect(x, y, iw, ih);
				int maxTextW = fullW - 2 * TEXT_MARGIN_X;
				drawCentered(g2, active.top, headerFont, ORANGE, fullW, maxTextW, TOP_Y * DISPLAY_SCALE);
				drawCentered(g2, active.middle, bodyFont, ORANGE, fullW, maxTextW, MID_Y * DISPLAY_SCALE);
				drawCentered(g2, active.bottom, bodyFont, WHITE, fullW, maxTextW, BOTTOM_Y * DISPLAY_SCALE);
			}
		}

		g2.dispose();

		return new Dimension(fullW, fullH);
	}

	/** Draws text centred horizontally, with {@code topY} being the CSS-style top of the line (line-height 1). */
	private static void drawCentered(Graphics2D g, String text, Font font, Color color, int width, int maxWidth, float topY)
	{
		if (text == null || text.isEmpty())
		{
			return;
		}
		g.setFont(font);
		FontMetrics fm = g.getFontMetrics();
		String shown = truncate(text, fm, maxWidth);
		int x = (width - fm.stringWidth(shown)) / 2;
		int baseline = Math.round(topY) + fm.getAscent();
		g.setColor(SHADOW);
		g.drawString(shown, x + 1, baseline + 1);
		g.setColor(color);
		g.drawString(shown, x, baseline);
	}

	/** Trims {@code text} with a trailing ellipsis so it fits within {@code maxWidth} px. */
	private static String truncate(String text, FontMetrics fm, int maxWidth)
	{
		if (maxWidth <= 0 || fm.stringWidth(text) <= maxWidth)
		{
			return text;
		}
		final String ellipsis = "...";
		int ellipsisW = fm.stringWidth(ellipsis);
		int end = text.length();
		while (end > 0 && fm.stringWidth(text.substring(0, end)) + ellipsisW > maxWidth)
		{
			end--;
		}
		return end <= 0 ? ellipsis : text.substring(0, end).trim() + ellipsis;
	}
}
