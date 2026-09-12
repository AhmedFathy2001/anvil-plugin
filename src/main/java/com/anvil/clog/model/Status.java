package com.anvil.clog.model;

public enum Status
{
	// Order matters: used as the primary sort key so incomplete tasks surface first.
	IN_PROGRESS,
	NOT_STARTED,
	COMPLETED
}
