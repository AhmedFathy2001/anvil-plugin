package com.anvil.clip;

import com.anvil.session.LocalPlayer;
import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.PluginConfigResponse;
import com.anvil.api.dto.ClipRelayResult;
import com.anvil.api.dto.Standings;
import com.anvil.io.DiscordWebhookClient;
import com.anvil.io.ObsReplayClient;
import com.anvil.ui.AnvilOverlay;
import com.anvil.util.AnvilChat;
import com.anvil.util.ClipMoments;
import com.anvil.util.CombatTarget;
import com.google.gson.Gson;
import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;

/**
 * Clips: asking OBS for the last N seconds of footage, and getting the file somewhere useful.
 *
 * <h2>Why there is a queue of pending requests</h2>
 *
 * <p>A clip's caption is built from what the plugin saw — drops, kills, tile completions, deaths,
 * missions — inside the buffer's own window. The footage ENDS when the hotkey is pressed; the file
 * arrives whenever OBS finishes writing it, which can be a minute later on a slow disk.</p>
 *
 * <p>So the inputs are snapshotted at the press and queued, and the save that lands takes the oldest
 * one. Reading them live instead captioned the clip with whatever the player wandered into while OBS
 * was still encoding — a boss that is not in the footage. The queue is bounded because a player who
 * presses the key eight times while OBS is wedged should lose the oldest requests, not memory.</p>
 *
 * <p>An unmatched save is normal, not an error: OBS has its own hotkey, and a clip saved with that
 * one has no request behind it. Those fall back to "now".</p>
 *
 * <h2>Two routes to Discord, in order</h2>
 *
 * <p>Preferred: hand the file to the clan's own site and let it post. Members then do not each have
 * to paste a webhook URL, and it is still not a URL handed to us by a server response — it is the
 * same configured base URL every other request uses. Gated on the capability, so a self-hosted site
 * that predates the route falls straight through.</p>
 *
 * <p>Fallback: upload from the player's own machine to a webhook THEY pasted into plugin config.
 * Blank means keep clips local, which is a legitimate choice and not a failure.</p>
 */
@Slf4j
@Singleton
public class ObsClipService
{
	/**
	 * A clip we asked for, and what was true when we asked.
	 *
	 * <p>Only ever read once, by the save it belongs to.</p>
	 */
	private static final class PendingClip
	{
		final long requestedAt;
		final CombatTarget.Seen target;

		PendingClip(long requestedAt, CombatTarget.Seen target)
		{
			this.requestedAt = requestedAt;
			this.target = target;
		}
	}

	/** Press the key eight times while OBS is wedged and the oldest requests go, not memory. */
	private static final int MAX_PENDING_CLIPS = 8;

	/** Grace on top of the clip length, for a hit that landed just before the window opened. */
	private static final long TARGET_GRACE_MS = 5_000L;

	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final AnvilConfig config;
	private final BingoApiClient apiClient;
	private final DiscordWebhookClient discordClient;
	private final AnvilChat chat;
	private final ClipMoments clipMoments;
	private final CombatTarget combatTarget;

	/** The live event config. A supplier rather than the value, because it is replaced on every poll. */
	private final Supplier<PluginConfigResponse> configSupplier;

	/** Who is playing, for the webhook caption. */
	private final Supplier<String> localPlayerName;

	/** Volatile for visibility; connect/disconnect are synchronized on {@link #obsLock}. */
	private volatile ObsReplayClient obsClip;
	private final Object obsLock = new Object();
	private final Deque<PendingClip> pendingClips = new ArrayDeque<>();

	@Inject
	ObsClipService(OkHttpClient okHttpClient, Gson gson, AnvilConfig config, BingoApiClient apiClient,
		DiscordWebhookClient discordClient, AnvilChat chat, ClipMoments clipMoments,
		CombatTarget combatTarget,
		Supplier<PluginConfigResponse> pluginConfig, LocalPlayer localPlayer)
	{
		this.okHttpClient = okHttpClient;
		this.gson = gson;
		this.config = config;
		this.apiClient = apiClient;
		this.discordClient = discordClient;
		this.chat = chat;
		this.clipMoments = clipMoments;
		this.combatTarget = combatTarget;
		this.configSupplier = pluginConfig;
		this.localPlayerName = localPlayer::name;
	}


	// ────────────────────────────────────────────────────────────── connection ──

