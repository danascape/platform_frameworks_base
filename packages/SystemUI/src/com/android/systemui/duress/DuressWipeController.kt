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

package com.android.systemui.duress

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.RemoteException
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.android.internal.statusbar.IStatusBarService
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.statusbar.CommandQueue
import javax.inject.Inject

/**
 * Shows the full-screen countdown that follows a duress ("auto-destruct") fingerprint match at the
 * keyguard, and gives the user a few seconds to call the wipe off.
 *
 * The match itself is detected in system_server, which has already refused the unlock by the time
 * this runs — see `FingerprintAuthenticationClient.onAuthenticated`. This class is purely the
 * presentation half: it draws the countdown above everything else on screen and, if it runs out,
 * asks system_server to do the reset. The reset is deliberately not performed here; see
 * [IStatusBarService.onDuressWipeConfirmed].
 */
@SysUISingleton
class DuressWipeController
@Inject
constructor(
    private val context: Context,
    private val commandQueue: CommandQueue,
    private val windowManager: WindowManager,
    private val statusBarService: IStatusBarService,
    @Main private val handler: Handler,
) : CoreStartable, CommandQueue.Callbacks {

    private var overlay: View? = null
    private var countdownView: TextView? = null
    private var secondsRemaining = 0

    private val tick =
        object : Runnable {
            override fun run() {
                secondsRemaining--
                if (secondsRemaining <= 0) {
                    confirmWipe()
                } else {
                    countdownView?.text = secondsRemaining.toString()
                    handler.postDelayed(this, TICK_MS)
                }
            }
        }

    override fun start() {
        commandQueue.addCallback(this)
    }

    override fun showDuressWipeCountdown() {
        if (overlay != null) {
            // Already counting down. Pressing the finger again must not restart the clock the user
            // may be reaching for Cancel to stop.
            return
        }

        val view = LayoutInflater.from(context).inflate(R.layout.duress_wipe_overlay, null)
        countdownView = view.requireViewById(R.id.duress_wipe_countdown)
        view.requireViewById<Button>(R.id.duress_wipe_cancel).setOnClickListener { cancelWipe() }

        // Swallow every key we can reach so that Back cannot dismiss the countdown by accident;
        // Cancel is the only way out.
        view.isFocusableInTouchMode = true
        view.setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }

        secondsRemaining = COUNTDOWN_SECONDS
        countdownView?.text = secondsRemaining.toString()

        try {
            windowManager.addView(view, buildLayoutParams())
        } catch (e: RuntimeException) {
            // Only addView is guarded, so this is a window-manager refusal (bad token, already
            // added) rather than a layout bug. Without the overlay there is no countdown and no
            // cancel affordance, but system_server has already refused the unlock and asked for the
            // wipe, so honour it rather than silently dropping it.
            Log.e(TAG, "Unable to show duress countdown; wiping immediately", e)
            countdownView = null
            confirmWipe()
            return
        }

        overlay = view
        view.requestFocus()
        handler.postDelayed(tick, TICK_MS)
    }

    private fun cancelWipe() {
        Log.w(TAG, "Duress wipe cancelled by user")
        teardown()
    }

    private fun confirmWipe() {
        teardown()
        try {
            statusBarService.onDuressWipeConfirmed()
        } catch (e: RemoteException) {
            Log.e(TAG, "Unable to request duress wipe", e)
        }
    }

    private fun teardown() {
        handler.removeCallbacks(tick)
        overlay?.let { windowManager.removeViewImmediate(it) }
        overlay = null
        countdownView = null
    }

    private fun buildLayoutParams() =
        WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                // Layer 27 for holders of INTERNAL_SYSTEM_WINDOW, i.e. above the keyguard, the
                // notification shade and the navigation bar. See
                // WindowManagerPolicy#getWindowLayerFromTypeLw.
                WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                // No FLAG_NOT_TOUCH_MODAL: the countdown must eat touches so that a keyguard button
                // underneath cannot be hit while it is up. FLAG_SHOW_WHEN_LOCKED because the whole
                // point is that it appears over a locked device.
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            )
            .apply {
                title = "DuressWipeCountdown"
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                // Draw behind the status and navigation bars so the overlay really is full screen.
                fitInsetsTypes = 0
            }

    private companion object {
        const val TAG = "DuressWipeController"

        /** Long enough to undo a mis-press, short enough to be useful under coercion. */
        const val COUNTDOWN_SECONDS = 3
        const val TICK_MS = 1000L
    }
}
