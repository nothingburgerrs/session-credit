package com.phonekeys;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@SuppressLint("MissingPermission")
public class MainActivity extends Activity implements HidKeyboard.Listener {

    private static final int REQ_PERMS = 1;
    private static final int REQ_ENABLE_BT = 2;

    private static final int BG = 0xFF15131F;
    private static final int CARD = 0xFF221F31;
    private static final int ACCENT = 0xFF6C5CE7;
    private static final int TEXT = 0xFFECEAF6;
    private static final int MUTED = 0xFF9C98B3;
    private static final int GREEN = 0xFF2ECC71;
    private static final int AMBER = 0xFFF1C40F;
    private static final int RED = 0xFFE74C3C;

    private HidKeyboard keyboard;
    private TypingSounds sounds;
    private SharedPreferences prefs;

    private TextView statusDot, statusText;
    private Button connectButton;
    private KeyField field;
    private String lastText = "";
    private boolean suppressWatcher;
    private boolean haptics;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        sounds = new TypingSounds(this);
        sounds.setVolume(prefs.getInt("volume", 80) / 100f);
        sounds.setStyle(prefs.getInt("style", 1));
        haptics = prefs.getBoolean("haptics", true);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());
        ensurePermissionsThenStart();
    }

    @Override
    protected void onDestroy() {
        if (keyboard != null) keyboard.stop();
        sounds.release();
        super.onDestroy();
    }

    // ------------------------------------------------------------ setup flow

    private String[] neededPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE};
        }
        return new String[0];
    }

    private boolean hasPermissions() {
        for (String p : neededPermissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private void ensurePermissionsThenStart() {
        if (!hasPermissions()) {
            requestPermissions(neededPermissions(), REQ_PERMS);
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            setStatus(RED, "This phone has no Bluetooth.");
            return;
        }
        if (!adapter.isEnabled()) {
            setStatus(AMBER, "Turn on Bluetooth to continue");
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT);
            return;
        }
        if (keyboard == null) {
            keyboard = new HidKeyboard(this, this);
            setStatus(AMBER, "Starting keyboard mode…");
            if (!keyboard.start()) {
                setStatus(RED, "This phone doesn't support Bluetooth keyboard mode.");
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQ_PERMS) return;
        if (hasPermissions()) {
            ensurePermissionsThenStart();
        } else {
            setStatus(RED, "Allow \"Nearby devices\" so the phone can act as a keyboard. Tap to retry.");
            statusText.setOnClickListener(v -> ensurePermissionsThenStart());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ENABLE_BT) ensurePermissionsThenStart();
    }

    // ------------------------------------------------------------ HID callbacks

    @Override
    public void onReadyChanged(boolean registered) {
        runOnUiThread(() -> {
            if (keyboard != null && keyboard.isConnected()) return;
            if (registered) {
                setStatus(AMBER, "Ready. Tap \"Connect to tablet\".");
            } else {
                setStatus(RED, "Keyboard mode stopped. Reopen the app to retry.");
            }
        });
    }

    @Override
    public void onConnectionChanged(BluetoothDevice device, int state) {
        runOnUiThread(() -> {
            String name = deviceName(device);
            switch (state) {
                case BluetoothProfile.STATE_CONNECTED:
                    setStatus(GREEN, "Connected to " + name + " — start typing!");
                    connectButton.setText("Disconnect");
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    field.requestFocus();
                    break;
                case BluetoothProfile.STATE_CONNECTING:
                    setStatus(AMBER, "Connecting to " + name + "…");
                    break;
                case BluetoothProfile.STATE_DISCONNECTED:
                    if (keyboard.isConnected()) return;
                    setStatus(AMBER, "Disconnected. Tap \"Connect to tablet\".");
                    connectButton.setText("Connect to tablet");
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    break;
                default:
                    break;
            }
        });
    }

    private static String deviceName(BluetoothDevice d) {
        String n = d.getName();
        return n == null ? d.getAddress() : n;
    }

    // ------------------------------------------------------------ actions

    private boolean ready() {
        if (keyboard == null || !keyboard.isRegistered()) {
            Toast.makeText(this, "Keyboard mode isn't ready yet", Toast.LENGTH_SHORT).show();
            ensurePermissionsThenStart();
            return false;
        }
        return true;
    }

    private void makeVisible() {
        if (!ready()) return;
        Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 180);
        startActivity(i);
        new AlertDialog.Builder(this)
                .setTitle("Pair with your tablet")
                .setMessage("On the tablet open Settings › Connections › Bluetooth and tap this phone "
                        + "under \"Available devices\". Accept the pairing on both screens.\n\n"
                        + "Already paired before? Unpair the phone on the tablet first, then pair again "
                        + "while this app is open so the tablet sees it as a keyboard.")
                .setPositiveButton("OK", null)
                .show();
    }

    private void connectOrDisconnect() {
        if (!ready()) return;
        if (keyboard.isConnected()) {
            keyboard.disconnect();
            return;
        }
        Set<BluetoothDevice> bonded = BluetoothAdapter.getDefaultAdapter().getBondedDevices();
        final List<BluetoothDevice> devices = new ArrayList<>(bonded);
        if (devices.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("No paired devices")
                    .setMessage("Tap \"Make phone visible\" first and pair from the tablet.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        String[] names = new String[devices.size()];
        for (int i = 0; i < names.length; i++) names[i] = deviceName(devices.get(i));
        new AlertDialog.Builder(this)
                .setTitle("Pick your tablet")
                .setItems(names, (dlg, which) -> {
                    BluetoothDevice d = devices.get(which);
                    setStatus(AMBER, "Connecting to " + deviceName(d) + "…");
                    if (!keyboard.connect(d)) setStatus(RED, "Couldn't start connection. Try again.");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void sendSpecial(int key, TypingSounds.Kind sound) {
        feedback(sound);
        if (keyboard != null) keyboard.press(KeyMap.MOD_NONE, key);
    }

    private void feedback(TypingSounds.Kind kind) {
        sounds.play(kind);
        if (haptics) field.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    private void clearField() {
        suppressWatcher = true;
        field.setText("");
        suppressWatcher = false;
        lastText = "";
    }

    /** Compares the box before/after each edit and sends the difference to the tablet. */
    private final TextWatcher watcher = new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}

        @Override
        public void afterTextChanged(Editable s) {
            if (suppressWatcher) return;
            String now = s.toString();
            int prefix = 0;
            int max = Math.min(now.length(), lastText.length());
            while (prefix < max && now.charAt(prefix) == lastText.charAt(prefix)) prefix++;
            int removed = lastText.length() - prefix;
            String added = now.substring(prefix);
            lastText = now;
            if (removed == 0 && added.isEmpty()) return;

            if (keyboard != null) {
                keyboard.backspace(removed);
                keyboard.type(added);
            }
            if (added.isEmpty()) {
                feedback(TypingSounds.Kind.BACKSPACE);
            } else {
                char c = added.charAt(added.length() - 1);
                feedback(c == '\n' ? TypingSounds.Kind.ENTER
                        : c == ' ' ? TypingSounds.Kind.SPACE : TypingSounds.Kind.KEY);
            }
            // Keep the phone-side box short; the tablet already has the text.
            if (now.length() > 1500) clearField();
        }
    };

    // ------------------------------------------------------------ UI

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    private TextView label(String s, int sizeSp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String s, int bg, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setBackground(rounded(bg, 14));
        b.setOnClickListener(l);
        b.setMinHeight(dp(48));
        return b;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(rounded(CARD, 18));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        return c;
    }

    private LinearLayout.LayoutParams spaced(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp);
        return p;
    }

    private void setStatus(int color, String text) {
        statusDot.setTextColor(color);
        statusText.setText(text);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(16));

        root.addView(label("PhoneKeys", 26, TEXT, true));
        root.addView(label("Your phone is the keyboard. Your tablet does the rest.", 14, MUTED, false));

        // Status + connection
        LinearLayout statusCard = card();
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = label("●", 18, AMBER, false);
        statusText = label("Getting ready…", 15, TEXT, false);
        statusText.setPadding(dp(8), 0, 0, 0);
        statusRow.addView(statusDot);
        statusRow.addView(statusText);
        statusCard.addView(statusRow);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        Button visible = button("Make phone visible", 0xFF3A3552, v -> makeVisible());
        connectButton = button("Connect to tablet", ACCENT, v -> connectOrDisconnect());
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        gap.leftMargin = dp(10);
        btnRow.addView(visible, half);
        btnRow.addView(connectButton, gap);
        statusCard.addView(btnRow, spaced(12));
        root.addView(statusCard, spaced(16));

        // Typing area
        field = new KeyField(this);
        field.setHint("Tap here and type…");
        field.setHintTextColor(MUTED);
        field.setTextColor(TEXT);
        field.setTextSize(18);
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setBackground(rounded(CARD, 18));
        field.setPadding(dp(16), dp(14), dp(16), dp(14));
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        field.addTextChangedListener(watcher);
        field.setOnEmptyBackspace(() -> {
            feedback(TypingSounds.Kind.BACKSPACE);
            if (keyboard != null) keyboard.backspace(1);
        });
        LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        fieldParams.topMargin = dp(14);
        fieldParams.height = 0;
        field.setMinHeight(dp(120));
        root.addView(field, fieldParams);

        // Special keys
        HorizontalScrollView keysScroll = new HorizontalScrollView(this);
        keysScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout keys = new LinearLayout(this);
        addKey(keys, "Esc", () -> sendSpecial(KeyMap.KEY_ESC, TypingSounds.Kind.KEY));
        addKey(keys, "Tab", () -> sendSpecial(KeyMap.KEY_TAB, TypingSounds.Kind.KEY));
        addKey(keys, "←", () -> sendSpecial(KeyMap.KEY_LEFT, TypingSounds.Kind.KEY));
        addKey(keys, "↑", () -> sendSpecial(KeyMap.KEY_UP, TypingSounds.Kind.KEY));
        addKey(keys, "↓", () -> sendSpecial(KeyMap.KEY_DOWN, TypingSounds.Kind.KEY));
        addKey(keys, "→", () -> sendSpecial(KeyMap.KEY_RIGHT, TypingSounds.Kind.KEY));
        addKey(keys, "⌫", () -> sendSpecial(KeyMap.KEY_BACKSPACE, TypingSounds.Kind.BACKSPACE));
        addKey(keys, "Del", () -> sendSpecial(KeyMap.KEY_DELETE, TypingSounds.Kind.BACKSPACE));
        addKey(keys, "Enter", () -> sendSpecial(KeyMap.KEY_ENTER, TypingSounds.Kind.ENTER));
        addKey(keys, "Clear box", this::clearField);
        keysScroll.addView(keys);
        root.addView(keysScroll, spaced(10));

        // Sound settings
        LinearLayout soundCard = card();
        LinearLayout soundRow = new LinearLayout(this);
        soundRow.setGravity(Gravity.CENTER_VERTICAL);
        soundRow.addView(label("Typing sound", 15, TEXT, true),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Spinner style = new Spinner(this);
        ArrayAdapter<String> styles = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, TypingSounds.STYLE_NAMES);
        style.setAdapter(styles);
        style.setSelection(prefs.getInt("style", 1));
        style.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean first = true;
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                sounds.setStyle(pos);
                prefs.edit().putInt("style", pos).apply();
                if (!first) field.postDelayed(() -> sounds.play(TypingSounds.Kind.KEY), 150);
                first = false;
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        soundRow.addView(style);
        soundCard.addView(soundRow);

        LinearLayout volRow = new LinearLayout(this);
        volRow.setGravity(Gravity.CENTER_VERTICAL);
        volRow.addView(label("Volume", 14, MUTED, false));
        SeekBar vol = new SeekBar(this);
        vol.setMax(100);
        vol.setProgress(prefs.getInt("volume", 80));
        vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                sounds.setVolume(p / 100f);
                prefs.edit().putInt("volume", p).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { sounds.play(TypingSounds.Kind.KEY); }
        });
        volRow.addView(vol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        CheckBox vib = new CheckBox(this);
        vib.setText("Vibrate");
        vib.setTextColor(MUTED);
        vib.setChecked(haptics);
        vib.setOnCheckedChangeListener((b, on) -> {
            haptics = on;
            prefs.edit().putBoolean("haptics", on).apply();
        });
        volRow.addView(vib);
        soundCard.addView(volRow, spaced(6));
        root.addView(soundCard, spaced(12));

        root.setBackgroundColor(BG);
        return root;
    }

    private void addKey(LinearLayout row, String text, Runnable action) {
        Button b = button(text, 0xFF2E2A42, v -> action.run());
        b.setTextSize(15);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setMinWidth(dp(52));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(46));
        p.rightMargin = dp(8);
        row.addView(b, p);
    }
}
