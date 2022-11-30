/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.mobile.data.repository

import android.net.ConnectivityManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.demomode.DemoMode
import com.android.systemui.demomode.DemoModeController
import com.android.systemui.statusbar.pipeline.mobile.data.repository.demo.DemoMobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.demo.DemoModeMobileConnectionDataSource
import com.android.systemui.statusbar.pipeline.mobile.data.repository.demo.model.FakeNetworkEventModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.demo.validMobileEvent
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.MobileConnectionsRepositoryImpl
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.kotlinArgumentCaptor
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

/**
 * The switcher acts as a dispatcher to either the `prod` or `demo` versions of the repository
 * interface it's switching on. These tests just need to verify that the entire interface properly
 * switches over when the value of `demoMode` changes
 */
@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class MobileRepositorySwitcherTest : SysuiTestCase() {
    private lateinit var underTest: MobileRepositorySwitcher
    private lateinit var realRepo: MobileConnectionsRepositoryImpl
    private lateinit var demoRepo: DemoMobileConnectionsRepository
    private lateinit var mockDataSource: DemoModeMobileConnectionDataSource

    @Mock private lateinit var connectivityManager: ConnectivityManager
    @Mock private lateinit var subscriptionManager: SubscriptionManager
    @Mock private lateinit var telephonyManager: TelephonyManager
    @Mock private lateinit var logger: ConnectivityPipelineLogger
    @Mock private lateinit var demoModeController: DemoModeController

    private val globalSettings = FakeSettings()
    private val fakeNetworkEventsFlow = MutableStateFlow<FakeNetworkEventModel?>(null)

    private val scope = CoroutineScope(IMMEDIATE)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        // Never start in demo mode
        whenever(demoModeController.isInDemoMode).thenReturn(false)

        mockDataSource =
            mock<DemoModeMobileConnectionDataSource>().also {
                whenever(it.mobileEvents).thenReturn(fakeNetworkEventsFlow)
            }

        realRepo =
            MobileConnectionsRepositoryImpl(
                connectivityManager,
                subscriptionManager,
                telephonyManager,
                logger,
                fakeBroadcastDispatcher,
                globalSettings,
                context,
                IMMEDIATE,
                scope,
                mock(),
            )

        demoRepo =
            DemoMobileConnectionsRepository(
                dataSource = mockDataSource,
                scope = scope,
                context = context,
            )

        underTest =
            MobileRepositorySwitcher(
                scope = scope,
                realRepository = realRepo,
                demoMobileConnectionsRepository = demoRepo,
                demoModeController = demoModeController,
            )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `active repo matches demo mode setting`() =
        runBlocking(IMMEDIATE) {
            whenever(demoModeController.isInDemoMode).thenReturn(false)

            var latest: MobileConnectionsRepository? = null
            val job = underTest.activeRepo.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(realRepo)

            startDemoMode()

            assertThat(latest).isEqualTo(demoRepo)

            finishDemoMode()

            assertThat(latest).isEqualTo(realRepo)

            job.cancel()
        }

    @Test
    fun `subscription list updates when demo mode changes`() =
        runBlocking(IMMEDIATE) {
            whenever(demoModeController.isInDemoMode).thenReturn(false)

            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1, SUB_2))

            var latest: List<SubscriptionInfo>? = null
            val job = underTest.subscriptionsFlow.onEach { latest = it }.launchIn(this)

            // The real subscriptions has 2 subs
            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1, SUB_2))
            getSubscriptionCallback().onSubscriptionsChanged()

            assertThat(latest).isEqualTo(listOf(SUB_1, SUB_2))

            // Demo mode turns on, and we should see only the demo subscriptions
            startDemoMode()
            fakeNetworkEventsFlow.value = validMobileEvent(subId = 3)

            // Demo mobile connections repository makes arbitrarily-formed subscription info
            // objects, so just validate the data we care about
            assertThat(latest).hasSize(1)
            assertThat(latest!![0].subscriptionId).isEqualTo(3)

            finishDemoMode()

            assertThat(latest).isEqualTo(listOf(SUB_1, SUB_2))

            job.cancel()
        }

    private fun startDemoMode() {
        whenever(demoModeController.isInDemoMode).thenReturn(true)
        getDemoModeCallback().onDemoModeStarted()
    }

    private fun finishDemoMode() {
        whenever(demoModeController.isInDemoMode).thenReturn(false)
        getDemoModeCallback().onDemoModeFinished()
    }

    private fun getSubscriptionCallback(): SubscriptionManager.OnSubscriptionsChangedListener {
        val callbackCaptor =
            kotlinArgumentCaptor<SubscriptionManager.OnSubscriptionsChangedListener>()
        verify(subscriptionManager)
            .addOnSubscriptionsChangedListener(any(), callbackCaptor.capture())
        return callbackCaptor.value
    }

    private fun getDemoModeCallback(): DemoMode {
        val captor = kotlinArgumentCaptor<DemoMode>()
        verify(demoModeController).addCallback(captor.capture())
        return captor.value
    }

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate

        private const val SUB_1_ID = 1
        private val SUB_1 =
            mock<SubscriptionInfo>().also { whenever(it.subscriptionId).thenReturn(SUB_1_ID) }

        private const val SUB_2_ID = 2
        private val SUB_2 =
            mock<SubscriptionInfo>().also { whenever(it.subscriptionId).thenReturn(SUB_2_ID) }
    }
}
