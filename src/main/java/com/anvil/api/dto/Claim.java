package com.anvil.api.dto;

public class Claim
{
	public int tileId;
	public String label;
	public int points;
	public String rsn;        // finisher's RSN; null when unattributed
	public String at;         // ISO completion time
}
