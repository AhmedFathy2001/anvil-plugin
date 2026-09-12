package com.anvil.api.dto;

public class HomeBoard
{
	/**
	 * WHICH board this is. Zero on a site that predates the field.
	 *
	 * <p>Load-bearing for the same reason {@link ClanBoard#eventId} is: a co-hosted board is
	 * reported by every host, so the panel has to be able to say that the summary it is
	 * rendering and a row under "Also live" are one event rather than two.</p>
	 */
	public int eventId;
	public String eventName;
	public int tilesComplete;
	public int tilesTotal;
	public boolean pointsScored;
}
