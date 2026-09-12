package com.anvil.api.dto;

// One difficulty band: tiles with points >= min (and below the next band's min) fall in this
// tier. Admin-configured server-side and served here so the Tier filter needs no plugin update.
public class TierBand
{
	public String key;    // stable slug used in filter state
	public String label;  // shown on the Tier chip
	public int min;       // inclusive lower bound on points
}
