package com.anvil.api.dto;

public class WeeklyComp
{
	public int id;
	public String title;
	public String type;   // "skill" | "boss" | "efficiency" (EHP/EHB)
	public String metric; // skill/boss key, or "ehp" | "ehb" on an efficiency comp
	/** The metric spelled for people ("Phosani's Nightmare"); null on a site that predates it. */
	public String metricLabel;
	public String startDate;
	public String endDate;
}
