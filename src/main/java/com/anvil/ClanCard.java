package com.anvil;

import java.util.List;

/**
 * One clan as the panel shows it — the JSON of {@code GET /api/clans/<slug>/card} ({@code clan-card}
 * capability). The Clan tab draws your own clan's; Lookup draws any listed clan's, and compare reads two.
 */
public final class ClanCard
{
	public String slug;
	public String name;
	public int members;
	public int activeThisWeek;
	/** EHP + EHB gained by the members in the last 7 days. */
	public double weekHours;
	public double totalEhp;
	public double totalEhb;
	public List<Gainer> topWeek;
	public List<Player> topPlayers;
	/** Null until a member has synced their log. */
	public Clog clog;
	public List<Highlight> highlights;
	public List<Competition> competitions;

	public static final class Gainer
	{
		public String rsn;
		public double hours;
	}

	public static final class Player
	{
		public String rsn;
		public long overallXp;
	}

	public static final class Clog
	{
		public int average;
		public int synced;
	}

	public static final class Highlight
	{
		public String rsn;
		public String label;
		public Integer itemId;
		public String kind;
		public String at;
	}

	public static final class Competition
	{
		public String type;
		public String metric;
		public String label;
	}

	/** {@code /api/clans/lookup} answer. */
	public static final class LookupResult
	{
		public List<Found> clans;
	}

	public static final class Found
	{
		public String slug;
		public String name;
	}
}
