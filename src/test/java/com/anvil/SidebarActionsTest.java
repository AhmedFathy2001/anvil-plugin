package com.anvil;

import com.google.gson.Gson;
import okhttp3.OkHttpClient;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What the side panel is allowed to offer, per clan.
 *
 * The rule that matters: a roster is scraped from the clan channel this account is standing in, so
 * it can never be synced for a clan you aren't in — admin or not — because you cannot see it.
 */
public class SidebarActionsTest
{
	private static AnvilSidebarDataSource source()
	{
		return new AnvilSidebarDataSource(() -> new PluginConfigResponse(),
			new BingoApiClient(new Gson(), new OkHttpClient()), null);
	}

	@Test
	public void offersNothingWithNoPluginBound()
	{
		// Tests and the pre-wiring window: the panel must render rather than blow up.
		AnvilSidebarDataSource.PanelActions home = source().actionsFor();
		assertFalse(home.canSyncRoster);
		assertFalse(home.canSyncProfile);
		assertNull(home.rosterNote);
	}

	@Test
	public void bannerSoundCallsAreSafeWithoutAPlugin()
	{
		AnvilSidebarDataSource ds = source();
		ds.toggleBannerSound("nope.wav");
		ds.copyBannerSoundsPath();
		ds.importSounds(BannerSoundService.Kind.BANNER);
		assertFalse(ds.bannerSoundOn("nope.wav"));
	}
}
