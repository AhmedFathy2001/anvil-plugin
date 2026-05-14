package com.osrsbingo;

import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

import javax.inject.Inject;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.concurrent.ConcurrentLinkedQueue;

public class BingoDropNotificationOverlay extends Overlay
{
	private static final long DISPLAY_DURATION_MS = 3000;
	private static final long FADE_DURATION_MS = 500;
	private static final int ARC = 6;
	private static final int PAD_X = 14;
	private static final int PAD_Y = 10;

	// OSRS interface colors
	private static final Color BG_COLOR = new Color(62, 53, 41, 230);
	private static final Color BORDER_COLOR = new Color(110, 95, 70);
	private static final Color BORDER_HIGHLIGHT = new Color(160, 140, 100);
	private static final Color TITLE_COLOR = new Color(255, 152, 31); // OSRS orange
	private static final Color TEXT_COLOR = new Color(255, 255, 255);
	private static final Color PROGRESS_COLOR = new Color(255, 215, 0); // Gold
	private static final Color COMPLETE_COLOR = new Color(0, 255, 128); // Green
	private static final Color SHADOW_COLOR = new Color(0, 0, 0, 160);

	private static class Notification
	{
		final String label;
		final int current;
		final int required;

		Notification(String label, int current, int required)
		{
			this.label = label;
			this.current = current;
			this.required = required;
		}
	}

	private final ConcurrentLinkedQueue<Notification> queue = new ConcurrentLinkedQueue<>();
	private Notification active;
	private long showUntil;

	@Inject
	public BingoDropNotificationOverlay(OsrsBingoPlugin plugin)
	{
		super(plugin);
		setPosition(OverlayPosition.TOP_CENTER);
		setPriority(OverlayPriority.HIGHEST);
	}

	public void show(String label, int current, int required)
	{
		queue.add(new Notification(label, current, required));
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		long now = System.currentTimeMillis();

		// Advance to next notification if current one expired or doesn't exist
		if (active == null || now >= showUntil)
		{
			active = queue.poll();
			if (active == null)
			{
				return null;
			}
			showUntil = now + DISPLAY_DURATION_MS;
		}

		long remaining = showUntil - now;
		float alpha = 1.0f;
		if (remaining < FADE_DURATION_MS)
		{
			alpha = (float) remaining / FADE_DURATION_MS;
		}

		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		boolean complete = active.current >= active.required;
		String title = complete ? "Tile Completed!" : "Bingo Drop!";
		String progress = "(" + active.current + "/" + active.required + ")";

		Font titleFont = FontManager.getRunescapeBoldFont();
		Font textFont = FontManager.getRunescapeFont();

		FontMetrics titleFm = g2.getFontMetrics(titleFont);
		FontMetrics textFm = g2.getFontMetrics(textFont);

		int titleWidth = titleFm.stringWidth(title);
		int labelWidth = textFm.stringWidth(active.label);
		int progressWidth = textFm.stringWidth(progress);
		int secondRowWidth = labelWidth + 8 + progressWidth;

		int contentWidth = Math.max(titleWidth, secondRowWidth);
		int boxWidth = contentWidth + PAD_X * 2;
		int boxHeight = titleFm.getHeight() + textFm.getHeight() + PAD_Y * 2 + 4;

		Composite orig = g2.getComposite();
		g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));

		// Background
		RoundRectangle2D rect = new RoundRectangle2D.Float(0, 0, boxWidth, boxHeight, ARC, ARC);
		g2.setColor(BG_COLOR);
		g2.fill(rect);

		// Double border (OSRS style: dark outer, lighter inner)
		g2.setColor(withAlpha(BORDER_COLOR, alpha));
		g2.setStroke(new BasicStroke(2f));
		g2.draw(rect);
		RoundRectangle2D inner = new RoundRectangle2D.Float(2, 2, boxWidth - 4, boxHeight - 4, ARC, ARC);
		g2.setColor(withAlpha(BORDER_HIGHLIGHT, alpha));
		g2.setStroke(new BasicStroke(1f));
		g2.draw(inner);

		// Title with shadow
		int y = PAD_Y + titleFm.getAscent();
		int titleX = (boxWidth - titleWidth) / 2;
		g2.setFont(titleFont);
		g2.setColor(withAlpha(SHADOW_COLOR, alpha));
		g2.drawString(title, titleX + 1, y + 1);
		g2.setColor(withAlpha(TITLE_COLOR, alpha));
		g2.drawString(title, titleX, y);

		// Tile label + progress with shadow
		y += titleFm.getHeight() + 4;
		int labelX = (boxWidth - secondRowWidth) / 2;
		g2.setFont(textFont);

		// Label shadow + text
		g2.setColor(withAlpha(SHADOW_COLOR, alpha));
		g2.drawString(active.label, labelX + 1, y + 1);
		g2.setColor(withAlpha(TEXT_COLOR, alpha));
		g2.drawString(active.label, labelX, y);

		// Progress shadow + text
		int progressX = labelX + labelWidth + 8;
		g2.setColor(withAlpha(SHADOW_COLOR, alpha));
		g2.drawString(progress, progressX + 1, y + 1);
		g2.setColor(withAlpha(complete ? COMPLETE_COLOR : PROGRESS_COLOR, alpha));
		g2.drawString(progress, progressX, y);

		g2.setComposite(orig);
		g2.dispose();

		return new Dimension(boxWidth, boxHeight);
	}

	private static Color withAlpha(Color c, float alpha)
	{
		int a = Math.round(c.getAlpha() * alpha);
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
	}
}
