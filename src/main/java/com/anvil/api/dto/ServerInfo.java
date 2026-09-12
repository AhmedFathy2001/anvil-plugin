package com.anvil.api.dto;

import java.util.List;

public class ServerInfo
{
	public String version;      // site semver, e.g. "1.0.0"
	public String sha;          // exact commit the site image was built from
	public int apiLevel;        // breaking-change counter; bumps are rare and loud
	public List<String> capabilities;
	/**
	 * The address this deployment would rather be reached at, e.g. "https://anvilosrs.com".
	 *
	 * One site now serves every clan, so a per-clan subdomain is a legacy address: it works, but
	 * it resolves the clan from the hostname instead of from your token, which stops being
	 * right the moment you join a second clan. The canonical address keeps working either way.
	 *
	 * SENT BY THE SERVER rather than baked in here, because Anvil is self-hostable — a
	 * hard-coded anvilosrs.com would tell every self-hoster to point at somebody else's site.
	 * Null on older sites and on any deployment that names none, in which case we say nothing.
	 */
	public String canonicalUrl;
}
