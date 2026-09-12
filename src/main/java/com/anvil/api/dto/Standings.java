package com.anvil.api.dto;

import java.util.List;

public class Standings
{
	public int yourRank;      // caller's 1-based rank; 0 when they have no scoring row yet
	public long yourPoints;
	public int yourTasks;
	public int total;         // full board length (entries is capped)
	public List<StandingEntry> entries;
}
