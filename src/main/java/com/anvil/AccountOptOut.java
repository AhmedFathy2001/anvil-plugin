package com.anvil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Characters this RuneLite install has been told are not the signed-in Anvil user's.
 *
 * <p>The Account Token names whoever set this RuneLite up, not whoever is playing. Siblings share a
 * PC; friends log in at each other's. So the plugin links every character it plays by default — the
 * one-click path — and the side panel's "Not my account" puts the logged-in character here. While a
 * character in this set is logged in, the plugin sends Anvil nothing at all
 * ({@link BingoApiClient#setAccountPaused}).
 *
 * <p>Stored as digests, never raw: the account hash is a credential the site treats as proof of
 * control, and the name is somebody else's. Both are kept so the launcher's pre-login name hint can
 * be withheld too. RuneLite-free and immutable so it is unit-testable; the plugin persists
 * {@link #serialize()} in its own config.
 */
final class AccountOptOut
{
	static final AccountOptOut EMPTY = new AccountOptOut(Collections.emptySet());

	private final Set<String> digests;

	private AccountOptOut(Set<String> digests)
	{
		this.digests = Collections.unmodifiableSet(digests);
	}

	static AccountOptOut parse(String raw)
	{
		Set<String> out = new LinkedHashSet<>();
		if (raw != null)
		{
			for (String part : raw.split(","))
			{
				String p = part.trim();
				if (!p.isEmpty())
				{
					out.add(p);
				}
			}
		}
		return new AccountOptOut(out);
	}

	String serialize()
	{
		return String.join(",", digests);
	}

	/** Is this character opted out? Either key matches: the hash is rename-proof, the name works pre-login. */
	boolean contains(long accountHash, String rsn)
	{
		String h = hashKey(accountHash);
		String n = nameKey(rsn);
		return (h != null && digests.contains(h)) || (n != null && digests.contains(n));
	}

	/** The launcher's pre-login name: withhold the hint for a character that's opted out. */
	boolean containsName(String rsn)
	{
		String n = nameKey(rsn);
		return n != null && digests.contains(n);
	}

	AccountOptOut with(long accountHash, String rsn)
	{
		Set<String> next = new LinkedHashSet<>(digests);
		String h = hashKey(accountHash);
		String n = nameKey(rsn);
		if (h != null)
		{
			next.add(h);
		}
		if (n != null)
		{
			next.add(n);
		}
		return new AccountOptOut(next);
	}

	AccountOptOut without(long accountHash, String rsn)
	{
		Set<String> next = new LinkedHashSet<>(digests);
		next.remove(hashKey(accountHash));
		next.remove(nameKey(rsn));
		return new AccountOptOut(next);
	}

	boolean isEmpty()
	{
		return digests.isEmpty();
	}

	static String hashKey(long accountHash)
	{
		return accountHash == -1L ? null : "h" + digest("hash:" + accountHash);
	}

	static String nameKey(String rsn)
	{
		String norm = Rsn.normalize(rsn);
		return norm.isEmpty() ? null : "n" + digest("rsn:" + norm);
	}

	/** First 16 hex chars of SHA-256 — plenty to tell a handful of local characters apart. */
	private static String digest(String s)
	{
		try
		{
			byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder(16);
			for (int i = 0; i < 8; i++)
			{
				sb.append(String.format("%02x", d[i]));
			}
			return sb.toString();
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException("SHA-256 is always available", e);
		}
	}
}
