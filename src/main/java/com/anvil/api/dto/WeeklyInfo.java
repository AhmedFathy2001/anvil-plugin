package com.anvil.api.dto;

public class WeeklyInfo
{
	public String type;   // "skill" | "boss"
	public String title;
	public String metric;
	/** The metric spelled for people ("Phosani's Nightmare"); null on a site that predates it. */
	public String metricLabel;
}
