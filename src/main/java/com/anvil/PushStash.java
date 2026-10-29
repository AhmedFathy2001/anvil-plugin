package com.anvil;

import lombok.RequiredArgsConstructor;
import com.google.gson.Gson;
import java.io.IOException;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

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
final class PushStash
{
	static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;

	/** One character's waiting pushes. Plain fields only: it round-trips through Gson as-is. */
	static final class Data
	{
		long savedAt;
		Map<String, Integer> kc = new HashMap<>();
		Map<String, Integer> xp = new HashMap<>();
		Map<String, Integer> activity = new HashMap<>();
		List<SavedMoment> moments = new ArrayList<>();

		boolean isEmpty()
		{
			return empty(kc) && empty(xp) && empty(activity) && (moments == null || moments.isEmpty());
		}

		private static boolean empty(Map<?, ?> m)
		{
			return m == null || m.isEmpty();
		}
	}

	/** {@link AnvilMoments.Moment} as stored — a plain DTO, so nothing final needs reconstructing. */
	static final class SavedMoment
	{
		String kind;
		Integer itemId;
		String itemName;
		int quantity;
		Long valueGp;
		String source;
		String sourceKind;
		Integer kc;
		String taskName;
		String tier;
		long at;
		String key;

		static SavedMoment of(AnvilMoments.Moment m)
		{
			SavedMoment s = new SavedMoment();
			s.kind = m.kind;
			s.itemId = m.itemId;
			s.itemName = m.itemName;
			s.quantity = m.quantity;
			s.valueGp = m.valueGp;
			s.source = m.source;
			s.sourceKind = m.sourceKind;
			s.kc = m.kc;
			s.taskName = m.taskName;
			s.tier = m.tier;
			s.at = m.at;
			s.key = m.key;
			return s;
		}

		AnvilMoments.Moment toMoment()
		{
			return new AnvilMoments.Moment(kind, itemId, itemName, quantity, valueGp, source, sourceKind, kc,
				taskName, tier, at, key);
		}
	}

	private final Gson gson;
	private volatile Filepath dir;


	void setDir(Filepath dir)
	{
		this.dir = dir;
	}

	/** The file stem for a character: by account hash when known, else by name. Null when neither is. */
	static String keyFor(String rsn, String accountHash)
	{
		if (accountHash != null && !accountHash.isEmpty())
		{
			return DiskFiles.fingerprint("h:" + accountHash);
		}
		if (rsn != null && !rsn.trim().isEmpty())
		{
			return DiskFiles.fingerprint("r:" + rsn.trim().toLowerCase(Locale.ROOT));
		}
		return null;
	}

	/** Merge two stashes — counts are absolute, so the larger wins; moments by key, newer wins. */
	static Data merge(Data older, Data newer)
	{
		if (older == null)
		{
			return newer;
		}
		Data out = new Data();
		out.savedAt = Math.max(older.savedAt, newer.savedAt);
		mergeMax(out.kc, older.kc, newer.kc);
		mergeMax(out.xp, older.xp, newer.xp);
		mergeMax(out.activity, older.activity, newer.activity);
		Map<String, SavedMoment> byKey = new LinkedHashMap<>();
		for (List<SavedMoment> list : Arrays.asList(older.moments, newer.moments))
		{
			if (list != null)
			{
				for (SavedMoment m : list)
				{
					if (m != null && m.key != null)
					{
						byKey.put(m.key, m);
					}
				}
			}
		}
		out.moments = new ArrayList<>(byKey.values());
		return out;
	}

	private static void mergeMax(Map<String, Integer> into, Map<String, Integer> a, Map<String, Integer> b)
	{
		for (Map<String, Integer> m : Arrays.asList(a, b))
		{
			if (m != null)
			{
				m.forEach((k, v) ->
				{
					if (k != null && v != null)
					{
						into.merge(k, v, Integer::max);
					}
				});
			}
		}
	}

	private Filepath file(String key)
	{
		Filepath d = DiskFiles.ensureDir(dir);
		return d == null || key == null ? null : d.joinSegment("pushes-" + key + ".json");
	}

	/** Save {@code data} for the character {@code key}, folded into anything already saved. */
	void save(String key, Data data)
	{
		Filepath f = file(key);
		if (f == null || data == null || data.isEmpty())
		{
			return;
		}
		Data merged = merge(DiskFiles.readJson(gson, f, Data.class), data);
		try
		{
			DiskFiles.writeAtomic(f, gson.toJson(merged));
			log.debug("Saved unsent pushes for later");
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Couldn't save unsent pushes: {}", e.getMessage());
		}
	}

	/** What was saved for {@code key} (deleting it), or null when nothing fresh was. */
	Data take(String key, long now)
	{
		Filepath f = file(key);
		Data d = DiskFiles.readJson(gson, f, Data.class);
		DiskFiles.deleteQuietly(f);
		return d == null || now - d.savedAt > MAX_AGE_MS ? null : d;
	}

	/** Signed out: drop every character's saved pushes. */
	void clearAll()
	{
		DiskFiles.listFiles(DiskFiles.ensureDir(dir), n -> n.startsWith("pushes-")).forEach(DiskFiles::deleteQuietly);
	}
}
