package com.anvil.notify;

import com.anvil.detect.CombatAchievementTier;

/** A combat-achievement completion parked for the next tick, once the point total has moved. */
public final class PendingCaTask {

    public final CombatAchievementTier tier;
    public final String task;

    public PendingCaTask(CombatAchievementTier tier, String task) {
        this.tier = tier;
        this.task = task;
    }
}
