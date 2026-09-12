package com.anvil.api;

import com.anvil.api.dto.PermanentSubmissionException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;

/**
 * What a failed request means, and what to say about it.
 *
 * <h2>Two audiences, two sentences</h2>
 *
 * <p>Responses are JSON; players are not. {@code HTTP 429 — {"error":"…","retryAfterMs":49445}} in a
 * chat box is the shape of a bug report, not an explanation — so the server's own {@code error}
 * field is unwrapped when there is one, and everything else gets a sentence written for a person.
 * The diagnostic form still goes to client.log, from the caller.</p>
 *
 * <h2>Will it ever succeed?</h2>
 *
 * <p>A 4xx that is not 401, 408 or 429 will never succeed however many times it is retried, so it
 * becomes a {@link PermanentSubmissionException} and the pending store stops re-sending it. There is
 * one exception, and it matters: {@code start_proof_required} is a 4xx that WILL succeed later. The
 * drop really happened — throwing it away because a screenshot is outstanding is the worst possible
 * outcome — so it stays in the store and goes up once they have taken their starting shot.</p>
 */
public final class ApiErrors
{
	private ApiErrors()
	{
	}

	public static boolean isPermanentFailure(int code)
	{
		return code >= 400 && code < 500 && code != 401 && code != 408 && code != 429;
	}

	/**
	 * The one 4xx that is NOT permanent: the site requires a starting shot (site lib/startProof) and
	 * this player hasn't filed one yet. The drop really happened — throwing it away because a
	 * screenshot is outstanding is the worst possible outcome — so it stays in the pending store and
	 * goes up on a later retry, once they've taken their shot.
	 */
	public static final String START_PROOF_REQUIRED = "start_proof_required";

	/**
	 * The server's own words, or a plain sentence when it didn't offer any.
	 *
	 * Responses are JSON; players are not. "HTTP 429 — {"error":"...","retryAfterMs":49445}" in a
	 * chat box is the shape of a bug report, not an explanation, so the message field is unwrapped
	 * and everything else gets a sentence written for a person.
	 */
	public static String friendlyError(int code, String body)
	{
		String serverSaid = null;
		try
		{
			JsonObject json = new JsonParser().parse(body).getAsJsonObject();
			if (json.has("error") && !json.get("error").isJsonNull())
			{
				serverSaid = json.get("error").getAsString();
			}
		}
		catch (Exception ignored)
		{
			// Not JSON, or not the shape we expect — fall through to the generic wording.
		}
		if (serverSaid != null && !serverSaid.isEmpty())
		{
			return serverSaid;
		}
		switch (code)
		{
			case 401:
			case 403:
				return "your account token isn't valid for this clan's site";
			case 404:
				return "this clan's site doesn't have that endpoint yet";
			case 413:
				return "that was too large for the site to accept";
			case 429:
				return "the site is asking us to slow down";
			default:
				return code >= 500 ? "the clan's site is having trouble" : "the site refused it (HTTP " + code + ")";
		}
	}

	/** Milliseconds the server asked us to wait, or 0 when it didn't say. */
	public static long retryAfterFrom(String body)
	{
		try
		{
			JsonObject json = new JsonParser().parse(body).getAsJsonObject();
			if (json.has("retryAfterMs") && !json.get("retryAfterMs").isJsonNull())
			{
				return Math.max(0, json.get("retryAfterMs").getAsLong());
			}
		}
		catch (Exception ignored)
		{
			// No hint; the caller falls back to its own backoff.
		}
		return 0;
	}

	/**
	 * A failure a player will read: the site's own sentence, classified the same way as any other.
	 *
	 * The diagnostic form (status, body) still goes to the log via the caller — this is what ends up
	 * in a chat box, where a JSON blob is worse than saying nothing.
	 */
	public static IOException friendlyFailure(int code, String body)
	{
		String message = friendlyError(code, body);
		boolean awaitingStartProof = body != null && body.contains(START_PROOF_REQUIRED);
		return isPermanentFailure(code) && !awaitingStartProof
			? new PermanentSubmissionException(message)
			: new IOException(message);
	}

	/** Test seam for the retry classification above — the rule is worth pinning, the call sites aren't. */
	public static IOException submissionErrorForTest(String context, int code, String responseBody)
	{
		return submissionError(context, code, responseBody);
	}

	public static IOException submissionError(String context, int code, String responseBody)
	{
		String message = context + ": HTTP " + code + " — " + responseBody;
		boolean awaitingStartProof = responseBody != null && responseBody.contains(START_PROOF_REQUIRED);
		return isPermanentFailure(code) && !awaitingStartProof
			? new PermanentSubmissionException(message)
			: new IOException(message);
	}
}
