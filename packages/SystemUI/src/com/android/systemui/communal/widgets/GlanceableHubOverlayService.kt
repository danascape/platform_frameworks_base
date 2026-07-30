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

package com.android.systemui.communal.widgets

import android.appwidget.AppWidgetHost.AppWidgetHostListener
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.widget.RemoteViews
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.android.systemui.communal.data.repository.CommunalWidgetRepository
import com.android.systemui.communal.domain.interactor.CommunalSettingsInteractor
import com.android.systemui.communal.shared.model.CommunalWidgetContentModel
import com.android.systemui.communal.shared.model.GlanceableHubMultiUserHelper
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.CommunalLog
import com.android.systemui.shared.communal.GlanceableHubWidget
import com.android.systemui.shared.communal.IGlanceableHubOverlay
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Publishes the Glanceable Hub's widgets to a trusted host outside SystemUI, so the same widgets
 * can be rendered somewhere the hub's own window cannot reach — in practice Launcher's "-1" page.
 *
 * SystemUI keeps the [android.appwidget.AppWidgetHost]; app widget ids are keyed by calling uid and
 * package in {@code AppWidgetServiceImpl} and cannot be handed to another app. What crosses the
 * binder is each widget's [AppWidgetProviderInfo] and [RemoteViews], which the remote host inflates
 * into its own [android.appwidget.AppWidgetHostView].
 *
 * Bound clients hold the app widget host open (see
 * [CommunalAppWidgetHost.acquireListeningLease]) because the hub itself only listens while it is
 * visible, and the overlay needs updates while the device is unlocked.
 */
