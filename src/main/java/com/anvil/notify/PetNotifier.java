package com.anvil.notify;

import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.PluginConfigResponse;
import com.anvil.api.dto.Claim;
import com.anvil.api.dto.DropFacts;
import com.anvil.clog.model.Status;
import com.anvil.detect.AbstractRarityService;
import com.anvil.detect.DropLuck;
import com.anvil.detect.DropSource;
import com.anvil.util.AnvilChat;
import com.anvil.util.TaskRunner;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;

/**
 * Pets, which the game announces in two messages that arrive in either order.
 *
 * <p>"You have a funny feeling like you're being followed" says a pet dropped and does not say
 * which. "New item added to your collection log: Vorki" names it and does not say it was a pet. The
 * post wants both, so the first one parks a pending pet and the second claims it by name — and if no
 * name arrives inside the window, the post goes out as "Pet drop!" rather than not going out.</p>
 *
 * <p>That ordering is the game's, not ours, and it is fragile enough to be worth stating: the two
 * lines are separate chat messages, and everything here is built so that either order works and
 * neither produces two posts.</p>
 */
@Slf4j
@Singleton
public class PetNotifier
{
    private final AnvilConfig config;
    private final BingoApiClient apiClient;
    private final ItemManager itemManager;
    private final AnvilChat chat;
    private final TaskRunner tasks;
    private final AnvilEmbeds embeds;
    private final LootSourceMemory lootSource;
    private final MomentsService moments;

    private Supplier<PluginConfigResponse> pluginConfig = () -> null;
    private Supplier<String> localPlayerName = () -> null;
    /** Manual proofs are the plugin's job, not this one's — handed in so a pet can still get one. */
    private java.util.function.BiConsumer<String, String> manualProof = (a, b) -> { };

    @Inject
    PetNotifier(AnvilConfig config, BingoApiClient apiClient, ItemManager itemManager, AnvilChat chat,
            TaskRunner tasks, AnvilEmbeds embeds, LootSourceMemory lootSource, MomentsService moments) {
        this.config = config;
        this.apiClient = apiClient;
        this.itemManager = itemManager;
        this.chat = chat;
        this.tasks = tasks;
        this.embeds = embeds;
        this.lootSource = lootSource;
        this.moments = moments;
    }

    public void bind(Supplier<PluginConfigResponse> pluginConfig, Supplier<String> localPlayerName,
            java.util.function.BiConsumer<String, String> manualProof) {
        this.pluginConfig = pluginConfig;
        this.localPlayerName = localPlayerName;
        this.manualProof = manualProof;
    }

    /**
     * A pet drop waiting on its name before it posts.
     *
     * <p>The chat line that announces a pet doesn't say WHICH pet — it's the same sentence for a
     * Baby mole and a Tangleroot. The only line that names it is the collection-log unlock, which
     * follows a tick or two later, so the post waits for it rather than going out as "a pet".
     *
     * <p>This assumes the pet line lands FIRST, which is the order the game sends them. If it ever
     * arrived second the pet would post unnamed and the unlock would post separately — the same two
     * posts this code replaces, so the failure mode is the old behaviour rather than a broken one.
     */
    public static final class PendingPet {

        final boolean duplicate;
        final String source;
        final String sourceKind;
        final Integer killCount;
        /**
         * Whether this pet will actually be POSTED. The wait for the name happens either way — the
         * clan's site feed wants it too — so this is what tells the collection-log line whether
         * standing down would leave the pet unannounced.
         */
        public final boolean announce;
        /** The queued feed entry waiting for the same name, if any. */
        final String momentKey;
        String name;
        /**
         * Where it really came from, and the count there — resolvable only once the name is known
         * (see DropSource.resolvePetSource). Null until then, and null afterwards for a pet nothing
         * can place, which is what makes the post fall back to {@link #source}.
         */
        String resolvedSource;
        Integer resolvedKc;

