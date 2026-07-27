package com.anvil;

import com.google.gson.Gson;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Provides;
import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Singleton;
import okhttp3.OkHttpClient;
import org.junit.Test;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Guards a failure that once made the plugin fail to load: a {@code @Provides} method reading a not-yet-injected
 * {@code this.}-field. Guice may invoke {@link AnvilPlugin#provideSidebarDataSource} before the plugin's own
 * {@code @Inject} fields are populated, so the provider must take its collaborators as PARAMETERS. This boots a
 * real injector, then invokes the real provider on an <em>uninjected</em> {@code new AnvilPlugin()} — the scenario
 * that used to NPE. A regression to {@code this.apiClient} throws here; dropping the param fails {@code getDeclaredMethod}.
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

		// The @Inject-constructor graph resolves under a real injector.
		BingoApiClient client = inj.getInstance(BingoApiClient.class);
		assertNotNull(client);

		// getDeclaredMethod fails if the provider drops a param; invoking on an uninjected plugin reproduces the bug.
		AnvilPlugin uninjectedPlugin = new AnvilPlugin();
		Method provider = AnvilPlugin.class.getDeclaredMethod(
			"provideSidebarDataSource", BingoApiClient.class, ScheduledExecutorService.class);
		provider.setAccessible(true);
		Object sds = provider.invoke(uninjectedPlugin, client, Executors.newSingleThreadScheduledExecutor());
		assertNotNull("provider must not read not-yet-injected this.-fields", sds);
		assertTrue(sds instanceof SidebarDataSource);

		// A resolved SidebarDataSource is usable straight away (single-home; unconfigured ⇒ empty).
		assertTrue(((SidebarDataSource) sds).fetchConnections().isEmpty());
	}
}
