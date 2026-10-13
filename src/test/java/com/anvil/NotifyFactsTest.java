package com.anvil;

import com.google.gson.Gson;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
import static org.junit.Assert.assertTrue;

/**
 * The facts a notification carries are the whole contract with the site's lib/pluginEmbeds.ts, so
 * their exact JSON is pinned here; the site's tests pin what it builds from them.
 */
public class NotifyFactsTest
{
	@Test
	public void dropFactsLeaveOutWhatIsUnknown()
	{
		NotifyFacts f = new NotifyFacts("drop", "Zezima").put("source", "Vorkath").put("sourceKind", "npc")
			.put("kc", 54).item(22111, "Dragonbone necklace", 1, 12_345_678L).put("dropRate", 1.0 / 1000)
			.flag("guaranteed", false).flag("troll", false).flag("spoon", true).put("note", (String) null);
		assertEquals("{\"rsn\":\"Zezima\",\"source\":\"Vorkath\",\"sourceKind\":\"npc\",\"kc\":54,"
			+ "\"items\":[{\"id\":22111,\"name\":\"Dragonbone necklace\",\"qty\":1,\"value\":12345678}],"
			+ "\"dropRate\":0.001,\"spoon\":true}", f.facts.toString());
	}

	@Test
	public void aHaulCarriesEveryItemAndNoRsnWhenThereIsNone()
	{
		NotifyFacts f = new NotifyFacts("drop", null).put("source", (String) null).put("kc", (Integer) null)
			.item(1, "Item A", 1, 1_000_000L).item(2, "Item B", 4, 2_000_000L);
		assertEquals("{\"items\":[{\"id\":1,\"name\":\"Item A\",\"qty\":1,\"value\":1000000},"
			+ "{\"id\":2,\"name\":\"Item B\",\"qty\":4,\"value\":2000000}]}", f.facts.toString());
	}

	@Test
	public void achievementFacts()
	{
		assertEquals("{\"rsn\":\"Zezima\",\"task\":\"Into the Den of Giants\",\"tier\":\"Hard\",\"totalPoints\":216,"
				+ "\"nextThreshold\":726}",
			new NotifyFacts("caTask", "Zezima").put("task", "Into the Den of Giants")
				.put("tier", CombatAchievementTier.HARD.getDisplayName()).put("totalPoints", 216)
				.put("nextThreshold", 726).facts.toString());
		assertEquals("{\"rsn\":\"Zezima\",\"quest\":\"Dragon Slayer II\",\"difficulty\":\"Grandmaster\"}",
			new NotifyFacts("quest", "Zezima").put("quest", "Dragon Slayer II").put("difficulty", "Grandmaster")
				.facts.toString());
		assertEquals("{\"rsn\":\"Zezima\",\"total\":1800,\"milestone\":1800,\"current\":1803,\"max\":2277}",
			new NotifyFacts("totalLevel", "Zezima").put("total", 1800).flag("maxed", false).put("milestone", 1800)
				.put("current", 1803).put("max", 2277).facts.toString());
		// Maxed: no milestone, no distance.
		assertEquals("{\"rsn\":\"Zezima\",\"total\":2277,\"maxed\":true}",
			new NotifyFacts("totalLevel", "Zezima").put("total", 2277).flag("maxed", true)
				.put("milestone", (Integer) null).facts.toString());
	}

	@Test
	public void theRequestSendsKindAndFactsNotAnEmbed() throws Exception
	{
		assertEquals("{\"channel\":\"levels\",\"kind\":\"level\",\"facts\":{\"rsn\":\"Zezima\",\"skill\":\"Slayer\","
				+ "\"level\":90,\"at99\":12,\"skills\":23}}",
			send(new NotifyFacts("level", "Zezima").put("skill", "Slayer").put("level", 90).put("at99", 12)
				.put("skills", 23), null));
		// An older site's kind, without the level.
		assertEquals("{\"channel\":\"levels\",\"kind\":\"level99\",\"facts\":{\"rsn\":\"Zezima\",\"skill\":\"Slayer\","
				+ "\"at99\":12,\"skills\":23}}",
			send(new NotifyFacts("level99", "Zezima").put("skill", "Slayer").put("level", (Integer) null).put("at99", 12)
				.put("skills", 23), null));

		// With a screenshot it's multipart: the same JSON as payload_json, plus the named file.
		String body = send(new NotifyFacts("caTier", null).put("tier", "Elite"), new byte[]{1, 2, 3});
		assertTrue(body.contains("{\"channel\":\"levels\",\"kind\":\"caTier\",\"facts\":{\"tier\":\"Elite\"}}"));
		assertTrue(body.contains("filename=\"anvil-shot.png\""));
		assertFalse(body.contains("\"embed\""));
	}

	private static String send(NotifyFacts f, byte[] png) throws InterruptedException
	{
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<String> body = new AtomicReference<>();
		OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain ->
		{
			Request r = chain.request();
			Buffer b = new Buffer();
			r.body().writeTo(b);
			body.set(b.readUtf8());
			done.countDown();
			return new Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("x")
				.body(ResponseBody.create(MediaType.parse("application/json"), "{\"ok\":true}")).build();
		}).build();
		BingoApiClient c = new BingoApiClient(new Gson(), http);
		c.configure("tok");
		c.postNotification("levels", null, f, png, "anvil-shot.png");
		assertTrue(done.await(5, TimeUnit.SECONDS));
		return body.get();
	}
}
