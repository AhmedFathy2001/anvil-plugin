package com.anvil;

import java.lang.reflect.Proxy;
import org.junit.Test;

/**
 * The service is a singleton that outlives a plugin stop/start. Its executor used to stay terminated after
 * shutdown(), so the next banner threw RejectedExecutionException out of the loot handler — before the drop
 * was queued — and the tile showed "complete" in game while nothing reached the site.
 */
public class BannerSoundServiceTest
{
	private static AnvilConfig soundOn()
	{
		return (AnvilConfig) Proxy.newProxyInstance(AnvilConfig.class.getClassLoader(), new Class<?>[]{AnvilConfig.class},
			(proxy, method, args) -> method.getName().equals("bannerSound") ? Boolean.TRUE : null);
	}

	@Test
	public void playAfterShutdownDoesNotThrow()
	{
		BannerSoundService sound = new BannerSoundService(soundOn(), null);
		sound.play(BannerSoundService.Kind.BANNER);
		sound.shutdown();
		sound.play(BannerSoundService.Kind.BANNER);
		sound.play(BannerSoundService.Kind.MISSION);
		sound.shutdown();
	}
}