        PendingPet(boolean duplicate, String source, String sourceKind, Integer killCount,
                   boolean announce, String momentKey) {
            this.duplicate = duplicate;
            this.source = source;
            this.sourceKind = sourceKind;
            this.killCount = killCount;
            this.announce = announce;
            this.momentKey = momentKey;
        }
    }

    private final Object petLock = new Object();

    private PendingPet pendingPet;

    /**
     * How long the pet post waits for its collection-log line. Long enough to cover the unlock
     * landing a tick or two later, short enough that the post still reads as immediate — and short
     * enough that it can't swallow an unrelated unlock from the next kill.
     */
    private static final long PET_NAME_WINDOW_MS = 2_000;

    /**
     * Claim a collection-log unlock as the name of the pet we just noticed.
     *
     * <p>Returns the pet when this line was its name — which tells the caller two different things
     * it must not confuse: the unlock is already accounted for (so it is not ALSO a drop), and, if
     * that pet is going to be posted, the ordinary collection-log posts should stand down or the
     * same pet lands twice, once as 🐾 and once as 📕. A member who only wanted the site feed still
     * gets their normal clog post, because for them nothing else is going to mention it.
     */
    public PendingPet claimPetName(String itemName) {
        if (itemName == null || itemName.isEmpty()) {
            return null;
        }
        PendingPet pet;
        synchronized (petLock) {
            if (pendingPet == null || pendingPet.name != null) {
                return null;
            }
            pendingPet.name = itemName;
            pet = pendingPet;
        }
        // Naming it is also what makes its SOURCE knowable: until now the only candidate was the
        // last loot the client saw. Resolve once, here, so the clan feed and the Discord post can
        // never disagree about which boss it came from.
        String resolved = DropSource.resolvePetSource(lootSource.dropFacts(), itemName, pet.source, lootSource.killCounts);
        synchronized (petLock) {
            pet.resolvedSource = resolved;
            pet.resolvedKc = resolved == null ? null
                    : (resolved.equalsIgnoreCase(pet.source) ? pet.killCount : lootSource.killCountFor(resolved));
        }
        moments.namePetMoment(pet.momentKey, itemName, resolved, pet.resolvedKc);
        return pet;
    }

    /**
     * Note a pet drop. The source and kill count are captured NOW — by the time anything is sent the
     * player may have moved on, and "from Callisto at 1,204 KC" is the whole story of the drop.
     *
     * <p>The clan's feed is recorded unconditionally; only the Discord post is gated. They are
     * different things: switching off the drops channel is a statement about a channel, not about
     * whether the pet happened.
     */
    public void handlePetDrop(boolean duplicate) {
        String source;
        String sourceKind;
        {
            LootSourceMemory.Recent recent = lootSource.recentLoot();
            source = recent.source;
            sourceKind = recent.kind;
        }
        Integer kc = lootSource.killCountFor(source);
        String momentKey = moments.recordPetMoment(source, sourceKind, kc);
        boolean announce = config.notifyPets() && embeds.notifyEnabled("pets");

        // Nothing is waiting on the name — no post to make and no feed entry to fill in — so don't
        // park a pet nothing will ever collect: the next collection-log line would claim it.
        if (!announce && momentKey == null) {
            return;
        }
        PendingPet pet = new PendingPet(duplicate, source, sourceKind, kc, announce, momentKey);
        synchronized (petLock) {
            pendingPet = pet;
        }
        if (tasks.isLive()) {
            tasks.runLater(() -> flushPetNotification(pet), PET_NAME_WINDOW_MS);
        } else {
            flushPetNotification(pet);
        }
    }

