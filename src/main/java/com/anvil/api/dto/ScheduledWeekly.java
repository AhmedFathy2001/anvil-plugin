package com.anvil.api.dto;

public class ScheduledWeekly
{
	public int id;
	public String title;
	public String type;
	public String metric;
	/** The metric spelled for people ("Phosani's Nightmare"); null on a site that predates it. */
	public String metricLabel;
	public String status;
	public String startDate;
	public String endDate;
}
