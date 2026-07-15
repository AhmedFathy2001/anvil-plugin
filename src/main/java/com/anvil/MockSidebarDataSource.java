package com.anvil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Stand-in {@link SidebarDataSource} used while the multi-home federation backend is built separately.
 * Returns two fake clans at varying progress so the {@link AnvilSidebarPanel} can be developed and
 * reviewed end-to-end against a stable shape. One tile nudges forward each fetch so the auto-refresh
 * poll visibly moves a bar. Never throws or emits a per-connection error (those paths are the real
 * implementation's to populate).
 */
@Slf4j
@Singleton
public class MockSidebarDataSource implements SidebarDataSource
{
	/** Advances each fetch to make the auto-refresh poll visibly move a progress bar. */
	private final AtomicInteger tick = new AtomicInteger();

	@Override
	public List<ConnectionView> fetchConnections() throws SidebarDataException
	{
		// Creeps 0 → cap over successive polls so a bar visibly advances; wraps so it never completes.
		int creep = tick.getAndIncrement() % 40; // 0..39

		List<ConnectionView> connections = new ArrayList<>();

		// Clan 1 — "Active now" shows a solo self task, a shared task (You + Kayle dedup), and a teammate task.
		List<ActivityEntry> feed1 = Arrays.asList(
			new ActivityEntry("s" + (900 + creep), "", "You", 102, "500 Zulrah KC", ActivityEntry.Kind.PROGRESS, 2, true),
			new ActivityEntry("c31", "", "Kayle", 140, "Tanzanite fang", ActivityEntry.Kind.COMPLETE, 0, false),
			new ActivityEntry("s880", "", "Sara", 141, "Any barrows item", ActivityEntry.Kind.PROGRESS, 1, false),
			new ActivityEntry("s875", "", "You", 143, "Full Graceful set", ActivityEntry.Kind.PROGRESS, 1, true));
		List<ConnectionView.ActiveTask> active1 = Arrays.asList(
			new ConnectionView.ActiveTask(
				new ClogTaskModel.TaskRow(102, "500 Zulrah KC", ClogTaskModel.Type.STAT, 420 + creep * 2, 500, -1),
				Arrays.asList("You"), true),
			new ConnectionView.ActiveTask(
				new ClogTaskModel.TaskRow(150, "Kill 50 Vorkath", ClogTaskModel.Type.STAT, 22 + creep, 50, -1),
				Arrays.asList("You", "Kayle"), true),
			new ConnectionView.ActiveTask(
				new ClogTaskModel.TaskRow(141, "Any barrows item", ClogTaskModel.Type.DROP, 4, 5, -1),
				Arrays.asList("Sara"), false));

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
			),
			feed1, active1));

		List<ActivityEntry> feed2 = Arrays.asList(
			new ActivityEntry("c50", "", "Rex", 201, "Inferno cape", ActivityEntry.Kind.COMPLETE, 0, false),
			new ActivityEntry("s710", "", "Mara", 202, "Any godsword", ActivityEntry.Kind.PROGRESS, 1, false));
		List<ConnectionView.ActiveTask> active2 = Arrays.asList(
			new ConnectionView.ActiveTask(
				new ClogTaskModel.TaskRow(203, "10M Mining XP", ClogTaskModel.Type.STAT, 9_300_000 + creep * 15_000, 10_000_000, -1),
				Arrays.asList("You"), true),
			new ConnectionView.ActiveTask(
				new ClogTaskModel.TaskRow(202, "Any godsword", ClogTaskModel.Type.DROP, 1, 2, -1),
				Arrays.asList("Mara"), false));

		connections.add(new ConnectionView(
			"22222222-2222-4222-8222-222222222222",
			"Trailblazer League",
			"Leagues Relay",
			14, 20,
			Arrays.asList(
				new ConnectionView.TileProgressView("Inferno cape", 1, 1, true),
				new ConnectionView.TileProgressView("10M Mining XP", 9_300_000 + creep * 15_000, 10_000_000, false),
				new ConnectionView.TileProgressView("Any godsword", 1, 2, false)
			),
			feed2, active2));

		return connections;
	}
}
