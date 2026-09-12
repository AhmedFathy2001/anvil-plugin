package com.anvil.api.dto;

public class ClanRef
{
	public String slug;
	public String name;
	/** 'member' or 'guest' — a dropdown says which of these is your home. Null on activeClan. */
	public String kind;
	/** The most relevant thing running here, or null. */
	public ClanBoard live;
	/**
	 * How many things are running here in total — boards plus the competition.
	 *
	 * {@link #live} is one line in a dropdown, so it names ONE. Without this a clan running five
	 * boards reads exactly like a clan running one, and the named board is an arbitrary pick
	 * presented as the whole answer. Zero on a site too old to send it, which is indistinguishable
	 * from "nothing running" — and on such a site there is no count to show anyway.
	 */
	public int liveCount;
}
