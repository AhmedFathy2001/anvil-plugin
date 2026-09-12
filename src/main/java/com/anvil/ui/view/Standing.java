package com.anvil.ui.view;

/** One row of a weekly leaderboard, with the caller's own row flagged. */
public final class Standing
{
	public final int rank;
	public final String rsn;
	public final long gained;
	public final boolean self;

	public Standing(int rank, String rsn, long gained, boolean self)
	{
		this.rank = Math.max(0, rank);
		this.rsn = rsn == null ? "" : rsn;
		this.gained = Math.max(0, gained);
		this.self = self;
	}
}
