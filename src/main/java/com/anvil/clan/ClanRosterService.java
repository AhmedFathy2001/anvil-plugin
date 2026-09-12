package com.anvil.clan;

import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.dto.AdminUnauthorizedException;
import com.anvil.api.dto.ClanChange;
import com.anvil.api.dto.ClanMember;
import com.anvil.api.dto.ClanMismatchException;
import com.anvil.api.dto.ClanSyncResponse;
import com.anvil.api.dto.RateLimitedException;
import com.anvil.util.AnvilChat;
import com.anvil.util.SyncBackoff;
import com.anvil.util.TaskRunner;
import java.io.IOException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.clan.ClanChannel;
import net.runelite.api.clan.ClanRank;
import net.runelite.api.clan.ClanSettings;
import net.runelite.api.clan.ClanTitle;
import net.runelite.client.callback.ClientThread;

/**
 * Who this account is to the clan's site, and pushing the in-game clan roster to it.
 *
 * <p>Two things that look separate and are not. Whether the site calls you an admin is the only
 * reason the roster button exists, the sync is the only thing that answer is used for, and a sync
 * that comes back "not an admin" is how the answer gets revoked. Keeping them apart meant one flag
 * written from three places and read from four.</p>
 *
 * <h2>What a roster push is</h2>
 *
 * <p>The site's member list drifts from the clan's the moment anybody joins, leaves or is renamed,
 * and a drifted list means a real member's drops land as a guest. The data is right there in the
 * client, so this scrapes the clan tab and posts it.</p>
 *
 * <p>Everything here is a reason NOT to send. It rewrites every member row on the site, the button
 * for it is in two places, and until there was a cooldown an impatient double-click did that twice.
 * So: only admins, only with a token, only when the clan tab has actually loaded, never two at once,
 * at most once a minute after one that worked, and with a doubling wait after one that did not — so
 * a site that is down is not asked again every time somebody clicks.</p>
 *
 * <h2>Reading the roster is a client-thread operation, and the panel is not on it</h2>
 *
 * <p>{@code getClanChannel()} may only be touched from the client thread, and the side panel runs on
 * Swing's. So the answer is cached here, refreshed on the game tick, and read from anywhere — which
 * is also what lets the panel decide whether to offer the button at all.</p>
 *
 * <p>That cache has hysteresis, and it is not decoration. The channel is momentarily null on login,
 * on a world hop, and while the clan tab loads; mirroring that straight through made the button
 * appear and disappear for no reason a player could see. It takes about six seconds of the channel
 * genuinely being gone before the button goes, and it comes back the instant the channel does.</p>
 */
@Slf4j
@Singleton
public class ClanRosterService
{
	/** Callback for the async sync, so a caller can report the outcome where its user is looking. */
	public interface Callback
	{
		void onResult(boolean ok, String message);
	}

	/** ~6 seconds of a genuinely absent channel before we withdraw the button. */
	private static final int CLAN_ROSTER_GRACE_TICKS = 10;

	/** At most one automatic push per half hour per session. */
	private static final long AUTO_ROSTER_MIN_GAP_MS = 30 * 60 * 1000;

	/** How long a "no, not an admin" stands before we ask again. */
	private static final long ADMIN_REPROBE_MS = 5 * 60_000L;

	/** A push that worked buys a minute of quiet. */
	private static final long ROSTER_PUSH_COOLDOWN_MS = 60_000L;

	/** The clan list lands a moment after the channel does; give it time rather than racing it. */
	public static final long AUTO_ROSTER_DELAY_MS = 5_000;

	private final Client client;
	private final ClientThread clientThread;
	private final AnvilConfig config;
	private final BingoApiClient apiClient;
	private final AnvilChat chat;
	private final TaskRunner tasks;

	// ── is this account an admin of the clan we are addressing ──────────────────────────────
	private volatile boolean admin;
	private volatile boolean probeAttempted;
	/** The clan the admin answer belongs to — an answer does not survive switching clans. */
	private volatile String answerFor = "";
	/** When the last probe ran, so a failed one is retried instead of costing the whole session. */
	private volatile long lastProbeAt;

