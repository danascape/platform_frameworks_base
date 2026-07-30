/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.appwidget.AppWidgetEvent
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.DeadObjectException
import android.os.TransactionTooLargeException
import android.widget.RemoteViews
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.communal.shared.model.GlanceableHubMultiUserHelper
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import javax.annotation.concurrent.GuardedBy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Communal app widget host that creates a [CommunalAppWidgetHostView]. */
class CommunalAppWidgetHost(
    context: Context,
    private val backgroundScope: CoroutineScope,
    hostId: Int,
    logBuffer: LogBuffer,
    glanceableHubMultiUserHelper: GlanceableHubMultiUserHelper,
) : AppWidgetHost(context, hostId) {

    init {
        // The app widget host should never be accessed from a headless system user.
        glanceableHubMultiUserHelper.assertNotInHeadlessSystemUser()
    }

    private val logger = Logger(logBuffer, TAG)

    private val _appWidgetIdToRemove = MutableSharedFlow<Int>()

    /** App widget ids that have been removed and no longer available. */
    val appWidgetIdToRemove: SharedFlow<Int> = _appWidgetIdToRemove.asSharedFlow()

    @GuardedBy("observers") private val observers = mutableSetOf<Observer>()

    private val listenerLock = Any()

    /**
     * The listener each caller of [setListener] installed. [AppWidgetHost] only keeps one listener
     * per widget id, but the hub has several interested parties (the widget view, [CommunalWidgetHost]
     * tracking provider info, and any out-of-process host bound through
     * [GlanceableHubOverlayService]), so the real listener registered with the framework is a
     * [FanOutListener] that dispatches to all of them.
     */
    @GuardedBy("listenerLock") private val primaryListeners = mutableMapOf<Int, AppWidgetHostListener>()

    @GuardedBy("listenerLock")
    private val auxListeners = mutableMapOf<Int, MutableSet<AppWidgetHostListener>>()

    @GuardedBy("listenerLock") private val fanOutListeners = mutableMapOf<Int, FanOutListener>()

    private val listeningLock = Any()

    /** Whether [startListening] has been called without a matching [stopListening]. */
    @GuardedBy("listeningLock") private var listeningRequested = false

    /** Tags of callers holding the host open through [acquireListeningLease]. */
    @GuardedBy("listeningLock") private val listeningLeases = mutableSetOf<String>()

    /** Whether the host is currently listening to the framework. */
    @GuardedBy("listeningLock") private var listening = false

    /** Bumped on every listening transition, so a deferred stop can tell it has been superseded. */
    @GuardedBy("listeningLock") private var listeningGeneration = 0

    override fun onAppWidgetRemoved(appWidgetId: Int) {
        backgroundScope.launch {
            logger.i({ "App widget removed from system: $int1" }) { int1 = appWidgetId }
            _appWidgetIdToRemove.emit(appWidgetId)
        }
    }

    override fun allocateAppWidgetId(): Int {
        return super.allocateAppWidgetId().also { appWidgetId ->
            backgroundScope.launch {
                synchronized(observers) {
                    observers.forEach { observer -> observer.onAllocateAppWidgetId(appWidgetId) }
                }
            }
        }
    }

    override fun deleteAppWidgetId(appWidgetId: Int) {
        super.deleteAppWidgetId(appWidgetId)
        backgroundScope.launch {
            synchronized(observers) {
                observers.forEach { observer -> observer.onDeleteAppWidgetId(appWidgetId) }
            }
        }
    }

    override fun startListening() {
        synchronized(listeningLock) { listeningRequested = true }
        updateListening()
    }

    override fun stopListening() {
        synchronized(listeningLock) { listeningRequested = false }
        updateListening()
    }

    /**
     * Keeps the host listening for as long as the lease is held, independently of [startListening].
     *
     * The hub only calls [startListening] while it is visible, but an out-of-process host such as
     * Launcher's overlay page needs widget updates while the device is unlocked and the hub is not.
     * Leases and [startListening] are OR'd together so neither can turn the other off.
     */
    fun acquireListeningLease(tag: String) {
        synchronized(listeningLock) { listeningLeases.add(tag) }
        updateListening()
    }

    /** Releases a lease taken by [acquireListeningLease]. */
    fun releaseListeningLease(tag: String) {
        synchronized(listeningLock) { listeningLeases.remove(tag) }
        updateListening()
    }

    /**
     * Starts or stops listening to match the requested state. Edge triggered, so repeated calls
     * from any single source are harmless.
     */
    private fun updateListening() {
        val shouldListen: Boolean
        val generation: Int
        synchronized(listeningLock) {
            shouldListen = listeningRequested || listeningLeases.isNotEmpty()
            if (shouldListen == listening) {
                return
            }
            listening = shouldListen
            generation = ++listeningGeneration
        }

        if (shouldListen) {
            doStartListening()
        } else {
            doStopListening(generation)
        }
    }

    private fun doStartListening() {
        try {
            super.startListening()
        } catch (e: Exception) {
            if (!e.isBinderSizeError()) {
                throw RuntimeException(e)
            }
            // We ignore the binder size error, which is caused by the list of RemoteViews passed
            // back being too large that the binder buffer space runs out. See b/14255011 and
            // b/402970061 for more context.
        }
        backgroundScope.launch {
            synchronized(observers) {
                observers.forEach { observer -> observer.onHostStartListening() }
            }
        }
    }

    /**
     * Stopping is deferred to the background scope, so a lease taken in the meantime would
     * otherwise be undone by a stop that is already obsolete. [generation] identifies the
     * transition that scheduled this call; a newer one supersedes it.
     */
    private fun doStopListening(generation: Int) {
        backgroundScope.launch {
            val superseded = synchronized(listeningLock) { generation != listeningGeneration }
            if (!superseded) {
                super.stopListening()
                synchronized(observers) {
                    observers.forEach { observer -> observer.onHostStopListening() }
                }
            }
        }
    }

    override fun setListener(appWidgetId: Int, listener: AppWidgetHostListener) {
        synchronized(listenerLock) { primaryListeners[appWidgetId] = listener }
        installFanOut(appWidgetId)
    }

    override fun removeListener(appWidgetId: Int) {
        val stillWanted =
            synchronized(listenerLock) {
                primaryListeners.remove(appWidgetId)
                auxListeners[appWidgetId]?.isNotEmpty() == true
            }
        if (!stillWanted) {
            synchronized(listenerLock) { fanOutListeners.remove(appWidgetId) }
            super.removeListener(appWidgetId)
        }
    }

    /**
     * Adds an additional listener for [appWidgetId] without displacing the one installed by
     * [setListener]. The listener is called back with the widget's current [RemoteViews].
     */
    fun addAuxListener(appWidgetId: Int, listener: AppWidgetHostListener) {
        synchronized(listenerLock) {
            auxListeners.getOrPut(appWidgetId) { mutableSetOf() }.add(listener)
        }
        installFanOut(appWidgetId)
    }

    /** Removes a listener added by [addAuxListener]. */
    fun removeAuxListener(appWidgetId: Int, listener: AppWidgetHostListener) {
        val stillWanted =
            synchronized(listenerLock) {
                val remaining = auxListeners[appWidgetId]
                remaining?.remove(listener)
                if (remaining?.isEmpty() == true) {
                    auxListeners.remove(appWidgetId)
                }
                auxListeners.containsKey(appWidgetId) || primaryListeners.containsKey(appWidgetId)
            }
        if (!stillWanted) {
            synchronized(listenerLock) { fanOutListeners.remove(appWidgetId) }
            super.removeListener(appWidgetId)
        }
    }

    /**
     * (Re)registers the fan-out listener for [appWidgetId]. [AppWidgetHost.setListener] pushes the
     * widget's current views to the listener, which is how a newly added listener gets caught up;
     * the redundant re-push to listeners that were already attached is harmless.
     */
    private fun installFanOut(appWidgetId: Int) {
        val fanOut =
            synchronized(listenerLock) {
                fanOutListeners.getOrPut(appWidgetId) { FanOutListener(appWidgetId) }
            }
        super.setListener(appWidgetId, fanOut)
    }

    /** Dispatches host callbacks for one widget id to every interested listener. */
    private inner class FanOutListener(private val appWidgetId: Int) : AppWidgetHostListener {

        private fun targets(): List<AppWidgetHostListener> =
            synchronized(listenerLock) {
                buildList {
                    primaryListeners[appWidgetId]?.let { add(it) }
                    auxListeners[appWidgetId]?.let { addAll(it) }
                }
            }

        override fun onUpdateProviderInfo(appWidget: AppWidgetProviderInfo?) {
            targets().forEach { it.onUpdateProviderInfo(appWidget) }
        }

        override fun updateAppWidget(views: RemoteViews?) {
            targets().forEach { it.updateAppWidget(views) }
        }

        override fun updateAppWidgetDeferred(packageName: String?, appWidgetId: Int) {
            // Each target resolves the deferred views itself. Targets that proxy to another
            // process rely on the default implementation running here, in the process that owns
            // the host, because AppWidgetService#getAppWidgetViews is uid checked.
            targets().forEach { it.updateAppWidgetDeferred(packageName, appWidgetId) }
        }

        override fun onViewDataChanged(viewId: Int) {
            targets().forEach { it.onViewDataChanged(viewId) }
        }

        override fun collectWidgetEvent(): AppWidgetEvent? {
            if (!android.appwidget.flags.Flags.engagementMetrics()) return null
            return targets().firstNotNullOfOrNull { it.collectWidgetEvent() }
        }
    }

    fun addObserver(observer: Observer) {
        synchronized(observers) { observers.add(observer) }
    }

    fun removeObserver(observer: Observer) {
        synchronized(observers) { observers.remove(observer) }
    }

    /**
     * Allows another class to observe the [CommunalAppWidgetHost] and handle any logic there.
     *
     * This is mainly for testability as it is difficult to test a real instance of [AppWidgetHost]
     * which communicates with framework services.
     *
     * Note: all the callbacks are launched from the background scope.
     */
    interface Observer {
        /** Called immediately after the host has started listening for widget updates. */
        fun onHostStartListening() {}

        /** Called immediately after the host has stopped listening for widget updates. */
        fun onHostStopListening() {}

        /** Called immediately after a new app widget id has been allocated. */
        fun onAllocateAppWidgetId(appWidgetId: Int) {}

        /** Called immediately after an app widget id is to be deleted. */
        fun onDeleteAppWidgetId(appWidgetId: Int) {}
    }

    companion object {
        private const val TAG = "CommunalAppWidgetHost"

        private fun Exception.isBinderSizeError(): Boolean {
            return cause is TransactionTooLargeException || cause is DeadObjectException
        }
    }
}
