package com.anvil;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;

/**
 * A tripwire for blocking work on the two threads that must never wait: the client thread (the game
 * loop) and the EDT (the side panel, and the game's mouse and keyboard on their way in). A network
 * round trip or a disk write on either is a freeze; 1.10.1 shipped one. Every blocking primitive
 * calls {@link #check} first, so a new caller that slips onto either thread is named in the log —
 * once per kind of call, with the stack that got it there — rather than felt as a hitch.
 */
@Slf4j
final class OffThread
{
	private static volatile BooleanSupplier onClientThread = () -> false;
	private static final Set<String> reported = ConcurrentHashMap.newKeySet();

	private OffThread()
	{
	}

	/** Bound by the plugin at startUp ({@code client::isClientThread}); unbound reads as "not". */
	static void bindClientThread(BooleanSupplier check)
	{
		onClientThread = check == null ? () -> false : check;
	}

	/** {@code what} is about to block. Warns if that's on the client thread or the EDT. */
	static void check(String what)
	{
		String thread = where();
		if (thread != null && reported.add(what + "@" + thread))
		{
			log.warn("Anvil: {} blocked the {} — a freeze; please report this log", what, thread, new Throwable("called from"));
		}
	}

	/** The thread that must not block, if this is one; null otherwise. */
	static String where()
	{
		if (SwingUtilities.isEventDispatchThread())
		{
			return "Swing thread (side panel)";
		}
		try
		{
			return onClientThread.getAsBoolean() ? "client thread (game)" : null;
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}
}
