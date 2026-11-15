package com.anvil;

import com.google.gson.Gson;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.runelite.client.util.Filepath;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The on-disk stores went unexercised for a while (they never got their folders), so whatever users
 * have lying around — no folder, an empty one, a half-written file — must read as "nothing there".
 */
public class DiskFilesTest
{
	private Path tmp;
	private Filepath root;

	@Before
	public void setUp() throws Exception
	{
		tmp = Files.createTempDirectory("anvil-diskfiles");
		// Filepath has no public way in outside the client; a test-only reflective root.
		Constructor<Filepath> c = Filepath.class.getDeclaredConstructor(Path.class, Path.class);
		c.setAccessible(true);
		root = c.newInstance(tmp, tmp);
	}

	@After
	public void tearDown() throws Exception
	{
		root.deleteRecursively();
	}

	@Test
	public void absentEmptyAndTruncatedFilesReadAsNothing() throws Exception
	{
		Gson gson = new Gson();
		Filepath dir = root.joinSegment("missing");
		assertTrue(DiskFiles.listFiles(dir, n -> true).isEmpty());
		assertTrue(DiskFiles.listFiles(null, n -> true).isEmpty());
		assertNull(DiskFiles.readJson(gson, dir.joinSegment("x.json"), PushStash.Data.class));
		assertNull(DiskFiles.readJson(gson, null, PushStash.Data.class));

		Filepath empty = root.joinSegment("empty.json");
		empty.write(new byte[0]);
		assertNull(DiskFiles.readJson(gson, empty, PushStash.Data.class));
		Filepath cut = root.joinSegment("cut.json");
		cut.write("{\"savedAt\": 5, \"kc\": {\"Zul");
		assertNull(DiskFiles.readJson(gson, cut, PushStash.Data.class));
	}

	@Test
	public void listFilesIsOneLevelAndFiltersByName() throws Exception
	{
		root.joinSegment("a.json").write("{}");
		root.joinSegment("b.png").write("x");
		Filepath sub = root.joinSegment("sounds");
		sub.createDirectories();
		sub.joinSegment("c.json").write("{}");
		List<Filepath> json = DiskFiles.listFiles(root, n -> n.endsWith(".json"));
		assertEquals(1, json.size());
		assertEquals("a.json", json.get(0).getFileName());
	}

	@Test
	public void pendingStoreSkipsBrokenFilesAndRoundTrips() throws Exception
	{
		PendingSubmissionStore store = new PendingSubmissionStore(new Gson());
		store.setRoot(root.joinSegment("pending"));
		assertTrue(store.loadAll().isEmpty());

		PendingSubmissionStore.PendingSubmission p = new PendingSubmissionStore.PendingSubmission();
		p.tileId = 7;
		p.timestamp = System.currentTimeMillis();
		p.label = "Vorkath head";
		p.clan = "rivals";
		assertNotNull(store.save(p, new byte[]{1, 2, 3}));
		root.joinSegment("pending").joinSegment("1-1.json").write(new byte[0]);
		root.joinSegment("pending").joinSegment("2-2.json").write("{\"tileId\": 2, \"lab");

		List<PendingSubmissionStore.PendingSubmission> all = store.loadAll();
		assertEquals(1, all.size());
		assertEquals("Vorkath head", all.get(0).label);
		assertEquals("rivals", all.get(0).clan);
		assertEquals(3, store.readScreenshot(all.get(0)).length);
	}

	/** A proof an older build saved for one tile is replayed as a `tile` fact; facts and manual proofs load as they are. */
	@Test
	public void pendingStoreTurnsAnOlderBuildsProofIntoATileFact() throws Exception
	{
		PendingSubmissionStore store = new PendingSubmissionStore(new Gson());
		store.setRoot(root.joinSegment("pending"));
		Filepath dir = root.joinSegment("pending");
		dir.createDirectories();
		dir.joinSegment("9-1.json").write("{\"tileId\":9,\"amount\":1,\"label\":\"Inferno\",\"note\":\"[Auto] Inferno cleared\","
			+ "\"durationSeconds\":3000,\"timestamp\":" + System.currentTimeMillis() + ",\"screenshotFile\":\"9-1.jpg\"}");
		dir.joinSegment("8-1.json").write("{\"tileId\":8,\"facts\":[{\"k\":\"loot\"}],\"timestamp\":" + System.currentTimeMillis() + "}");
		dir.joinSegment("7-1.json").write("{\"tileId\":7,\"manual\":true,\"timestamp\":" + System.currentTimeMillis() + "}");
		java.util.Map<Integer, PendingSubmissionStore.PendingSubmission> byTile = new java.util.HashMap<>();
		for (PendingSubmissionStore.PendingSubmission p : store.loadAll()) byTile.put(p.tileId, p);
		assertEquals("[{\"k\":\"tile\",\"t\":9,\"n\":1,\"sec\":3000,\"note\":\"[Auto] Inferno cleared\"}]", byTile.get(9).facts.toString());
		assertEquals("[{\"k\":\"loot\"}]", byTile.get(8).facts.toString());
		assertNull(byTile.get(7).facts);
	}

