package com.anvil.session;

import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.EventConfigStore;
import com.anvil.clan.ClanRosterService;
import com.anvil.clip.ObsClipService;
import com.anvil.util.TaskRunner;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;

/**
 * A plugin setting changed, and what has to happen before the next poll.
 *
 * <p>Most of it is "re-ask the site", which the store debounces. Two keys are not: the Site URL and
 * the Account Token are deliberate one-shot edits — a paste, or the sign-in flow storing the token —
 * not the rapid churn the debounce exists to coalesce. Waiting on it left the sidebar looking dead
 * for up to fifteen seconds: the token was live, the cache filled about a second later, but the
 * panel only repaints on its own timer.</p>
 */
@Singleton
public class SettingsRouter
{
	private static final String GROUP = "osrsbingo";

	private final Client client;
	private final AnvilConfig config;
	private final BingoApiClient apiClient;
	private final TaskRunner tasks;
	private final EventConfigStore configStore;
	private final SessionIdentity session;
	private final ClanRosterService roster;
	private final ObsClipService clips;

	@Inject
	SettingsRouter(Client client, AnvilConfig config, BingoApiClient apiClient, TaskRunner tasks,
		EventConfigStore configStore, SessionIdentity session, ClanRosterService roster,
		ObsClipService clips)
	{
		this.client = client;
		this.config = config;
		this.apiClient = apiClient;
		this.tasks = tasks;
		this.configStore = configStore;
		this.session = session;
		this.roster = roster;
		this.clips = clips;
	}

	/** Point the client at whatever Site URL and Account Token are configured right now. */
	public void configureApiClient()
	{
		apiClient.configure(config.apiUrl(), config.playerToken());
	}

	public void onConfigChanged(String group, String key)
	{
		if (!GROUP.equals(group))
		{
			return;
		}
		configureApiClient();

		boolean credentials = "apiUrl".equals(key) || "playerToken".equals(key);
		if (credentials)
		{
			// Fetch now and poke the panel when it lands — see the class note.
			configStore.refreshNowAndRepaint();
		}
		else
		{
			configStore.scheduleRefresh();
		}

		// Setup pasted mid-session (the typical first install: enable the plugin while logged in,
		// then enter Site URL + Account Token): stamp the RSN/account hash and greet now, since no
		// LOGGED_IN transition will fire to do it. Reset the admin probe so a new token gets
		// re-checked — the URL/token pair has to be re-evaluated after an edit. The single-threaded
		// executor runs this before the debounced refresh, so that refresh already carries the
		// headers.
		if (credentials && client.getGameState() == GameState.LOGGED_IN && tasks.isLive())
		{
			roster.resetProbe();
			tasks.run(session::stampIdentityAndGreet);
		}

		// (Re)establish or tear down the OBS clip connection when its settings change.
		if ("clipsEnabled".equals(key) || "obsHost".equals(key) || "obsPort".equals(key)
			|| "obsPassword".equals(key))
		{
			if (config.clipsEnabled())
			{
				clips.connect();
			}
			else
			{
				clips.disconnect();
			}
		}
		else if ("clipLengthSeconds".equals(key) || "clipMp4".equals(key))
		{
			// Adopt the new length/format live — OBS restarts the buffer with the new settings. The
			// service checks for itself whether there is a live connection to tell.
			clips.applyClipLength();
		}
	}
}
