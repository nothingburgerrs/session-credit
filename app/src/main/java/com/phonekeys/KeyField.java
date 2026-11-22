package com.phonekeys;

import android.content.Context;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.widget.EditText;

/**
 * Text box that also reports backspace presses when it is already empty,
 * so you can keep deleting text on the tablet after clearing the phone side.
 */
final class KeyField extends EditText {
    private Runnable onEmptyBackspace;

    KeyField(Context context) { super(context); }

    void setOnEmptyBackspace(Runnable r) { onEmptyBackspace = r; }

    private boolean handleEmptyBackspace() {
        if (length() == 0 && onEmptyBackspace != null) {
            onEmptyBackspace.run();
            return true;
        }
        return false;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        InputConnection base = super.onCreateInputConnection(outAttrs);
        if (base == null) return null;
        return new InputConnectionWrapper(base, true) {
            @Override
            public boolean deleteSurroundingText(int before, int after) {
                if (before > 0 && handleEmptyBackspace()) return true;
                return super.deleteSurroundingText(before, after);
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_DEL && length() == 0) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) handleEmptyBackspace();
                    return true;
                }
                return super.sendKeyEvent(event);
            }
        };
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DEL && handleEmptyBackspace()) return true;
        return super.onKeyDown(keyCode, event);
    }
}
