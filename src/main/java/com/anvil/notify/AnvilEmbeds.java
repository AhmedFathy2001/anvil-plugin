package com.anvil.notify;

import com.anvil.api.BingoApiClient;
import com.anvil.api.PluginConfigResponse;
import com.anvil.api.dto.NotifyChannels;
import com.anvil.util.TaskRunner;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.DrawManager;

/**
 * Everything the clan's Discord posts have in common: the pieces of an embed, the screenshot that
 * may or may not ride with it, and whether the channel wants to hear about it at all.
 *
 * <p>The colours, the author line, the thumbnail, the wiki link, the backticked stat field — those
 * are static, because they are the grammar of a post and need nothing but their arguments. What
 * takes injection is the part that touches the world: capturing a frame, pricing an item, asking
 * the config which channels a clan has configured.</p>
 *
 * <h2>The screenshot is named, not attached</h2>
 *
 * <p>The file goes up in the same multipart request and Discord resolves {@code attachment://name}
 * against it. If the capture then fails, the reference has to come back out or the post renders a
 * broken frame — which is why there is a {@code postWithOptionalShot} rather than an {@code if}.</p>
 *
 * <h2>And a stalled capture must not stall the post</h2>
 *
 * <p>Frames normally arrive in about fifty milliseconds. A minimised client never renders one at
 * all, so the capture is raced against a timeout and the post goes out without its picture rather
 * than not going out. Delivery is latched: whichever of the two arrives first wins, exactly once.</p>
 */
@Slf4j
@Singleton
public class AnvilEmbeds
{
    private final BingoApiClient apiClient;
    private final ItemManager itemManager;
    private final DrawManager drawManager;
    private final TaskRunner tasks;

    /** The live event config — replaced on every poll, so a supplier and not the value. */
    private Supplier<PluginConfigResponse> pluginConfig = () -> null;

    @Inject
    AnvilEmbeds(BingoApiClient apiClient, ItemManager itemManager, DrawManager drawManager, TaskRunner tasks) {
        this.apiClient = apiClient;
        this.itemManager = itemManager;
        this.drawManager = drawManager;
        this.tasks = tasks;
    }

    public void bind(Supplier<PluginConfigResponse> pluginConfig) {
        this.pluginConfig = pluginConfig;
    }

    /** The site accent, on a rare-drop post. */
    public static int rareColor() {
        return RARE_EMBED_COLOR;
    }

    /** Blue, so an achievement post is not mistaken for a drop at a glance. */
    public static int achievementColor() {
        return CA_EMBED_COLOR;
    }

    public static String caIconUrl() {
        return CA_ICON_URL;
    }

    public static String caWikiUrl() {
        return CA_WIKI_URL;
    }

    private static final int RARE_EMBED_COLOR = 0xD4A017; // gold, matches the site accent
    private static final int CA_EMBED_COLOR = 0x4A90D9; // blue, distinct from rare-drop gold
    // Combat Achievements crest + hub page — the embed's thumbnail and title link. Both are plain
    // strings handed to Discord in the payload; the plugin never fetches either.
    private static final String CA_ICON_URL = "https://oldschool.runescape.wiki/images/Combat_Achievements_icon.png";

    private static final String CA_WIKI_URL = "https://oldschool.runescape.wiki/w/Combat_Achievements";

    /** A one-entry Discord `fields` array, inline so it sits beside the description rather than under it. */
    static JsonArray oneField(String name, String value) {
        JsonObject field = new JsonObject();
        field.addProperty("name", name);
        field.addProperty("value", value);
        field.addProperty("inline", true);
        JsonArray fields = new JsonArray();
        fields.add(field);
        return fields;
    }

    /**
     * RuneLite's static export of the game cache — the exact sprite the client renders, on a public
     * CDN Discord can fetch. Mirrors the site's lib/tileIcons.itemIconUrl.
     */
    static String itemIconUrl(int itemId) {
        return "https://static.runelite.net/cache/item/icon/" + itemId + ".png";
    }

    /**
     * Who this is about, in the embed's author line — omitted when we could not read a name.
     *
     * <p>Five posts carry it and five do not, which reads as an oversight rather than a decision;
     * this is the shared copy, so whoever decides that only has to change one thing.</p>
     */
    static void addAuthor(JsonObject embed, String rsn) {
        if (rsn == null || rsn.isEmpty()) {
            return;
        }
        JsonObject author = new JsonObject();
        author.addProperty("name", rsn);
        embed.add("author", author);
    }

    /** The little picture in the embed's corner. */
    static void addThumbnail(JsonObject embed, String url) {
        JsonObject thumb = new JsonObject();
        thumb.addProperty("url", url);
        embed.add("thumbnail", thumb);
    }

