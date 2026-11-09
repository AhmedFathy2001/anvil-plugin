package com.anvil;

import lombok.RequiredArgsConstructor;
import com.google.gson.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

/**
 * The site's live channel: a socket that sends the team's progress, the
 * board and pinned-tile numbers as they change (docs/PLUGIN_WIRE.md), so the polls can slow right
 * down while it's up. Down or refused, nothing breaks: the plugin polls as it always did.
 *
 * <p>{@link #scope} is the whole interface. A new scope (another board or team) re-tickets, since the
 * ticket is what decides what this client hears; null closes it.</p>
 */
@Slf4j
@RequiredArgsConstructor
final class PushSocket extends WebSocketListener {
	private static final long FIRST_RETRY_MS = 2_000;
	private static final long MAX_RETRY_MS = 5 * 60_000;
	/** Close code for "ticket expired": reconnect at once with a fresh one. */
	private static final int TICKET_EXPIRED = 4001;

	private final BingoApiClient api;
	private final ScheduledExecutorService executor;
	private final Gson gson;
	private final Consumer<JsonObject> onMessage;

	private String scope;
	private volatile WebSocket socket;
	private ScheduledFuture<?> pending;
	private int failures;
	private volatile boolean live;

	/** Up and hearing changes, so polls may slow down. */
	boolean live() {
		return live;
	}

	/** Listen for this board-and-team, or stop listening (null). Same scope again is a no-op. */
	synchronized void scope(String next) {
		if (Objects.equals(next, scope)) return;
		scope = next;
		failures = 0;
		close();
		if (next != null) retryIn(0);
	}

	private void close() {
		live = false;
		if (pending != null) {
			pending.cancel(false);
			pending = null;
		}
		if (socket != null) {
			socket.close(1000, null);
			socket = null;
		}
	}

	private synchronized void retryIn(long ms) {
		if (scope == null || pending != null) return;
		try {
			pending = executor.schedule(this::connect, ms, TimeUnit.MILLISECONDS);
		} catch (RejectedExecutionException e) {
			// shutting down
		}
	}

	private void connect() {
		// Flags after the board and team (AnvilPlugin.syncPush): ":f" chat-feed lines, ":r" panel rows.
		boolean feed;
		boolean rows;
		synchronized (this) {
			pending = null;
			feed = scope != null && scope.matches(".*:f(:r)?$");
			// Always: this plugin reads panel rows, and the site only sends them to one that says so
			// (an older plugin would log each). ":r" only decides whether to connect with no board.
			rows = true;
		}
		// A blocking HTTP call: on OkHttp's pool, not the one executor thread every push and flush shares
		// (a hung site would stall all of it for the read timeout, on every retry).
		try {
			api.interactiveExecutor().execute(() -> withTicket(api.fetchPushTicket(feed, rows)));
		} catch (RejectedExecutionException e) {
			// shutting down
		}
	}

	private void withTicket(BingoApiClient.PushTicket ticket) {
		synchronized (this) {
			if (scope == null || socket != null) return;
			if (ticket == null || ticket.url == null || ticket.ticket == null) {
				retryIn(RetryPolicy.backoffMs(++failures, FIRST_RETRY_MS, MAX_RETRY_MS, Math.random()));
				return;
			}
			socket = api.openPush(ticket, this);
		}
	}

	@Override
	public void onMessage(WebSocket ws, String text) {
		JsonObject m;
		try {
			m = gson.fromJson(text, JsonObject.class);
		} catch (RuntimeException e) {
			return;
		}
		if (m == null || ws != socket || !m.has("t")) return;
		if ("hello".equals(m.get("t").getAsString())) {
			// Connected; the site sends our team's view straight after.
			synchronized (this) {
				failures = 0;
			}
			live = true;
		}
		onMessage.accept(m);
	}

	@Override
	public void onClosing(WebSocket ws, int code, String reason) {
		ws.close(1000, null);
		dropped(ws, code == TICKET_EXPIRED);
	}

	@Override
	public void onFailure(WebSocket ws, Throwable t, Response response) {
		dropped(ws, false);
	}

	private synchronized void dropped(WebSocket ws, boolean ticketExpired) {
		if (ws != socket) {
			return; // an old socket we already replaced or closed
		}
		socket = null;
		live = false;
		retryIn(ticketExpired ? 0 : RetryPolicy.backoffMs(++failures, FIRST_RETRY_MS, MAX_RETRY_MS, Math.random()));
	}
}
