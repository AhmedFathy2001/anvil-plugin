package com.anvil.session;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;

/**
 * Who is playing, asked late.
 *
 * <p>The answer changes: an account can log out and another log in without the plugin restarting,
 * and right after a LOGGED_IN transition the name can still be unpopulated. So nothing holds the
 * name — everything that needs it asks at the moment it needs it, which is usually the moment a
 * proof is captured or a request is stamped.</p>
 */
@Singleton
public class LocalPlayer
{
	private final Client client;

	@Inject
	LocalPlayer(Client client)
	{
		this.client = client;
	}

	/** The logged-in account's display name, or null when nobody is logged in yet. */
	public String name()
	{
		if (client == null || client.getLocalPlayer() == null)
		{
			return null;
		}
		return client.getLocalPlayer().getName();
	}
}
