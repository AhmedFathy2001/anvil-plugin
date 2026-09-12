package com.anvil.api.dto;

import java.util.Set;

public class ItemRequirement
{
	public int itemId;
	public String name;
	public int requiredAmount;
	public int currentAmount;
	// Set name: ungrouped (null/blank) items are ALWAYS required; items sharing a group form one
	// set. How the sets combine is the tile's groupMode. Null/absent on a plain "collect all of
	// these" collection.
	public String group;
	// How many DISTINCT items in this set satisfy it. 0/absent = all of them (a full set), which
	// is what every collection authored before set modes existed means. 1 is "any one item from
	// this source" — with groupMode "all" that's a unique from each DT2 boss.
	public int groupRequire;
}
