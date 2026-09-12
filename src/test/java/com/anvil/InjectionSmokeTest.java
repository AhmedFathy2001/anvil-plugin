package com.anvil;

import com.anvil.api.BingoApiClient;
import com.anvil.ui.SidebarDataSource;
import com.google.gson.Gson;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Provides;
import javax.inject.Singleton;
import okhttp3.OkHttpClient;
import java.lang.reflect.Method;
import javax.inject.Provider;
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
	private static Injector sidebarGraph()
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
	public void sidebarProviderResolvesNothingEagerly() throws Exception
	{
		// The @Inject-constructor graph resolves under a real injector.
		BingoApiClient client = sidebarGraph().getInstance(BingoApiClient.class);
		assertNotNull(client);

		// Every collaborator the sidebar binding reaches back for arrives as a Provider, and none of
		// them may be resolved while the binding is being built — the panel is constructed early,
		// and resolving the board there would drag half the graph up with it. Providers that throw
		// on sight prove it: if the method touches one, this fails.
		Provider<Object> explodes = () ->
		{
			throw new AssertionError("the sidebar binding must resolve nothing until it is used");
		};
		Method provider = null;
		for (Method m : AnvilModule.class.getDeclaredMethods())
		{
			if (m.getName().equals("provideSidebarDataSource"))
			{
				provider = m;
			}
		}
		assertNotNull("AnvilModule must provide the sidebar data source", provider);
		provider.setAccessible(true);

		Object[] args = new Object[provider.getParameterCount()];
		for (int i = 0; i < args.length; i++)
		{
			Class<?> t = provider.getParameterTypes()[i];
			args[i] = BingoApiClient.class.equals(t) ? client
				: Provider.class.equals(t) ? explodes
					: null;
		}
		Object sds = provider.invoke(new AnvilModule(), args);
		assertNotNull(sds);
		assertTrue(sds instanceof SidebarDataSource);
	}

	/**
	 * The bindings live on a module, not on the plugin — and the module has no fields to read.
	 *
	 * <p>This is the structural half of the bug this class was written for. A {@code @Provides}
	 * method on the plugin can be invoked before the plugin's own {@code @Inject} fields are
	 * populated, so reading one NPEs and the whole plugin fails to load. A module with no instance
	 * fields cannot do it at all.</p>
	 */
	@Test
	public void bindingsLiveOnAFieldlessModule()
	{
		for (java.lang.reflect.Method m : AnvilPlugin.class.getDeclaredMethods())
		{
			assertTrue("@Provides belongs on AnvilModule, not the plugin: " + m.getName(),
				!m.isAnnotationPresent(com.google.inject.Provides.class));
		}
		for (java.lang.reflect.Field f : AnvilModule.class.getDeclaredFields())
		{
			assertTrue("AnvilModule must hold no instance state — a @Provides method could read it "
					+ "before Guice has filled it in: " + f.getName(),
				java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.isSynthetic());
		}
	}

	/**
	 * Field initialisers run BEFORE Guice injects, so anything built in one must not capture an
	 * injected field by value.
	 *
	 * <p>The trap is a bound method reference. {@code apiClient::submitStatKc} written as a field
	 * initialiser evaluates {@code apiClient} immediately — which is null — and throws on the spot;
	 * {@code batch -> apiClient.submitStatKc(batch)} reads it when the push actually runs. The
	 * difference is invisible at the call site and the compiler is happy with both.</p>
	 *
	 * <p>Constructing an uninjected plugin runs every field initialiser it has. If one of them ever
	 * captures an injected field again, this is where it shows up.</p>
	 */
	@Test
	public void fieldInitialisersDoNotCaptureNotYetInjectedFields()
	{
		AnvilPlugin plugin = new AnvilPlugin();
		assertNotNull("every field initialiser must survive construction before injection", plugin);
	}
}
