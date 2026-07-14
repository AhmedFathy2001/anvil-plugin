package com.anvil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Stand-in {@link SidebarDataSource} used while the multi-home federation backend is built on a
 * separate track. Returns two fake clans with a handful of fake tiles at varying progress so the
 * {@link AnvilSidebarPanel} (clan filter, summary, nearest-tiles list, states) can be developed and
 * reviewed end-to-end against a stable shape.
 *
 * <p>Two behaviours make the panel's live wiring observable without a server:</p>
 * <ul>
 *   <li>A small simulated latency so the panel's loading state is actually exercised.</li>
 *   <li>One tile nudges forward a little on each fetch, so the auto-refresh poll visibly moves the
 *       progress bars — proof the refresh hook re-renders from the source.</li>
 * </ul>
 *
 * <p>This never throws and never emits a per-connection error; the error/empty paths are exercised
 * by the panel against the interface contract, and the real implementation will populate them.</p>
 */
@Slf4j
@Singleton
public class MockSidebarDataSource implements SidebarDataSource
{
	/** Simulated network latency so the panel's loading spinner is real. */
	private static final long FAKE_LATENCY_MS = 250;

	/** Advances each fetch to make the auto-refresh poll visibly move a progress bar. */
	private final AtomicInteger tick = new AtomicInteger();

	@Override
	public List<ConnectionView> fetchConnections() throws SidebarDataException
	{
		try
		{
			Thread.sleep(FAKE_LATENCY_MS);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new SidebarDataException("Interrupted while loading progress", e);
		}

		// Creeps 0 → cap over successive polls so a bar visibly advances; wraps so it never completes.
		int creep = tick.getAndIncrement() % 40; // 0..39

		List<ConnectionView> connections = new ArrayList<>();

		connections.add(new ConnectionView(
			"11111111-1111-4111-8111-111111111111",
			"The Anvil Clan",
			"Summer Bingo 2026",
			7, 25,
			Arrays.asList(
				new ConnectionView.TileProgressView("Any barrows item", 4, 5, false),
				new ConnectionView.TileProgressView("500 Zulrah KC", 420 + creep * 2, 500, false),
				new ConnectionView.TileProgressView("Full Graceful set", 5, 6, false),
				new ConnectionView.TileProgressView("Dragon warhammer", 0, 1, false)
			)));

		connections.add(new ConnectionView(
			"22222222-2222-4222-8222-222222222222",
			"Trailblazer League",
			"Leagues Relay",
			14, 20,
			Arrays.asList(
				new ConnectionView.TileProgressView("Inferno cape", 1, 1, true),
				new ConnectionView.TileProgressView("10M Mining XP", 9_300_000 + creep * 15_000, 10_000_000, false),
				new ConnectionView.TileProgressView("Any godsword", 1, 2, false)
			)));

		return connections;
	}
}
