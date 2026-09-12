package com.anvil.track;

import com.anvil.api.PluginConfigResponse;
import java.util.function.Supplier;

/**
 * The three things every tracker needs from the plugin and cannot be injected.
 *
 * <p><b>The config is a supplier, not the value.</b> {@code /config} is re-fetched every thirty
 * seconds and the response object is replaced wholesale — so a tracker handed the object at startup
 * would go on crediting tiles from a board that has since ended, and would never see a tile somebody
 * added this morning. Handing over the supplier means every read is of the current one.</p>
 *
 * <p><b>Refreshing is a callback</b> because a credit should pull the board back: the tile's new
 * total is what the side panel shows, and the server may have completed it. The trackers should not
 * have to know how that happens.</p>
 *
 * <p><b>The player name is read late</b>, at the moment a proof is captured rather than when the
 * tracker is built — an account can log out and another log in without the plugin restarting.</p>
 */
public interface Tracker
{
	void bind(Supplier<PluginConfigResponse> pluginConfig, Runnable refreshConfig,
		Supplier<String> localPlayerName);
}
