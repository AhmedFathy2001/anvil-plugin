package com.anvil;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The shared request helpers in BingoApiClient, driven through a canned-response interceptor. */
public class ApiClientHttpTest
{
	private final List<Request> seen = new ArrayList<>();
	private final List<String> bodies = new ArrayList<>();
	private int code = 200;
	private String reply = "{}";
	private String etag;

	private BingoApiClient client()
	{
		OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain ->
		{
			Request r = chain.request();
			seen.add(r);
			Buffer b = new Buffer();
			if (r.body() != null)
			{
				r.body().writeTo(b);
			}
			bodies.add(b.readUtf8());
			Response.Builder rb = new Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(code).message("x")
				.body(ResponseBody.create(MediaType.parse("application/json"), reply));
			if (etag != null)
			{
				rb.header("ETag", etag);
			}
			return rb.build();
		}).build();
		BingoApiClient c = new BingoApiClient(new Gson(), http);
		c.configure("tok");
		return c;
	}

	@Test
	public void conditionalGetReusesTheCachedBodyOn304() throws IOException
	{
		BingoApiClient c = client();
		etag = "\"v1\"";
		reply = "{\"clanName\":\"Foo\"}";
		PluginConfigResponse first = c.fetchConfig();
		assertEquals("Foo", first.clanName);
		assertNull(seen.get(0).header("If-None-Match"));
		code = 304;
		assertSame(first, c.fetchConfig());
		assertEquals("\"v1\"", seen.get(1).header("If-None-Match"));
	}

	@Test
	public void configFetch404IsTheNoClanState()
	{
		BingoApiClient c = client();
		code = 404;
		try
		{
			c.fetchConfig();
			fail();
		}
		catch (IOException e)
		{
			assertEquals("Config fetch failed: HTTP 404", e.getMessage());
		}
		assertTrue(c.signedInWithoutClan());
		code = 500;
		assertNull(c.fetchBoard());
	}

	@Test
	public void statPushesShareOneShapeAndPlainErrors() throws IOException
	{
		BingoApiClient c = client();
		Map<String, Integer> kc = new LinkedHashMap<>();
		kc.put("Zulrah", 5);
		kc.put(null, 1);
		c.submitStatKc(kc);
		assertEquals("{\"stats\":[{\"name\":\"Zulrah\",\"kc\":5}]}", bodies.get(0));
		c.submitStatXp(Collections.singletonMap("mining", 10));
		assertEquals("{\"skills\":[{\"name\":\"mining\",\"xp\":10}]}", bodies.get(1));
		code = 400;
		reply = "";
		try
		{
			c.submitStatActivities(Collections.singletonMap("cluesElite", 3));
			fail();
		}
		catch (BingoApiClient.PermanentSubmissionException e)
		{
			fail("stat pushes are never classified permanent");
		}
		catch (IOException e)
		{
			assertEquals("Activity push failed: HTTP 400 — no body", e.getMessage());
		}
	}

	@Test
	public void submissionsAreClassified()
	{
		BingoApiClient c = client();
		code = 409;
		reply = "{\"error\":\"done\"}";
		try
		{
			c.submitTimed(1, 2, 3, 60, "u", "n", 4);
			fail();
		}
		catch (BingoApiClient.PermanentSubmissionException e)
		{
			assertEquals("Timed submission failed: HTTP 409 — {\"error\":\"done\"}", e.getMessage());
		}
		catch (IOException e)
		{
			fail("4xx is permanent");
		}
		assertTrue(bodies.get(0).contains("\"durationSeconds\":60"));
		assertTrue(bodies.get(0).contains("\"amount\":1"));
	}

	@Test
	public void wholeLogPushSpeaksToPeople() throws IOException
	{
		BingoApiClient c = client();
		reply = "{\"added\":2}";
		Map<Integer, Integer> items = new LinkedHashMap<>();
		items.put(4151, 1);
		assertEquals(2, c.submitClogItems(items).added);
		assertEquals("{\"items\":[{\"id\":4151,\"q\":1}]}", bodies.get(0));
		code = 429;
		reply = "{\"error\":\"Slow down\",\"retryAfterMs\":49445}";
		try
		{
			c.submitClogItems(items);
			fail();
		}
		catch (BingoApiClient.RateLimitedException e)
		{
			assertEquals("Slow down", e.getMessage());
			assertEquals(49445, e.retryAfterMs);
		}
		code = 200;
		reply = "not json";
		assertEquals(0, c.submitClogItems(items).added);
	}

	@Test
	public void publicReadsCarryOnlyTheToken()
	{
		BingoApiClient c = client();
		c.setCurrentRsn("Zezima");
		reply = "{\"clans\":[]}";
		assertFalse(c.lookupClans("foo") == null);
		assertNull("lookups are anonymous", seen.get(0).header("Authorization"));
		code = 404;
		assertTrue(c.fetchPlayerCard("Zezima").notFound);
		assertEquals("Bearer tok", seen.get(1).header("Authorization"));
		assertNull(seen.get(1).header("X-RSN"));
	}

	@Test
	public void progressPushKeepsItsWireShape() throws IOException
	{
		BingoApiClient c = client();
		Map<String, Integer> progress = new LinkedHashMap<>();
		progress.put("questPoints", 300);
		progress.put(AccountProgress.ACCOUNT_TYPE_KEY, 1);
		Map<Integer, Integer> varps = new LinkedHashMap<>();
		varps.put(3116, 7);
		c.submitProgress(progress, "quest", Collections.singletonList(new AccountProgress.Item(5, "Cook's Assistant", 2)), varps, 40);
		assertEquals("{\"progress\":[{\"key\":\"questPoints\",\"value\":300}],\"accountType\":1,"
			+ "\"items\":[{\"category\":\"quest\",\"items\":[{\"id\":5,\"name\":\"Cook's Assistant\",\"state\":2}]}],"
			+ "\"caVarps\":{\"3116\":7},\"caPoints\":40}", bodies.get(0));
	}
}
