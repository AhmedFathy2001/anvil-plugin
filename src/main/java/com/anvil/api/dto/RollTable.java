package com.anvil.api.dto;

import java.util.List;

/**
 * A boss whose vestige is on a fixed rotation (the DT2 four): its unique table rolls
 * non-vestige, non-vestige, VESTIGE, so the count since the last vestige tells a player how
 * close they are. Server-side data (the site's lib/rollTables) so a cadence change or a
 * corrected item list needs no plugin release. Empty/absent on older servers — the tracker
 * then simply says nothing.
 */
public class RollTable
{
	public String boss;                 // NPC name as the loot event reports it
	public List<Integer> rollItemIds;   // every item that counts as one roll
	public int vestigeItemId;
	public String vestigeName;
	public int rollsPerVestige;         // counting the vestige itself: 1, 2, vestige
}
