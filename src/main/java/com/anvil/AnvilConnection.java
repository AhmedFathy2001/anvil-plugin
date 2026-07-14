package com.anvil;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * One Anvil home the plugin is connected to, and everything that must be tracked <em>per home</em>
 * so multi-home works with <strong>no cross-talk</strong>. This is the multi-home generalisation of
 * the pile of single-instance fields {@link AnvilPlugin} holds for one site: each connection owns its
 * own {@link BingoApiClient} (⇒ its own base URL, token, and conditional-GET ETags), its own polled
 * {@link PluginConfigResponse}, its own derived {@link TileIndex}, and its own live-sidebar state
 * ({@link AnvilActivityLog} + {@link WorkingOnTracker}).
 *
 * <h3>Connection #0 preserves today's behaviour exactly</h3>
 * The primary connection (index 0) is <em>not</em> a copy of plugin state — it is a thin view over
 * it. Its {@link #config()} reads the very {@link PluginConfigResponse} the plugin already polls
 * (through {@code AnvilPlugin::getPluginConfig}); its {@link #client()} is the injected singleton
 * {@link BingoApiClient} the plugin already uses. So with no extra homes configured, nothing about
 * the primary's polling, matching, submitting, or ETags changes — the multi-home layer just wraps it.
 * The primary never builds a {@link TileIndex} here (the plugin keeps its own in-place indexes for
 * matching), so its {@link #tileIndex()} stays empty; only extra connections use it.
 *
 * <h3>Extra connections are fully self-owned</h3>
 * An extra connection (index ≥ 1) holds its own configured {@link BingoApiClient} and stores the
 * config it polls itself, rebuilding its {@link TileIndex} on each poll — with the same
 * gain-progress-floor that keeps an in-progress gain tile from snapping backwards across a refresh
 * ({@link #restoreGainProgressFloor}), applied to <em>this</em> connection's config only.
 *
 * <p>Not thread-safe for concurrent writers; poll each connection from a single thread (the plugin's
 * executor). Reads used by the sidebar are cheap snapshots.</p>
 */
public final class AnvilConnection
{
	/** Stable id used by the primary connection — matches the single-home data source's constant. */
	public static final String LOCAL_INSTANCE_ID = "local";

	private final int index;
	private final boolean primary;
	private final BingoApiClient client;

	/** Selection key for the clan filter. "local" for the primary; the normalized base URL for extras. */
	private final String instanceId;

	/** Optional human label (extras only) — falls back to the polled clan name when blank. */
	private final String label;

	/** Primary only: reads the plugin's live config. Null for extras. */
	private final Supplier<PluginConfigResponse> primaryConfigSupplier;

	/** Extras only: the config this connection polled itself. Null for the primary. */
	private volatile PluginConfigResponse polledConfig;

	/** Extras only: derived indexes for matching game events on this home. Empty for the primary. */
	private volatile TileIndex tileIndex = TileIndex.empty();

	// Live-sidebar state, scoped to this connection's active event.
	private final AnvilActivityLog activityLog = new AnvilActivityLog();
	private final WorkingOnTracker workingOn = new WorkingOnTracker();
	private int scopedEventId = -1;

	// Per-tile timed-clear fan-out dedup: a raid completion is announced over several correlated chat
	// lines that the plugin may replay, so gate each of THIS connection's timed tiles to one credit per
	// window (mirrors the plugin's own lastTimedSubmittedAt). Keyed by this connection's tileId.
	private final Map<Integer, Long> lastTimedFanoutAt = new HashMap<>();

	private AnvilConnection(int index, boolean primary, BingoApiClient client, String instanceId,
		String label, Supplier<PluginConfigResponse> primaryConfigSupplier)
	{
		this.index = index;
		this.primary = primary;
		this.client = client;
		this.instanceId = instanceId;
		this.label = label == null ? "" : label;
		this.primaryConfigSupplier = primaryConfigSupplier;
	}

	/**
	 * The primary connection (index 0) — a view over the plugin's existing singleton client and live
	 * config. Constructing this changes nothing about how the plugin polls or submits for its home.
	 */
	public static AnvilConnection primary(BingoApiClient client, Supplier<PluginConfigResponse> configSupplier)
	{
		return new AnvilConnection(0, true, client, LOCAL_INSTANCE_ID, "", configSupplier);
	}

	/**
	 * An extra connection (index ≥ 1) for one opt-in {@link FederationHome}. The supplied client must
	 * already be {@link BingoApiClient#configure configured} with the home's base URL + token.
	 */
	public static AnvilConnection extra(int index, FederationHome home, BingoApiClient client)
	{
		return extra(index, home, client, null);
	}

	/**
	 * An extra connection with an explicit stable instanceId — used by the broker flow, which learns the
	 * real UUID from {@code /exchange}. A blank override falls back to the (stable) base URL, which is
	 * the best a Layer-0 manual home can offer.
	 */
	public static AnvilConnection extra(int index, FederationHome home, BingoApiClient client, String instanceIdOverride)
	{
		String id = instanceIdOverride != null && !instanceIdOverride.isEmpty() ? instanceIdOverride : home.baseUrl;
		return new AnvilConnection(index, false, client, id, home.label, null);
	}

	public int index()
	{
		return index;
	}

	public boolean isPrimary()
	{
		return primary;
	}

	public BingoApiClient client()
	{
		return client;
	}

	public String instanceId()
	{
		return instanceId;
	}

	/** This connection's active config — the plugin's live config for the primary, the polled one for extras. */
	public PluginConfigResponse config()
	{
		return primary ? primaryConfigSupplier.get() : polledConfig;
	}

	/** Extras only: the derived indexes for matching game events on this home. Empty for the primary. */
	public TileIndex tileIndex()
	{
		return tileIndex;
	}

	/**
	 * Adopt a freshly-polled config on an extra connection: preserve locally-counted gain progress
	 * (so a refresh never regresses an in-progress gain tile), swap in the new config, and rebuild the
	 * derived indexes. No-op semantics for the primary (which never polls through here).
	 */
	public void setPolledConfig(PluginConfigResponse cfg)
	{
		if (primary)
		{
			return;
		}
		Map<Integer, Integer> priorGains = snapshotGainProgress(this.polledConfig);
		restoreGainProgressFloor(cfg, priorGains);
		this.polledConfig = cfg;
		this.tileIndex = TileIndex.build(cfg);
	}

	// ---- Live-sidebar state (per connection) -----------------------------------------------------

	public AnvilActivityLog activityLog()
	{
		return activityLog;
	}

	public WorkingOnTracker workingOn()
	{
		return workingOn;
	}

	public int scopedEventId()
	{
		return scopedEventId;
	}

	public void setScopedEventId(int eventId)
	{
		this.scopedEventId = eventId;
	}

	/** Reset the feed + spotlight when this connection's active event changes (or clears). */
	public void resetLiveState()
	{
		activityLog.reset();
		workingOn.reset();
		scopedEventId = -1;
		synchronized (lastTimedFanoutAt)
		{
			lastTimedFanoutAt.clear();
		}
	}

	/**
	 * Gate for a timed-clear fan-out to this connection's {@code tileId}: records {@code now} and returns
	 * {@code true} the first time within {@code windowMs}, {@code false} for repeats — so a replayed raid
	 * completion credits each of this clan's timed tiles at most once. Thread-safe.
	 */
	public boolean markTimedFanout(int tileId, long now, long windowMs)
	{
		synchronized (lastTimedFanoutAt)
		{
			Long last = lastTimedFanoutAt.get(tileId);
			if (last != null && (now - last) < windowMs)
			{
				return false;
			}
			lastTimedFanoutAt.put(tileId, now);
			return true;
		}
	}

	/** The clan/event label for the clan filter: explicit label ?? team name ?? event name ?? host. */
	public String displayName()
	{
		if (label != null && !label.isEmpty())
		{
			return label;
		}
		PluginConfigResponse cfg = config();
		if (cfg != null)
		{
			if (cfg.team != null && cfg.team.name != null && !cfg.team.name.isEmpty())
			{
				return cfg.team.name;
			}
			if (cfg.event != null && cfg.event.name != null && !cfg.event.name.isEmpty())
			{
				return cfg.event.name;
			}
		}
		return primary ? "" : instanceId; // extras fall back to the host so the row is never blank
	}

	// ---- Gain-progress floor (per connection) ----------------------------------------------------

	/** Snapshot each tracked gain's locally-counted currentAmount by tileId (pre-refresh state). */
	private static Map<Integer, Integer> snapshotGainProgress(PluginConfigResponse cfg)
	{
		Map<Integer, Integer> m = new HashMap<>();
		if (cfg != null && cfg.trackedGains != null)
		{
			for (PluginConfigResponse.TrackedGain g : cfg.trackedGains)
			{
				if (g != null)
				{
					m.put(g.tileId, g.currentAmount);
				}
			}
		}
		return m;
	}

	/** Raise each fresh gain's currentAmount to at least the locally-counted value, capped at required. */
	private static void restoreGainProgressFloor(PluginConfigResponse fresh, Map<Integer, Integer> local)
	{
		if (fresh == null || fresh.trackedGains == null || local.isEmpty())
		{
			return;
		}
		for (PluginConfigResponse.TrackedGain g : fresh.trackedGains)
		{
			if (g == null)
			{
				continue;
			}
			Integer prior = local.get(g.tileId);
			if (prior != null && prior > g.currentAmount)
			{
				g.currentAmount = Math.min(prior, g.requiredAmount);
			}
		}
	}
}
