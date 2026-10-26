package com.anvil;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;
import okio.ByteString;
import org.junit.*;

import static org.junit.Assert.*;

public class PushSocketTest
{
	/** A socket that only remembers whether it was closed. */
	private static final class FakeSocket implements WebSocket
	{
		boolean closed;

		@Override public Request request() { return null; }
		@Override public long queueSize() { return 0; }
		@Override public boolean send(String text) { return true; }
		@Override public boolean send(ByteString bytes) { return true; }
		@Override public boolean close(int code, String reason) { closed = true; return true; }
		@Override public void cancel() { closed = true; }
	}

	/** Hands out a ticket (or none) and records every socket it opened. */
	private static final class FakeApi extends BingoApiClient
	{
		final List<FakeSocket> opened = new CopyOnWriteArrayList<>();
		volatile boolean ticketsAvailable = true;
		volatile int ticketsAsked;

		FakeApi()
		{
			super(new Gson(), new OkHttpClient());
		}

		@Override
		PushTicket fetchPushTicket()
		{
			ticketsAsked++;
			if (!ticketsAvailable)
			{
				return null;
			}
			PushTicket t = new PushTicket();
			t.url = "wss://anvilosrs.com/api/plugin/push/ws";
			t.ticket = "t" + ticketsAsked;
			return t;
		}

		@Override
		WebSocket openPush(PushTicket ticket, WebSocketListener listener)
		{
			FakeSocket s = new FakeSocket();
			opened.add(s);
			return s;
		}
	}

	private ScheduledExecutorService executor;
	private FakeApi api;
	private PushSocket push;
	private final List<JsonObject> heard = new CopyOnWriteArrayList<>();

	@Before
	public void setUp()
	{
		executor = Executors.newSingleThreadScheduledExecutor();
		api = new FakeApi();
		push = new PushSocket(api, executor, new Gson(), heard::add);
	}

	@After
	public void tearDown()
	{
		executor.shutdownNow();
	}

	private void settle() throws Exception
	{
		executor.submit(() -> { }).get(2, TimeUnit.SECONDS);
	}

	@Test
	public void connectsForAScopeAndGoesLiveOnHello() throws Exception
	{
		push.scope("5:2");
		settle();
		assertEquals(1, api.opened.size());
		assertFalse(push.live());

		push.onMessage(api.opened.get(0), "{\"t\":\"hello\"}");
		assertTrue(push.live());
		assertTrue("hello is the socket's own business", heard.isEmpty());
		push.onMessage(api.opened.get(0), "{\"t\":\"tile\",\"eventId\":5,\"tileId\":9}");
		push.onMessage(api.opened.get(0), "not json");
		assertEquals(1, heard.size());
		assertEquals(9, heard.get(0).get("tileId").getAsInt());
	}

	@Test
	public void sameScopeIsANoOpAndANewOneReTickets() throws Exception
	{
		push.scope("5:2");
		push.scope("5:2");
		settle();
		assertEquals(1, api.opened.size());

		push.scope("6:3");
		settle();
		assertTrue("the old board's socket is closed", api.opened.get(0).closed);
		assertEquals(2, api.opened.size());
	}

	@Test
	public void anExpiredTicketReconnectsAtOnce() throws Exception
	{
		push.scope("5:2");
		settle();
		FakeSocket first = api.opened.get(0);
		push.onMessage(first, "{\"t\":\"hello\"}");

		push.onClosing(first, 4001, "ticket expired");
		assertFalse(push.live());
		settle();
		assertEquals(2, api.opened.size());
		assertEquals(2, api.ticketsAsked);
	}

	@Test
	public void stoppingClosesAndNeverReconnects() throws Exception
	{
		push.scope("5:2");
		settle();
		push.onMessage(api.opened.get(0), "{\"t\":\"hello\"}");

		push.scope(null);
		assertTrue(api.opened.get(0).closed);
		assertFalse(push.live());
		push.onFailure(api.opened.get(0), new java.io.IOException("gone"), null);
		settle();
		assertEquals(1, api.opened.size());
	}

	@Test
	public void noTicketBacksOffInsteadOfSpinning() throws Exception
	{
		api.ticketsAvailable = false;
		push.scope("5:2");
		settle();
		settle();
		assertEquals("one ask, then a wait of seconds", 1, api.ticketsAsked);
		assertTrue(api.opened.isEmpty());
	}

	@Test
	public void aReplacedSocketIsIgnored() throws Exception
	{
		push.scope("5:2");
		settle();
		FakeSocket old = api.opened.get(0);
		push.scope("6:3");
		settle();

		push.onMessage(old, "{\"t\":\"hello\"}");
		assertFalse(push.live());
		assertTrue(heard.isEmpty());
		push.onFailure(old, new java.io.IOException("late"), null);
		settle();
		assertEquals("a late failure of the old socket doesn't reconnect", 2, api.opened.size());
	}
}
