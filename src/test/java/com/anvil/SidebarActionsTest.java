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
			new BingoApiClient(new Gson(), new OkHttpClient()));
	}

	@Test
	public void offersNothingForAnotherClan()
	{
		SidebarDataSource.PanelActions remote = source().actionsFor("some-other-clan");
		assertFalse("a roster you can't see is not a button", remote.canSyncRoster);
		assertFalse("and there is no wire to send a profile there yet", remote.canSyncProfile);
		assertNull(remote.rosterNote);
	}

	@Test
	public void offersNothingWithNoPluginBound()
	{
		// Tests and the pre-wiring window: the panel must render rather than blow up.
		SidebarDataSource.PanelActions home = source().actionsFor(AnvilSidebarDataSource.LOCAL_INSTANCE_ID);
		assertFalse(home.canSyncRoster);
		assertFalse(home.canSyncProfile);
	}

	@Test
	public void bannerSoundCallsAreSafeWithoutAPlugin()
	{
		AnvilSidebarDataSource ds = source();
		ds.toggleBannerSound("nope.wav");
		ds.copyBannerSoundsPath();
		ds.importBannerSounds();
		assertFalse(ds.bannerSoundOn("nope.wav"));
	}
}
