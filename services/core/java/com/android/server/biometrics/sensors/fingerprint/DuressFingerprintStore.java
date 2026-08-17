/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.biometrics.sensors.fingerprint;

import static android.hardware.fingerprint.FingerprintManager.FINGERPRINT_ID_NONE;

import android.annotation.NonNull;
import android.hardware.fingerprint.Fingerprint;
import android.os.Environment;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Remembers which fingerprint enrolment is a user's duress ("auto-destruct") finger.
 *
 * <p>The duress finger is an ordinary enrolment as far as the HAL is concerned — there is no such
 * thing as a wipe-only template, so the HAL matches it exactly like any other finger. All that
 * distinguishes it is the id recorded here, which
 * {@link com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintAuthenticationClient}
 * consults on every match in order to turn a successful keyguard match into a device wipe instead
 * of an unlock.
 *
 * <p>Kept out of {@code Settings.Secure} deliberately: that is readable by ordinary apps, whereas
 * these files live under {@code /data/system/users/<id>/}, which only the system can reach.
 *
 * <p>Because HAL template ids are recycled, a stale mark is dangerous in one specific direction —
 * an id whose template is gone but whose mark survives would silently promote the *next* enrolment
 * that lands on that id to duress, wiping the device on an innocent finger. Callers must therefore
 * drop the mark whenever the enrolment goes away; {@link #pruneIfMissing} does that check against a
 * live enrolment list, and {@code FingerprintService} runs it before every enrolment (the only
 * moment an id can be recycled) as well as on explicit removal.
 */
public class DuressFingerprintStore {

    private static final String TAG = "DuressFingerprintStore";

    private static final String FILE_NAME = "duress_fingerprint";

    private static final Object sInstanceLock = new Object();

    @GuardedBy("sInstanceLock")
    private static DuressFingerprintStore sInstance;

    /**
     * The store is a process singleton rather than an injected dependency: the read happens deep
     * inside {@code FingerprintAuthenticationClient}, and threading an instance down through
     * {@code FingerprintProvider} and {@code Sensor} to reach it would touch a dozen constructors
     * for no benefit inside a single-process service.
     */
    public static DuressFingerprintStore getInstance() {
        synchronized (sInstanceLock) {
            if (sInstance == null) {
                sInstance = new DuressFingerprintStore();
            }
            return sInstance;
        }
    }

    private final Object mLock = new Object();

    /** userId -> duress enrolment id. Absent means "not loaded yet", not "none". */
    @GuardedBy("mLock")
    private final SparseIntArray mCache = new SparseIntArray();

    /**
     * Returns the enrolment id marked as the duress finger for {@code userId}, or
     * {@link android.hardware.fingerprint.FingerprintManager#FINGERPRINT_ID_NONE} if there is none.
     */
    public int get(int userId) {
        synchronized (mLock) {
            final int cached = mCache.get(userId, Integer.MIN_VALUE);
            if (cached != Integer.MIN_VALUE) {
                return cached;
            }
            final int loaded = readLocked(userId);
            mCache.put(userId, loaded);
            return loaded;
        }
    }

    /**
     * Marks {@code fpId} as the duress finger for {@code userId}, replacing any previous mark. Pass
     * {@link android.hardware.fingerprint.FingerprintManager#FINGERPRINT_ID_NONE} to clear.
     */
    public void set(int userId, int fpId) {
        synchronized (mLock) {
            if (get(userId) == fpId) {
                return;
            }
            if (fpId == FINGERPRINT_ID_NONE) {
                deleteLocked(userId);
            } else {
                writeLocked(userId, fpId);
            }
            mCache.put(userId, fpId);
            // Never log the id itself — knowing which finger wipes the device is exactly what an
            // attacker with logcat access would want.
            Slog.i(TAG, "Duress fingerprint " + (fpId == FINGERPRINT_ID_NONE ? "cleared" : "set")
                    + " for user " + userId);
        }
    }

    /** Clears any duress mark for {@code userId}. */
    public void clear(int userId) {
        set(userId, FINGERPRINT_ID_NONE);
    }

    /** Whether {@code fpId} is {@code userId}'s duress finger. */
    public boolean isDuress(int userId, int fpId) {
        return fpId != FINGERPRINT_ID_NONE && get(userId) == fpId;
    }

    /**
     * Drops the mark if the enrolment it points at is no longer in {@code enrolled}, closing the
     * template-id recycling hazard described in the class doc. Cheap when nothing is marked.
     */
    public void pruneIfMissing(int userId, @NonNull List<Fingerprint> enrolled) {
        final int duressId = get(userId);
        if (duressId == FINGERPRINT_ID_NONE) {
            return;
        }
        for (Fingerprint fp : enrolled) {
            if (fp.getBiometricId() == duressId) {
                return;
            }
        }
        Slog.w(TAG, "Duress enrolment for user " + userId + " is gone; dropping the mark");
        clear(userId);
    }

    /** Forgets the in-memory state for a removed user; its data directory is deleted with it. */
    public void onUserRemoved(int userId) {
        synchronized (mLock) {
            mCache.delete(userId);
        }
    }

    // ── Persistence ───────────────────────────────────────────────────────────────

    private static AtomicFile fileFor(int userId) {
        return new AtomicFile(new File(Environment.getUserSystemDirectory(userId), FILE_NAME));
    }

    @GuardedBy("mLock")
    private int readLocked(int userId) {
        final AtomicFile file = fileFor(userId);
        if (!file.getBaseFile().exists()) {
            return FINGERPRINT_ID_NONE;
        }
        try {
            final String contents =
                    new String(file.readFully(), StandardCharsets.UTF_8).trim();
            return Integer.parseInt(contents);
        } catch (IOException | NumberFormatException e) {
            // Fail open: treat an unreadable mark as "no duress finger" rather than risk wiping on
            // a finger we can no longer positively identify.
            Slog.e(TAG, "Unable to read duress fingerprint for user " + userId, e);
            return FINGERPRINT_ID_NONE;
        }
    }

    @GuardedBy("mLock")
    private void writeLocked(int userId, int fpId) {
        final AtomicFile file = fileFor(userId);
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(Integer.toString(fpId).getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch (IOException e) {
            Slog.e(TAG, "Unable to write duress fingerprint for user " + userId, e);
            if (out != null) {
                file.failWrite(out);
            }
        }
    }

    @GuardedBy("mLock")
    private void deleteLocked(int userId) {
        fileFor(userId).delete();
    }
}
