package com.swearprom.magicstorage.magic_storage;

public enum SearchMode {
    NORMAL,
    TAG,
    MOD;

    public SearchMode next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public SearchMode previous() {
        return values()[(ordinal() - 1 + values().length) % values().length];
    }

    public String apply(String rawText) {
        if (rawText.isEmpty()) return rawText;
        return switch (this) {
            case NORMAL -> rawText;
            case TAG -> rawText.startsWith("#") ? rawText : "#" + rawText;
            case MOD -> rawText.startsWith("@") ? rawText : "@" + rawText;
        };
    }
}
