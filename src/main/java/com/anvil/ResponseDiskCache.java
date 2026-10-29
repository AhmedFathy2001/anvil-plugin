package com.anvil;

import lombok.RequiredArgsConstructor;
import com.google.gson.Gson;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * The last good {@code /api/plugin/config} and active {@code /api/plugin/board} answers, kept on disk
 * per signed-in account so the next launch can draw the side panel straight away and open with a
 * conditional GET instead of a full download.
 *
 * <p>DISPLAY ONLY. What comes back from here is never handed to tracking — an event can end, a team
 * can change and a token can be revoked between sessions — so the plugin shows it until the site's
 * first live answer and then forgets it (see AnvilPlugin#sidebarConfig).
 *
 * <p>Files live in {@code <plugin dir>/cache/}, named {@code <kind>-<fingerprint>.json}, where the
 * fingerprint is a truncated SHA-256 of the Account Token: the token itself never touches disk. Not
 * at the plugin directory's root, where PendingSubmissionStore reads every JSON file as a proof.
 */
@RequiredArgsConstructor
@Slf4j
final class ResponseDiskCache
{
	static final String CONFIG = "config";
	static final String CHAT_PATTERNS = "chat-patterns";
	/** A board bigger than this isn't worth the disk; the next launch just downloads it. */
	private static final int MAX_BODY_CHARS = 2_000_000;

	/** One saved answer. */
	static final class Entry
	{
		String url;
		String etag;
		String body;
		/** The character the request was made as (X-RSN, else the launcher hint), or null. */
		String rsn;
		long savedAt;
	}

	private final Gson gson;
	private volatile Filepath dir;
	/** Fingerprint of the token in use, "" when signed out. */
	private volatile String account = "";
	/** kind → etag (or body hash) last written, so an unchanged 30s poll doesn't rewrite the file. */
	private final Map<String, String> written = new ConcurrentHashMap<>();


	void setDir(Filepath dir)
	{
		this.dir = dir;
	}

	/**
	 * Point at the account behind {@code token}. Returns the fingerprint whose files should now be
	 * deleted (signing out, or a different account signing in), else null. Memory only — the caller
	 * deletes off the client thread.
	 */
	String switchAccount(String token)
	{
		String next = DiskFiles.fingerprint(token);
		String prev = account;
		if (next.equals(prev))
		{
			return null;
		}
		account = next;
		written.clear();
		return prev.isEmpty() ? null : prev;
	}

	private Filepath file(String kind, String acct)
	{
		Filepath d = DiskFiles.ensureDir(dir);
		return d == null || acct == null || acct.isEmpty() ? null : d.joinSegment(kind + "-" + acct + ".json");
	}

	/** The saved answer of {@code kind} for the current account, or null. Blocking. */
	Entry load(String kind)
	{
		// Missing or unreadable reads as nothing saved; the next fresh answer overwrites a bad file.
		Entry e = DiskFiles.readJson(gson, file(kind, account), Entry.class);
		if (e == null || e.body == null || e.url == null)
		{
			return null;
		}
		written.put(kind, e.etag != null ? e.etag : Integer.toString(e.body.hashCode()));
		return e;
	}

	/** Save a fresh 200 body. Blocking; called from the HTTP worker that fetched it, never the client thread. */
	void store(String kind, String url, String etag, String body, String rsn)
	{
		if (body == null || body.length() > MAX_BODY_CHARS)
		{
			return;
		}
		String acct = account;
		String marker = etag != null ? etag : Integer.toString(body.hashCode());
		if (marker.equals(written.get(kind)))
		{
			return;
		}
		Filepath f = file(kind, acct);
		if (f == null)
		{
			return;
		}
		Entry e = new Entry();
		e.url = url;
		e.etag = etag;
		e.body = body;
		e.rsn = rsn;
		e.savedAt = System.currentTimeMillis();
		try
		{
			DiskFiles.writeAtomic(f, gson.toJson(e));
			written.put(kind, marker);
		}
		catch (IOException | RuntimeException ex)
		{
			log.debug("Couldn't save {}: {}", kind, ex.getMessage());
		}
	}

	/** Delete every saved answer for {@code acct}. Blocking. */
	void clear(String acct)
	{
		if (acct == null || acct.isEmpty())
		{
			return;
		}
		DiskFiles.deleteQuietly(file(CONFIG, acct));
	}
}
