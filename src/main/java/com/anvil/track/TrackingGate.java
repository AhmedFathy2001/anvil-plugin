package com.anvil.track;

import com.anvil.AnvilConfig;
import com.anvil.api.PluginConfigResponse;
import com.anvil.ui.AnvilOverlay;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * The three reasons nothing is being credited, asked in one place.
 *
 * <p>Auto-submit switched off, no event config loaded, or the event is not currently running. Every
 * tracker checks all three before it does anything, and before this existed four of them checked two
 * by hand and tested the third separately and silently — so "event not currently active" never
 * reached client.log on those paths while it did on the others.</p>
 *
 * <p>A reason is logged once per session. These gates run for every loot event, every kill and every
 * chat line, so logging unconditionally would flood the file that support requests are read from.</p>
 */
@Slf4j
@Singleton
public class TrackingGate
{
    private final AnvilConfig config;

    private Supplier<PluginConfigResponse> pluginConfig = () -> null;

    @Inject
    TrackingGate(AnvilConfig config) {
        this.config = config;
    }

    public void bind(Supplier<PluginConfigResponse> pluginConfig) {
        this.pluginConfig = pluginConfig;
    }

    /** A new login may have different settings; let the reasons be said once more. */
    public void onLogout() {
        loggedSuppressions.clear();
    }

    // Once-per-session tracking-suppression notices, so a member's client.log answers "why did
    // nothing track" without a line per suppressed loot event. Keyed by reason; reset at login.
    private final Set<String> loggedSuppressions = new LinkedHashSet<>();

    /**
     * Logs a tracking-suppression reason once per session at INFO. The gates this guards run
     * for every loot/kill/chat signal, so unconditional logging would flood client.log —
     * once per reason keeps the log diagnostic ("send me your client.log") without the spam.
     */
    public void logSuppressed(String reason) {
        if (loggedSuppressions.add(reason)) {
            log.info("Anvil tracking suppressed — {} (logged once per session)", reason);
        }
    }

    /** The suppression reason for the shared config gates, or null when tracking is live. */
    public String reason() {
        if (!config.autoSubmit()) {
            return "auto-submit disabled in plugin settings";
        }
        if (pluginConfig.get() == null) {
            return "no event config loaded (not enrolled, or token/RSN not resolved)";
        }
        if (!AnvilOverlay.isEventActive(pluginConfig.get().event)) {
            return "event not currently active";
        }
        return null;
    }
}
