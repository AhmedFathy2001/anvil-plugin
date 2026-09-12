package com.anvil.io;

import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.PluginConfigResponse;
import com.anvil.util.AnvilChat;
import com.anvil.util.TaskRunner;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.callback.ClientThread;

/**
 * The support log a member sends when something is wrong, and nothing they would not want to send.
 *
 * <p>"It isn't working" is most of what arrives, and answering it used to mean a round of questions
 * about which URL they typed and whether they are signed in. This writes the answers down: client
 * and plugin versions, whether a site is configured at all, which account is logged in, what event
 * the plugin thinks is running, and every proof still queued on disk with its age.</p>
 *
 * <p><b>Never the account token.</b> The header says whether one is SET, which is the diagnostic
 * question, and never what it is — this file gets pasted into Discord.</p>
 */
@Singleton
public class DebugSupportLog
{
    private final Client client;
    private final ClientThread clientThread;
    private final AnvilConfig config;
    private final BingoApiClient apiClient;
    private final DebugLogExporter exporter;
    private final PendingSubmissionStore pendingSubmissionStore;
    private final AnvilChat chat;
    private final TaskRunner tasks;

    /** The live event config — replaced on every poll, so a supplier and not the value. */
    private Supplier<PluginConfigResponse> pluginConfig = () -> null;

    @Inject
    DebugSupportLog(Client client, ClientThread clientThread, AnvilConfig config, BingoApiClient apiClient,
            DebugLogExporter exporter, PendingSubmissionStore pendingSubmissionStore, AnvilChat chat,
            TaskRunner tasks) {
        this.client = client;
        this.clientThread = clientThread;
        this.config = config;
        this.apiClient = apiClient;
        this.exporter = exporter;
        this.pendingSubmissionStore = pendingSubmissionStore;
        this.chat = chat;
        this.tasks = tasks;
    }

    public void bind(Supplier<PluginConfigResponse> pluginConfig) {
        this.pluginConfig = pluginConfig;
    }

    /**
     * Save a shareable support log (a diagnostic header + the Anvil-relevant slice of client.log) and
     * tell the player where it went. Header is built on the client thread (safe access to game/plugin
     * state), then the disk work runs on the executor so we never touch the filesystem on the UI thread.
     */
    public void export() {
        clientThread.invoke(() -> {
            final String header = buildDiagnosticHeader();
            final Runnable job = () -> {
                DebugLogExporter.Result res = exporter.export(header);
                clientThread.invokeLater(() -> {
                    // Anvil's own chat styling, and only what actually happened: the folder no longer
                    // opens (LinkBrowser::open is restricted for hub releases), so saying it did sent
                    // people looking at a file manager that never appeared.
                    if (res == null) {
                        chat.send("Couldn't save the debug log. Look in your .runelite/anvil-debug "
                                + "folder, or ask your clan admin for help.");
                    } else {
                        chat.send("Debug log saved — its path is on your clipboard. Paste that into "
                                + "your file manager and send the newest 'anvil-debug' file to your clan admin.");
                    }
                });
            };
            // A false return is only reachable mid-shutdown, with the hotkey already unregistered —
            // there is nothing left to export into, so there is nothing to say about it either.
            tasks.run(job);
        });
    }

    /** Non-secret diagnostics that make a support log actionable. Never includes tokens. */
    private String buildDiagnosticHeader() {
        String nl = System.lineSeparator();
        StringBuilder sb = new StringBuilder();
        sb.append("=== Anvil debug export ===").append(nl);
        sb.append("Generated: ").append(ZonedDateTime.now()).append(nl);
        sb.append("OS: ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append(" (")
                .append(System.getProperty("os.arch")).append(')').append(nl);
        sb.append("Java: ").append(System.getProperty("java.version")).append(nl);
        String pkgVer = getClass().getPackage() != null ? getClass().getPackage().getImplementationVersion() : null;
        sb.append("Plugin version: ").append(pkgVer != null ? pkgVer : "(dev/unknown)").append(nl);

        sb.append("Site URL: ").append(blankToNone(config.apiUrl())).append(nl);
        sb.append("Account token set: ").append(config.playerToken().isEmpty() ? "no" : "yes").append(nl);
        sb.append("API configured: ").append(apiClient.isConfigured() ? "yes" : "no").append(nl);
        sb.append("Current RSN: ").append(blankToNone(apiClient.getCurrentRsn())).append(nl);
        GameState gs = client.getGameState();
        sb.append("Game state: ").append(gs != null ? gs.name() : "?").append(nl);

        PluginConfigResponse pc = pluginConfig.get();
        if (pc != null && pc.event != null) {
            sb.append("Active event: ").append(pc.event.name).append(" (id ").append(pc.event.id).append(')').append(nl);
        } else {
            sb.append("Active event: none").append(nl);
        }

        try {
            List<PendingSubmissionStore.PendingSubmission> pend = pendingSubmissionStore.loadAll();
            sb.append("Pending submissions: ").append(pend.size()).append(nl);
            long now = System.currentTimeMillis();
            for (PendingSubmissionStore.PendingSubmission p : pend) {
                sb.append("  - '").append(p.label).append("' tile ").append(p.tileId)
                        .append(", event ").append(p.eventId)
                        .append(", rsn ").append(p.capturedRsn)
                        .append(", age ").append((now - p.timestamp) / 60000L).append("m").append(nl);
            }
        } catch (Exception e) {
            sb.append("Pending submissions: (error reading: ").append(e.getMessage()).append(')').append(nl);
        }
        return sb.toString();
    }

    private static String blankToNone(String s) {
        return (s == null || s.isEmpty()) ? "(none)" : s;
    }
}
