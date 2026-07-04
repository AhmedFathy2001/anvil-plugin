package com.anvil;

/**
 * Binomial-probability helpers for multi-roll drop rarity.
 *
 * Adapted from Dink (pajlads/DinkPlugin), BSD-2-Clause — see THIRD_PARTY_NOTICES.md. Trimmed to the
 * methods the rarity service needs and rewritten to avoid the Guava dependency.
 */
final class MathUtils {
    static final double EPSILON = 0.00001;

    // Precomputed factorials 0..9 — max rolls in npc_drops.json is 9.
    private static final int[] FACTORIALS;

    static {
        int n = 10;
        int[] facts = new int[n];
        facts[0] = 1;
        for (int i = 1; i < n; i++) {
            facts[i] = i * facts[i - 1];
        }
        FACTORIALS = facts;
    }

    private MathUtils() {
    }

    static boolean lessThanOrEqual(double a, double b) {
        return a < b || Math.abs(a - b) <= EPSILON;
    }

    static double binomialProbability(double p, int nTrials, int kSuccess) {
        // https://en.wikipedia.org/wiki/Binomial_distribution#Probability_mass_function
        return binomialCoefficient(nTrials, kSuccess) * Math.pow(p, kSuccess) * Math.pow(1 - p, nTrials - kSuccess);
    }

    private static int binomialCoefficient(int n, int k) {
        return FACTORIALS[n] / (FACTORIALS[k] * FACTORIALS[n - k]);
    }
}
