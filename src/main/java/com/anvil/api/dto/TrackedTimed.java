package com.anvil.api.dto;

// Timed-clear tile: the plugin times the named activity and submits a clear time. The tile
// completes server-side when a submitted durationSeconds is ≤ thresholdSeconds (pass/fail).
public class TrackedTimed
{
	public int tileId;
	public int position;   // board position — the list mirrors the site's tile order (0 on old servers)
	public String label;
	public String description;
	public int points;
	public String category;
	public String activity;            // e.g. "Inferno", "Chambers of Xeric"
	public int thresholdSeconds;       // complete if a clear is at or under this
	public int partySize;              // raids — exact party size required; 0/absent = any size
	public int itemId = -1;            // activity's signature reward (Colosseum → quiver) for the icon
	public boolean completed;          // team already completed this tile
}
