package ru.dune.settingslock;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String MASTER_PIN = "3270";
    private static final String PREFS = "pin_prefs";
    private static final String KEY_PIN = "saved_pin";
    private static final String KEY_GRANT_UNTIL = "settings_grant_until";
    private static final long SETTINGS_LAUNCH_GRANT_MS = 120_000L;

    private SharedPreferences prefs;
    private final StringBuilder enteredPin = new StringBuilder();
    private String tempSetupPin;
    private TextView statusText;
    private TextView pinDisplay;
    private TextView serviceStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setContentView(R.layout.activity_main);
        statusText = findViewById(R.id.statusText);
        pinDisplay = findViewById(R.id.pinDisplay);
        serviceStatus = findViewById(R.id.serviceStatus);
        setupButtons();
        updatePrompt();
        updateServiceStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateServiceStatus();
    }

    private void updateServiceStatus() {
        if (serviceStatus == null) return;
        boolean enabled = isAccessibilityServiceEnabled();
        serviceStatus.setText(enabled
                ? "Защита активна"
                : "Защита выключена — включите службу ниже");
        serviceStatus.setTextColor(enabled ? 0xFF00E676 : 0xFFFFC107);
    }

    private boolean isAccessibilityServiceEnabled() {
        String enabledServices = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabledServices)) return false;
        String expected = new ComponentName(this, DuneSettingsService.class).flattenToString();
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabledServices);
        while (splitter.hasNext()) {
            if (expected.equalsIgnoreCase(splitter.next())) return true;
        }
        return false;
    }

    private void openAccessibilitySettings() {
        // Разрешаем текущий вход в Settings, чтобы служба не закрыла экран,
        // пока пользователь вручную включает её в списке специальных возможностей.
        prefs.edit().putLong(KEY_GRANT_UNTIL,
                System.currentTimeMillis() + 5L * 60L * 1000L).apply();
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть специальные возможности", Toast.LENGTH_LONG).show();
        }
    }

    private void updatePrompt() {
        if (statusText == null) return;
        if (!prefs.contains(KEY_PIN)) {
            statusText.setText(tempSetupPin == null ? "Задайте 4-значный PIN" : "Повторите PIN");
        } else {
            statusText.setText("Введите PIN");
        }
        renderDots();
    }

    private void setupButtons() {
        int[] numIds = {
                R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4,
                R.id.btn5, R.id.btn6, R.id.btn7, R.id.btn8, R.id.btn9
        };
        for (int id : numIds) {
            Button button = findViewById(id);
            button.setOnClickListener(v -> appendDigit(button.getText().toString()));
        }

        findViewById(R.id.btnClear).setOnClickListener(v -> {
            enteredPin.setLength(0);
            renderDots();
        });
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnEnableService).setOnClickListener(v -> openAccessibilitySettings());
    }

    private void appendDigit(String digit) {
        if (enteredPin.length() >= 4) return;
        enteredPin.append(digit);
        renderDots();
        if (enteredPin.length() == 4) {
            String pin = enteredPin.toString();
            enteredPin.setLength(0);
            handlePin(pin);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            appendDigit(String.valueOf(keyCode - KeyEvent.KEYCODE_0));
            return true;
        }
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
            appendDigit(String.valueOf(keyCode - KeyEvent.KEYCODE_NUMPAD_0));
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            if (enteredPin.length() > 0) enteredPin.deleteCharAt(enteredPin.length() - 1);
            renderDots();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void renderDots() {
        if (pinDisplay == null) return;
        StringBuilder dots = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            dots.append(i < enteredPin.length() ? "● " : "_ ");
        }
        pinDisplay.setText(dots.toString().trim());
    }

    private void handlePin(String pin) {
        // Мастер-код всегда принимается — в том числе до создания личного PIN.
        if (MASTER_PIN.equals(pin)) {
            Toast.makeText(this, "Мастер-код принят", Toast.LENGTH_SHORT).show();
            openSystemSettings();
            return;
        }

        if (!prefs.contains(KEY_PIN)) {
            if (tempSetupPin == null) {
                tempSetupPin = pin;
                updatePrompt();
            } else if (tempSetupPin.equals(pin)) {
                prefs.edit().putString(KEY_PIN, pin).apply();
                Toast.makeText(this, "PIN сохранён", Toast.LENGTH_SHORT).show();
                openSystemSettings();
            } else {
                Toast.makeText(this, "PIN не совпал. Повторите установку", Toast.LENGTH_SHORT).show();
                tempSetupPin = null;
                updatePrompt();
            }
            return;
        }

        if (prefs.getString(KEY_PIN, "").equals(pin)) {
            openSystemSettings();
        } else {
            Toast.makeText(this, "Неверный PIN", Toast.LENGTH_SHORT).show();
            renderDots();
        }
    }

    private void openSystemSettings() {
        prefs.edit().putLong(KEY_GRANT_UNTIL,
                System.currentTimeMillis() + SETTINGS_LAUNCH_GRANT_MS).apply();
        try {
            Intent intent = new Intent(Settings.ACTION_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        } catch (Exception e) {
            prefs.edit().remove(KEY_GRANT_UNTIL).apply();
            Toast.makeText(this, "Не удалось открыть системные настройки", Toast.LENGTH_LONG).show();
        }
    }
}
