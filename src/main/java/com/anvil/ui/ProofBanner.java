package com.anvil.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The banner burned into a proof screenshot, and the stacking of two frames into one.
 *
 * <p>A proof is a PNG that has to stand on its own. It is looked at later, by somebody who was not
 * there, possibly after the event has ended and the site has moved on — so what it shows has to be
 * <em>in the image</em>, not in an overlay that happened to be rendering or a chat line that
 * happened to be enabled. That is why this draws the item, the count, the account, the team, the
 * event and a UTC timestamp directly onto the pixels rather than relying on anything around
 * them.</p>
 *
 * <h2>Why some proofs are two frames</h2>
 *
 * <p>Loot does not all appear at once. A big stack or a corpse pile lands over the following
 * seconds, so a shot taken the instant the drop registered can show an empty floor, and a shot taken
 * once it has settled can miss the moment it happened. {@link #stack} puts both in one image, gold
 * bar between them, each labelled — "AT DROP" above, "MOMENTS LATER" below.</p>
 *
 * <p>Entirely drawing: no client, no config, no network. Everything it needs arrives as an
 * argument, which also means it can be looked at without a game running.</p>
 */
public final class ProofBanner
{
	/** The site's accent gold. The one colour in here that means anything. */
	private static final Color GOLD = new Color(212, 160, 23);

	/** Near-black at 90% — dark enough to read white text on any scene behind it. */
	private static final Color PANEL = new Color(20, 18, 14, 230);

	private ProofBanner()
	{
	}

	/** What the banner says about who and where, beyond the drop itself. */
	public static final class Context
	{
		public final String rsn;
		public final String teamName;
		public final String eventName;

		public Context(String rsn, String teamName, String eventName)
		{
			this.rsn = rsn;
			this.teamName = teamName;
			this.eventName = eventName;
		}
	}

	/**
	 * Stack the at-drop frame above the settled one, with a labelled gold divider.
	 *
	 * @return the flush frame unchanged when there is no trigger frame to stack — the caller does not
	 *         have to know whether dual frames were on, or whether the first capture arrived.
	 */
	public static BufferedImage stack(BufferedImage triggerFrame, BufferedImage flushFrame)
	{
		if (triggerFrame == null)
		{
			return flushFrame;
		}
		int divider = 4;
		int w = Math.max(triggerFrame.getWidth(), flushFrame.getWidth());
		int h = triggerFrame.getHeight() + divider + flushFrame.getHeight();
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		try
		{
			g.setColor(Color.BLACK);
			g.fillRect(0, 0, w, h);
			g.drawImage(triggerFrame, 0, 0, null);
			g.setColor(GOLD);
			g.fillRect(0, triggerFrame.getHeight(), w, divider);
			g.drawImage(flushFrame, 0, triggerFrame.getHeight() + divider, null);
			tag(g, "AT DROP", w, 0);
			tag(g, "MOMENTS LATER", w, triggerFrame.getHeight() + divider);
		}
		finally
		{
			g.dispose();
		}
		return out;
	}

	/** The standard drop banner: "<label>  ×<amount>  (<current>/<required>)" under a BINGO DROP title. */
	public static void drawDrop(BufferedImage img, String label, int amount, int current, int required,
		Context ctx, BufferedImage itemIcon)
	{
		draw(img, "BINGO DROP", label + "  ×" + amount + "  (" + current + "/" + required + ")", ctx, itemIcon);
	}

	/**
	 * Burn the banner onto the top-left of {@code img}.
	 *
	 * <p>Box width follows the widest line it has to hold, so a long event name grows the panel
	 * rather than running off the edge of it.</p>
	 */
	public static void draw(BufferedImage img, String title, String detail, Context ctx, BufferedImage itemIcon)
	{
		Graphics2D g = img.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

			List<String> meta = metaLines(ctx);

			Font titleFont = new Font(Font.SANS_SERIF, Font.BOLD, 14);
			Font detailFont = new Font(Font.SANS_SERIF, Font.BOLD, 18);
			Font metaFont = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
			FontMetrics tfm = g.getFontMetrics(titleFont);
			FontMetrics dfm = g.getFontMetrics(detailFont);
			FontMetrics mfm = g.getFontMetrics(metaFont);

			boolean hasIcon = itemIcon != null && itemIcon.getWidth() > 0 && itemIcon.getHeight() > 0;
			int iconW = hasIcon ? 36 : 0;
			int iconGap = hasIcon ? 10 : 0;

			int textW = Math.max(dfm.stringWidth(detail), tfm.stringWidth(title));
			for (String s : meta)
			{
				textW = Math.max(textW, mfm.stringWidth(s));
			}

			int padX = 14;
			int padY = 10;
			int boxW = iconW + iconGap + textW + padX * 2;
			int contentH = tfm.getHeight() + dfm.getHeight() + 4 + 6 + meta.size() * (mfm.getHeight() + 1);
			int boxH = Math.max(contentH, iconW > 0 ? 32 : 0) + padY * 2;
			int boxX = 12;
			int boxY = 12;

			// Drop shadow
			g.setColor(new Color(0, 0, 0, 120));
			g.fillRoundRect(boxX + 3, boxY + 3, boxW, boxH, 10, 10);
			// Background
			g.setColor(PANEL);
			g.fillRoundRect(boxX, boxY, boxW, boxH, 10, 10);
			// Gold accent border
			g.setStroke(new BasicStroke(2f));
			g.setColor(GOLD);
			g.drawRoundRect(boxX, boxY, boxW, boxH, 10, 10);

			if (hasIcon)
			{
				g.drawImage(itemIcon, boxX + padX, boxY + padY, 36, 32, null);
			}
			int textX = boxX + padX + iconW + iconGap;

			// Title in gold
			g.setFont(titleFont);
			g.setColor(GOLD);
			int textY = boxY + padY + tfm.getAscent();
			g.drawString(title, textX, textY);

			// Detail in white
			g.setFont(detailFont);
			g.setColor(Color.WHITE);
			int detailY = textY + tfm.getHeight() + 4;
			g.drawString(detail, textX, detailY);

			// Proof meta lines
			g.setFont(metaFont);
			g.setColor(new Color(220, 220, 220));
			int my = detailY + 6;
			for (String s : meta)
			{
				my += mfm.getHeight() + 1;
				g.drawString(s, textX, my);
			}
		}
		finally
		{
			g.dispose();
		}
	}

	/**
	 * Who, where, and when — the lines under the drop itself.
	 *
	 * <p>Anything we could not read is omitted rather than printed empty: a proof that says
	 * "Team:" and nothing else is worse than one that does not mention a team. The UTC stamp is
	 * always there, because it is the one line that is never unavailable and the one a dispute turns
	 * on.</p>
	 */
	static List<String> metaLines(Context ctx)
	{
		List<String> meta = new ArrayList<>();
		if (ctx != null)
		{
			if (ctx.rsn != null && !ctx.rsn.isEmpty())
			{
				meta.add("RSN: " + ctx.rsn);
			}
			if (ctx.teamName != null)
			{
				meta.add("Team: " + ctx.teamName);
			}
			if (ctx.eventName != null)
			{
				meta.add("Event: " + ctx.eventName);
			}
		}
		meta.add("UTC: " + ZonedDateTime.now(ZoneOffset.UTC)
			.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
		return meta;
	}

	/** The small top-right label naming which moment a stacked frame shows. */
	private static void tag(Graphics2D g, String text, int frameW, int frameTop)
	{
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		Font font = new Font(Font.SANS_SERIF, Font.BOLD, 12);
		g.setFont(font);
		FontMetrics fm = g.getFontMetrics(font);
		int padX = 8;
		int padY = 4;
		int bw = fm.stringWidth(text) + padX * 2;
		int bh = fm.getHeight() + padY * 2;
		int x = frameW - bw - 10;
		int y = frameTop + 10;
		g.setColor(PANEL);
		g.fillRoundRect(x, y, bw, bh, 8, 8);
		g.setColor(GOLD);
		g.drawRoundRect(x, y, bw, bh, 8, 8);
		g.drawString(text, x + padX, y + padY + fm.getAscent());
	}
}
