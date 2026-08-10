package com.android.server.ext;

import android.annotation.IntRange;
import android.app.AlarmManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Slog;

/**
 * Infrastructure for actions that:
 * - happen after a user-configurable device-wide delay
 * - need to be taken even when the device is in deep sleep
 * - need to be rescheduled based on some listenable event
 *
 * The delay is stored as a millisecond value in a {@link Settings.Global} setting, a value of 0
 * disables the action.
 */
public abstract class DelayedConditionalAction {
    protected final SystemServerExt sse;
    protected final Thread thread;
    protected final Handler handler;

    protected final String settingKey;
    private final int settingDefaultValue;

    protected final AlarmManager alarmManager;
    private final AlarmManager.OnAlarmListener alarmListener;

    protected DelayedConditionalAction(SystemServerExt sse, String settingKey,
            int settingDefaultValue, Handler handler) {
        this.sse = sse;
        this.settingKey = settingKey;
        this.settingDefaultValue = settingDefaultValue;

        Looper looper = handler.getLooper();
        thread = looper.getThread();
        this.handler = handler;

        if (thread != Thread.currentThread()) {
            throw new IllegalStateException("all calls should happen on the same thread");
        }

        alarmManager = sse.context.getSystemService(AlarmManager.class);
        alarmListener = () -> {
            String TAG = getLogTag();
            Slog.d(TAG, "alarm triggered");

            if (getDelayDurationMillis() == 0) {
                Slog.d(TAG, "alarm has been disabled, returning");
                return;
            }

            alarmTriggered();
        };
    }

    public void init() {
        registerStateListener();
        registerSettingObserver();

        update();
    }

    private void registerSettingObserver() {
        Uri uri = Settings.Global.getUriFor(settingKey);
        // onChange() is dispatched on the handler's thread, same as all the other callbacks
        var observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                update();
            }
        };
        sse.context.getContentResolver().registerContentObserver(uri, false, observer);
    }

    private boolean alarmScheduled;

    protected final void update() {
        final String TAG = getLogTag();
        final Thread curThread = Thread.currentThread();

        if (curThread != thread) {
            String msg = "update() called on an unknown thread " + curThread;
            throw new IllegalStateException(msg);
        }

        Slog.d(TAG, "update: alarm already scheduled: " + alarmScheduled);

        if (alarmScheduled) {
            alarmManager.cancel(alarmListener);
            Slog.d(TAG, "canceled previous alarm");
            alarmScheduled = false;
        }

        boolean shouldScheduleAlarm = shouldScheduleAlarm();
        Slog.d(TAG, "shouldScheduleAlarm: " + shouldScheduleAlarm);
        if (!shouldScheduleAlarm) {
            return;
        }

        long delayMillis = getDelayDurationMillis();
        Slog.d(TAG, "delayMillis: " + delayMillis);

        if (delayMillis == 0) {
            return;
        }

        long current = SystemClock.elapsedRealtime();

        if (Long.MAX_VALUE - delayMillis < current) {
            return;
        }

        final long triggerAt = current + delayMillis;

        alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt,
                    getClass().getName(), alarmListener, handler);
        Slog.d(TAG, "scheduled alarm");
        alarmScheduled = true;
    }

    @IntRange(from = 0)
    private long getDelayDurationMillis() {
        int value = Settings.Global.getInt(sse.context.getContentResolver(),
                settingKey, settingDefaultValue);
        return Math.max(0, value);
    }

    // Make sure to use the same Handler that is used for all other callbacks;
    // call update() to reschedule / cancel the alarm
    protected abstract void registerStateListener();

    protected abstract boolean shouldScheduleAlarm();
    protected abstract void alarmTriggered();

    protected abstract String getLogTag();
}
