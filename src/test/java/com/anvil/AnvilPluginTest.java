package com.anvil;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class AnvilPluginTest
{
	// loadBuiltin is varargs over a generic type, so handing it one class is an unchecked generic
	// array creation. There is no non-varargs overload; this is how every plugin's dev launcher does it.
	@SuppressWarnings("unchecked")
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(AnvilPlugin.class);
		RuneLite.main(args);
	}
}
