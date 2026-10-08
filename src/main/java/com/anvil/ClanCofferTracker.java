package com.anvil;

import java.util.Locale;

/**
 * Turns repeated reads of the Clan Coffer coin stack into auditable balance transitions.
 *
 * <p>The widget gives us the amount, not who moved it. A nearby server-authored chat line can name
 * the local player, but it is used only for attribution: the balance delta remains the source of
 * truth and an unconfirmed movement is still reported anonymously.</p>
 */
final class ClanCofferTracker
{
	private static final long ACTOR_WINDOW_MS = 15_000L;

	enum Kind
	{
		SNAPSHOT("snapshot"),
		DEPOSIT("deposit"),
		WITHDRAWAL("withdrawal");

		final String wire;

		Kind(String wire)
		{
			this.wire = wire;
		}
	}

	static final class Observation
	{
		final Kind kind;
		final int beforeBalance;
		final int afterBalance;
		final boolean actorConfirmed;

		Observation(Kind kind, int beforeBalance, int afterBalance, boolean actorConfirmed)
		{
			this.kind = kind;
			this.beforeBalance = beforeBalance;
			this.afterBalance = afterBalance;
			this.actorConfirmed = actorConfirmed;
		}
	}

	private Integer lastBalance;
	private Kind recentSelfAction;
	private long recentSelfActionAt;

	void reset()
	{
		lastBalance = null;
		recentSelfAction = null;
		recentSelfActionAt = 0L;
	}

	/** Remember a server-authored coffer line only when it names this account (or says “you”). */
	void onChatLine(String line, String localRsn, long now)
	{
		Kind action = selfAction(line, localRsn);
		if (action != null)
		{
			recentSelfAction = action;
			recentSelfActionAt = now;
		}
	}

	Observation observe(int balance, long now)
	{
		if (balance < 0)
		{
			return null;
		}
		if (lastBalance == null)
		{
			lastBalance = balance;
			return new Observation(Kind.SNAPSHOT, balance, balance, false);
		}
		int before = lastBalance;
		if (before == balance)
		{
			return null;
		}

		Kind kind = balance > before ? Kind.DEPOSIT : Kind.WITHDRAWAL;
		boolean confirmed = recentSelfAction == kind
			&& now >= recentSelfActionAt
			&& now - recentSelfActionAt <= ACTOR_WINDOW_MS;
		lastBalance = balance;
		// One chat line may explain one physical transition, never a later unrelated one.
		recentSelfAction = null;
		recentSelfActionAt = 0L;
		return new Observation(kind, before, balance, confirmed);
	}

	static Kind selfAction(String line, String localRsn)
	{
		if (line == null)
		{
			return null;
		}
		String plain = line.replaceAll("<[^>]*>", "").trim();
		String lower = plain.toLowerCase(Locale.ROOT);
		if (!lower.contains("coffer"))
		{
			return null;
		}
		boolean namesSelf = lower.startsWith("you ") || lower.startsWith("you've ") || lower.startsWith("you have ");
		String self = Rsn.normalize(localRsn);
		if (!namesSelf && !self.isEmpty())
		{
			String normalizedLine = Rsn.normalize(plain);
			namesSelf = normalizedLine.equals(self) || normalizedLine.startsWith(self + " ");
		}
		if (!namesSelf)
		{
			return null;
		}
		if (lower.contains("deposited") || lower.contains("has deposited") || lower.contains("have deposited")
			|| lower.contains("donated") || lower.contains("has donated") || lower.contains("have donated"))
		{
			return Kind.DEPOSIT;
		}
		if (lower.contains("withdrew") || lower.contains("withdrawn") || lower.contains("has withdrawn")
			|| lower.contains("have withdrawn"))
		{
			return Kind.WITHDRAWAL;
		}
		return null;
	}
}