	// ── can we read the roster at all ───────────────────────────────────────────────────────
	private volatile boolean rosterReadable;
	private int missingTicks;

	// ── push state ──────────────────────────────────────────────────────────────────────────
	private long lastAutoSyncAt;
	private volatile boolean autoSyncRunning;
	private volatile boolean panelSyncRunning;
	/**
	 * The next push was started by the plugin, not a person, so it reports itself ONLY if the roster
	 * actually moved. Silence on a login where nothing changed; a line when somebody joined, left or
	 * was renamed, because that is news whether or not you asked for it.
	 */
	private volatile boolean announceNext;
	/**
	 * Has an automatic sync already reported itself this login?
	 *
	 * <p>The first of a session always speaks, even to say nothing moved: that line is how you know
	 * the plugin is talking to your site at all, and its absence is what made a working sync look
	 * broken. Every one after it speaks only for news.</p>
	 */
	private volatile boolean reportedThisLogin;
	private volatile long pushAllowedAt;
	private final SyncBackoff backoff = new SyncBackoff();
	private volatile String lastSummary;

	@Inject
	ClanRosterService(Client client, ClientThread clientThread, AnvilConfig config,
		BingoApiClient apiClient, AnvilChat chat, TaskRunner tasks)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.apiClient = apiClient;
		this.chat = chat;
		this.tasks = tasks;
	}

	// ─────────────────────────────────────────────────────────── admin identity ──

	/** Does the site call this account an admin of the clan we are currently addressing? */
	public boolean isAdmin()
	{
		return admin;
	}

	/** Revoke it — on sign-out, and when a sync comes back saying the token is not an admin. */
	public void clearAdmin()
	{
		admin = false;
	}

	/** Let the next probe run: a new login, or new credentials. */
	public void resetProbe()
	{
		probeAttempted = false;
	}

	/**
	 * Ask the site, once per login, whether this token is an admin (GET /api/plugin/me).
	 *
	 * <p>Blocking. Callers run it on the background thread.</p>
	 */
	public void probeAdmin()
	{
		if (probeAttempted)
		{
			return;
		}
		String token = config.playerToken();
		String url = config.apiUrl();
		if (token == null || token.isEmpty() || url == null || url.isEmpty())
		{
			return;
		}
		probeAttempted = true;
		lastProbeAt = System.currentTimeMillis();
		admin = apiClient.fetchIsAdmin(token);
	}

	/**
	 * Ask again whether this account is an admin, while the answer is still no.
	 *
	 * <p>The probe used to be strictly once per login, and it latched its "attempted" flag BEFORE the
	 * request — so a site that was restarting at the three-second mark cost an admin their
	 * "Sync clan roster" button for the entire session, with nothing in chat to say why. Re-asking
	 * every few minutes costs one tiny request and heals that by itself. A yes is never re-checked;
	 * losing admin mid-session is a logout-shaped problem, not a poll-shaped one.</p>
	 *
	 * <p>Blocking. Callers run it on the background thread.</p>
	 */
	public void maybeReprobeAdmin()
	{
		if (admin || !apiClient.isConfigured())
		{
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastProbeAt < ADMIN_REPROBE_MS)
		{
			return;
		}
		lastProbeAt = now;
		if (apiClient.fetchIsAdmin(config.playerToken()))
		{
			admin = true;
			log.info("Anvil: admin confirmed on retry — clan-sync button is back");
		}
	}

	/**
	 * Drop a cached "you are an admin" the moment we start addressing a different clan.
	 *
	 * <p>The probe is answered once per session and a yes is never re-checked, which was right when a
	 * deployment WAS a clan. It is not right now: the same token is an owner in one clan and a plain
	 * member in the next, so an answer carried across a switch shows a button that cannot work.
	 * Clearing it makes the ordinary re-probe ask again, against the clan we are actually
	 * addressing.</p>
	 */
	public void forgetAdminAnswerOnClanChange()
	{
		String now = apiClient.getActiveClan();
		if (now.equals(answerFor))
		{
			return;
		}
		answerFor = now;
		admin = false;
		probeAttempted = false;
		lastProbeAt = 0L; // ask immediately rather than waiting out the re-probe interval
	}

	// ──────────────────────────────────────────────────────── reading the roster ──

	/** The cached answer, safe to read from the Swing EDT. Refreshed by {@link #onGameTick()}. */
	public boolean isClanRosterReadable()
	{
		return rosterReadable;
	}

	/** Refresh the cached answer, with the hysteresis described on the class. */
	public void onGameTick()
	{
		if (isClanScrapeAvailable())
		{
			rosterReadable = true;
			missingTicks = 0;
			return;
		}
		if (rosterReadable && ++missingTicks < CLAN_ROSTER_GRACE_TICKS)
		{
			return; // a blink, not a departure
		}
		rosterReadable = false;
	}

	/** Client thread only. Is the local player in a clan channel we can scrape? */
	public boolean isClanScrapeAvailable()
	{
		ClanChannel ch = client.getClanChannel();
		ClanSettings settings = client.getClanSettings();
		return ch != null && settings != null && settings.getMembers() != null && !settings.getMembers().isEmpty();
	}

	/** Client thread only. The clan's name, or null when there isn't one to read. */
	public String getClanName()
	{
		ClanSettings settings = client.getClanSettings();
		return settings == null ? null : settings.getName();
	}

	// ───────────────────────────────────────────────────────────── the push ──

	/** A login cleared: the next automatic sync may speak even to say nothing changed. */
	public void onLogout()
	{
		reportedThisLogin = false;
	}

	/**
	 * The sidebar's "Sync clan roster" button.
	 *
	 * <p>Its own in-flight guard so a double click is one push, and the result reported in chat where
	 * the player is looking. Refused outright when the clan channel is not readable — the roster is
	 * scraped from it, so there would be nothing to send.</p>
	 */
	public void syncFromPanel(Runnable onDone)
	{
		if (!admin)
		{
			chat.send("Only clan admins can sync the roster.");
			return;
		}
		// The CACHED answer, because this arrives from the side panel on the EDT — reading the clan
		// channel from there is the thread violation that swallowed the profile button's message.
		if (!rosterReadable)
		{
			chat.send("Join your clan channel first — the roster is read from it.");
			return;
		}
		if (panelSyncRunning)
		{
			chat.send("Already syncing the roster.");
			return;
		}
		panelSyncRunning = true;
		chat.send("Syncing the clan roster...");
		sync((ok, msg) ->
		{
			panelSyncRunning = false;
			onDone.run();
		});
	}

	/**
	 * Push the roster if we are allowed to and have not recently.
	 *
	 * <p>Every guard is a reason not to send: not an admin, no token, roster not loaded yet, one
	 * already running, or one ran within the last half hour.</p>
	 */
	public void autoSync()
	{
		if (autoSyncRunning || !admin)
		{
			return;
		}
		String token = config.playerToken();
		if (token == null || token.isEmpty() || !apiClient.isConfigured())
		{
			return;
		}
		long now = System.currentTimeMillis();
		if (lastAutoSyncAt != 0 && now - lastAutoSyncAt < AUTO_ROSTER_MIN_GAP_MS)
		{
			return;
		}
		autoSyncRunning = true;
		lastAutoSyncAt = now;
		announceNext = true;
		sync((ok, msg) ->
		{
			autoSyncRunning = false;
			if (ok)
			{
				log.debug("Clan roster auto-synced: {}", msg);
			}
			else
			{
				// Most likely "roster not loaded yet" — let the next channel change try again.
				lastAutoSyncAt = 0;
				log.debug("Clan roster auto-sync skipped: {}", msg);
			}
		});
	}

	/** Scrape the roster on the client thread, then POST it off it. */
	public void sync(Callback cb)
	{
		if (!tasks.isLive())
		{
			cb.onResult(false, "Plugin not running");
			return;
		}
		String token = config.playerToken();
		if (token == null || token.isEmpty())
		{
			cb.onResult(false, "Set your account token in plugin config first.");
			return;
		}

		// Wait your turn. Both buttons and the login-time push come through here, so this is the one
		// place that can hold the line.
		boolean automatic = announceNext;
		long gate = System.currentTimeMillis();
		boolean backedOff = !backoff.ready(gate);
		long waitMs = backedOff ? backoff.secondsUntilReady(gate) * 1000L : pushAllowedAt - gate;
		if (waitMs > 0)
		{
			// This attempt isn't happening, so it doesn't get to speak for the login either.
			announceNext = false;
			long secs = Math.max(1, (waitMs + 999) / 1000);
			String why = backedOff
				? "The site didn't take the last roster push — trying again in " + secs + "s."
				: "The roster was just synced — try again in " + secs + "s.";
			if (!automatic)
			{
				chat.send(why);
			}
			cb.onResult(false, why);
			return;
		}

		clientThread.invokeLater(() ->
		{
			if (!isClanScrapeAvailable())
			{
				cb.onResult(false, "Open the clan tab in OSRS first so the roster is loaded.");
				return;
			}
			ClanSettings settings = client.getClanSettings();
			String clanName = settings.getName();
			List<ClanMember> members = new ArrayList<>();
			for (net.runelite.api.clan.ClanMember m : settings.getMembers())
			{
				ClanMember out = new ClanMember();
				out.rsn = m.getName();
				ClanRank rank = m.getRank();
				if (rank != null)
				{
					ClanTitle title = settings.titleForRank(rank);
					out.rank = title != null ? title.getName() : String.valueOf(rank.getRank());
				}
				LocalDate joined = m.getJoinDate();
				if (joined != null)
				{
					out.joinedDays = (int) ChronoUnit.DAYS.between(joined, LocalDate.now());
				}
				members.add(out);
			}

			tasks.run(() -> post(clanName, members, automatic, cb));
		});
	}

	private void post(String clanName, List<ClanMember> members, boolean automatic, Callback cb)
	{
		try
		{
			ClanSyncResponse r = apiClient.syncClan(config.playerToken(), clanName, members);
			backoff.onSuccess();
			pushAllowedAt = System.currentTimeMillis() + ROSTER_PUSH_COOLDOWN_MS;
			lastSummary = "+" + r.added + " added · " + r.updated + " updated · " + r.markedLeft + " left";
			announceNext = false;
			if (automatic)
			{
				reportAutomatic(r);
			}
			else
			{
				chat.send("Clan roster synced: " + lastSummary);
				reportPerMemberChanges(r);
			}
			reportPlanLimit(r);
			cb.onResult(true, lastSummary);
		}
		catch (AdminUnauthorizedException e)
		{
			// Not (or no longer) an admin — hide the button until the next login probe.
			admin = false;
			chat.send("Clan sync failed: your account token isn't an admin (or was revoked).");
			cb.onResult(false, "Your account token isn't an admin (or was revoked).");
		}
		catch (ClanMismatchException e)
		{
			String server = e.serverClanName == null ? "(not set)" : e.serverClanName;
			chat.send("Clan sync failed: clan name doesn't match site config (" + server + ").");
			cb.onResult(false, "Clan name doesn't match site config (" + server + ").");
		}
		catch (RateLimitedException e)
		{
			// The site said when. Hold exactly that long rather than guessing at it.
			pushAllowedAt = System.currentTimeMillis() + Math.max(e.retryAfterMs, 1_000L);
			log.debug("Clan sync rate-limited for {}ms", e.retryAfterMs);
			if (!automatic)
			{
				chat.send("The site is limiting roster syncs — try again in "
					+ Math.max(1, (e.retryAfterMs + 999) / 1000) + "s.");
			}
			cb.onResult(false, "Rate limited by the site.");
		}
		catch (IOException e)
		{
			// Might clear on its own (site down, network gone), so wait longer each time instead of
			// letting a button turn into a retry loop against a dead host.
			backoff.onFailure(System.currentTimeMillis());
			log.warn("Clan sync failed: {}", e.getMessage());
			if (!automatic)
			{
				chat.send("Clan sync failed: " + e.getMessage());
			}
			cb.onResult(false, "Sync failed: " + e.getMessage());
		}
	}

	/**
	 * A sync nobody asked for reports itself only when the roster actually MOVED — except for the
	 * first of a login, which speaks either way so you know the plugin is talking to your site.
	 */
	private void reportAutomatic(ClanSyncResponse r)
	{
		List<String> parts = new ArrayList<>();
		if (r.added > 0)
		{
			parts.add(r.added + " joined");
		}
		if (r.returned > 0)
		{
			parts.add(r.returned + " returned");
		}
		if (r.markedLeft > 0)
		{
			parts.add(r.markedLeft + " left");
		}
		if (r.renamed > 0)
		{
			parts.add(r.renamed + " renamed");
		}
		boolean moved = !parts.isEmpty();
		boolean firstThisLogin = !reportedThisLogin;
		reportedThisLogin = true;
		if (moved)
		{
			chat.send("Clan roster updated: " + String.join(", ", parts) + ".");
		}
		else if (firstThisLogin)
		{
			chat.send("Clan roster checked — nothing changed.");
		}
	}

	/**
	 * One chat line per member change, capped so a busy sync doesn't flood the chatbox. Only for a
	 * sync somebody asked for: the automatic one has said its piece.
	 */
	private void reportPerMemberChanges(ClanSyncResponse r)
	{
		if (r.changes == null || r.changes.isEmpty())
		{
			return;
		}
		int cap = 12;
		int shown = 0;
		for (ClanChange ch : r.changes)
		{
			if (shown >= cap)
			{
				break;
			}
			String line = changeLine(ch);
			if (line == null)
			{
				continue;
			}
			chat.send(line);
			shown++;
		}
		if (r.changes.size() > cap)
		{
			chat.send("...and " + (r.changes.size() - cap) + " more changes (see Discord audit feed).");
		}
	}

	/** One member change as a sentence, or null for a kind we have nothing to say about. */
	private static String changeLine(ClanChange ch)
	{
		switch (ch.type == null ? "" : ch.type)
		{
			case "joined":
				return ch.rsn + " joined the clan.";
			case "left":
				return ch.rsn + " left the clan.";
			case "returned":
				return ch.rsn + " returned to the clan.";
			case "renamed":
				return (ch.oldRsn == null ? "?" : ch.oldRsn) + " is now known as " + ch.rsn + ".";
			case "rank_changed":
				return ch.rsn + " is now " + (ch.newRank == null ? "ranked" : ch.newRank)
					+ (ch.oldRank != null ? " (was " + ch.oldRank + ")" : "") + ".";
			default:
				return null;
		}
	}

	/**
	 * Plan limit. The admin running the sync is the one person who can act on this and they are right
	 * here, so say it in-game rather than leaving it to a banner they would have to open the site to
	 * see. Names first, because "6 members were not added" is only useful if you know WHICH six.
	 */
	private void reportPlanLimit(ClanSyncResponse r)
	{
		if (r.refusedNewMembers != null && !r.refusedNewMembers.isEmpty())
		{
			int cap = 6;
			String names = String.join(", ",
				r.refusedNewMembers.subList(0, Math.min(cap, r.refusedNewMembers.size())));
			String more = r.refusedNewMembers.size() > cap
				? " and " + (r.refusedNewMembers.size() - cap) + " more"
				: "";
			chat.send("Not added (plan limit): " + names + more + ".");
		}
		// Whatever the server wants to say about the cap, in its own words.
		if (r.capNotice != null && !r.capNotice.isEmpty())
		{
			chat.send(r.capNotice);
		}
	}
}
