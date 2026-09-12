package com.anvil.track;

import java.util.HashMap;
import java.util.Map;
import java.awt.image.BufferedImage;
import com.anvil.AnvilConfig;
import com.anvil.api.dto.TrackedDrop;
import com.anvil.util.TaskRunner;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * How many of a thing we wait for before telling the server about it.
 *
 * <h2>Why wait at all</h2>
 *
 * <p>A barrage kills six NPCs in one tick, and a bank-standing gather adds a hundred items a minute.
 * Submitting each one separately would send a hundred requests, take a hundred screenshots, and post
 * a hundred Discord lines for what the player experienced as one thing happening. So a credit is
 * held briefly, added to, and sent once — with the running total the tile actually reached.</p>
 *
 * <p>The hold is short on purpose. A proof screenshot has to look like the moment it describes, and
 * a window long enough to be efficient is long enough for the player to have walked away.</p>
 */
@Slf4j
@Singleton
public class DropBatch
{
    // Drop coalescing — batch rapid same-tile drops into one screenshot + one submission.
    // Without this, killing 1 NPC that drops a stack of 2000 would fire 2000 captures and
    // hammer the server. Aggregates by (tileId, itemId), scheduled-flushed after a brief
    // settle delay so a kill spree still results in one well-annotated PNG.
    public static final long COALESCE_FLUSH_MS = 2_500;

    private final AnvilConfig config;
    private final TaskRunner tasks;
    private final Coalescer coalescer;
    private final ProofPipeline proofs;
    private final net.runelite.client.ui.DrawManager drawManager;

    @Inject
    DropBatch(AnvilConfig config, TaskRunner tasks, Coalescer coalescer, ProofPipeline proofs,
            net.runelite.client.ui.DrawManager drawManager) {
        this.config = config;
        this.tasks = tasks;
        this.coalescer = coalescer;
        this.proofs = proofs;
        this.drawManager = drawManager;
    }

    private static class DropAggregate extends TileAggregate {

        final TrackedDrop drop;
        final Integer trackingItemId;
        // Frame grabbed the moment the first drop of the burst landed. The flush shot fires
        // COALESCE_FLUSH_MS later (loot settled on the floor); the proof bakes both. RuneLite
        // hands listeners a copy of the graphics buffer, so holding it is safe.
        volatile BufferedImage triggerFrame;

        DropAggregate(TrackedDrop drop, Integer trackingItemId) {
            this.drop = drop;
            this.trackingItemId = trackingItemId;
        }
    }

    // Keyed on tileId:itemId (or tileId:- for non-per-item tiles).
    private final Map<String, DropAggregate> pendingAggregates = new HashMap<>();

    public void queue(TrackedDrop drop, int amount,
            int snapshotCurrent, int snapshotRequired, Integer trackingItemId) {
        if (!tasks.isLive()) {
            return;
        }
        final String key = drop.tileId + ":" + (trackingItemId == null ? "-" : trackingItemId);
        synchronized (pendingAggregates) {
            DropAggregate agg = pendingAggregates.get(key);
            if (agg == null) {
                agg = new DropAggregate(drop, trackingItemId);
                pendingAggregates.put(key, agg);
                // First drop of the burst: grab the at-drop frame now. The flush shot lands
                // COALESCE_FLUSH_MS later, when slow floor loot (corpse piles, big stacks) is
                // visible — the proof shows both moments.
                if (config.dualProofFrames()) {
                    final DropAggregate fresh = agg;
                    drawManager.requestNextFrameListener(img -> fresh.triggerFrame = (BufferedImage) img);
                }
            }
            coalescer.arm(agg, amount, snapshotCurrent, snapshotRequired,
                    () -> flushAggregate(key), COALESCE_FLUSH_MS);
        }
    }

    private void flushAggregate(String key) {
        coalescer.flushThrottled(pendingAggregates, key, this::doSubmitAggregate);
    }

    private void doSubmitAggregate(DropAggregate agg) {
        coalescer.noteUpload();
        proofs.captureAndSubmit(agg.drop, agg.total, agg.snapshotCurrent, agg.snapshotRequired, agg.trackingItemId,
                agg.triggerFrame);
    }
}