	public void connect()
	{
		synchronized (obsLock)
		{
			disconnect();
			obsClip = new ObsReplayClient(
				okHttpClient,
				gson,
				config.obsHost(),
				config.obsPort(),
				config.obsPassword(),
				this::onClipSaved,
				() ->
				{
					/* connected — no chat spam */
				},
				// Per-save failures (e.g. the Replay Buffer isn't started) — tell the player why.
				chat::send,
				config::clipLengthSeconds,
				() -> config.clipMp4() ? "mp4" : null,
				config::postObsTriggeredClips
			);
			obsClip.connect();
		}
	}

	public void disconnect()
	{
		synchronized (obsLock)
		{
			if (obsClip != null)
			{
				obsClip.disconnect();
				obsClip = null;
			}
		}
	}

	/**
	 * Reconnect tick, from the 30s loop.
	 *
	 * <p>OBS often isn't up yet when RuneLite launches, so the one-shot connect at startup can miss.
	 * Without this, clips only worked after toggling the config off and on again.</p>
	 */
	public void maybeReconnect()
	{
		if (!config.clipsEnabled())
		{
			disconnect();
			return;
		}
		ObsReplayClient c = obsClip;
		if (c == null || !c.isConnected())
		{
			connect();
		}
	}

	/** The config changed while we were connected — push the new clip length to OBS. */
	public void applyClipLength()
	{
		ObsReplayClient c = obsClip;
		if (config.clipsEnabled() && c != null && c.isConnected())
		{
			c.applyClipLength();
		}
	}

	// ──────────────────────────────────────────────────────────────── capture ──

	/** Hotkey handler — ask OBS to flush the replay buffer. */
	public void capture()
	{
		if (!config.clipsEnabled())
		{
			return;
		}
		ObsReplayClient c = obsClip;
		if (c == null || !c.isConnected())
		{
			chat.send("Clip capture: OBS isn't connected. Make sure OBS is running with the WebSocket "
				+ "server + Replay Buffer enabled.");
			connect(); // opportunistic, so the next press can work
			return;
		}
		// Snapshot the caption inputs NOW — the footage ends here, whatever time the file arrives.
		synchronized (pendingClips)
		{
			while (pendingClips.size() >= MAX_PENDING_CLIPS)
			{
				pendingClips.removeFirst();
			}
			pendingClips.addLast(new PendingClip(System.currentTimeMillis(), combatTarget.snapshot()));
		}
		chat.send("Saving clip...");
		c.saveReplayBuffer();
	}

	/**
	 * OBS finished writing a clip. Runs off the client thread.
	 *
	 * <p>Posts it to the clan's clips channel when it is small enough for Discord; otherwise a quiet
	 * in-game notice, because the file is still on disk and that is not nothing.</p>
	 */
	private void onClipSaved(String path)
	{
		if (path == null || path.isEmpty())
		{
			return;
		}
		File file = new File(path);
		if (!file.exists())
		{
			chat.send("Clip saved by OBS, but the file couldn't be found to post.");
			return;
		}
		long maxBytes = (long) Math.max(1, config.clipMaxMb()) * 1024L * 1024L;
		long size = file.length();
		if (size > maxBytes)
		{
			chat.send("Clip saved locally (" + (size / (1024L * 1024L)) + "MB) — too big to auto-post to Discord.");
			return;
		}

		int clipSeconds = Math.max(1, config.clipLengthSeconds());
		// The request this file answers, oldest first. Absent when OBS saved a clip we didn't ask for
		// (someone pressed OBS's own hotkey), in which case "now" is the best we know.
		PendingClip pending;
		synchronized (pendingClips)
		{
			pending = pendingClips.pollFirst();
		}
		long clipEndedAt = pending != null ? pending.requestedAt : System.currentTimeMillis();
		String moment = caption(pending, clipEndedAt, clipSeconds);

		if (postViaClanSite(file, size, moment, clipSeconds))
		{
			return;
		}
		postViaOwnWebhook(file, moment);
	}

	/**
	 * What the clip caught, in one line.
	 *
	 * <p>The moments the plugin saw inside the buffer's own window, and failing that, whoever we were
	 * fighting. "Fighting Vorkath" is a caption; "Clipped during Test missions bingo" is a timestamp
	 * with extra steps. Null when there is genuinely nothing to say.</p>
	 */
	private String caption(PendingClip pending, long clipEndedAt, int clipSeconds)
	{
		String moment = clipMoments.summarize(clipEndedAt, clipSeconds, 3);
		if (moment != null)
		{
			return moment;
		}
		// From the SNAPSHOT, not the live value: by the time a slow save lands, the live target is
		// whatever they wandered into since, and it would caption the clip with a boss that isn't in it.
		CombatTarget.Seen target = pending != null ? pending.target : combatTarget.snapshot();
		return target.freshAt(clipEndedAt, clipSeconds * 1000L + TARGET_GRACE_MS)
			? "⚔️ Fighting " + target.name
			: null;
	}

