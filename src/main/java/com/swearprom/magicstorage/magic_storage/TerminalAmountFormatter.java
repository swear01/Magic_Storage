package com.swearprom.magicstorage.magic_storage;

final class TerminalAmountFormatter {
    private static final long[] DIVISORS = {
            1_000L,
            1_000_000L,
            1_000_000_000L,
            1_000_000_000_000L,
            1_000_000_000_000_000L,
            1_000_000_000_000_000_000L
    };
    private static final String[] UNITS = {"K", "M", "G", "T", "P", "E"};

    private TerminalAmountFormatter() {
    }

    static String formatCompact(long amount) {
        if (amount < 0) throw new IllegalArgumentException("Amount must be non-negative");
        if (amount < DIVISORS[0]) return Long.toString(amount);

        int unit = 0;
        while (unit + 1 < DIVISORS.length && amount >= DIVISORS[unit + 1]) unit++;
        long divisor = DIVISORS[unit];
        long whole = amount / divisor;
        if (whole >= 100) return whole + UNITS[unit];

        long tenths = amount / (divisor / 10);
        long fraction = tenths % 10;
        return fraction == 0
                ? (tenths / 10) + UNITS[unit]
                : (tenths / 10) + "." + fraction + UNITS[unit];
    }
}
