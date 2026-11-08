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
}
