package com.anvil;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What keeps a 1–10 minute site outage from losing notifications, counts and moments, and from
 * turning every queued thing into a request every few seconds: the retry classification, the backoff
 * schedule, the outbox's bounds, and the stash merge.
 */
public class OutageResilienceTest
{
	private ScheduledExecutorService scheduler;
	private ExecutorService pool;

	@Before
	public void setUp()
	{
		scheduler = Executors.newSingleThreadScheduledExecutor();
		pool = Executors.newSingleThreadExecutor();
	}

	@After
	public void tearDown()
	{
		scheduler.shutdownNow();
		pool.shutdownNow();
	}

	// ── RetryPolicy ───────────────────────────────────────────────────────────────────────────

	@Test
	public void permanentIsA4xxOtherThanAuthTimeoutAndRateLimit()
	{
		assertTrue(RetryPolicy.isPermanent(400));
		assertTrue(RetryPolicy.isPermanent(403));
		assertTrue(RetryPolicy.isPermanent(404));
		assertTrue(RetryPolicy.isPermanent(422));
		assertFalse("401 before X-RSN is stamped clears on its own", RetryPolicy.isPermanent(401));
		assertFalse(RetryPolicy.isPermanent(408));
		assertFalse(RetryPolicy.isPermanent(429));
		assertFalse(RetryPolicy.isPermanent(500));
		assertFalse(RetryPolicy.isPermanent(503));
		assertFalse(RetryPolicy.isPermanent(200));
	}

	@Test
	public void backoffDoublesToTheCap()
	{
		// jitter 0.5 = no spread
		assertEquals(0, RetryPolicy.backoffMs(0, 15_000, 300_000, 0.5));
		assertEquals(15_000, RetryPolicy.backoffMs(1, 15_000, 300_000, 0.5));
		assertEquals(30_000, RetryPolicy.backoffMs(2, 15_000, 300_000, 0.5));
		assertEquals(60_000, RetryPolicy.backoffMs(3, 15_000, 300_000, 0.5));
		assertEquals(240_000, RetryPolicy.backoffMs(5, 15_000, 300_000, 0.5));
		assertEquals(300_000, RetryPolicy.backoffMs(6, 15_000, 300_000, 0.5));
		assertEquals(300_000, RetryPolicy.backoffMs(500, 15_000, 300_000, 0.5));
	}

	@Test
	public void jitterSpreadsTwentyPercentAndNeverPassesTheCap()
	{
		assertEquals(12_000, RetryPolicy.backoffMs(1, 15_000, 300_000, 0));
		assertEquals(18_000, RetryPolicy.backoffMs(1, 15_000, 300_000, 1));
		assertEquals(300_000, RetryPolicy.backoffMs(9, 15_000, 300_000, 1));
		assertEquals(240_000, RetryPolicy.backoffMs(9, 15_000, 300_000, 0));
	}

	@Test
	public void syncBackoffTakesItsOwnSchedule()
	{
		SyncBackoff b = new SyncBackoff(30_000, 300_000);
		assertFalse(b.backingOff());
		b.onFailure(0);
		assertTrue(b.backingOff());
		assertFalse(b.ready(29_999));
		assertTrue(b.ready(30_000));
		for (int i = 0; i < 10; i++)
		{
			b.onFailure(0);
		}
		assertTrue("capped at five minutes", b.ready(300_000));
		b.reset();
		assertTrue(b.ready(0));
	}

	// ── CoalescedPush ─────────────────────────────────────────────────────────────────────────

	@Test
	public void coalescedRetryStartsAtTheSettleDelayAndCapsAtFiveMinutes()
	{
		assertEquals(15_000, CoalescedPush.retryDelayMs(1, 15_000, 0.5));
		assertEquals(30_000, CoalescedPush.retryDelayMs(2, 15_000, 0.5));
		assertEquals(CoalescedPush.RETRY_CAP_MS, CoalescedPush.retryDelayMs(20, 15_000, 0.5));
	}