	@Test
	public void stashAndCacheTreatCorruptFilesAsNothingSaved() throws Exception
	{
		Gson gson = new Gson();
		PushStash stash = new PushStash(gson);
		stash.setDir(root.joinSegment("outbox"));
		assertNull(stash.take("k", 0));
		root.joinSegment("outbox").joinSegment("pushes-k.json").write("{\"savedAt\":");
		assertNull(stash.take("k", 0));
		stash.clearAll();

		ResponseDiskCache cache = new ResponseDiskCache(gson);
		cache.setDir(root.joinSegment("cache"));
		cache.switchAccount("token");
		assertNull(cache.load(ResponseDiskCache.CONFIG));
		cache.store(ResponseDiskCache.CONFIG, "u", "e", "{}", null);
		assertEquals("{}", cache.load(ResponseDiskCache.CONFIG).body);
	}

	/** What a proof still owes (facts to credit, keys to upload to) is rewritten in place; the screenshot stays. */
	@Test
	public void aProofKeepsWhatItStillOwes() throws Exception
	{
		PendingSubmissionStore store = new PendingSubmissionStore(new Gson());
		store.setRoot(root.joinSegment("pending"));
		PendingSubmissionStore.PendingSubmission p = new PendingSubmissionStore.PendingSubmission();
		p.timestamp = System.currentTimeMillis();
		p.facts = new Gson().fromJson("[{\"k\":\"loot\",\"items\":[[10,1]]}]", com.google.gson.JsonArray.class);
		assertNotNull(store.save(p, new byte[]{1, 2, 3}));
		p.facts = new Gson().fromJson("[{\"k\":\"loot\",\"items\":[[10,1]],\"done\":[1]}]", com.google.gson.JsonArray.class);
		p.keys.addProperty("key-1", "Whip, Fang");
		p.keyAt = 42;
		store.update(p);

		List<PendingSubmissionStore.PendingSubmission> all = store.loadAll();
		assertEquals(1, all.size());
		assertEquals("[{\"k\":\"loot\",\"items\":[[10,1]],\"done\":[1]}]", all.get(0).facts.toString());
		assertEquals("Whip, Fang", all.get(0).keys.get("key-1").getAsString());
		assertEquals(42, all.get(0).keyAt);
		assertEquals(3, store.readScreenshot(all.get(0)).length);
		assertNull(PendingSubmissionStore.retries(new Gson().fromJson("{\"retry\":[]}", com.google.gson.JsonObject.class)));
	}

	// ── Unsent facts (drop credits) across a client close ───────────────────────────────────────

	private static com.google.gson.JsonObject fact(int tile)
	{
		return new Gson().fromJson("{\"k\":\"tile\",\"t\":" + tile + ",\"n\":1}", com.google.gson.JsonObject.class);
	}

	private PushStash stash()
	{
		PushStash stash = new PushStash(new Gson());
		stash.setDir(root.joinSegment("outbox"));
		return stash;
	}

	@Test
	public void unsentFactsAndMomentsSurviveARestartAndAreHandedBackOnce()
	{
		PushStash.Data d = new PushStash.Data();
		d.savedAt = 1_000;
		d.account = "signin-a";
		d.facts.add(fact(7));
		d.facts.add(fact(8));
		d.moments.add(new AnvilMoments.Moment("pet", 12921, "Pet snakeling", 1, 5L, "Zulrah", "npc", 44, 900, "k1"));
		assertTrue(!d.isEmpty());
		stash().save("char", d);

		// A new client: nothing in memory, only the file.
		PushStash.Data back = stash().take("char", 2_000);
		assertEquals("signin-a", back.account);
		assertEquals("[{\"k\":\"tile\",\"t\":7,\"n\":1},{\"k\":\"tile\",\"t\":8,\"n\":1}]", back.facts.toString());
		// The moment's final fields come back as saved, with no constructor to run.
		AnvilMoments.Moment m = back.moments.get(0);
		assertEquals("pet", m.kind);
		assertEquals(Integer.valueOf(12921), m.itemId);
		assertEquals(Long.valueOf(5), m.valueGp);
		assertEquals(Integer.valueOf(44), m.kc);
		assertNull(m.taskName);
		assertEquals(900, m.at);
		assertEquals("k1", m.key);

		// Taking deletes: a client that dies after the resend finds nothing to send a second time.
		assertNull(stash().take("char", 2_000));
	}

