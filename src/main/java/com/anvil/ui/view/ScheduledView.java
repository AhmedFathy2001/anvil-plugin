package com.anvil.ui.view;

import com.anvil.detect.LadderMissions;

/**
 * A bingo event on the clan's schedule that ISN'T the caller's own board — one starting soon, or a
 * live one they aren't enrolled in. Read-only: there's no progress to show, so the card is the
 * pitch (what, when, how big) plus a link to sign up on the site.
 */
public final class ScheduledView
{
	public final int id;
	public final String title;
	public final String startDate;
	public final String endDate;
	/** True when it's already running (the caller just isn't in it); false = still upcoming. */
	public final boolean live;
	/** Tiles configured, or 0 when the site didn't say. */
	public final int tileCount;
	/**
	 * The site's `boardSize`, whose MEANING depends on the format: N for a classic N×N grid, the
	 * tile COUNT for every list format (Leagues, tile race, ladder). See {@link #squareGrid()}.
	 */
	public final int boardSize;
	public final String format;
	public final String scoringMode;
	/** The event's page on the Anvil site, or {@code null} when the base URL is unknown. */
	public final String url;

	public ScheduledView(int id, String title, String startDate, String endDate, boolean live,
		int tileCount, int boardSize, String format, String scoringMode, String url)
	{
		this.id = id;
		this.title = title == null || title.isEmpty() ? "Bingo" : title;
		this.startDate = startDate;
		this.endDate = endDate;
		this.live = live;
		this.tileCount = Math.max(0, tileCount);
		this.boardSize = Math.max(0, boardSize);
		this.format = format;
		this.scoringMode = scoringMode;
		this.url = url;
	}

	/** "Bingo" / "Bingo (points)" / "Tile race" / "Ladder" — same wording as the in-game schedule. */
	public String kindLabel()
	{
		if (LadderMissions.isLadder(format))
		{
			return "Ladder";
		}
		if ("tilerace".equalsIgnoreCase(format))
		{
			return "Tile race";
		}
		return "points".equalsIgnoreCase(scoringMode) ? "Bingo (points)" : "Bingo";
	}

	/**
	 * Whether {@code boardSize} is a grid SIDE or a tile COUNT.
	 *
	 * <p>Only a classic bingo is square. Every other format — Leagues and its reveal-policy
	 * variants, tile race, ladder — is a task LIST whose boardSize is simply how many tiles it
	 * has, so treating it as a side length turns a 240-task Leagues board into a "240×240"
	 * claim about 57,600 tiles.
	 */
	private boolean squareGrid()
	{
		if (format != null && !format.isEmpty())
		{
			return "bingo".equalsIgnoreCase(format) && !"points".equalsIgnoreCase(scoringMode);
		}
		// A site too old to tell us. Believe the geometry instead: a square board's tile count is
		// its side squared, and anything that doesn't add up is a list.
		return tileCount > 0 && boardSize > 0 && boardSize * boardSize == tileCount;
	}

	/** "5×5 · 25 tiles" for a grid, "240 tiles" for a list, or "" when the site said nothing. */
	public String sizeLabel()
	{
		if (squareGrid())
		{
			// Deliberately NOT derived from boardSize² — a board that is still being authored has
			// fewer tiles than its grid has cells, and the honest thing is to say so.
			String tiles = tileCount > 0 ? tileCount + (tileCount == 1 ? " tile" : " tiles") : "";
			if (boardSize <= 0)
			{
				return tiles;
			}
			return tiles.isEmpty() ? boardSize + "×" + boardSize : boardSize + "×" + boardSize + " · " + tiles;
		}
		// A list: boardSize IS the tile count, so it stands in when the authored count is absent.
		int count = tileCount > 0 ? tileCount : boardSize;
		return count > 0 ? count + (count == 1 ? " tile" : " tiles") : "";
	}
}