	@Test
	public void aPermanentRefusalDropsTheBatchInsteadOfRetryingForever()
	{
		AtomicInteger sends = new AtomicInteger();
		CoalescedPush push = new CoalescedPush("KC", 60_000, false, () -> scheduler, () -> true, b ->
		{
			sends.incrementAndGet();
			throw new BingoApiClient.PermanentSubmissionException("KC push failed: HTTP 403", 403);
		}, () -> { });
		push.queue("Vorkath", 10);
		push.flush();
		assertEquals(1, sends.get());
		assertTrue("dropped, not requeued", push.snapshot().isEmpty());
	}

	@Test
	public void aTransientFailureKeepsTheLargerValue()
	{
		CoalescedPush push = new CoalescedPush("XP", 60_000, false, () -> scheduler, () -> true, b ->
		{
			throw new IOException("Skill XP push failed: HTTP 401 — X-RSN");
		}, () -> { });
		push.queue("mining", 100);
		push.flush();
		assertEquals(Integer.valueOf(100), push.snapshot().get("mining"));
		push.reset();
	}

	@Test
	public void restoreMergesMaxPerKey()
	{
		CoalescedPush push = new CoalescedPush("KC", 60_000, false, () -> scheduler, () -> true, b -> { }, () -> { });
		push.queue("Zulrah", 50);
		Map<String, Integer> saved = new HashMap<>();
		saved.put("Zulrah", 40);
		saved.put("Vorkath", 7);
		push.restore(saved);
		assertEquals(Integer.valueOf(50), push.snapshot().get("Zulrah"));
		assertEquals(Integer.valueOf(7), push.snapshot().get("Vorkath"));
		push.reset();
	}

	// ── NotifyOutbox ──────────────────────────────────────────────────────────────────────────

	private static NotifyOutbox.Item item(long createdAt, int pngBytes)
	{
		NotifyOutbox.Item it = NotifyOutbox.newItem(createdAt);
		it.url = "https://anvilosrs.com/api/plugin/notify";
		it.channel = "drops";
		it.payload = "{}";
		it.pngBytes = pngBytes;
		return it;
	}

	@Test
	public void overflowDropsExpiredThenOldest()
	{
		long now = NotifyOutbox.MAX_AGE_MS * 3;
		NotifyOutbox.Item expired = item(now - NotifyOutbox.MAX_AGE_MS - 1, 0);
		NotifyOutbox.Item a = item(now - 3, 0);
		NotifyOutbox.Item b = item(now - 2, 0);
		NotifyOutbox.Item c = item(now - 1, 0);
		List<NotifyOutbox.Item> evict = NotifyOutbox.overflow(Arrays.asList(expired, a, b, c), now, 2, NotifyOutbox.MAX_AGE_MS, Long.MAX_VALUE);
		assertEquals(Arrays.asList(expired, a), evict);
	}

	@Test
	public void overflowKeepsScreenshotsUnderTheByteCap()
	{
		long now = 1_000_000;
		NotifyOutbox.Item a = item(now - 3, 600);
		NotifyOutbox.Item b = item(now - 2, 600);
		NotifyOutbox.Item c = item(now - 1, 600);
		List<NotifyOutbox.Item> evict = NotifyOutbox.overflow(Arrays.asList(a, b, c), now, 100, NotifyOutbox.MAX_AGE_MS, 1_300);
		assertEquals(Collections.singletonList(a), evict);
		assertTrue(NotifyOutbox.overflow(Arrays.asList(a, b), now, 100, NotifyOutbox.MAX_AGE_MS, 1_300).isEmpty());
	}

	@Test
	public void aNewEntryWaitsAboutFifteenSecondsBeforeItsFirstRetry()
	{
		NotifyOutbox.Item it = NotifyOutbox.newItem(0);
		assertTrue(it.nextAttemptAt >= 12_000 && it.nextAttemptAt <= 18_000);
	}

	@Test
	public void theOutboxCapsItsCountInMemory()
	{
		NotifyOutbox outbox = new NotifyOutbox(new Gson(), () -> scheduler, () -> pool);
		for (int i = 0; i < NotifyOutbox.MAX_ITEMS + 5; i++)
		{
			outbox.enqueue(item(System.currentTimeMillis(), 0), null);
		}
		assertEquals(NotifyOutbox.MAX_ITEMS, outbox.size());
		outbox.clear();
		assertEquals(0, outbox.size());
	}

