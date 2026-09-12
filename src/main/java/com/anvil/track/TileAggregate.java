package com.anvil.track;

import java.util.concurrent.ScheduledFuture;

/**
 * What every coalescing tile aggregate is: a running total, the progress as it stood when the
 * burst last moved, and the flush that has been armed for it.
 *
 * <p>Drops, kills and gains each had their own copy of these four fields and their own copy of
 * the code that maintains them. The interesting difference between the three is what they submit;
 * the coalescing is the same coalescing.</p>
 */
public abstract class TileAggregate
{

    /** Everything that has landed in this burst so far. */
    protected int total;
    protected int snapshotCurrent;
    protected int snapshotRequired;
    /** The armed flush. Cancelled and replaced every time the burst moves — see {@link Coalescer#arm}. */
    protected ScheduledFuture<?> flushTask;
}
