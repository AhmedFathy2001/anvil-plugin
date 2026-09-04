package com.anvil;

/**
 * Schedules one step of a polling flow to run later, off the Swing EDT.
 *
 * Production binds RuneLite's shared {@link java.util.concurrent.ScheduledExecutorService}; tests
 * bind a scheduler that runs the step inline, so a sign-in loop that would take two minutes of
 * wall-clock finishes in a millisecond and asserts deterministically.
 *
 * <p>Hoisted out of the federation data source for the same reason as {@link BrowserOpener}: the
 * device sign-in poll outlives federation.</p>
 */
@FunctionalInterface
public interface PollScheduler
{
	void schedule(Runnable step, long delayMs);
}