	@Test
	public void aPassStopsAtTheFirstFailureAndDropsPermanentRefusals()
	{
		NotifyOutbox outbox = new NotifyOutbox(new Gson(), () -> scheduler, () -> pool);
		List<String> tried = new ArrayList<>();
		outbox.setSender((it, png) ->
		{
			tried.add(it.channel);
			switch (it.channel)
			{
				case "sent":
					return NotifyOutbox.Result.SENT;
				case "refused":
					return NotifyOutbox.Result.PERMANENT;
				default:
					return NotifyOutbox.Result.RETRY;
			}
		});
		for (String ch : Arrays.asList("sent", "refused", "down", "after"))
		{
			NotifyOutbox.Item it = item(System.currentTimeMillis(), 0);
			it.channel = ch;
			it.nextAttemptAt = 0;
			outbox.enqueue(it, null);
		}
		outbox.pump();
		assertEquals("one failure ends the pass", Arrays.asList("sent", "refused", "down"), tried);
		assertEquals("the failed one and the untried one wait", 2, outbox.size());
		outbox.stop();
	}

	// ── Moments + PushStash ───────────────────────────────────────────────────────────────────

	@Test
	public void restoredMomentsSkipOldOnesAndYieldToNewerSightings()
	{
		AnvilMoments moments = new AnvilMoments();
		long now = 10L * PushStash.MAX_AGE_MS;
		AnvilMoments.Moment queued = new AnvilMoments.Moment("pet", null, "Vorki", 1, null, "Vorkath", "npc", 50, now, "k1");
		moments.record(queued);
		AnvilMoments.Moment stale = new AnvilMoments.Moment("drop", 1, "x", 1, null, null, null, null, now - PushStash.MAX_AGE_MS - 1, "old");
		AnvilMoments.Moment sameKey = new AnvilMoments.Moment("pet", null, null, 1, null, null, null, null, now - 5, "k1");
		AnvilMoments.Moment fresh = new AnvilMoments.Moment("death", null, null, 1, null, "Zulrah", "npc", null, now - 5, "k2");
		moments.restore(Arrays.asList(stale, sameKey, fresh), now, PushStash.MAX_AGE_MS);
		List<AnvilMoments.Moment> all = moments.snapshot();
		assertEquals(2, all.size());
		assertEquals("Vorki", all.stream().filter(m -> m.key.equals("k1")).findFirst().get().itemName);
	}

	@Test
	public void stashKeysByHashThenNameAndNeverByNothing()
	{
		assertNull(PushStash.keyFor(null, null));
		assertNull(PushStash.keyFor("  ", ""));
		assertEquals(PushStash.keyFor("Zezima", null), PushStash.keyFor("zezima ", null));
		assertFalse(PushStash.keyFor("Zezima", "123").equals(PushStash.keyFor("Zezima", null)));
		assertEquals(16, PushStash.keyFor(null, "123").length());
	}

	@Test
	public void stashMergeKeepsTheLargerCountAndEveryMoment()
	{
		PushStash.Data a = new PushStash.Data();
		a.savedAt = 1;
		a.kc.put("Zulrah", 10);
		a.moments.add(PushStash.SavedMoment.of(new AnvilMoments.Moment("pet", null, null, 1, null, null, null, null, 1, "k1")));
		PushStash.Data b = new PushStash.Data();
		b.savedAt = 2;
		b.kc.put("Zulrah", 12);
		b.xp.put("mining", 5);
		b.moments.add(PushStash.SavedMoment.of(new AnvilMoments.Moment("death", null, null, 1, null, null, null, null, 2, "k2")));
		PushStash.Data m = PushStash.merge(a, b);
		assertEquals(2, m.savedAt);
		assertEquals(Integer.valueOf(12), m.kc.get("Zulrah"));
		assertEquals(Integer.valueOf(5), m.xp.get("mining"));
		assertEquals(2, m.moments.size());
		assertEquals("k2", m.moments.get(1).toMoment().key);
	}
}
