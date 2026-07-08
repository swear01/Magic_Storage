package com.swearprom.magicstorage.magic_storage;

final class TerminalLayout {
    static final int VIEW_BUTTON_SIZE = 18;
    private static final int VIEW_BUTTON_SPACING = 2;

    private TerminalLayout() {
    }

    static int viewButtonX() {
        return -VIEW_BUTTON_SIZE - VIEW_BUTTON_SPACING;
    }

    static int viewButtonY(int index) {
        return 6 + index * (VIEW_BUTTON_SIZE + VIEW_BUTTON_SPACING);
    }
}
