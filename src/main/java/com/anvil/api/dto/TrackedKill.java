package com.anvil.api.dto;

import java.util.List;

// Kill-count tile: the plugin counts kills of any NPC named in targetNpcs (case-insensitive,
// need NOT be on the hiscores) toward requiredAmount. Same submission flow as a simple drop.
public class TrackedKill
{
	public int tileId;
	public int position;   // board position — the list mirrors the site's tile order (0 on old servers)
	public String label;
	public String description;
	public int points;
	public String category;
	public List<String> targetNpcs;   // any of these NPC names counts a kill
	public int requiredAmount;
	public int currentAmount;
	public String trackingMode;        // "team" | "individual"/"solo"
	// Shared kills. "per-kill" = a kill several members were in credits ONCE (the server
	// correlates their reports); coopMinMembers = the kill only counts with at least that many
	// of the team in it. Either one makes this client attach what it could see of its company.
	// Absent/"per-member" + 0 on older servers, which is the historical every-member counting.
	public String coopCredit;
	public int coopMinMembers;
	// What one credit IS, for player-facing wording only ("kill", "lap", …). Agility-lap tiles
	// ride this list because the game's lap counter is the same chat line boss KC uses, so the
	// matching is identical and only the noun differs. Null on older servers → "kill".
	public String unit;

	/** Singular noun for one credit on this tile — "kill" unless the server says otherwise. */
	public String unitNoun()
	{
		return unit == null || unit.trim().isEmpty() ? "kill" : unit.trim();
	}

	public boolean needsCoopFingerprint()
	{
		return "per-kill".equalsIgnoreCase(coopCredit) || coopMinMembers > 0;
	}
}