class GlanceableHubOverlayService
@Inject
constructor(
    private val widgetRepository: CommunalWidgetRepository,
    private val appWidgetHost: CommunalAppWidgetHost,
    private val communalWidgetHost: CommunalWidgetHost,
    private val appWidgetManager: Optional<AppWidgetManager>,
    private val settingsInteractor: CommunalSettingsInteractor,
    private val editWidgetsActivityStarter: EditWidgetsActivityStarter,
    private val glanceableHubMultiUserHelper: GlanceableHubMultiUserHelper,
    @Main private val mainDispatcher: CoroutineDispatcher,
    @CommunalLog logBuffer: LogBuffer,
) : LifecycleService() {

    init {
        // Widgets are owned by the user they belong to, never by the headless system user.
        glanceableHubMultiUserHelper.assertNotInHeadlessSystemUser()
    }

    private val logger = Logger(logBuffer, TAG)

    /** Jobs pushing the widget list to each registered client. */
    private val widgetsListeners = mutableMapOf<IBinder, WidgetsListenerRecord>()

    /** Aux host listeners registered on behalf of clients, keyed by widget id then client. */
    private val hostListeners = mutableMapOf<Int, MutableMap<IBinder, AppWidgetHostListener>>()

    private var listeningLeaseHeld = false

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        if (!settingsInteractor.isCommunalEnabled.value) {
            logger.w("Bind rejected: the glanceable hub is not enabled")
            return null
        }
        return OverlayBinder()
    }

    override fun onDestroy() {
        synchronized(this) {
            widgetsListeners.values.forEach { it.job.cancel() }
            widgetsListeners.clear()

            hostListeners.forEach { (appWidgetId, listeners) ->
                listeners.values.forEach { appWidgetHost.removeAuxListener(appWidgetId, it) }
            }
            hostListeners.clear()

            releaseHostLocked()
        }
        logger.i("Service destroyed")
        super.onDestroy()
    }

    /**
     * Holds the app widget host open while at least one client is listening. The hub drives
     * [CommunalAppWidgetHost.startListening] only while it is visible, so without this the overlay
     * would render stale views once the device is unlocked.
     */
    private fun acquireHostLocked() {
        if (listeningLeaseHeld) return
        listeningLeaseHeld = true
        communalWidgetHost.startObservingHost()
        appWidgetHost.acquireListeningLease(TAG)
        logger.i("Holding the app widget host open for the overlay")
    }

    private fun releaseHostLocked() {
        if (!listeningLeaseHeld) return
        listeningLeaseHeld = false
        appWidgetHost.releaseListeningLease(TAG)
        communalWidgetHost.stopObservingHost()
        logger.i("Released the app widget host")
    }

    private fun addWidgetsListenerInternal(listener: IGlanceableHubOverlay.IWidgetsListener) {
        val binder = listener.asBinder()
        if (!binder.isBinderAlive) {
            logger.w("Ignoring widgets listener with a dead binder")
            return
        }

        synchronized(this) {
            if (widgetsListeners.containsKey(binder)) return

            acquireHostLocked()

            val death =
                IBinder.DeathRecipient { removeWidgetsListenerInternal(binder, unlink = false) }
            try {
                binder.linkToDeath(death, 0)
            } catch (e: RemoteException) {
                logger.w("Widgets listener died before it could be registered")
                releaseHostIfIdleLocked()
                return
            }

            val job =
                widgetRepository.communalWidgets
                    .onEach { widgets ->
                        try {
                            listener.onWidgetsUpdated(widgets.map { it.toSharedModel() })
                        } catch (e: RemoteException) {
                            logger.e({ "Error pushing widget update: $str1" }) {
                                str1 = e.localizedMessage
                            }
                        }
                    }
                    .launchIn(lifecycleScope)

            widgetsListeners[binder] = WidgetsListenerRecord(job, death)
        }
    }

    private fun removeWidgetsListenerInternal(binder: IBinder, unlink: Boolean = true) {
        synchronized(this) {
            val record = widgetsListeners.remove(binder) ?: return
            record.job.cancel()
            if (unlink) {
                try {
                    binder.unlinkToDeath(record.death, 0)
                } catch (e: NoSuchElementException) {
                    // Already gone.
                }
            }
            releaseHostIfIdleLocked()
        }
    }

    private fun releaseHostIfIdleLocked() {
        if (widgetsListeners.isEmpty() && hostListeners.isEmpty()) {
            releaseHostLocked()
        }
    }

    private fun setWidgetHostListenerInternal(
        appWidgetId: Int,
        listener: IGlanceableHubOverlay.IWidgetHostListener,
    ) {
        val binder = listener.asBinder()
        if (!binder.isBinderAlive) {
            logger.w("Ignoring widget host listener with a dead binder")
            return
        }

        val adapter =
            synchronized(this) {
                acquireHostLocked()
                val perWidget = hostListeners.getOrPut(appWidgetId) { mutableMapOf() }
                // Replacing an existing registration from the same client is a no-op for the host.
                perWidget[binder]?.let { appWidgetHost.removeAuxListener(appWidgetId, it) }
                createAdapter(listener).also { perWidget[binder] = it }
            }

        // Registering pushes the widget's current views to the new listener, so do it off the
        // lock: it makes a binder call into the system server and back out to the client.
        appWidgetHost.addAuxListener(appWidgetId, adapter)
    }

    private fun removeWidgetHostListenerInternal(
        appWidgetId: Int,
        listener: IGlanceableHubOverlay.IWidgetHostListener,
    ) {
        val adapter =
            synchronized(this) {
                val perWidget = hostListeners[appWidgetId] ?: return
                val removed = perWidget.remove(listener.asBinder()) ?: return
                if (perWidget.isEmpty()) {
                    hostListeners.remove(appWidgetId)
                }
                releaseHostIfIdleLocked()
                removed
            }
        appWidgetHost.removeAuxListener(appWidgetId, adapter)
    }

    private fun updateWidgetSizeInternal(
        appWidgetId: Int,
        minWidthDp: Int,
        minHeightDp: Int,
        maxWidthDp: Int,
        maxHeightDp: Int,
    ) {
        val manager = appWidgetManager.orElse(null) ?: return
        // AppWidgetService checks the calling uid owns the widget, so this has to happen here
        // rather than in the remote host's AppWidgetHostView.
        val options =
            Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, minWidthDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, minHeightDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, maxWidthDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, maxHeightDp)
            }
        try {
            manager.updateAppWidgetOptions(appWidgetId, options)
        } catch (e: IllegalArgumentException) {
            logger.w({ "Could not update size of widget $int1" }) { int1 = appWidgetId }
        }
    }

    private fun startEditModeInternal() {
        lifecycleScope.launch(mainDispatcher) {
            editWidgetsActivityStarter.startActivity(shouldOpenWidgetPickerOnStart = false)
        }
    }

    /**
     * Wraps a client's binder callback as an [AppWidgetHostListener].
     *
     * [AppWidgetHostListener.updateAppWidgetDeferred] is deliberately not overridden: its default
     * implementation resolves the deferred [RemoteViews] through the app widget service and then
     * calls [AppWidgetHostListener.updateAppWidget] on this same object. Because that runs in
     * SystemUI, which owns the host, the client only ever sees resolved views — it could not fetch
     * them itself.
     */
    private fun createAdapter(
        listener: IGlanceableHubOverlay.IWidgetHostListener
    ): AppWidgetHostListener =
        object : AppWidgetHostListener {
            override fun onUpdateProviderInfo(appWidget: AppWidgetProviderInfo?) {
                try {
                    listener.onUpdateProviderInfo(appWidget)
                } catch (e: RemoteException) {
                    logger.e({ "Error pushing provider info: $str1" }) { str1 = e.localizedMessage }
                }
            }

            override fun updateAppWidget(views: RemoteViews?) {
                try {
                    listener.updateAppWidget(views)
                } catch (e: RemoteException) {
                    logger.e({ "Error pushing remote views: $str1" }) { str1 = e.localizedMessage }
                }
            }

            override fun onViewDataChanged(viewId: Int) {
                try {
                    listener.onViewDataChanged(viewId)
                } catch (e: RemoteException) {
                    logger.e({ "Error pushing view data change: $str1" }) {
                        str1 = e.localizedMessage
                    }
                }
            }
        }

    private inner class OverlayBinder : IGlanceableHubOverlay.Stub() {

        override fun addWidgetsListener(listener: IGlanceableHubOverlay.IWidgetsListener?) {
            listener ?: return
            withClearedIdentity { addWidgetsListenerInternal(listener) }
        }

        override fun removeWidgetsListener(listener: IGlanceableHubOverlay.IWidgetsListener?) {
            listener ?: return
            withClearedIdentity { removeWidgetsListenerInternal(listener.asBinder()) }
        }

        override fun setWidgetHostListener(
            appWidgetId: Int,
            listener: IGlanceableHubOverlay.IWidgetHostListener?,
        ) {
            listener ?: return
            withClearedIdentity { setWidgetHostListenerInternal(appWidgetId, listener) }
        }

        override fun removeWidgetHostListener(
            appWidgetId: Int,
            listener: IGlanceableHubOverlay.IWidgetHostListener?,
        ) {
            listener ?: return
            withClearedIdentity { removeWidgetHostListenerInternal(appWidgetId, listener) }
        }

        override fun updateWidgetSize(
            appWidgetId: Int,
            minWidthDp: Int,
            minHeightDp: Int,
            maxWidthDp: Int,
            maxHeightDp: Int,
        ) {
            withClearedIdentity {
                updateWidgetSizeInternal(
                    appWidgetId,
                    minWidthDp,
                    minHeightDp,
                    maxWidthDp,
                    maxHeightDp,
                )
            }
        }

        override fun startEditMode() {
            withClearedIdentity { startEditModeInternal() }
        }

        private fun withClearedIdentity(block: () -> Unit) {
            val identity = clearCallingIdentity()
            try {
                block()
            } finally {
                restoreCallingIdentity(identity)
            }
        }
    }

    private class WidgetsListenerRecord(val job: Job, val death: IBinder.DeathRecipient)

    companion object {
        private const val TAG = "GlanceableHubOverlayService"

        private fun CommunalWidgetContentModel.toSharedModel(): GlanceableHubWidget =
            when (this) {
                is CommunalWidgetContentModel.Available ->
                    GlanceableHubWidget.available(
                        appWidgetId,
                        providerInfo,
                        rank,
                        spanY,
                        providerInfo.profile ?: android.os.Process.myUserHandle(),
                    )
                is CommunalWidgetContentModel.Pending ->
                    GlanceableHubWidget.pending(appWidgetId, componentName, icon, rank, spanY, user)
            }
    }
}
