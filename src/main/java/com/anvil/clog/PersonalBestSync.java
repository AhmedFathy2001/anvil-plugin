package com.anvil.clog;

import com.anvil.api.dto.RateLimitedException;
import com.anvil.api.dto.PermanentSubmissionException;
import com.anvil.util.AnvilChat;
import java.util.function.Supplier;
import java.util.List;
import com.anvil.api.PluginConfigResponse;
import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.ProfileSubmissions;
import com.anvil.detect.PersonalBests;
import com.anvil.util.SyncBackoff;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Personal bests: the times the account already has, and the ones it sets while playing.
 *
 * <h2>Where the existing ones come from</h2>
 *
 * <p>RuneLite's own Chambers-of-Xeric-style PB tracker has been writing them to its config for
 * years, under the {@code personalbest} group. Reading those is a one-time import that saves the
 * member re-running everything they have already done — and it is a READ of the local config, of
 * their own times, on their own machine.</p>
 *
 * <p>Team sizes make that awkward: a PB is keyed by activity AND party size, so each activity is
 * probed across the sizes the game actually uses rather than guessed at. The import is marked done
 * once it succeeds, so a member who has none does not pay for the probe every login.</p>
 */
@Slf4j
@Singleton
public class PersonalBestSync
{
	private final AnvilConfig config;
	private final BingoApiClient apiClient;
	private final ConfigManager configManager;
	private final ProfileSubmissions profile;

	/** The live board — which activities this event actually wants times for. */
	private final Supplier<PluginConfigResponse> pluginConfig;

	private final AnvilChat chat;

	/**
	 * Does the clan's site have somewhere to put this at all?
	 *
	 * <p>A capability answer rather than a 404: a site that predates these endpoints would
	 * otherwise be asked every thirty seconds, forever, by every member of the clan.</p>
	 *
	 * <p>A {@link javax.inject.Provider} because ProfileSync owns that answer and injects this class
	 * — asking for it directly would be a cycle.</p>
	 */
	private final javax.inject.Provider<ProfileSync> clog;

	/**
	 * The account these times belong to.
	 *
	 * <p>Set when the profile sync binds to a login and cleared on logout: a time is per ACCOUNT, and
	 * pushing an alt's under the main's name would be worse than pushing nothing.</p>
	 */
	private volatile String profileSyncRsn;

	/** A chat line that might be a personal-best announcement. Cheap to offer; the tracker decides. */
	public void onChatLine(String plain, long nowMs)
	{
		personalBests.onChatLine(plain, nowMs);
	}

	/**
	 * An activity just finished, named by its kill-count line.
	 *
	 * <p>That is what lets a PB line arriving a tick later be attributed to the right boss rather
	 * than to whichever one happened to be seen last.</p>
	 */
	public void onActivitySeen(String activity, long nowMs)
	{
		personalBests.onActivitySeen(activity, nowMs);
	}

	/** A new account's times are not this one's. */
	public void reset()
	{
		personalBests.reset();
	}

	/** Restore what was already pushed for this account, so a relog does not re-send everything. */
	public void restoreState(String key)
	{
		personalBests.restoreState(configManager.getConfiguration("osrsbingo", CFG_PB_STATE + ":" + key));
	}

	/**
	 * A login settled on an account: restore what was already pushed for it, then import whatever
	 * RuneLite's own tracker has been recording all along.
	 */
	public void onAccountBound(String key)
	{
		restoreState(key);
		importRuneLitePersonalBests(key);
	}

	/** Bind to the account that just logged in, or {@code null} on logout. */
	public void bindAccount(String rsn)
	{
		this.profileSyncRsn = rsn;
	}

	@Inject
	PersonalBestSync(AnvilConfig config, BingoApiClient apiClient, ConfigManager configManager,
		ProfileSubmissions profile, Supplier<PluginConfigResponse> pluginConfig,
		AnvilChat chat, javax.inject.Provider<ProfileSync> clog)
	{
		this.config = config;
		this.apiClient = apiClient;
		this.configManager = configManager;
		this.profile = profile;
		this.pluginConfig = pluginConfig;
		this.chat = chat;
		this.clog = clog;
	}

    private static final String CFG_PB_STATE = "pbSyncState";

    private static final String CFG_PB_IMPORTED = "pbImportedFromRuneLite";

    /** RuneLite's own chat-commands store, read once to seed a profile (see importRuneLitePersonalBests). */
    private static final String RUNELITE_PB_GROUP = "personalbest";

    /** Exact team sizes RuneLite files raids under ("chambers of xeric 3 players"). */
    private static final int MAX_PB_TEAM_SIZE = 24;

    /** Large teams are recorded as a RANGE, not a count — the buckets the raid chat itself prints. */
    private static final String[] PB_TEAM_BUCKETS = {
        "5+ players", "10+ players", "11-15 players", "16-23 players", "24+ players",
        "25+ players", "50+ players", "100+ players",
    };

    private final SyncBackoff pbBackoff = new SyncBackoff();

    private final PersonalBests personalBests = new PersonalBests();