    /**
     * Post the pet, with whatever the wait turned up.
     *
     * <p>Every field here is omitted rather than guessed when it isn't known: a skilling pet fires no
     * loot event, so it has no source, no KC and no drop rate, and inventing any of those for a clan
     * channel would be worse than a shorter post.
     */
    private void flushPetNotification(PendingPet pet) {
        synchronized (petLock) {
            if (pendingPet == pet) {
                pendingPet = null;
            }
        }
        // The wait may have been for the site feed alone (drops channel off) — the name has been
        // filled in by now either way, and there is nothing here to post.
        if (!pet.announce) {
            return;
        }
        String rsn = localPlayerName.get();
        String shotName = "anvil-pet.png";
        String petName = pet.name;

        // Where it ACTUALLY came from, settled in claimPetName the moment the name arrived (the feed
        // took the same answer). A pet that never got named, or one nothing can place, falls back to
        // the observed loot source — which is all this ever had.
        String source;
        Integer killCount;
        synchronized (petLock) {
            source = pet.name == null ? pet.source : pet.resolvedSource;
            killCount = pet.name == null ? pet.killCount : pet.resolvedKc;
        }

        JsonObject embed = new JsonObject();
        AnvilEmbeds.addAuthor(embed, rsn);
        embed.addProperty("title", petName != null ? "🐾 " + petName : "🐾 Pet drop!");
        String name = AnvilEmbeds.who(rsn);
        embed.addProperty("description", pet.duplicate
                // The duplicate line is the game's own joke about a pet you already have.
                ? name + " has a funny feeling like they would have been followed."
                : name + " has a funny feeling like they're being followed.");
        embed.addProperty("color", AnvilEmbeds.rareColor());

        JsonArray fields = new JsonArray();
        fields.add(AnvilEmbeds.statField("Status", pet.duplicate ? "Duplicate" : "New!"));
        if (source != null && !source.isEmpty()) {
            fields.add(AnvilEmbeds.statField("From", source));
        } else {
            // A skilling pet has no monster; the skill is the only true thing there is to say, and
            // saying nothing at all is still better than naming the last thing that dropped loot.
            DropFacts.Pet entry = DropSource.petEntry(lootSource.dropFacts(), petName);
            if (entry != null && entry.skill != null && !entry.skill.isEmpty()) {
                fields.add(AnvilEmbeds.statField("From", LootSourceMemory.capitalize(entry.skill)));
            }
        }
        if (killCount != null && killCount > 0) {
            fields.add(AnvilEmbeds.statField("KC", String.format("%,d", killCount)));
        }

        // Real rarity or none: the rate comes from the same service the rare-drop posts price
        // against, asked with this pet's own item id.
        Integer itemId = petName != null ? lootSource.resolveItemIdByName(petName) : null;
        Double dropRate = petRarity(itemId, source, pet.sourceKind);
        if (dropRate != null && dropRate > 0) {
            fields.add(AnvilEmbeds.statField("Rarity", "1 in " + String.format("%,.0f", 1.0 / dropRate)));
            String luck = DropLuck.luckLabel(dropRate, killCount);
            if (luck != null && !luck.isEmpty()) {
                fields.add(AnvilEmbeds.statField("Luck", luck));
            }
        }
        embed.add("fields", fields);

        AnvilEmbeds.addItemThumbnail(embed, itemId);
        if (petName != null) {
            AnvilEmbeds.addWikiUrl(embed, petName);
        }

        if (config.petScreenshot()) {
            AnvilEmbeds.addAttachment(embed, shotName);
            embeds.postWithScreenshot("pets", embed, shotName);
        } else {
            apiClient.postNotification("pets", null, embed, null, null);
        }
    }

    /** This pet's drop rate from the rarity table its source uses, or null when nothing can price it. */
    private Double petRarity(Integer itemId, String source, String sourceKind) {
        if (itemId == null || itemId <= 0 || source == null || source.isEmpty()) {
            return null;
        }
        // The resolved source may be a monster the client never saw loot from, in which case there
        // is no observed KIND either — but a pet the server files under a killable monster is by
        // definition an npc drop, so the npc table is the right one to ask.
        AbstractRarityService service = lootSource.raritySource(sourceKind == null ? "npc" : sourceKind);
        if (service == null) {
            return null;
        }
        OptionalDouble r = service.getRarity(source, itemId, 1);
        return r.isPresent() && r.getAsDouble() > 0 ? r.getAsDouble() : null;
    }
}