    /** The item's own sprite as the thumbnail. Silent for an id we could not resolve. */
    static void addItemThumbnail(JsonObject embed, Integer itemId) {
        if (itemId != null && itemId > 0) {
            addThumbnail(embed, itemIconUrl(itemId));
        }
    }

    /**
     * Point the embed's big image at the screenshot that will ride along with it.
     *
     * <p>Named rather than attached: the file goes up in the same multipart request, and Discord
     * resolves {@code attachment://name} against it. If the capture then fails, the reference has to
     * be removed or Discord renders a broken frame — see {@link #postWithOptionalShot}.</p>
     */
    static void addAttachment(JsonObject embed, String shotName) {
        JsonObject image = new JsonObject();
        image.addProperty("url", "attachment://" + shotName);
        embed.add("image", image);
    }

    /** The OSRS wiki page for a thing, as the embed's title link. */
    static void addWikiUrl(JsonObject embed, String pageName) {
        // The OSRS wiki uses underscores for spaces.
        embed.addProperty("url", "https://oldschool.runescape.wiki/w/" + pageName.replace(' ', '_'));
    }

    /**
     * The name to say when we have one, and something that still reads as a sentence when we do not.
     *
     * <p>Thirteen copies of this ternary. A post that says "A clan member just got a Twisted bow" is
     * worth making; one that says "null just got" is not.</p>
     */
    static String who(String rsn) {
        return rsn != null && !rsn.isEmpty() ? rsn : "A clan member";
    }

    /**
     * Post it, with the screenshot if the member wants one.
     *
     * <p>The embed arrives already pointing at {@code attachment://<shotName>}, so the no-screenshot
     * path has to take that reference back out — an embed naming a file that never arrives renders
     * as a broken image.</p>
     */
    void postWithOptionalShot(String channel, JsonObject embed, String shotName, boolean wantShot) {
        if (wantShot) {
            postWithScreenshot(channel, embed, shotName);
        } else {
            embed.remove("image");
            apiClient.postNotification(channel, null, embed, null, null);
        }
    }

    static JsonObject embedField(String name, String value, boolean inline) {
        JsonObject f = new JsonObject();
        f.addProperty("name", name);
        f.addProperty("value", value);
        f.addProperty("inline", inline);
        return f;
    }

    /** An inline field whose value is a number or short token — boxed with backticks. */
    static JsonObject statField(String name, String value) {
        return embedField(name, "`" + value.replace("`", "") + "`", true);
    }

    /**
     * Higher of GE price and high-alch value for a single item. Safe to call on
     * the client thread.
     */
    /** Higher of GE price and high-alch value for one item. Safe on the client thread. */
    public long itemUnitValue(int itemId) {
        long ge = 0;
        long ha = 0;
        try {
            ge = Math.max(0, itemManager.getItemPrice(itemId));
        } catch (Exception ignored) {
        }
        try {
            ItemComposition comp = itemManager.getItemComposition(itemId);
            if (comp != null) {
                ha = Math.max(0, comp.getHaPrice());
            }
        } catch (Exception ignored) {
        }
        return Math.max(ge, ha);
    }

    /**
     * Whether a clan notification channel ("deaths", "pvpKills", "rareDrops",
     * "combatAchievements") has a Discord webhook configured on the site. The
     * flags are fetched on launch as part of the plugin config and refreshed
     * periodically. When true, the plugin posts the notification to its own
     * server (/api/plugin/notify), which forwards it to Discord — the plugin
     * never sees the webhook URL itself.
     */
    /**
     * Whether the clan has somewhere to put this kind of post.
     *
     * <p>Five kinds -- pets, levels, quests, diaries, collection-log slots -- used to be announced
     * under the name of the channel they shared. Each has its own name now, and a site that predates
     * that split sends no flag for it; a boxed null there means "ask the channel it came from"
     * rather than "off", so an older site keeps posting 99s exactly where it always did.</p>
     *
     * <p>Unknown names return false. The old default arm answered {@code rareDrops} for anything it
     * didn't recognise, which would have quietly routed every new channel through the drop flag.</p>
     */
    public boolean notifyEnabled(String channel) {
        PluginConfigResponse cfg = pluginConfig.get();
        if (cfg == null || cfg.notify == null) {
            return false;
        }
        return channelEnabled(cfg.notify, channel);
    }

