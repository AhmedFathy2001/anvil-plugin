package com.anvil.api.dto;

public class ClanChange
{
	public String type;     // "joined" | "left" | "returned" | "renamed" | "rank_changed"
	public String rsn;
	public String oldRsn;   // populated only on rename
	public String oldRank;  // populated only on rank_changed
	public String newRank;  // populated only on rank_changed
}
