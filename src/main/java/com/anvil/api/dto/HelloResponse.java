package com.anvil.api.dto;

import java.util.List;

public class HelloResponse
{
	public boolean knownMember;
	public boolean isGuest;
	// WHICH CLAN ANSWERED, and about whom. "Tracked as a guest" is a claim about one person in one
	// clan and the message named neither, so somebody in two clans could not tell a wrong answer
	// from a surprising one. Null on a site that predates this — the log line says so rather than
	// printing "null".
	public String clanName;
	public String clanSlug;
	public String rsn;
	/** 'member' | 'guest' on a seat that exists; null when the site made no seat. */
	public String seatKind;
	// What's running right now, for an in-game greeting on login.
	public List<WeeklyInfo> activeWeekly;
	public List<BingoInfo> activeBingos;
}
