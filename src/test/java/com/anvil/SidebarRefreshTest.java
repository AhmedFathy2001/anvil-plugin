package com.anvil;

import com.google.gson.Gson;
import javax.swing.SwingUtilities;
import okhttp3.OkHttpClient;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** A clicked Refresh always gives the button back, whatever the refetch behind it does. */
public class SidebarRefreshTest
{
	@Test(timeout = 10_000)
	public void refreshButtonComesBackWhenTheRefetchThrows() throws Exception
	{
		BingoApiClient api = new BingoApiClient(new Gson(), new OkHttpClient());
		AnvilSidebarDataSource ds = new AnvilSidebarDataSource(PluginConfigResponse::new, api)
		{
			@Override
			void refreshConfig(boolean force)
			{
				throw new IllegalStateException("site sent something odd");
			}
		};
		AnvilSidebarPanel[] p = new AnvilSidebarPanel[1];
		SwingUtilities.invokeAndWait(() -> {
			p[0] = new AnvilSidebarPanel(ds, api, null, null, null, null, null);
			p[0].onScreen = true;
			p[0].refresh(true);
			assertEquals("Refreshing…", p[0].refreshButton.getText());
		});
		boolean[] back = new boolean[1];
		while (!back[0]) {
			SwingUtilities.invokeAndWait(() -> back[0] = p[0].refreshButton.isEnabled());
		}
		SwingUtilities.invokeAndWait(() -> assertEquals("Refresh", p[0].refreshButton.getText()));
		assertTrue(back[0]);
	}
}