	/**
	 * Hand it to the clan's site and let that post it.
	 *
	 * @return true when the clip is dealt with and the caller should stop.
	 */
	private boolean postViaClanSite(File file, long size, String moment, int clipSeconds)
	{
		PluginConfigResponse cfg = configSupplier.get();
		boolean relayAvailable = cfg != null && cfg.serverSupports("clip-relay") && apiClient.isConfigured();
		if (!relayAvailable)
		{
			return false;
		}
		chat.send("Uploading clip to your clan's Discord...");
		// Only an event that is actually RUNNING. The config carries whatever board this account is
		// enrolled in, and enrolment starts when sign-ups open — so a clip taken eight weeks before the
		// first tile went up was captioned "Clipped during <that board>", a claim about a competition
		// that hasn't happened yet.
		boolean eventRunning = AnvilOverlay.isEventActive(cfg.event);
		String eventName = eventRunning ? cfg.event.name : null;
		// Their board position rides along: the footage can show the kill but not that it put them
		// top of the month.
		Standings standings = eventRunning ? cfg.event.monthlyStandings : null;
		ClipRelayResult result = apiClient.postClip(
			file, moment, eventName, clipSeconds, contentTypeForClip(file.getName()),
			standings != null ? standings.yourRank : 0,
			standings != null ? standings.yourPoints : 0);
		switch (result)
		{
			case POSTED:
				chat.send("Clip posted to the clan Discord.");
				return true;
			case TOO_LARGE:
				chat.send("Clip saved locally — too big for Discord ("
					+ (size / (1024L * 1024L)) + "MB). Try a shorter clip length.");
				return true;
			case NO_CHANNEL:
				// The clan hasn't set a clips channel. A personal webhook still works, so only stop
				// here when there isn't one.
				if (ownWebhook().isEmpty())
				{
					chat.send("Clip saved locally — your clan has no clips channel set up yet.");
					return true;
				}
				return false;
			case UNSUPPORTED:
			case FAILED:
			default:
				return false;
		}
	}

	/** Upload straight from this machine to a webhook the player pasted into plugin config. */
	private void postViaOwnWebhook(File file, String moment)
	{
		String webhook = ownWebhook();
		if (webhook.isEmpty())
		{
			PluginConfigResponse cfg = configSupplier.get();
			boolean relayAvailable = cfg != null && cfg.serverSupports("clip-relay") && apiClient.isConfigured();
			chat.send(relayAvailable
				? "Clip saved locally — couldn't reach your clan's Discord just now."
				: "Clip saved locally — paste a Clips Discord webhook URL in the plugin config to auto-post.");
			return;
		}
		String rsn = localPlayerName.get();
		String content = (rsn != null && !rsn.isEmpty() ? rsn : "A clan member") + " clipped 🎬"
			+ (moment != null ? "\n" + moment : "");
		chat.send("Uploading clip to Discord...");
		// Stream from disk on the upload client (generous timeouts); only claim success once Discord
		// actually accepts it, so a 413/429/timeout reads as a failure rather than as silence.
		discordClient.sendWithFile(webhook, content, file, file.getName(),
			contentTypeForClip(file.getName()), ok ->
			chat.send(ok
				? "Clip posted to Discord."
				: "Clip saved locally, but Discord didn't accept the upload "
					+ "(too big, rate-limited, or timed out)."));
	}

	/** The player's own clips webhook, trimmed; empty when unset. */
	private String ownWebhook()
	{
		String webhook = config.clipsWebhookUrl();
		return webhook == null ? "" : webhook.trim();
	}

	static String contentTypeForClip(String name)
	{
		String lower = name.toLowerCase();
		if (lower.endsWith(".mp4"))
		{
			return "video/mp4";
		}
		if (lower.endsWith(".mkv"))
		{
			return "video/x-matroska";
		}
		if (lower.endsWith(".mov"))
		{
			return "video/quicktime";
		}
		if (lower.endsWith(".webm"))
		{
			return "video/webm";
		}
		return "application/octet-stream";
	}
}
