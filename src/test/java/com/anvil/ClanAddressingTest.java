package com.anvil;

import com.google.gson.Gson;
import okhttp3.OkHttpClient;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * WHICH CLAN a request is for, once the Site URL stops naming one.
 *
 * The member types one address and one Anvil answers on it for every clan, so on the canonical form
 * the URL says nothing about which clan is meant and the server picks from the token. That works,
 * and it is invisible: a member drafted onto two live boards had no way to see which one their drops
 * were filing into, and no way to change it.
 *
 * So there are two slugs and they are not the same thing. The one the MEMBER picked wins outright.
 * The one the SERVER last said it answered for is adopted for everything else — which is what makes
 * a submission, a starting shot and its upload land in the right clan on an address that names none,
 * since those routes resolve a clan from the address rather than from the token.
 *
 * The config poll is the exception that makes Auto work: on Auto it stays unaddressed, so every poll
 * is a fresh question and a member whose live board moves to their other clan follows it.
 */
public class ClanAddressingTest
{
	private BingoApiClient client;

	@Before
	public void setUp()
	{
		client = new BingoApiClient(new Gson(), new OkHttpClient());
		client.configure("https://anvilosrs.com", "tok");
	}

	// ---- Auto: nothing chosen, nothing heard yet ---------------------------------------------------

	@Test
	public void withNothingKnownEveryUrlIsTheBareRoot()
	{
		assertEquals("https://anvilosrs.com/api/plugin/stats", client.clanUrl("/api/plugin/stats"));
		assertEquals("https://anvilosrs.com/api/plugin/config", client.configUrl("/api/plugin/config"));
		assertEquals("", client.getActiveClan());
	}

	@Test
	public void theServerSAnswerIsAdoptedForEverythingButTheNextQuestion()
	{
		client.setResolvedClan("theafkspot");

		// Everything the plugin DOES is now addressed…
		assertEquals("https://anvilosrs.com/c/theafkspot/api/plugin/stats", client.clanUrl("/api/plugin/stats"));
		assertEquals("https://anvilosrs.com/c/theafkspot/api/events/12/submissions",
			client.clanUrl("/api/events/12/submissions"));
		assertEquals("https://anvilosrs.com/c/theafkspot/api/upload", client.clanUrl("/api/upload"));

		// …but the poll that ASKS stays open, so the site can still change its mind.
		assertEquals("https://anvilosrs.com/api/plugin/config", client.configUrl("/api/plugin/config"));
		assertEquals("theafkspot", client.getActiveClan());
	}

	@Test
	public void onAutoTheSiteCanMoveUsToAnotherClan()
	{
		client.setResolvedClan("theafkspot");
		client.setResolvedClan("vanguard"); // a new board went live over there
		assertEquals("https://anvilosrs.com/c/vanguard/api/plugin/stats", client.clanUrl("/api/plugin/stats"));
	}

	// ---- An explicit pick outranks the server ------------------------------------------------------

	@Test
	public void aMemberSPickBeatsWhateverTheServerResolved()
	{
		client.setResolvedClan("theafkspot");
		client.setChosenClan("vanguard");

		assertEquals("https://anvilosrs.com/c/vanguard/api/plugin/stats", client.clanUrl("/api/plugin/stats"));
		// And the poll is addressed too — they asked for this clan, so stop re-asking.
		assertEquals("https://anvilosrs.com/c/vanguard/api/plugin/config", client.configUrl("/api/plugin/config"));
		assertEquals("vanguard", client.getActiveClan());
	}

	@Test
	public void choosingAutoAgainHandsTheDecisionBack()
	{
		client.setResolvedClan("theafkspot");
		client.setChosenClan("vanguard");
		client.setChosenClan(""); // the Auto row

		assertEquals("https://anvilosrs.com/c/theafkspot/api/plugin/stats", client.clanUrl("/api/plugin/stats"));
		assertEquals("https://anvilosrs.com/api/plugin/config", client.configUrl("/api/plugin/config"));
	}

	// ---- The slug is validated, because it goes straight into a URL --------------------------------

	@Test
	public void anythingThatIsNotASlugNamesNoClan()
	{
		for (String bad : new String[]{
			null, "", "   ",
			"a",                                // too short for the site's own pattern
			"Not A Slug",                       // spaces
			"THEAFKSPOT/../../evil",            // traversal
			"theafkspot/api/plugin/config",     // a path, not a slug
			"the_afk_spot",                     // underscore is not in the alphabet
			"..",
			"a".repeat(33),                     // past the 32-char cap
		})
		{
			client.setChosenClan(bad);
			client.setResolvedClan(bad);
			assertEquals("rejected: " + bad,
				"https://anvilosrs.com/api/plugin/stats", client.clanUrl("/api/plugin/stats"));
		}
	}

	@Test
	public void aSlugIsLowercasedAndTrimmedRatherThanRefused()
	{
		client.setChosenClan("  TheAfkSpot  ");
		assertEquals("https://anvilosrs.com/c/theafkspot/api/plugin/stats", client.clanUrl("/api/plugin/stats"));
	}

	// ---- Signing in is about a person, so it never carries a clan ----------------------------------

	@Test
	public void deviceSignInIsNeverAddressed()
	{
		client.setChosenClan("vanguard");
		client.setResolvedClan("theafkspot");

		// The member has no clan yet at this point — that is the whole reason they are signing in — and
		// the site's device-auth pair is a platform route that a clan prefix would only redirect out of.
		assertEquals("https://anvilosrs.com/api/plugin/auth/start", client.rootUrl("/api/plugin/auth/start"));
		assertEquals("https://anvilosrs.com/api/plugin/auth/poll", client.rootUrl("/api/plugin/auth/poll"));
	}

	// ---- Old addresses are untouched, which is the point of keeping them ---------------------------

	@Test
	public void aPerClanSubdomainStillWorksAndIsNotDoublyAddressed()
	{
		// A jar installed before any of this has its clan's own hostname stored. The site resolves the
		// clan from that host and ignores what the token says — but it still ANSWERS with a slug, and
		// prefixing on top of a host that already names the clan is harmless: /c/<same slug> resolves to
		// the same clan. Pinned so nobody 'optimises' the prefix away and breaks the apex instead.
		client.configure("https://theafkspot.anvilosrs.com", "tok");
		client.setResolvedClan("theafkspot");
		assertEquals("https://theafkspot.anvilosrs.com/c/theafkspot/api/plugin/stats",
			client.clanUrl("/api/plugin/stats"));
	}

	@Test
	public void anUnconfiguredClientBuildsNothingSurprising()
	{
		BingoApiClient blank = new BingoApiClient(new Gson(), new OkHttpClient());
		blank.setResolvedClan("theafkspot");
		// No base URL: the caller checks isConfigured() before ever calling out, and the builder must
		// not throw on the way to that check.
		assertEquals("null/c/theafkspot/api/plugin/stats", blank.clanUrl("/api/plugin/stats"));
	}
}