    /** The resolution itself, free of plugin state so the inheritance can be tested directly. */
    static boolean channelEnabled(NotifyChannels n, String channel) {
        if (n == null) {
            return false;
        }
        switch (channel) {
            case "rareDrops":
                return n.rareDrops;
            case "deaths":
                return n.deaths;
            case "combatAchievements":
                return n.combatAchievements;
            case "pvpKills":
                return n.pvpKills;
            case "pets":
                return n.pets != null ? n.pets : n.rareDrops;
            case "levels":
                return n.levels != null ? n.levels : n.combatAchievements;
            case "quests":
                return n.quests != null ? n.quests : n.combatAchievements;
            case "diaries":
                return n.diaries != null ? n.diaries : n.combatAchievements;
            case "collectionLog":
                return n.collectionLog != null ? n.collectionLog : n.combatAchievements;
            default:
                return false;
        }
    }

    // A stalled capture must not stall the notification: frames normally arrive within ~50ms,
    // so a few seconds of grace is already generous before posting without the screenshot.
    private static final long FRAME_CAPTURE_TIMEOUT_MS = 4000;

    /**
     * Captures the next rendered frame and hands the PNG bytes to
     * {@code consumer} OFF the client thread. The frame listener fires on the
     * client/AWT thread, so we immediately defer encoding to the executor; the
     * consumer then sends via OkHttp async. The game loop never waits on
     * either.
     *
     * <p>The consumer is guaranteed to run exactly once — with {@code null} when no frame
     * arrives in time (a minimized client can stop rendering, and the next-frame listener
     * then never fires) or the PNG encode fails. Callers post without the screenshot in
     * that case; before this guarantee, a stalled capture silently dropped the whole
     * notification (a Maggot King fang post vanished this way).
     */
    public void captureFrameAsync(Consumer<byte[]> consumer) {
        AtomicBoolean delivered = new AtomicBoolean(false);
        drawManager.requestNextFrameListener(image -> {
            if (!tasks.isLive()) {
                return;
            }
            tasks.run(() -> {
                byte[] png = null;
                try {
                    BufferedImage buffered = (BufferedImage) image;
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(buffered, "png", baos);
                    png = baos.toByteArray();
                } catch (Exception e) {
                    log.debug("Anvil frame capture failed: {}", e.getMessage());
                }
                if (delivered.compareAndSet(false, true)) {
                    consumer.accept(png);
                }
            });
        });
        tasks.runLater(() -> {
            if (delivered.compareAndSet(false, true)) {
                log.info("Anvil: no frame within {}ms — notifying without a screenshot", FRAME_CAPTURE_TIMEOUT_MS);
                consumer.accept(null);
            }
        }, FRAME_CAPTURE_TIMEOUT_MS);
    }

    /**
     * Captures a frame and posts a rareDrops embed; a failed or stalled capture posts the
     * embed without its image (stripping the attachment reference so Discord renders clean)
     * instead of not posting at all.
     */
    /**
     * Post an achievement embed, with a screenshot when the member wants one.
     *
     * A 99 and a max are the most screenshotted moments in the game, and both went out as text. The
     * comment that made it so said "matching combat-achievement posts" — which was simply untrue: CA
     * posts have taken a screenshot since `caScreenshot` existed, so the 99 was out of step with the
     * very thing it cited. Same shape as that path now, down to removing the image reference when the
     * capture fails, so a dropped frame degrades to the text post rather than an embed with a hole.
     */
    void postAchievement(JsonObject embed, boolean withShot) {
        if (!withShot) {
            apiClient.postNotification("levels", null, embed, null, null);
            return;
        }
        String shotName = "anvil-achievement.png";
        addAttachment(embed, shotName);
        Runnable capture = () -> captureFrameAsync(png -> {
            if (png == null) {
                embed.remove("image");
            }
            apiClient.postNotification("levels", null, embed, png, shotName);
        });

        // Let the room react before the shutter.
        //
        // The instant a 99 lands the screen holds the fireworks and nothing else; the congratulations
        // that make the screenshot worth keeping are still being typed. A beat's pause catches the
        // clan chat with the moment instead of an empty chatbox beneath it.
        //
        // Scheduled, never slept: a sleep here would park a shared RuneLite worker for a second and a
        // half, and the hub rejects Thread.sleep on sight. Without a usable executor -- shutting down
        // mid-level-up -- we capture now, because a slightly emptier screenshot beats none.
        if (!tasks.isLive()) {
            capture.run();
            return;
        }
        tasks.runLater(capture, ACHIEVEMENT_SHOT_DELAY_MS);
    }

    /** Pause between the achievement and its screenshot, long enough for clanmates' replies. */
    private static final long ACHIEVEMENT_SHOT_DELAY_MS = 1500;

    /** Capture, then post to {@code channel}; a failed capture drops the image and posts anyway. */
    void postWithScreenshot(String channel, JsonObject embed, String shotName) {
        captureFrameAsync(png -> {
            if (png == null) {
                embed.remove("image");
            }
            apiClient.postNotification(channel, null, embed, png, shotName);
        });
    }
}
