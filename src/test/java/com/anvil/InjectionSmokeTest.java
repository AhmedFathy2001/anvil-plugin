package com.anvil;

import com.google.gson.Gson;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Provides;
import java.lang.reflect.Method;
import javax.inject.Singleton;
import okhttp3.OkHttpClient;
import org.junit.Test;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Guards the exact class of failure that once made the whole plugin fail to load: a {@code @Provides}
 * method reading a not-yet-injected {@code this.}-field. When Guice satisfies the sidebar panel's
 * dependency it may invoke {@link AnvilPlugin#provideSidebarDataSource} <em>before</em> the plugin's own
 * {@code @Inject} fields are populated — so the provider must take its collaborators as PARAMETERS.
 *
 * <p>This boots a real Guice injector over the federation graph the sidebar needs
 * ({@link ConnectionManager}, {@link BingoApiClient}, {@link MockSidebarDataSource}), then invokes the
 * <em>real</em> {@code provideSidebarDataSource} on an <em>uninjected</em> {@code new AnvilPlugin()} — the
 * precise scenario that used to NPE. A regression to reading {@code this.connectionManager} throws here;
 * dropping the parameters makes {@code getDeclaredMethod} fail. Cheaper than a full RuneLite injector, and
 * it exercises the real provider, not a copy.</p>
 */
public class InjectionSmokeTest
{
	/** Binds just the two roots (Gson + OkHttp); everything else has an {@code @Inject} constructor. */
	private static Injector federationGraph()
	{
		return Guice.createInjector(new AbstractModule()
		{
			@Override
			protected void configure()
			{
				// All bindings are @Provides methods below; nothing to configure explicitly.
			}

			@Provides
			@Singleton
			Gson gson()
			{
				return new Gson();
			}

			@Provides
			@Singleton
			OkHttpClient okHttp()
			{
				return new OkHttpClient();
			}
		});
	}

	@Test
	public void sidebarGraphResolvesAndProviderTakesDepsAsParams() throws Exception
	{
		Injector inj = federationGraph();

		// The @Inject-constructor graph resolves under a real injector, and the manager is a singleton.
		ConnectionManager cm = inj.getInstance(ConnectionManager.class);
		BingoApiClient client = inj.getInstance(BingoApiClient.class);
		MockSidebarDataSource mock = inj.getInstance(MockSidebarDataSource.class);
		assertNotNull(cm);
		assertNotNull(client);
		assertSame("ConnectionManager must be a Guice singleton", cm, inj.getInstance(ConnectionManager.class));

		// getDeclaredMethod(...) with these exact param types fails if the provider ever drops its params
		// to read this.-fields again — and invoking it on an UNINJECTED plugin reproduces the load bug.
		AnvilPlugin uninjectedPlugin = new AnvilPlugin();
		Method provider = AnvilPlugin.class.getDeclaredMethod(
			"provideSidebarDataSource", ConnectionManager.class, BingoApiClient.class, MockSidebarDataSource.class);
		provider.setAccessible(true);
		Object sds = provider.invoke(uninjectedPlugin, cm, client, mock);
		assertNotNull("provider must not read not-yet-injected this.-fields", sds);
		assertTrue(sds instanceof SidebarDataSource);

		// A resolved SidebarDataSource is usable off a bare ConnectionManager (single-home, no extras).
		assertTrue(((SidebarDataSource) sds).fetchConnections().isEmpty());
	}

	@Test
	public void brokerProviderAlsoTakesConfigAsParam() throws Exception
	{
		// The other new provider must read the broker URL from an AnvilConfig PARAMETER, never this.config,
		// so each get() reflects the current config (and blank ⇒ a disabled client) — same lesson.
		Method provider = AnvilPlugin.class.getDeclaredMethod(
			"provideBrokerClient", Gson.class, OkHttpClient.class, AnvilConfig.class);
		provider.setAccessible(true);
		AnvilConfig blankConfig = new AnvilConfig() { }; // all @ConfigItem methods have defaults
		Object broker = provider.invoke(new AnvilPlugin(), new Gson(), new OkHttpClient(), blankConfig);
		assertTrue(broker instanceof BrokerClient);
		assertTrue("blank broker URL ⇒ disabled (pure single-home)", !((BrokerClient) broker).isEnabled());
	}
}
