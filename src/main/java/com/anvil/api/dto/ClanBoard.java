package com.anvil.api.dto;

import com.anvil.api.PluginConfigResponse;

/**
 * A live board in one of the person's clans.
 *
 * Shaped like {@link HomeBoard} but a type of its own because it carries the event id, and the id
 * is load-bearing: a co-hosted event belongs to every host, so the same board arrives listed under
 * each of them and the id is the only thing that says so. {@link HomeBoard} is the summary for the
 * clan the plugin is addressing, where the question never comes up.
 */
public class ClanBoard
{
	/**
	 * "bingo" or "weekly" — and half of this thing's identity.
	 *
	 * A board id and a competition id come out of different tables and collide freely, so anything
	 * deduping across clans has to key on the PAIR. Null on a site that predates the field, where
	 * only boards were ever reported.
	 */
	public String kind;
	public int eventId;
	public String eventName;
	/** Zero on a weekly: a competition has a leaderboard, not a board to fill. */
	public int tilesComplete;
	public int tilesTotal;
	public boolean pointsScored;

	/** True for a SOTW/BOTW — no board to fill, so no progress bar and no tile tally. */
	public boolean isWeekly()
	{
		return "weekly".equals(kind);
	}

	/** Identity across clans: the id alone is ambiguous between the two tables it can come from. */
	public String identity()
	{
		return kind == null || "bingo".equals(kind)
			? PluginConfigResponse.boardIdentity(eventId)
			: kind + ":" + eventId;
	}
}