    /**
     * Copy the personal bests RuneLite's own chat-commands plugin has already recorded, once.
     *
     * <p>Local config only -- the same store the player's own client wrote, on the same machine, for
     * the account they're logged into. Nothing is read about anyone else. Without it a profile
     * starts empty and fills in over months; with it, a player who has been playing for years sees
     * their real times the first time they open their clan profile.
     *
     * <p>Runs once per account per activity list (a flag in our own config), and does nothing when
     * chat-commands has never run -- then the live capture builds the set from the next kill onward.
     * The flag stores a signature of the names we asked for, so when the site adds an activity --
     * the awakened DT2 bosses, say -- everyone re-probes once for the new names. Re-running is safe:
     * a seeded time only ever replaces a slower one.
     */
    private void importRuneLitePersonalBests(String rsnKey) {
        if (!config.importRuneLitePbs() || !config.syncPersonalBests()) {
            return;
        }
        if (configManager.getRSProfileKey() == null || configManager.getRSProfileKey().isEmpty()) {
            return; // No RS profile yet -- try again next login rather than marking it done.
        }
        // The names to ask for. Without them there is nothing to probe, so we leave the flag unset
        // and try again once the site's config has landed.
        PluginConfigResponse cfg = pluginConfig.get();
        List<String> activities = cfg != null ? cfg.pbActivities : null;
        if (activities == null || activities.isEmpty()) {
            return;
        }
        // String.hashCode is specified, so the same list gives the same signature on every client
        // and every release. An older flag ("1", or an earlier list) simply doesn't match.
        String signature = Integer.toString(activities.hashCode());
        String done = configManager.getConfiguration("osrsbingo", CFG_PB_IMPORTED + ":" + rsnKey);
        if (signature.equals(done)) {
            return;
        }

        // ASK, don't list. RuneLite stores these under the RS-profile scope, and its only key-listing
        // API reads the main profile — so the obvious loop over getConfigurationKeys() silently found
        // nothing at all, whatever prefix it was given. getRSProfileConfiguration reads the right
        // store, one key at a time, which is why the names have to come from somewhere.
        Map<String, Integer> imported = new HashMap<>();
        for (String base : activities) {
            if (base == null || base.isEmpty()) {
                continue;
            }
            probeRuneLitePb(base, imported);
            // Every scale RuneLite files a raid under. Its own pattern is
            // "(?<teamsize>\\d+(?:\\+|-\\d+)? players?|Solo)", so a solo run is the WORD solo — not
            // "1 players" — and a big team is a bucket rather than an exact count. Probing only
            // "N players" therefore missed every solo raid time anyone had.
            probeRuneLitePb(base + " solo", imported);
            for (int size = 1; size <= MAX_PB_TEAM_SIZE; size++) {
                probeRuneLitePb(base + " " + size + " players", imported);
            }
            for (String bucket : PB_TEAM_BUCKETS) {
                probeRuneLitePb(base + " " + bucket, imported);
            }
        }

        int adopted = personalBests.seed(imported, System.currentTimeMillis());
        configManager.setConfiguration("osrsbingo", CFG_PB_IMPORTED + ":" + rsnKey, signature);
        log.info("Anvil: imported {} existing personal best(s) from RuneLite ({} probed)",
                adopted, imported.size());
        if (adopted > 0) {
            chat.send("Imported " + adopted + " personal best" + (adopted == 1 ? "" : "s")
                    + " from RuneLite.");
        }
    }

    /**
     * Try the personal-best import again on the periodic tick.
     *
     * <p>It needs three things that don't arrive together: an account (login), an RS profile key
     * (login), and the site's activity list (the first config fetch). Running it only at login meant
     * the config usually hadn't landed yet and the import quietly did nothing. The once-per-account
     * flag makes every call after a successful one free.
     */
    public void retryImport() {
        String rsn = profileSyncRsn;
        if (rsn != null && !rsn.isEmpty()) {
            importRuneLitePersonalBests(rsn);
        }
    }

    /** Config values are text; a missing or corrupt one just means "no fingerprint yet". */
    private static long parseLongOrZero(String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** Read one stored best, if RuneLite has it for this account. Times are seconds; ours centis. */
    private void probeRuneLitePb(String activity, Map<String, Integer> into) {
        String raw = configManager.getRSProfileConfiguration(RUNELITE_PB_GROUP, activity);
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        try {
            int centis = (int) Math.round(Double.parseDouble(raw.trim()) * 100.0);
            if (centis > 0) {
                into.put(activity, centis);
            }
        } catch (NumberFormatException e) {
            // Not a time -- skip the key rather than the import.
        }
    }

    /** Same contract for best times. */
    public void flush() {
        if (!config.syncPersonalBests() || !apiClient.isConfigured() || profileSyncRsn == null
                || !clog.get().supportsProfileSync()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!personalBests.isDue(now) || !pbBackoff.ready(now)) {
            return;
        }
        Map<String, Integer> batch = personalBests.nextBatch();
        try {
            profile.submitPersonalBests(batch);
        } catch (RateLimitedException e) {
            // Bests ride the same limiter; the batch stays dirty and goes up when it clears.
            pbBackoff.onFailure(System.currentTimeMillis());
            log.debug("Personal bests rate-limited for {}ms", e.retryAfterMs);
            return;
        } catch (PermanentSubmissionException e) {
            log.info("Personal bests refused, dropping the batch: {}", e.getMessage());
            personalBests.onSent(batch);
            return;
        } catch (Exception e) {
            pbBackoff.onFailure(now);
            log.debug("Personal best push failed, retrying in {}s: {}",
                    pbBackoff.secondsUntilReady(now), e.getMessage());
            return;
        }
        pbBackoff.onSuccess();
        personalBests.onSent(batch);
        configManager.setConfiguration("osrsbingo", CFG_PB_STATE + ":" + profileSyncRsn,
                personalBests.serializeState());
    }
}
