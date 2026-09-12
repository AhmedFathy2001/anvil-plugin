package com.anvil.clog.model;

/**
 * Fine-grained tile mechanic, mirroring the web admin's kind filter (standard / skill /
 * boss / drop / collection / kill / timed / diary / lms / value). Drives the in-clog Type
 * filter. STANDARD never originates from the plugin config today (manual tiles aren't
 * synced) but is kept for parity with the web so the cycle order matches.
 */
public enum Kind { STANDARD, SKILL, BOSS, DROP, COLLECTION, KILL, PVP, TIMED, DIARY, COMBAT_TASK, LMS, VALUE, GAIN, DEATHLESS }
