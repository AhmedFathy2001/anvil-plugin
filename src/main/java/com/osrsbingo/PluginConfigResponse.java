package com.osrsbingo;

import java.util.List;

public class PluginConfigResponse
{
	public EventInfo event;
	public TeamInfo team;
	public PlayerInfo player;
	public String codeword;
	public List<TrackedDrop> trackedDrops;
	public List<TrackedStat> trackedStats;

	public static class EventInfo
	{
		public int id;
		public String name;
		public String startDate;
		public String endDate;
		public String forceEndedAt;
	}

	public static class TeamInfo
	{
		public int id;
		public String name;
		public String color;
	}

	public static class PlayerInfo
	{
		public int id;
	}

	public static class TrackedDrop
	{
		public int tileId;
		public String label;
		public List<Integer> itemIds;
		public int requiredAmount;
		public int currentAmount;
		public List<ItemRequirement> itemRequirements;
		// null = accept any source. Otherwise must match one of: "npc", "event", "pvp".
		public List<String> acceptedSources;
	}

	public static class ItemRequirement
	{
		public int itemId;
		public String name;
		public int requiredAmount;
		public int currentAmount;
	}

	public static class TrackedStat
	{
		public int tileId;
		public String label;
		public String statName;     // e.g. "mining", "zulrah"
		public String statType;     // "skill" | "boss" | "kc"
		public String trackingMode; // "team" | "individual"
		public int currentAmount;   // gained XP / KC since baseline
		public int goalAmount;
	}
}
