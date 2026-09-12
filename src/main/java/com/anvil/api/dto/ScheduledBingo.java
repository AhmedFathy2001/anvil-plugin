package com.anvil.api.dto;

public class ScheduledBingo
{
	public int id;
	public String title;
	public String startDate;
	public String endDate;
	public String status; // "active" | "upcoming"
	public Integer boardSize; // N for an N×N board
	public Integer tileCount; // count of tiles configured for this event
	public String format;      // "bingo" | "tilerace" — picks the in-game view
	public String scoringMode; // "tiles" | "points"
}
