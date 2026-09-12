package com.anvil.notify;

import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.Set;
import java.util.LinkedHashSet;
import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.MediaUploads;
import com.anvil.api.PluginConfigResponse;
import com.anvil.session.LocalPlayer;
import com.anvil.util.AnvilChat;
import com.anvil.util.TaskRunner;
import com.google.gson.JsonObject;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import com.anvil.detect.GamePools;
import com.anvil.detect.QuestAnnounceTier;
import com.anvil.util.ChatText;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;

/**
 * A finished quest, read off the scroll the game draws to announce it.
 *
 * <p>There is no quest-completed event, so this reads the reward scroll's own text — and it reads it
 * a tick late, with a couple of retries, because the widget exists before its text is populated.
 * That is the whole reason for the retry count: the first read is usually blank.</p>
 */
@Slf4j
@Singleton
public class QuestNotifier
{
    // Session dedup — the scroll widget can reload (resizing, lag) without a new completion.
    private final Set<String> announcedQuests = new LinkedHashSet<>();

    // Quest-completed scroll interface — gameval InterfaceID.QUESTSCROLL (153); child 4 is
    // Questscroll.QUEST_TITLE, the "You have completed <Quest>!" line. Same signal RuneLite's
    // screenshot plugin keys off.
    private static final int QUEST_COMPLETED_GROUP_ID = 153;

    private static final int QUEST_COMPLETED_TEXT_CHILD = 4;

    // Quest-name extraction, ported from RuneLite's ScreenshotPlugin (BSD-2) — the scroll text
    // varies: "You have completed The Corsair Curse!", "'One Small Favour' completed!",
    // "Congratulations! You have defeated the Culinaromancer!" (RFD subquests), and the
    // "kind of"/"completely" phrasings of Hazeel Cult and Rag and Bone Man.
    private static final Pattern QUEST_PATTERN_1 = Pattern.compile(
            ".+?ve\\.*? (?<verb>been|rebuilt|.+?ed)? ?(?:the )?'?(?<quest>.+?)'?(?: [Qq]uest)?[!.]?$");

    private static final Pattern QUEST_PATTERN_2 = Pattern.compile(
            "'?(?<quest>.+?)'?(?: [Qq]uest)? (?<verb>[a-z]\\w+?ed)?(?: f.*?)?[!.]?$");

    /** The next account has its own quests to finish. */
    /** The interface group the game draws a finished quest's scroll into. */
    public static int questScrollGroup() {
        return QUEST_COMPLETED_GROUP_ID;
    }

    /** The next account has its own quests to finish. */
    public void onLogout() {
        announcedQuests.clear();
    }

    private final AnvilConfig config;
    private final BingoApiClient apiClient;
    private final TaskRunner tasks;
    private final AnvilChat chat;
    private final Supplier<PluginConfigResponse> pluginConfig;
    private final LocalPlayer localPlayer;
    private final AnvilEmbeds embeds;
    private final MediaUploads media;
    private final MomentsService moments;
    private final Client client;
    private final ClientThread clientThread;
    private final AchievementNotifier achievements;
    private final com.anvil.track.RecapCounters counters;

    @Inject
    QuestNotifier(AnvilConfig config, BingoApiClient apiClient, TaskRunner tasks, AnvilChat chat,
            Supplier<PluginConfigResponse> pluginConfig, LocalPlayer localPlayer,
            AnvilEmbeds embeds, MediaUploads media, MomentsService moments,
            Client client, ClientThread clientThread, AchievementNotifier achievements,
            com.anvil.track.RecapCounters counters) {
        this.config = config;
        this.apiClient = apiClient;
        this.tasks = tasks;
        this.chat = chat;
        this.pluginConfig = pluginConfig;
        this.localPlayer = localPlayer;
        this.embeds = embeds;
        this.media = media;
        this.moments = moments;
        this.client = client;
        this.clientThread = clientThread;
        this.achievements = achievements;
        this.counters = counters;
    }

