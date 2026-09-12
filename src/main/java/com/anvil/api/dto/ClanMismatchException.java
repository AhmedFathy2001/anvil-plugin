package com.anvil.api.dto;

public class ClanMismatchException extends Exception
{
	public final String serverClanName;
	public ClanMismatchException(String serverClanName)
	{
		super("Clan name mismatch");
		this.serverClanName = serverClanName;
	}
}
