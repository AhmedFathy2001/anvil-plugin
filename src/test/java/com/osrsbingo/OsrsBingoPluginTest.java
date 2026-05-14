package com.osrsbingo;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class OsrsBingoPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(OsrsBingoPlugin.class);
		RuneLite.main(args);
	}
}
