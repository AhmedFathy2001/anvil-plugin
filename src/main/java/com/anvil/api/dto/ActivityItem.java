package com.anvil.api.dto;

import com.anvil.ui.ActivityEntry;

/** One raw feed row from the endpoint. Built into an {@link ActivityEntry} via the constructor. */
public class ActivityItem
{
	public String id;
	public String ts;
	public String player;
	public int tileId;
	public String tileLabel;
	public String kind;   // "progress" | "complete" | "reveal" — map with ActivityEntry.Kind.fromWire
	public int amount;
	public boolean isSelf;
}
