package com.anvil;

import lombok.*;
import com.google.gson.*;
import java.io.*;
import java.util.*;
import lombok.extern.slf4j.*;
import net.runelite.client.util.*;

/**
 * Unsent KC / XP / activity counts and highlight-feed moments, saved per CHARACTER across a logout or a
 * client restart and handed back when that character logs in again.
 *
 * <p>Per character because every one of these is sent as whoever is logged in (X-RSN): restoring one
 * character's kill counts into another's session would credit the wrong account. The file is
 * {@code <plugin dir>/outbox/pushes-<fingerprint>.json}, the fingerprint a truncated SHA-256 of the
 * account hash (or the name when the hash isn't known), and reading it deletes it. Anything older
 * than {@link #MAX_AGE_MS} is ignored — the hiscores have caught up by then, and a day-old moment is
 * old news. All methods block on disk: executor only.
 */
@RequiredArgsConstructor
@Slf4j
class PushStash {
	static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;

	/** One character's waiting pushes. Plain fields only: it round-trips through Gson as-is. */
	static class Data {
		long savedAt;
		Map<String, Integer> kc = new HashMap<>();
		Map<String, Integer> xp = new HashMap<>();
		Map<String, Integer> activity = new HashMap<>();
		/** Stored as they are: Gson fills the final fields itself, under the same names older files used. */
		List<AnvilMoments.Moment> moments = new ArrayList<>();
		/** Drop and kill facts (POST /api/plugin/facts) still to send, as queued. Never null. */
		JsonArray facts = new JsonArray();
		/** Fingerprint of the sign-in token the facts were queued under: another sign-in doesn't send them. */
		String account;

		boolean isEmpty() {
			return empty(kc) && empty(xp) && empty(activity) && (moments == null || moments.isEmpty()) && facts.size() == 0;
		}

		static boolean empty(Map<?, ?> m) {
			return m == null || m.isEmpty();
		}
	}

	final Gson gson;
	@Setter volatile Filepath dir;


	/** The file stem for a character: by account hash when known, else by name. Null when neither is. */
	static String keyFor(String rsn, String accountHash) {
		if (accountHash != null && !accountHash.isEmpty()) return DiskFiles.fingerprint("h:" + accountHash);
		if (rsn != null && !rsn.trim().isEmpty()) return DiskFiles.fingerprint("r:" + rsn.trim().toLowerCase(Locale.ROOT));
		return null;
	}

	/** Merge two stashes — counts are absolute, so the larger wins; moments by key, newer wins. */
	static Data merge(Data older, Data newer) {
		// Either side can be missing: no stash on disk yet, or nothing left to take for this character.
		if (older == null || newer == null) return older == null ? newer : older;
		var out = new Data();
		out.savedAt = Math.max(older.savedAt, newer.savedAt);
		mergeMax(out.kc, older.kc, newer.kc);
		mergeMax(out.xp, older.xp, newer.xp);
		mergeMax(out.activity, older.activity, newer.activity);
		Map<String, AnvilMoments.Moment> byKey = new LinkedHashMap<>();
		for (var list : Arrays.asList(older.moments, newer.moments)) {
			if (list != null) {
				for (var m : list) {
					if (m != null && m.key != null) byKey.put(m.key, m);
				}
			}
		}
		out.moments = new ArrayList<>(byKey.values());
		// Facts are events, not totals: both sides' in order — only the newer sign-in's when the two
		// differ — and the oldest dropped past 50, which is what the site reads from one request.
		out.account = newer.account;
		if (Objects.equals(older.account, newer.account)) out.facts.addAll(older.facts);
		out.facts.addAll(newer.facts);
		while (out.facts.size() > 50) out.facts.remove(0);
		return out;
	}

	/** Empty {@code queue}, returning what it held. Call with the queue's monitor held. */
	static JsonArray drain(JsonArray queue) {
		var out = queue.deepCopy();
		while (queue.size() > 0) queue.remove(0);
		return out;
	}

	static void mergeMax(Map<String, Integer> into, Map<String, Integer> a, Map<String, Integer> b) {
		for (var m : Arrays.asList(a, b)) {
			if (m != null) {
				m.forEach((k, v) -> {
					if (k != null && v != null) into.merge(k, v, Integer::max);
				});
			}
		}
	}

	Filepath file(String key) {
		var d = DiskFiles.ensureDir(dir);
		return d == null || key == null ? null : d.joinSegment("pushes-" + key + ".json");
	}

	/** Save {@code data} for the character {@code key}, folded into anything already saved. */
	void save(String key, Data data) {
		var f = file(key);
		if (f == null || data == null || data.isEmpty()) return;
		// Always through merge, which bounds the facts — also when nothing was saved before.
		var old = DiskFiles.readJson(gson, f, Data.class);
		try {
			DiskFiles.writeAtomic(f, gson.toJson(merge(old == null ? new Data() : old, data)));
		} catch (IOException | RuntimeException e) {
		}
	}

	/** What was saved for {@code key} (deleting it), or null when nothing fresh was. */
	Data take(String key, long now) {
		var f = file(key);
		var d = DiskFiles.readJson(gson, f, Data.class);
		DiskFiles.deleteQuietly(f);
		return d == null || now - d.savedAt > MAX_AGE_MS ? null : d;
	}

	/** Signed out: drop every character's saved pushes. */
	void clearAll() {
		DiskFiles.listFiles(DiskFiles.ensureDir(dir), n -> n.startsWith("pushes-")).forEach(DiskFiles::deleteQuietly);
	}
}