    /**
     * Reads the quest-completed scroll and posts the completion, gated by the
     * configured difficulty threshold (default Master &amp; up). Runs a tick
     * after the widget loads so the text child is populated; retries a couple
     * of ticks if the text lands late.
     */
    public void scheduleQuestScrollRead(int attemptsLeft) {
        clientThread.invokeLater(() -> {
            net.runelite.api.widgets.Widget text = client.getWidget(QUEST_COMPLETED_GROUP_ID, QUEST_COMPLETED_TEXT_CHILD);
            String raw = text != null ? text.getText() : null;
            if (raw == null || raw.isEmpty()) {
                if (attemptsLeft > 0) {
                    scheduleQuestScrollRead(attemptsLeft - 1);
                }
                return;
            }
            // A widget, so the angle-bracket form is what appears here — but it costs nothing to
            // take the @ codes too, and the quest scroll is styled by the same game.
            String plain = ChatText.CHAT_TAG.matcher(raw).replaceAll(" ").replaceAll("\\s+", " ").trim();
            String quest = parseQuestScroll(plain);
            if (quest == null || quest.contains("partial completion")) {
                return; // unparseable, or Hazeel Cult's "kind of completed" — not a completion
            }
            if (!announcedQuests.add(quest.toLowerCase())) {
                return; // widget re-loaded for a quest already posted this session
            }
            postQuestCompletion(quest);
        });
    }

    /**
     * Parses the quest-completed scroll text into the quest name. Ported from
     * RuneLite's ScreenshotPlugin (BSD-2) so all the scroll's text variants
     * resolve correctly — RFD subquests become "Recipe for Disaster - X",
     * "completely completed Rag and Bone Man" becomes "Rag and Bone Man II",
     * and names genuinely containing "Quest" (Legends' Quest, Doric's Quest)
     * keep the word. Returns null when nothing matches. Package-private for
     * the unit test.
     */
    static String parseQuestScroll(String text) {
        Matcher m1 = QUEST_PATTERN_1.matcher(text);
        Matcher m2 = QUEST_PATTERN_2.matcher(text);
        Matcher m = m1.matches() ? m1 : m2;
        if (!m.matches()) {
            return null;
        }
        String quest = m.group("quest");
        String verb = m.group("verb") != null ? m.group("verb") : "";
        if (verb.contains("kind of")) {
            quest += " partial completion";
        } else if (verb.contains("completely")) {
            quest += " II";
        }
        final String questAndVerb = quest + verb;
        if (GamePools.RFD_TAGS.stream().anyMatch(questAndVerb::contains)) {
            quest = "Recipe for Disaster - " + quest;
        }
        final String questName = quest;
        if (GamePools.WORD_QUEST_IN_NAME_TAGS.stream().anyMatch(questName::contains)) {
            quest += " Quest";
        }
        return quest;
    }

    /**
     * Posts a quest completion to the clan achievements channel. Tier comes
     * from the baked name sets; a quest in neither set counts as below Master,
     * so only the "All quests" setting posts it. Message-only, like CA posts.
     */
    private void postQuestCompletion(String questName) {
        QuestAnnounceTier setting = config.questAnnounce();
        if (setting == QuestAnnounceTier.OFF || !embeds.notifyEnabled("quests")) {
            return;
        }
        String key = questName.toLowerCase();
        boolean gm = GamePools.GRANDMASTER_QUESTS.contains(key);
        boolean master = GamePools.MASTER_QUESTS.contains(key);
        if (setting == QuestAnnounceTier.GRANDMASTER && !gm) {
            return;
        }
        if (setting == QuestAnnounceTier.MASTER && !gm && !master) {
            return;
        }
        String rsn = localPlayer.name();
        String tierTag = gm ? " (Grandmaster)" : master ? " (Master)" : "";
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "🗺️ Quest complete!");
        embed.addProperty("description",
                AnvilEmbeds.who(rsn) + " just completed **" + questName + "**" + tierTag + "!");
        embed.addProperty("color", AnvilEmbeds.achievementColor());
        media.postNotification("quests", null, embed, null, null);
    }
}
