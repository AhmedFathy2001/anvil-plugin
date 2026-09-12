package com.anvil.api.dto;

public class ClanMember
{
	public String rsn;
	public String rank;
	public Integer joinedDays;
	// Only set for the locally-logged-in player — used by the site for stable identity /
	// rename detection. Null for everyone else; gson omits it from the payload.
	public String accountHash;
}
