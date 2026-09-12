package com.anvil.api.dto;

public class Mission
{
	public int tileId;
	public String label;
	public int points;        // face value before the decay ramp
	public String revealedAt; // ISO time this mission went live; null when unknown
	public String category;
	// Per-mission scoring so a bingo can mix missions with different behaviour. `decay` (may be
	// null) drives this mission's live grow/decay value; `lockout` marks first-to-clear-locks.
	// Absent on older sites — fall back to the event-level decay.
	public Decay decay;
	public boolean lockout;
}
