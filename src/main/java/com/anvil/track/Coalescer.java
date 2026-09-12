package com.anvil.track;

import com.anvil.util.TaskRunner;
import java.util.Map;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * One burst, one upload — the rule drops, kills and gains all obey, written once.
 *
 * <p>Killing one NPC that drops a stack of two thousand coins would otherwise fire two thousand
 * screenshot captures and two thousand submissions. So an event folds into an aggregate and a flush
 * is armed for a moment later; a second event before it fires pushes it out again. The submission
 * happens once the burst actually stops.</p>
 *
 * <h2>The two rules that used to be implicit in six places</h2>
 *
 * <p><b>{@link #arm} cancels before it re-arms.</b> Without that, a burst longer than the settle
 * window uploads once per settle rather than once.</p>
 *
 * <p><b>{@link #flushThrottled} removes from the map before it submits.</b> Anything arriving after
 * that point belongs to the NEXT burst — and a failed submit folds its count back by re-queueing,
 * rather than by holding the map entry hostage.</p>
 *
 * <h2>And why the throttle is shared</h2>
 *
 * <p>Several tiles completing at once would fire their proof uploads simultaneously, so the gap
 * between uploads is global. That means it has to live somewhere all three trackers and the proof
 * pipeline can see it — here, rather than in whichever one of them happened to own the field.
 * Pushing a flush further out is idempotent, so aggregates flushing in quick succession simply
 * serialise.</p>
 */
@Singleton
public class Coalescer
{
	/** Submissions go through a small gap so a completion storm never bursts the server. */
	private static final long UPLOAD_THROTTLE_MS = 600;

	private final TaskRunner tasks;

	private volatile long lastUploadAt;

	@Inject
	Coalescer(TaskRunner tasks)
	{
		this.tasks = tasks;
	}

	/** Something was just uploaded; the next one waits its turn. */
	public void noteUpload()
	{
		lastUploadAt = System.currentTimeMillis();
	}

	/**
	 * Fold this event into the aggregate and (re)arm its flush.
	 *
	 * <p>Callers hold the aggregate map's lock: the read, the update and the re-arm have to be one
	 * step, or two threads each arm a flush for the same key.</p>
	 */
	public void arm(TileAggregate agg, int amount, int snapshotCurrent, int snapshotRequired,
		Runnable flush, long delayMs)
	{
		agg.total += amount;
		agg.snapshotCurrent = snapshotCurrent;
		agg.snapshotRequired = snapshotRequired;
		if (agg.flushTask != null)
		{
			agg.flushTask.cancel(false);
		}
		agg.flushTask = tasks.runLater(flush, delayMs);
	}

	/** Take the aggregate for this key out of the map and submit it — now, or after the gap. */
	public <K, A extends TileAggregate> void flushThrottled(Map<K, A> pending, K key, Consumer<A> submit)
	{
		A agg;
		synchronized (pending)
		{
			agg = pending.remove(key);
		}
		if (agg == null || agg.total <= 0)
		{
			return;
		}
		long sinceLast = System.currentTimeMillis() - lastUploadAt;
		if (sinceLast < UPLOAD_THROTTLE_MS)
		{
			tasks.runLater(() -> submit.accept(agg), UPLOAD_THROTTLE_MS - sinceLast);
			return;
		}
		submit.accept(agg);
	}
}
