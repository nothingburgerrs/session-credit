package com.phonekeys;

/** Maps characters to USB HID keyboard usage codes (US layout). */
final class KeyMap {
    static final int MOD_NONE = 0x00;
    static final int MOD_LSHIFT = 0x02;

    static final int KEY_ENTER = 0x28;
    static final int KEY_ESC = 0x29;
    static final int KEY_BACKSPACE = 0x2A;
    static final int KEY_TAB = 0x2B;
    static final int KEY_SPACE = 0x2C;
    static final int KEY_DELETE = 0x4C;
    static final int KEY_RIGHT = 0x4F;
    static final int KEY_LEFT = 0x50;
    static final int KEY_DOWN = 0x51;
    static final int KEY_UP = 0x52;

    /** A key press: modifier byte + key usage code. */
    static final class Stroke {
        final int modifiers;
        final int key;
        Stroke(int modifiers, int key) { this.modifiers = modifiers; this.key = key; }
    }

    private static final String UNSHIFTED_SYMBOLS = "-=[]\\;'`,./";
    private static final String SHIFTED_SYMBOLS   = "_+{}|:\"~<>?";
    private static final int[] SYMBOL_CODES = {0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38};
    private static final String SHIFTED_DIGITS = ")!@#$%^&*(";

    private KeyMap() {}

    /** Turns typographic characters the phone keyboard may insert into plain ASCII. */
    static String normalize(String s) {
        return s.replace('‘', '\'').replace('’', '\'')
                .replace('“', '"').replace('”', '"')
                .replace('–', '-').replace('—', '-')
                .replace(' ', ' ')
                .replace("…", "...");
    }

    /** Returns the stroke for a character, or null if it can't be typed on a US keyboard. */
    static Stroke forChar(char c) {
        if (c >= 'a' && c <= 'z') return new Stroke(MOD_NONE, 0x04 + (c - 'a'));
        if (c >= 'A' && c <= 'Z') return new Stroke(MOD_LSHIFT, 0x04 + (c - 'A'));
        if (c >= '1' && c <= '9') return new Stroke(MOD_NONE, 0x1E + (c - '1'));
        if (c == '0') return new Stroke(MOD_NONE, 0x27);
        if (c == ' ') return new Stroke(MOD_NONE, KEY_SPACE);
        if (c == '\n') return new Stroke(MOD_NONE, KEY_ENTER);
        if (c == '\t') return new Stroke(MOD_NONE, KEY_TAB);
        int i = SHIFTED_DIGITS.indexOf(c);
        if (i >= 0) return new Stroke(MOD_LSHIFT, i == 0 ? 0x27 : 0x1E + (i - 1));
        i = UNSHIFTED_SYMBOLS.indexOf(c);
        if (i >= 0) return new Stroke(MOD_NONE, SYMBOL_CODES[i]);
        i = SHIFTED_SYMBOLS.indexOf(c);
        if (i >= 0) return new Stroke(MOD_LSHIFT, SYMBOL_CODES[i]);
        return null;
    }
}
