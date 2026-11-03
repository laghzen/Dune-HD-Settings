package ru.dune.settingslock;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class DuneSettingsService extends AccessibilityService {
    private static final String PREFS = "pin_prefs";
    private static final String KEY_GRANT_UNTIL = "settings_grant_until";

    // Основные системные Settings-пакеты Android TV / AOSP.
    private static final Set<String> SETTINGS_PACKAGES = new HashSet<>(Arrays.asList(
            "com.android.tv.settings",
            "com.android.settings",
            "com.google.android.tv.settings"
    ));

    // Системные диалоги могут временно показывать отдельный package поверх Settings.
    private static final Set<String> TRANSIENT_PACKAGES = new HashSet<>(Arrays.asList(
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller"
    ));

    private boolean settingsWindowVisible;
    private long lastBlockAt;
    private long lastPinLaunchAt;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        // Дублируем настройки из XML на случай особенностей прошивки Android TV.
        try {
            android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.eventTypes |= AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
                info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
                info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
                setServiceInfo(info);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_SETTINGS) {
            return super.onKeyEvent(event);
        }

        // Если пользователь уже прошёл PIN и находится в Settings, клавишу не блокируем.
        if (settingsWindowVisible) return false;

        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            launchPinScreen();
        }
        // Поглощаем DOWN и UP — иначе Android может всё-таки открыть Settings.
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence packageName = event.getPackageName();
        if (packageName == null) return;
        String currentPackage = packageName.toString();

        if (SETTINGS_PACKAGES.contains(currentPackage)) {
            if (settingsWindowVisible) return;
            if (hasFreshGrant()) {
                settingsWindowVisible = true;
                return;
            }
            blockSettingsWindow();
            return;
        }

        if (settingsWindowVisible && !TRANSIENT_PACKAGES.contains(currentPackage)) {
            // Вышли из Settings (например, на главный экран) — одноразовый пропуск больше не действует.
            settingsWindowVisible = false;
            clearGrant();
        }
    }

    private boolean hasFreshGrant() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        return prefs.getLong(KEY_GRANT_UNTIL, 0L) > System.currentTimeMillis();
    }

    private void clearGrant() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_GRANT_UNTIL).apply();
    }

    private void blockSettingsWindow() {
        long now = SystemClock.elapsedRealtime();
        if (lastBlockAt > 0L && now - lastBlockAt < 1200L) return;
        lastBlockAt = now;
        performGlobalAction(GLOBAL_ACTION_BACK);
        new Handler(Looper.getMainLooper()).postDelayed(this::launchPinScreen, 120L);
    }

    private void launchPinScreen() {
        long now = SystemClock.elapsedRealtime();
        if (lastPinLaunchAt > 0L && now - lastPinLaunchAt < 1200L) return;

        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && getPackageName().contentEquals(String.valueOf(root.getPackageName()))) {
                return;
            }
        } catch (Exception ignored) {
        }

        lastPinLaunchAt = now;
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("from_remote", true);
        startActivity(intent);
    }

    @Override
    public void onInterrupt() {
    }
}
