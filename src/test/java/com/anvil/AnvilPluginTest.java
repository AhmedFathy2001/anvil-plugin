package com.anvil;

import com.anvil.scenario.AnvilScenarioPlugin;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class AnvilPluginTest
{
	/**
	 * Dev client only. {@code -Danvil.site=https://beta.anvilosrs.com} (or env ANVIL_SITE) points it
	 * at beta to try a site that isn't released yet. Sign in again after switching: a token is per site.
	 */
	public static void main(String[] args) throws Exception
	{
		String dev = System.getProperty("anvil.site", System.getenv("ANVIL_SITE"));
		if (dev != null && dev.matches("https://[a-z0-9.-]+(:\\d+)?"))
		{
			BingoApiClient.useDevSite(dev);
		}
		// The scenario lab rides along in the dev client only (::anvil help) — see resources/scenarios/README.md.
		ExternalPluginManager.loadBuiltin(AnvilPlugin.class, AnvilScenarioPlugin.class);
		RuneLite.main(args);
	}
}
