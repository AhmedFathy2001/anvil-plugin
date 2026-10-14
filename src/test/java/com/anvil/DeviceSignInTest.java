package com.anvil;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static com.anvil.DeviceSignIn.isConfiguredHomeUrl;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The sign-in browser pin: only the CONFIGURED home origin + /link-device may ever open (§8 shape,
 * anchored to the member-typed Site URL instead of a hardcoded host). */
public class DeviceSignInTest
{
	private static final String HOME = "https://clan.anvilosrs.com";

	@Test
	public void acceptsOwnLinkDevicePage()
	{
		assertTrue(isConfiguredHomeUrl(HOME, HOME + "/link-device"));
		assertTrue(isConfiguredHomeUrl(HOME, HOME + "/link-device?code=ABCD-EFGH"));
	}

	@Test
	public void refusesForeignHosts()
	{
		assertFalse(isConfiguredHomeUrl(HOME, "https://evil.com/link-device"));
		assertFalse(isConfiguredHomeUrl(HOME, "https://clan.anvilosrs.com.evil.com/link-device"));
	}

	@Test
	public void refusesLookalikeAuthorityTricks()
	{
		assertFalse(isConfiguredHomeUrl(HOME, "https://clan.anvilosrs.com@evil.com/link-device"));
		assertFalse(isConfiguredHomeUrl(HOME, "https://clan.anvilosrs.com:8443/link-device")); // port mismatch
	}

	@Test
	public void refusesOtherPathsOnOwnHost()
	{
		assertFalse(isConfiguredHomeUrl(HOME, HOME + "/"));
		assertFalse(isConfiguredHomeUrl(HOME, HOME + "/profile"));
	}

	@Test
	public void refusesSchemeDowngrade()
	{
		// A https home never opens a http page; a deliberate http dev home may open its own http page.
		assertFalse(isConfiguredHomeUrl(HOME, "http://clan.anvilosrs.com/link-device"));
		assertTrue(isConfiguredHomeUrl("http://localhost:3000", "http://localhost:3000/link-device"));
	}

	@Test
	public void refusesGarbage()
	{
		assertFalse(isConfiguredHomeUrl(HOME, null));
		assertFalse(isConfiguredHomeUrl(HOME, ""));
		assertFalse(isConfiguredHomeUrl(HOME, "javascript:alert(1)"));
		assertFalse(isConfiguredHomeUrl(null, HOME + "/link-device"));
	}

	@Test
	public void cancelledFlowRunsNoFurtherSteps()
	{
		// The flow polls on RuneLite's shared executor, which outlives the plugin; a cancel at
		// shutdown must leave every queued step a no-op (no request, no callback).
		List<Runnable> queued = new ArrayList<>();
		List<Object> callbacks = new ArrayList<>();
		DeviceSignIn flow = new DeviceSignIn(null, url -> true, (step, delayMs) -> queued.add(step));
		flow.run(callbacks::add, callbacks::add);
		flow.cancel();
		for (Runnable step : new ArrayList<>(queued))
		{
			step.run();
		}
		assertEquals(1, queued.size());
		assertTrue(callbacks.isEmpty());
	}
}
