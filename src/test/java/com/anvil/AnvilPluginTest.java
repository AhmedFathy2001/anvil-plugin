package com.anvil;

import com.anvil.scenario.AnvilScenarioPlugin;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class AnvilPluginTest
{
	public static void main(String[] args) throws Exception
	{
		// The scenario lab rides along in the dev client only (::anvil help) — see resources/scenarios/README.md.
		ExternalPluginManager.loadBuiltin(AnvilPlugin.class, AnvilScenarioPlugin.class);
		RuneLite.main(args);
	}
}