	@Test
	public void staleFactsAreDroppedAndAnOlderFileStillReads() throws Exception
	{
		PushStash.Data d = new PushStash.Data();
		d.savedAt = 1_000;
		d.facts.add(fact(7));
		stash().save("char", d);
		assertNull(stash().take("char", 1_000 + PushStash.MAX_AGE_MS + 1));
		assertNull(stash().take("char", 1_001));

		// Written by a build that knew no facts: none, not null, and its moment under the same names.
		root.joinSegment("outbox").joinSegment("pushes-old.json").write(
			"{\"savedAt\":5,\"kc\":{\"Zulrah\":3},\"moments\":[{\"kind\":\"death\",\"quantity\":1,\"at\":4,\"key\":\"k9\"}]}");
		PushStash.Data old = stash().take("old", 6);
		assertEquals(0, old.facts.size());
		assertNull(old.account);
		assertEquals("k9", old.moments.get(0).key);
		assertEquals("death", old.moments.get(0).kind);
	}

	@Test
	public void factsMergeInOrderBoundedAndNeverAcrossSignIns()
	{
		PushStash.Data older = new PushStash.Data();
		older.account = "signin-a";
		for (int i = 1; i <= 40; i++) older.facts.add(fact(i));
		PushStash.Data newer = new PushStash.Data();
		newer.account = "signin-a";
		for (int i = 41; i <= 60; i++) newer.facts.add(fact(i));
		PushStash.Data m = PushStash.merge(older, newer);
		assertEquals(50, m.facts.size());
		assertEquals(11, m.facts.get(0).getAsJsonObject().get("t").getAsInt());
		assertEquals(60, m.facts.get(49).getAsJsonObject().get("t").getAsInt());
		assertEquals("signin-a", m.account);

		// Saved under another sign-in: the older facts are not carried into the newer one's file.
		newer.account = "signin-b";
		PushStash.Data other = PushStash.merge(older, newer);
		assertEquals(20, other.facts.size());
		assertEquals(41, other.facts.get(0).getAsJsonObject().get("t").getAsInt());
		assertEquals("signin-b", other.account);
	}

	@Test
	public void drainEmptiesTheQueueAndReturnsWhatItHeld()
	{
		com.google.gson.JsonArray q = new com.google.gson.JsonArray();
		q.add(fact(1));
		q.add(fact(2));
		com.google.gson.JsonArray out = PushStash.drain(q);
		assertEquals(0, q.size());
		assertEquals(2, out.size());
	}

	@Test
	public void pluginStashesQueuedFactsPerCharacterAndResendsOnlyUnderTheSameSignIn() throws Exception
	{
		Gson gson = new Gson();
		BingoApiClient api = new BingoApiClient(gson, new okhttp3.OkHttpClient());
		api.configure("token-a");
		api.setCurrentRsn("Zezima");
		AnvilPlugin p = new AnvilPlugin();
		p.apiClient = api;
		p.pushStash = stash();
		p.factQueue.add(fact(7));

		Runnable save = p.stashPushes();
		assertEquals("taken out of memory, so nothing sends it as nobody after a logout", 0, p.factQueue.size());
		save.run();
		String key = PushStash.keyFor("Zezima", null);
		PushStash.Data onDisk = DiskFiles.readJson(gson, root.joinSegment("outbox").joinSegment("pushes-" + key + ".json"), PushStash.Data.class);
		assertEquals(1, onDisk.facts.size());
		assertEquals(DiskFiles.fingerprint("token-a"), onDisk.account);

		// Another sign-in on this computer logs the same character in: the facts are not sent.
		api.configure("token-b");
		p.restorePushes("Zezima", null);
		assertEquals(0, p.factQueue.size());
		assertNull("and they are gone, not waiting for a third sign-in", stash().take(key, System.currentTimeMillis()));

		// The same sign-in: back in the queue (the executor, absent here, would send them).
		api.configure("token-a");
		p.factQueue.add(fact(9));
		p.stashPushes().run();
		p.executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
		try
		{
			p.restorePushes("Zezima", null);
			assertEquals(1, p.factQueue.size());
			assertEquals(9, p.factQueue.get(0).getAsJsonObject().get("t").getAsInt());
		}
		finally
		{
			p.executor.shutdownNow();
		}
	}
}
