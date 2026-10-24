package com.anvil.scenario;

import com.anvil.BingoApiClient;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Everything the harness does to the plugin's traffic, all from the outside:
 *
 * <ul>
 * <li><b>Site override.</b> The plugin's site is a compile-time constant (anvilosrs.com), so the
 * harness wraps the BingoApiClient's two OkHttp clients (reflection on its private fields — test
 * code only) with an interceptor that re-hosts anvilosrs.com requests onto the chosen site. Sign-in
 * included, so the side panel's Sign in button gives a token for THAT site.</li>
 * <li><b>Scenario header.</b> While a scenario runs (and for a grace period after, because pushes
 * are coalesced) every plugin request carries {@code X-Anvil-Scenario: <name>}, so a site can tell a
 * replayed pet from a real one.</li>
 * <li><b>Sync.</b> {@code GET /api/plugin/lab/scenarios} with the plugin's own auth headers.</li>
 * </ul>
 */
final class SiteBridge
{
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SiteBridge.class);

	static final String HEADER = "X-Anvil-Scenario";
	private static final long HEADER_GRACE_MS = 120_000;

	private volatile HttpUrl override;
	private volatile String scenario;
	private volatile long scenarioUntil;
	private boolean installed;

	/** null/blank/"off" clears it. Throws on a URL that doesn't parse. */
	void setSite(String url)
	{
		if (url == null || url.isBlank() || url.equalsIgnoreCase("off"))
		{
			override = null;
			return;
		}
		HttpUrl parsed = HttpUrl.parse(url.trim());
		if (parsed == null)
		{
			throw new IllegalArgumentException("not a URL: " + url);
		}
		override = parsed;
	}

	String site()
	{
		HttpUrl o = override;
		return o == null ? BingoApiClient.CANONICAL_SITE : o.toString().replaceAll("/$", "");
	}

	boolean overridden()
	{
		return override != null;
	}

	void scenarioStarted(String name)
	{
		scenario = name;
		scenarioUntil = Long.MAX_VALUE;
	}

	void scenarioEnded()
	{
		scenarioUntil = System.currentTimeMillis() + HEADER_GRACE_MS;
	}

	/** Wrap the api client's HTTP clients once. Returns false (and logs) if the fields moved. */
	boolean install(BingoApiClient api)
	{
		if (installed)
		{
			return true;
		}
		try
		{
			for (String fieldName : new String[]{"httpClient", "uploadClient"})
			{
				Field f = BingoApiClient.class.getDeclaredField(fieldName);
				f.setAccessible(true);
				OkHttpClient original = (OkHttpClient) f.get(api);
				f.set(api, original.newBuilder().addInterceptor(this::intercept).build());
			}
			installed = true;
			return true;
		}
		catch (ReflectiveOperationException | RuntimeException e)
		{
			log.warn("scenario harness: couldn't wrap BingoApiClient HTTP clients", e);
			return false;
		}
	}

	private Response intercept(Interceptor.Chain chain) throws IOException
	{
		Request req = chain.request();
		HttpUrl o = override;
		Request.Builder b = req.newBuilder();
		boolean changed = false;
		if (o != null && "anvilosrs.com".equalsIgnoreCase(req.url().host()))
		{
			HttpUrl.Builder u = req.url().newBuilder().scheme(o.scheme()).host(o.host()).port(o.port());
			// A base with a path prefix (rare) is prepended.
			List<String> prefix = new ArrayList<>(o.pathSegments());
			prefix.removeIf(String::isEmpty);
			if (!prefix.isEmpty())
			{
				List<String> segs = new ArrayList<>(prefix);
				segs.addAll(req.url().pathSegments());
				u.encodedPath("/");
				for (String s : segs)
				{
					u.addPathSegment(s);
				}
			}
			b.url(u.build());
			changed = true;
		}
		String s = scenario;
		if (s != null && System.currentTimeMillis() < scenarioUntil)
		{
			b.header(HEADER, s);
			changed = true;
		}
		return chain.proceed(changed ? b.build() : req);
	}

	/**
	 * Device sign-in against {@link #site()}. The side panel's Sign in refuses any login page that
	 * isn't on anvilosrs.com (DeviceSignIn.isConfiguredHomeUrl, a real safeguard), so with an override
	 * the harness runs the same RFC 8628 flow itself. Blocking — call off the client thread. Returns
	 * the token, or null after reporting why through {@code status}.
	 */
	String signIn(OkHttpClient http, Gson gson, java.util.function.Consumer<String> status,
		java.util.function.Consumer<String> openUrl) throws InterruptedException
	{
		BingoApiClient.DeviceAuthStart start;
		Request startReq = new Request.Builder().url(site() + "/api/plugin/auth/start")
			.header("X-Anvil-Plugin-Version", pluginVersion())
			.post(okhttp3.RequestBody.create(null, new byte[0])).build();
		try (Response r = http.newCall(startReq).execute())
		{
			if (!r.isSuccessful() || r.body() == null)
			{
				status.accept("sign-in start failed: HTTP " + r.code() + " from " + site());
				return null;
			}
			start = gson.fromJson(r.body().charStream(), BingoApiClient.DeviceAuthStart.class);
		}
		catch (IOException | RuntimeException e)
		{
			status.accept("sign-in start failed: " + e.getMessage());
			return null;
		}
		String url = start.verification_url_complete != null ? start.verification_url_complete : start.verification_url;
		status.accept("approve code " + start.user_code + " at " + url);
		openUrl.accept(url);
		long interval = Math.max(1, start.interval > 0 ? start.interval : 5) * 1000L;
		long deadline = System.currentTimeMillis() + (start.expires_in > 0 ? start.expires_in : 600) * 1000L;
		okhttp3.MediaType json = okhttp3.MediaType.parse("application/json");
		while (System.currentTimeMillis() < deadline)
		{
			Thread.sleep(interval);
			Request poll = new Request.Builder().url(site() + "/api/plugin/auth/poll")
				.header("X-Anvil-Plugin-Version", pluginVersion())
				.post(okhttp3.RequestBody.create(json,
					gson.toJson(java.util.Collections.singletonMap("device_code", start.device_code)))).build();
			try (Response r = http.newCall(poll).execute())
			{
				if (!r.isSuccessful() || r.body() == null)
				{
					continue;
				}
				BingoApiClient.DeviceAuthPoll p = gson.fromJson(r.body().charStream(), BingoApiClient.DeviceAuthPoll.class);
				if ("complete".equals(p.status) && p.token != null)
				{
					return p.token;
				}
				if ("expired".equals(p.status) || "denied".equals(p.status))
				{
					status.accept("sign-in " + p.status);
					return null;
				}
				if ("slow_down".equals(p.status))
				{
					interval += 5000;
				}
			}
			catch (IOException | RuntimeException e)
			{
				// transient — keep polling until the deadline
			}
		}
		status.accept("sign-in timed out");
		return null;
	}

	/** Same resource BingoApiClient reads (its constant is package-private to com.anvil). */
	static String pluginVersion()
	{
		try (java.io.InputStream in = BingoApiClient.class.getResourceAsStream("version.txt"))
		{
			return in == null ? "unknown" : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
		}
		catch (IOException e)
		{
			return "unknown";
		}
	}

	/** Outcome of a sync, for the chat line. */
	static final class SyncResult
	{
		final int count;
		final String error;

		SyncResult(int count, String error)
		{
			this.count = count;
			this.error = error;
		}
	}

	/**
	 * Fetch the site's scenarios and mirror them into {@code siteDir} (replacing what was there, so a
	 * scenario deleted on the site disappears here). Blocking — call off the client thread.
	 */
	SyncResult sync(OkHttpClient http, Gson gson, String token, String rsn, long accountHash, Path siteDir)
	{
		if (token == null || token.isBlank())
		{
			return new SyncResult(0, "not signed in — sign in from the Anvil side panel first");
		}
		Request.Builder rb = new Request.Builder().url(site() + "/api/plugin/lab/scenarios")
			.header("Authorization", "Bearer " + token)
			.header("X-Anvil-Plugin-Version", pluginVersion());
		if (rsn != null && !rsn.isEmpty())
		{
			rb.header("X-RSN", rsn);
		}
		if (accountHash != -1)
		{
			rb.header("X-Account-Hash", Long.toString(accountHash));
		}
		try (Response r = http.newCall(rb.build()).execute())
		{
			if (r.code() == 403)
			{
				return new SyncResult(0, "403 — this account isn't platform staff on " + site());
			}
			if (!r.isSuccessful() || r.body() == null)
			{
				return new SyncResult(0, "HTTP " + r.code() + " from " + site());
			}
			JsonElement root = new JsonParser().parse(r.body().string());
			JsonArray arr = root.getAsJsonObject().getAsJsonArray("scenarios");
			List<JsonObject> valid = new ArrayList<>();
			List<String> bad = new ArrayList<>();
			for (JsonElement e : arr)
			{
				try
				{
					JsonObject o = e.getAsJsonObject();
					Scenario.fromJson(o, Scenario.Origin.SITE); // validates the name (it becomes a file name) + steps
					valid.add(o);
				}
				catch (RuntimeException ex)
				{
					bad.add(ex.getMessage());
				}
			}
			Files.createDirectories(siteDir);
			try (DirectoryStream<Path> old = Files.newDirectoryStream(siteDir, "*.json"))
			{
				for (Path p : old)
				{
					Files.deleteIfExists(p);
				}
			}
			for (JsonObject o : valid)
			{
				Files.writeString(siteDir.resolve(o.get("name").getAsString() + ".json"),
					gson.newBuilder().setPrettyPrinting().create().toJson(o));
			}
			return new SyncResult(valid.size(), bad.isEmpty() ? null : "skipped " + bad.size() + " invalid: " + bad);
		}
		catch (IOException | RuntimeException e)
		{
			return new SyncResult(0, "sync failed: " + e.getMessage());
		}
	}
}
