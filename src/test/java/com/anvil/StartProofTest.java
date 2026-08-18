package com.anvil;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.google.gson.Gson;
import okhttp3.OkHttpClient;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * STARTING SHOT (site lib/startProof) — the plugin half. Covers what the sidebar is allowed to ask
 * for ({@link AnvilSidebarDataSource#startProof()}) and the one retry rule that keeps a real drop
 * from being thrown away while a screenshot is outstanding.
 */
public class StartProofTest
{
	private static PluginConfigResponse liveConfig()
	{
		PluginConfigResponse cfg = new PluginConfigResponse();
		cfg.event = new PluginConfigResponse.EventInfo();
		cfg.event.id = 7;
		cfg.event.name = "Summer Bingo";
		cfg.event.startDate = Instant.now().minus(1, ChronoUnit.HOURS).toString();
		cfg.event.endDate = Instant.now().plus(1, ChronoUnit.DAYS).toString();
		cfg.team = new PluginConfigResponse.TeamInfo();
		cfg.team.name = "Team Molten";
		return cfg;
	}

	private static PluginConfigResponse.StartProof owed()
	{
		PluginConfigResponse.StartProof sp = new PluginConfigResponse.StartProof();
		sp.required = true;
		sp.drawn = true;
		sp.location = "Edgeville bank";
		sp.keyword = "ANVIL-GRAPE-47";
		sp.needsUpload = true;
		return sp;
	}

	private static AnvilSidebarDataSource sourceFor(PluginConfigResponse cfg)
	{
		return new AnvilSidebarDataSource(() -> cfg, new BingoApiClient(new Gson(), new OkHttpClient()));
	}

	@Test
	public void owedShotIsOfferedWhileTheEventIsLive()
	{
		PluginConfigResponse cfg = liveConfig();
		cfg.startProof = owed();

		PluginConfigResponse.StartProof offered = sourceFor(cfg).startProof();
		assertNotNull(offered);
		assertEquals("Edgeville bank", offered.location);
		assertEquals("ANVIL-GRAPE-47", offered.keyword);
	}

	@Test
	public void nothingIsAskedForWhenNothingIsOwed()
	{
		// No starting-shot block at all — an event without the rule, or a site that predates it.
		assertNull(sourceFor(liveConfig()).startProof());

		// Required but not yet drawn: the event hasn't started, so there is no location and no keyword.
		PluginConfigResponse notDrawn = liveConfig();
		notDrawn.startProof = owed();
		notDrawn.startProof.drawn = false;
		assertNull(sourceFor(notDrawn).startProof());

		// Already filed — the server says the obligation is settled.
		PluginConfigResponse filed = liveConfig();
		filed.startProof = owed();
		filed.startProof.needsUpload = false;
		assertNull(sourceFor(filed).startProof());
	}

	@Test
	public void endedEventNeverAsksForAShot()
	{
		PluginConfigResponse over = liveConfig();
		over.startProof = owed();
		over.event.endDate = Instant.now().minus(1, ChronoUnit.MINUTES).toString();
		assertNull(sourceFor(over).startProof());

		PluginConfigResponse forceEnded = liveConfig();
		forceEnded.startProof = owed();
		forceEnded.event.forceEndedAt = Instant.now().toString();
		assertNull(sourceFor(forceEnded).startProof());
	}

	@Test
	public void captureRunsTheBoundActionAndIsSafeWithoutOne()
	{
		AnvilSidebarDataSource ds = sourceFor(liveConfig());
		ds.captureStartProof(); // unbound (tests, or before the plugin wires itself up) — must not throw

		AtomicInteger fired = new AtomicInteger();
		ds.setStartProofCapture(fired::incrementAndGet);
		ds.captureStartProof();
		assertEquals(1, fired.get());
	}

	@Test
	public void federationPassesTheHomeObligationThrough()
	{
		PluginConfigResponse cfg = liveConfig();
		cfg.startProof = owed();
		AnvilSidebarDataSource home = sourceFor(cfg);
		AtomicInteger fired = new AtomicInteger();
		home.setStartProofCapture(fired::incrementAndGet);

		// The relay layer must not swallow it: the shot is owed to the home we authenticate against.
		SidebarDataSource relayed = new FederationSidebarDataSource(
			new BingoApiClient(new Gson(), new OkHttpClient()), home,
			url -> true, (step, delayMs) -> step.run());
		assertNotNull(relayed.startProof());
		relayed.captureStartProof();
		assertEquals(1, fired.get());
	}

	private static PluginConfigResponse.StartProof checked()
	{
		PluginConfigResponse.StartProof sp = owed();
		sp.spot = new PluginConfigResponse.StartProof.Spot();
		sp.spot.x = 3094;
		sp.spot.y = 3491;
		sp.spot.radius = 25;
		sp.maxSessionMinutes = 15;
		return sp;
	}

	private static final long NOW = 1_800_000_000_000L;

	@Test
	public void aShotFromTheSpotOnAFreshSessionIsLetThrough()
	{
		// Six squares out, five minutes into the session: nothing to complain about.
		assertNull(StartProofRules.blockReason(checked(), NOW - 5 * 60_000L, NOW, 3100, 3489));
	}

	@Test
	public void standingSomewhereElseIsRefusedBeforeTheFrameIsGrabbed()
	{
		String reason = StartProofRules.blockReason(checked(), NOW - 60_000L, NOW, 2400, 3489);
		assertNotNull(reason);
		// The distance is the useful half of the message — "go to Edgeville bank" alone reads like a
		// bug when you believe you ARE at Edgeville bank.
		assertTrue(reason, reason.contains("694"));
		assertTrue(reason, reason.contains("Edgeville bank"));
	}

	@Test
	public void aSessionOlderThanTheWindowIsRefused()
	{
		String reason = StartProofRules.blockReason(checked(), NOW - 130 * 60_000L, NOW, 3094, 3491);
		assertNotNull(reason);
		assertTrue(reason, reason.contains("2h 10m"));
		assertTrue(reason, reason.toLowerCase().contains("log out"));
	}

	@Test
	public void notKnowingWhenTheSessionStartedCountsAsStale()
	{
		// Plugin enabled mid-session: we can't vouch for the logout that flushed the hiscores, and
		// "probably fine" is exactly the answer this rule exists to stop giving.
		String reason = StartProofRules.blockReason(checked(), StartProofRules.UNKNOWN_LOGIN, NOW, 3094, 3491);
		assertNotNull(reason);
		assertTrue(reason, reason.toLowerCase().contains("log out"));
	}

	@Test
	public void checksThatCannotRunNeverBlock()
	{
		// An older site: no pin, no window. Exactly the behaviour before any of this existed.
		assertNull(StartProofRules.blockReason(owed(), StartProofRules.UNKNOWN_LOGIN, NOW, 2400, 3489));

		// Pinned spot, but logged out / no position to read — the distance check simply doesn't run.
		PluginConfigResponse.StartProof spotOnly = checked();
		spotOnly.maxSessionMinutes = 0;
		assertNull(StartProofRules.blockReason(spotOnly, StartProofRules.UNKNOWN_LOGIN, NOW, null, null));

		// Session window on, position unknown, session fresh: still fine.
		assertNull(StartProofRules.blockReason(checked(), NOW - 60_000L, NOW, null, null));

		// And nothing owed at all can't block anything.
		assertNull(StartProofRules.blockReason(null, StartProofRules.UNKNOWN_LOGIN, NOW, null, null));
	}

	@Test
	public void distanceIsMeasuredTheWayTheGameMeasuresIt()
	{
		PluginConfigResponse.StartProof sp = checked();
		// The longer axis wins — 30 east and 4 north is 30 squares away, not 34.
		assertEquals(30, StartProofRules.distance(sp, 3124, 3495));
		assertEquals(0, StartProofRules.distance(sp, 3094, 3491));
		// Nothing to measure against.
		assertEquals(-1, StartProofRules.distance(owed(), 3094, 3491));
		assertEquals(-1, StartProofRules.distance(sp, null, null));
	}

	@Test
	public void awaitingAStartingShotIsRetryable()
	{
		// 409 is normally a permanent 4xx — but this one clears the moment the player files their
		// shot, and the pending drop must survive until then.
		java.io.IOException awaiting = BingoApiClient.submissionErrorForTest(
			"Submission failed", 409, "{\"error\":\"Upload your starting shot first\",\"code\":\"start_proof_required\"}");
		assertTrue(!(awaiting instanceof BingoApiClient.PermanentSubmissionException));

		// Any other 409 stays permanent (tile already complete, etc.) so it can't loop forever.
		java.io.IOException other = BingoApiClient.submissionErrorForTest(
			"Submission failed", 409, "{\"error\":\"Tile already complete\"}");
		assertTrue(other instanceof BingoApiClient.PermanentSubmissionException);
	}
}
