package com.anvil.api.dto;

import java.util.List;

public class ClanSyncResponse
{
	public int added;
	public int updated;
	public int markedLeft;
	public int renamed;
	public int returned;
	public List<ClanChange> changes;
	// Plan-limit state, added later. A site that predates it sends neither field and GSON leaves
	// them null/empty, so an older instance simply produces no cap line.
	//   capNotice         — one ready-to-show sentence, or null when there's nothing to say.
	//   refusedNewMembers — RSNs the plan limit kept off the roster on this sweep.
	public String capNotice;
	public List<String> refusedNewMembers;
}
