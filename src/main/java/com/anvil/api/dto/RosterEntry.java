package com.anvil.api.dto;

// One event participant, for 'team:other' matching. Only players with a team are included.
public class RosterEntry
{
	public String name;    // RSN as enrolled on the site
	public int teamId;
}
