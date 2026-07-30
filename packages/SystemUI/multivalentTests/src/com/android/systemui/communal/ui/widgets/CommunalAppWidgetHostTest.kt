/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.communal.ui.widgets

import android.appwidget.AppWidgetHost.AppWidgetHostListener
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.shared.model.fakeGlanceableHubMultiUserHelper
import com.android.systemui.communal.widgets.CommunalAppWidgetHost
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@SmallTest
@RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidJUnit4::class)
class CommunalAppWidgetHostTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private lateinit var testableLooper: TestableLooper
    private lateinit var underTest: CommunalAppWidgetHost

    @Before
    fun setUp() {
        testableLooper = TestableLooper.get(this)
        underTest =
            CommunalAppWidgetHost(
                context = context,
                backgroundScope = kosmos.applicationCoroutineScope,
                hostId = 116,
                logBuffer = logcatLogBuffer("CommunalAppWidgetHostTest"),
                glanceableHubMultiUserHelper = kosmos.fakeGlanceableHubMultiUserHelper,
            )
    }

    @Test
    fun appWidgetIdToRemove_emit() =
        testScope.runTest {
            val appWidgetIdToRemove by collectLastValue(underTest.appWidgetIdToRemove)

            // Nothing should be emitted yet
            assertThat(appWidgetIdToRemove).isNull()

            underTest.onAppWidgetRemoved(appWidgetId = 1)
            runCurrent()

            assertThat(appWidgetIdToRemove).isEqualTo(1)

            underTest.onAppWidgetRemoved(appWidgetId = 2)
            runCurrent()

            assertThat(appWidgetIdToRemove).isEqualTo(2)
        }

    @Test
    fun observer_onHostStartListeningTriggeredWhileObserverActive() =
        testScope.runTest {
            // Observer added
            val observer = mock<CommunalAppWidgetHost.Observer>()
            underTest.addObserver(observer)
            runCurrent()

            // Verify callback triggered
            verify(observer, never()).onHostStartListening()
            underTest.startListening()
            runCurrent()
            verify(observer).onHostStartListening()

            clearInvocations(observer)

            // Observer removed
            underTest.removeObserver(observer)
            // Listening is edge triggered, so go back to not listening for the next start to be a
            // real transition.
            underTest.stopListening()
            runCurrent()

            // Verify callback not triggered
            underTest.startListening()
            runCurrent()
            verify(observer, never()).onHostStartListening()
        }

    @Test
    fun observer_onHostStopListeningTriggeredWhileObserverActive() =
        testScope.runTest {
            // Observer added
            val observer = mock<CommunalAppWidgetHost.Observer>()
            underTest.addObserver(observer)
            // The host has to be listening for a stop to be a transition.
            underTest.startListening()
            runCurrent()

            // Verify callback triggered
            verify(observer, never()).onHostStopListening()
            underTest.stopListening()
            runCurrent()
            verify(observer).onHostStopListening()

            clearInvocations(observer)

            // Observer removed
            underTest.removeObserver(observer)
            underTest.startListening()
            runCurrent()

            // Verify callback not triggered
            underTest.stopListening()
            runCurrent()
            verify(observer, never()).onHostStopListening()
        }

    @Test
    fun listening_repeatedStartsOnlyTransitionOnce() =
        testScope.runTest {
            val observer = mock<CommunalAppWidgetHost.Observer>()
            underTest.addObserver(observer)
            runCurrent()

            underTest.startListening()
            underTest.startListening()
            underTest.startListening()
            runCurrent()

            verify(observer).onHostStartListening()
        }

    @Test
    fun listening_leaseKeepsHostListeningAcrossStopListening() =
        testScope.runTest {
            val observer = mock<CommunalAppWidgetHost.Observer>()
            underTest.addObserver(observer)
            runCurrent()

            // A lease starts the host on its own.
            underTest.acquireListeningLease("test")
            runCurrent()
            verify(observer).onHostStartListening()

            clearInvocations(observer)

            // The hub stopping must not tear the host down while the lease is held, otherwise the
            // out-of-process host would stop receiving widget updates.
            underTest.startListening()
            underTest.stopListening()
            runCurrent()
            verify(observer, never()).onHostStopListening()

            // Releasing the last holder does stop it.
            underTest.releaseListeningLease("test")
            runCurrent()
            verify(observer).onHostStopListening()
        }

    @Test
    fun listening_leaseDoesNotStopHostWhileHubIsListening() =
        testScope.runTest {
            val observer = mock<CommunalAppWidgetHost.Observer>()
            underTest.addObserver(observer)
            runCurrent()

            underTest.startListening()
            underTest.acquireListeningLease("test")
            runCurrent()
            verify(observer).onHostStartListening()

            clearInvocations(observer)

            underTest.releaseListeningLease("test")
            runCurrent()
            verify(observer, never()).onHostStopListening()
        }

    // Attaching a listener makes the framework push the widget's current views to every listener
    // attached to that id, which is what these tests observe. APP_WIDGET_ID is not bound to this
    // host, so the views pushed are null.

    @Test
    fun listeners_auxListenerDoesNotDisplacePrimary() =
        testScope.runTest {
            val primary = mock<AppWidgetHostListener>()
            val aux = mock<AppWidgetHostListener>()

            underTest.setListener(APP_WIDGET_ID, primary)
            clearInvocations(primary)

            underTest.addAuxListener(APP_WIDGET_ID, aux)

            // The primary is still attached and gets the update alongside the new listener.
            verify(primary).updateAppWidget(null)
            verify(aux).updateAppWidget(null)
        }

    @Test
    fun listeners_removingPrimaryKeepsAuxAttached() =
        testScope.runTest {
            val primary = mock<AppWidgetHostListener>()
            val aux = mock<AppWidgetHostListener>()

            underTest.setListener(APP_WIDGET_ID, primary)
            underTest.addAuxListener(APP_WIDGET_ID, aux)
            underTest.removeListener(APP_WIDGET_ID)
            clearInvocations(primary, aux)

            underTest.addAuxListener(APP_WIDGET_ID, aux)

            verify(primary, never()).updateAppWidget(null)
            verify(aux).updateAppWidget(null)
        }

    @Test
    fun listeners_removingAuxKeepsPrimaryAttached() =
        testScope.runTest {
            val primary = mock<AppWidgetHostListener>()
            val aux = mock<AppWidgetHostListener>()

            underTest.setListener(APP_WIDGET_ID, primary)
            underTest.addAuxListener(APP_WIDGET_ID, aux)
            underTest.removeAuxListener(APP_WIDGET_ID, aux)
            clearInvocations(primary, aux)

            underTest.setListener(APP_WIDGET_ID, primary)

            verify(primary).updateAppWidget(null)
            verify(aux, never()).updateAppWidget(null)
        }

    private companion object {
        // Deliberately not bound to this host.
        const val APP_WIDGET_ID = 1
    }

    @Test
    fun observer_onAllocateAppWidgetIdTriggeredWhileObserverActive() =
        testScope.runTest {
            // Observer added
            val observer = mock<CommunalAppWidgetHost.Observer>()
            underTest.addObserver(observer)
            runCurrent()

            // Verify callback triggered
            verify(observer, never()).onAllocateAppWidgetId(any())
            val id = underTest.allocateAppWidgetId()
            runCurrent()
            verify(observer).onAllocateAppWidgetId(eq(id))

            clearInvocations(observer)

            // Observer removed
            underTest.removeObserver(observer)
            runCurrent()

            // Verify callback not triggered
            underTest.allocateAppWidgetId()
            runCurrent()
            verify(observer, never()).onAllocateAppWidgetId(any())
        }

    @Test
    fun observer_onDeleteAppWidgetIdTriggeredWhileObserverActive() =
        testScope.runTest {
            // Observer added
            val observer = mock<CommunalAppWidgetHost.Observer>()
            underTest.addObserver(observer)
            runCurrent()

            // Verify callback triggered
            verify(observer, never()).onDeleteAppWidgetId(any())
            underTest.deleteAppWidgetId(1)
            runCurrent()
            verify(observer).onDeleteAppWidgetId(eq(1))

            clearInvocations(observer)

            // Observer removed
            underTest.removeObserver(observer)
            runCurrent()

            // Verify callback not triggered
            underTest.deleteAppWidgetId(2)
            runCurrent()
            verify(observer, never()).onDeleteAppWidgetId(any())
        }

    @Test
    fun observer_multipleObservers() =
        testScope.runTest {
            // Set up two observers
            val observer1 = mock<CommunalAppWidgetHost.Observer>()
            val observer2 = mock<CommunalAppWidgetHost.Observer>()
            underTest.addObserver(observer1)
            underTest.addObserver(observer2)
            runCurrent()

            // Verify both observers triggered
            verify(observer1, never()).onHostStartListening()
            verify(observer2, never()).onHostStartListening()
            underTest.startListening()
            runCurrent()
            verify(observer1).onHostStartListening()
            verify(observer2).onHostStartListening()

            // Observer 1 removed
            underTest.removeObserver(observer1)
            runCurrent()

            // Verify only observer 2 is triggered
            underTest.stopListening()
            runCurrent()
            verify(observer2).onHostStopListening()
            verify(observer1, never()).onHostStopListening()
        }
}
