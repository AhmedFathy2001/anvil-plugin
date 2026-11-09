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
	public void clogUnlockSendsTheItemWithItsKillOnlyWhenThereIsOne() throws IOException
	{
		BingoApiClient c = client();
		c.submitClogUnlock("Primordial crystal", "Cerberus", 118);
		c.submitClogUnlock("Pet chaos elemental", null, null);
		assertTrue(seen.get(0).url().encodedPath().endsWith("/api/plugin/clog"));
		assertEquals("{\"unlocks\":[{\"name\":\"Primordial crystal\",\"kcName\":\"Cerberus\",\"kc\":118}]}", bodies.get(0));
		assertEquals("{\"unlocks\":[{\"name\":\"Pet chaos elemental\"}]}", bodies.get(1));
	}

	@Test
	public void theWorldRidesEveryRequestAndIsFrozenIntoNotifications() throws Exception
	{
		BingoApiClient c = client();
		Map<String, Integer> kc = new LinkedHashMap<>();
		kc.put("Zulrah", 5);
		c.submitStatKc(kc);
		assertNull(seen.get(0).header("X-World-Types"));
		assertNull(seen.get(0).header("X-World-Activity"));

		c.setWorld("DEADMAN,MEMBERS", "Deadman");
		c.submitStatKc(kc);
		assertEquals("DEADMAN,MEMBERS", seen.get(1).header("X-World-Types"));
		assertEquals("Deadman", seen.get(1).header("X-World-Activity"));

		// Async: wait for the notification to reach the interceptor.
		int before = seen.size();
		c.postNotification("rareDrops", null, new NotifyFacts("drop", "Zezima").put("item", "Twisted bow"), null, null);
		long until = System.currentTimeMillis() + 5000;
		while (seen.size() == before && System.currentTimeMillis() < until)
		{
			Thread.sleep(10);
		}
		String body = bodies.get(before);
		assertTrue(body, body.contains("\"world\":{\"types\":\"DEADMAN,MEMBERS\",\"activity\":\"Deadman\"}"));
	}

	@Test
	public void modeLinesCanCarryTheirWorldAndScreenshot() throws Exception
	{
		BingoApiClient c = client();
		c.setWorld("DEADMAN,MEMBERS", "Deadman");
		c.submitModeLines(Collections.singletonList("A hard task"), new byte[]{1, 2, 3}, "anvil-mode.png");
		Request request = seen.get(0);
		assertTrue(request.url().encodedPath().endsWith("/api/plugin/mode-lines"));
		assertTrue(request.body().contentType().toString(), request.body().contentType().toString().startsWith("multipart/form-data"));
		String body = bodies.get(0);
		assertTrue(body, body.contains("name=\"payload_json\""));
		assertTrue(body, body.contains("\"lines\":[\"A hard task\"]"));
		assertTrue(body, body.contains("\"world\":{\"types\":\"DEADMAN,MEMBERS\",\"activity\":\"Deadman\"}"));
		assertTrue(body, body.contains("filename=\"anvil-mode.png\""));
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
		// A 4xx the site will always refuse is permanent, so CoalescedPush drops the batch instead of
		// resending it forever; a 401 (fired before X-RSN was stamped at login) stays retryable.
		code = 400;
		reply = "";
		try
		{
			c.submitStatActivities(Collections.singletonMap("cluesElite", 3));
			fail();
		}
		catch (BingoApiClient.PermanentSubmissionException e)
		{
			assertEquals("Activity push failed: HTTP 400 — no body", e.getMessage());
		}
		code = 401;
		try
		{
			c.submitStatXp(Collections.singletonMap("mining", 11));
			fail();
		}
		catch (BingoApiClient.PermanentSubmissionException e)
		{
			fail("a 401 at login clears once X-RSN is stamped");
		}
		catch (IOException e)
		{
			assertEquals("Skill XP push failed: HTTP 401 — no body", e.getMessage());
		}
	}

	/** Every credit goes as facts to the addressed clan, saying it reads other boards' tiles; a 4xx is for good. */
	@Test
	public void factsGoToTheClanAndAreClassified() throws IOException
	{
		BingoApiClient c = client();
		c.setChosenClan("home");
		reply = "{\"credits\":[]}";
		com.google.gson.JsonArray facts = new com.google.gson.JsonArray();
		facts.add(RuleEngine.tile(2, 1, "sec", 60));
		assertEquals("{\"credits\":[]}", c.postFacts(facts, null, null).toString());
		assertEquals("/c/home/api/plugin/facts", seen.get(0).url().encodedPath());
		assertEquals("1", seen.get(0).header("X-Anvil-Boards"));
		assertTrue(bodies.get(0).contains("\"facts\":[{\"k\":\"tile\",\"t\":2,\"n\":1,\"sec\":60}]"));
		assertFalse(bodies.get(0).contains("proofPending"));
		// A screenshot-worthy batch: still JSON, the proof meta along, and the picture promised for later.
		com.google.gson.JsonObject proof = new com.google.gson.JsonObject();
		proof.addProperty("tz", "UTC");
		c.postFacts(facts, proof, null);
		assertEquals("json", seen.get(1).body().contentType().subtype());
		assertTrue(bodies.get(1).contains("\"proof\":{\"tz\":\"UTC\"},\"proofPending\":true"));
		// The screenshot: multipart to /facts/proof with the key, the meta and the image.
		byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2};
		reply = "{\"ok\":true,\"attached\":1}";
		c.postProof("abcdefghijklmnopqrstuvwx", jpeg, proof);
		assertEquals("/c/home/api/plugin/facts/proof", seen.get(2).url().encodedPath());
		assertEquals("multipart", seen.get(2).body().contentType().type());
		assertTrue(bodies.get(2).contains("name=\"key\""));
		assertTrue(bodies.get(2).contains("abcdefghijklmnopqrstuvwx"));
		assertTrue(bodies.get(2).contains("name=\"file\"; filename=\"proof.jpg\""));
		// Too late: the site reversed those credits, and that is final.
		code = 410;
		reply = "{\"error\":\"Too late\"}";
		try
		{
			c.postProof("abcdefghijklmnopqrstuvwx", jpeg, proof);
			fail();
		}
		catch (BingoApiClient.PermanentSubmissionException e)
		{
			assertEquals(410, e.code);
		}
		code = 409;
		reply = "{\"error\":\"done\"}";
		try
		{
			c.postFacts(facts, null, null);
			fail();
		}
		catch (BingoApiClient.PermanentSubmissionException e)
		{
			assertEquals("Facts: HTTP 409 — {\"error\":\"done\"}", e.getMessage());
		}
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
	public void panelViewsNameTheCharacterUnlessItIsPaused()
	{
		BingoApiClient c = client();
		c.setCurrentRsn("Zezima");
		reply = "{\"ui\":{\"t\":\"text\",\"s\":\"hi\"}}";
		Map<String, String> args = new LinkedHashMap<>();
		args.put("rsn", "Lynx Titan");
		assertEquals("hi", c.fetchUi("player", args, "theafkspot").getAsJsonObject("ui").get("s").getAsString());
		Request r = seen.get(0);
		assertTrue(r.url().encodedPath().endsWith("/api/plugin/ui"));
		assertEquals("player", r.url().queryParameter("v"));
		assertEquals("Lynx Titan", r.url().queryParameter("rsn"));
		assertEquals("theafkspot", r.url().queryParameter("clan"));
		assertEquals("Bearer tok", r.header("Authorization"));
		assertEquals("Zezima", r.header("X-RSN"));

		c.setAccountPaused(true);
		c.postUi("goal-remove", Collections.singletonMap("id", "4"), Collections.emptyMap());
		assertNull("a paused character is never named", seen.get(1).header("X-RSN"));
		assertEquals("POST", seen.get(1).method());
		assertTrue(bodies.get(1).contains("\"act\":\"goal-remove\""));
		assertTrue(bodies.get(1).contains("\"id\":\"4\""));

		code = 500;
		assertNull(c.fetchUi("you", Collections.emptyMap(), null));
	}

	@Test
	public void siteLinksStayOnTheSitesHost()
	{
		BingoApiClient c = client();
		String link = c.siteLink("/p/Zezima?tab=records");
		assertTrue(link, link.endsWith("/p/Zezima?tab=records"));
		assertEquals(okhttp3.HttpUrl.parse(c.rootUrl("/")).host(), okhttp3.HttpUrl.parse(link).host());
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

	@Test
	public void tokenOptionalCallsCarryThePluginVersion() throws IOException
	{
		BingoApiClient c = client();
		c.hello("Main Guy");
		for (Request r : seen)
		{
			assertEquals(BingoApiClient.PLUGIN_VERSION, r.header("X-Anvil-Plugin-Version"));
			assertEquals("Bearer tok", r.header("Authorization"));
		}
	}

	@Test
	public void everyRefusalIsTypedWithItsStatus()
	{
		BingoApiClient c = client();
		code = 503;
		try
		{
			c.submitEventCounters(1, 2, 3, 4, 5, 6);
			fail();
		}
		catch (IOException e)
		{
			assertTrue(e instanceof BingoApiClient.HttpStatusException);
			assertEquals(503, ((BingoApiClient.HttpStatusException) e).code);
			assertEquals("Counter push failed: HTTP 503 — {}", e.getMessage());
		}
		code = 401;
		try
		{
			c.uploadImage(new byte[]{1}, "a.png");
			fail();
		}
		catch (IOException e)
		{
			assertEquals(401, ((BingoApiClient.HttpStatusException) e).code);
			assertEquals("Image upload failed: HTTP 401", e.getMessage());
		}
	}
}
