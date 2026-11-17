package com.anvil;

import java.lang.reflect.Proxy;
import net.runelite.api.Client;
import net.runelite.api.Player;
import org.junit.Test;

import static org.junit.Assert.*;

/** The right-click "Anvil chat: connected" line: only players who are SENDING through Anvil chat. */
public class RelayPresenceTest
{
	/** A plugin logged in as "Me Myself" with Anvil chat in {@code mode}. */
	private static AnvilPlugin plugin(AnvilConfig.Relay mode)
	{
		AnvilPlugin p = new AnvilPlugin();
		p.config = (AnvilConfig) Proxy.newProxyInstance(AnvilConfig.class.getClassLoader(), new Class<?>[]{AnvilConfig.class},
			(o, m, a) -> "anvilChat".equals(m.getName()) ? mode : null);
		Player me = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
			(o, m, a) -> "getName".equals(m.getName()) ? "Me Myself" : null);
		p.client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(o, m, a) -> "getLocalPlayer".equals(m.getName()) ? me : null);
		return p;
	}

	@Test
	public void ourOwnNameReadsConnectedOnlyInASendingMode()
	{
		assertFalse(plugin(AnvilConfig.Relay.Off).relayConnected("Me Myself"));
		assertFalse("receiving only: nobody is shown us, so neither are we", plugin(AnvilConfig.Relay.See_others).relayConnected("Me Myself"));
		assertTrue(plugin(AnvilConfig.Relay.See_send).relayConnected("Me Myself"));
		assertTrue(plugin(AnvilConfig.Relay.See_send_PMs).relayConnected("<img=2>me_myself"));
	}

	@Test
	public void othersReadConnectedOnlyWhenTheSiteNamedThemAsSending()
	{
		AnvilPlugin p = plugin(AnvilConfig.Relay.See_others);
		assertFalse(p.relayConnected("Bob Two"));
		p.relayHere.add("bob two");
		assertTrue("a receiver still sees who is sending", p.relayConnected("Bob_Two"));
		assertFalse(p.relayConnected("Carol"));
		p.relayHere.remove("bob two");
		assertFalse(p.relayConnected("Bob Two"));
	}
}
