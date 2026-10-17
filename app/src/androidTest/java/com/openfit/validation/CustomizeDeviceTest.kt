package com.openfit.validation

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.openfit.R
import com.openfit.ui.theme.OpenFitTheme
import com.openfit.ui.today.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import android.graphics.Bitmap
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse

class CustomizeDeviceTest {
    private val instrumentation get() = ValidationContext.instrumentation
    private val app get() = ValidationContext.app
    private val saved = AtomicReference<TodayLayout?>()
    private val closed = AtomicBoolean()
    private val initial = TodayLayout(focus = FocusPreset.Custom, custom = listOf(TodayTile.Steps, TodayTile.Distance, TodayTile.SleepDuration, null))

    private fun launch(light: Boolean) {
        val activityClass = Class.forName("com.openfit.validation.ValidationActivity")
        // Reflection keeps this test source compilable in ordinary builds, where the fixture Activity is absent.
        val companion = activityClass.getField("Companion").get(null)
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            OpenFitTheme(darkTheme = !light) { CustomizeTodayRoute(initial, saved::set, { closed.set(true) }, {}) }
        }
        companion.javaClass.methods.single { it.name == "setContent" }.invoke(companion, content)
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
        instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
        instrumentation.startActivitySync(Intent().setClassName(app.packageName, activityClass.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (find(app.getString(R.string.customize_title)) == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        SystemClock.sleep(100)
    }

    private fun find(label: String, descriptionOnly: Boolean = false): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            node.refresh()
            if (node.contentDescription?.toString() == label || !descriptionOnly && node.text?.toString()?.lineSequence()?.any { it == label } == true) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it) }?.let { return it }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }

    private fun timerText(): String? {
        fun visit(node: AccessibilityNodeInfo): String? {
            node.refresh()
            node.text?.toString()?.takeIf { it.matches(Regex("\\d+:\\d{2}")) }?.let { return it }
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)?.let { return it }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }

    private fun scrollTo(label: String, horizontal: Boolean = false, backward: Boolean = false) {
        repeat(16) {
            if (find(label, descriptionOnly = true) != null || find(label) != null) return
            fun scrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                node.refresh()
                val direction = when {
                    horizontal && backward -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
                    horizontal -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
                    backward -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
                    else -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                }
                if (node.isScrollable && node.actionList.any { action -> action.id == direction }) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let(::scrollable)?.let { return it }
                return null
            }
            val node = instrumentation.uiAutomation.rootInActiveWindow?.let(::scrollable)
            assertNotNull("No scrollable list for $label", node)
            node!!.performAction(if (backward) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(150)
        }
        fail("Could not scroll to $label")
    }
    private fun click(label: String) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var node = find(label, descriptionOnly = true) ?: find(label)
            while (node != null) {
                node.refresh()
                if (node.isEnabled && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK } &&
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(150)
                    return
                }
                node = node.parent
            }
            SystemClock.sleep(100)
        }
        capture("failed-click.png")
        val labels = mutableListOf<String>()
        fun collect(node: AccessibilityNodeInfo) {
            node.text?.toString()?.let(labels::add)
            node.contentDescription?.toString()?.let(labels::add)
            for (i in 0 until node.childCount) node.getChild(i)?.let(::collect)
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::collect)
        fail("No clickable $label; visible labels: ${labels.take(50)}")
    }

    private fun capture(name: String) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(app.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun replaceScene(content: @androidx.compose.runtime.Composable () -> Unit) {
        val companion = Class.forName("com.openfit.validation.ValidationActivity").getField("Companion").get(null)
        val current = companion.javaClass.methods.single { it.name == "getCurrent" }.invoke(companion) as ComponentActivity
        val scene: @androidx.compose.runtime.Composable () -> Unit = {
            OpenFitTheme(darkTheme = false) {
                androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize().systemBarsPadding()) { content() }
            }
        }
        companion.javaClass.methods.single { it.name == "setContent" }.invoke(companion, scene)
        instrumentation.runOnMainSync { current.javaClass.getMethod("renderScene").invoke(current) }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
    }

    @After fun finish() {
        val fixture = runCatching { Class.forName("com.openfit.validation.ValidationActivity") }.getOrNull() ?: return
        val companion = fixture.getField("Companion").get(null)
        val current = companion.javaClass.methods.single { it.name == "getCurrent" }.invoke(companion) as? android.app.Activity
        instrumentation.runOnMainSync { current?.finish() }
        companion.javaClass.methods.single { it.name == "setCurrent" }.invoke(companion, null)
        val empty: @androidx.compose.runtime.Composable () -> Unit = {}
        companion.javaClass.methods.single { it.name == "setContent" }.invoke(companion, empty)
    }

    @Test fun moveFocusThenSaveInLightTheme() {
        launch(light = true)
        capture("customize-light.png")
        click(app.getString(R.string.focus_custom))
        capture("focus-light.png")
        click(app.getString(R.string.customize_move_up, app.getString(R.string.tile_distance)))
        click(app.getString(R.string.save))
        click(app.getString(R.string.save))
        assertEquals(listOf(TodayTile.Distance, TodayTile.Steps, TodayTile.SleepDuration, null), saved.get()?.custom)
    }

    @Test fun offRowAndCloseDiscardDraftInDarkTheme() {
        launch(light = false)
        capture("customize-dark.png")
        click(app.getString(R.string.focus_custom))
        click(app.getString(R.string.custom_metric, 2))
        click(app.getString(R.string.custom_off))
        click(app.getString(R.string.save))
        click(app.getString(R.string.close))
        assertTrue(closed.get())
        assertNull(saved.get())
    }

    @Test fun customFocusDraftSurvivesActivityRecreation() {
        launch(light = true)
        click(app.getString(R.string.focus_custom))
        click(app.getString(R.string.customize_move_up, app.getString(R.string.tile_distance)))
        val fixture = Class.forName("com.openfit.validation.ValidationActivity")
        val companion = fixture.getField("Companion").get(null)
        val activity = companion.javaClass.methods.single { it.name == "getCurrent" }.invoke(companion) as android.app.Activity
        instrumentation.runOnMainSync { activity.recreate() }
        SystemClock.sleep(500)
        instrumentation.waitForIdleSync()
        click(app.getString(R.string.save))
        click(app.getString(R.string.save))
        assertEquals(TodayTile.Distance, saved.get()?.custom?.first())
    }

    @Test fun sleepPlannerSavesTheChosenWindow() {
        launch(light = true)
        val result = AtomicReference<com.openfit.profile.Profile?>()
        replaceScene { com.openfit.ui.sleep.SleepPlanner(com.openfit.profile.Profile(sleepGoalMinutes = 480, wakeTime = java.time.LocalTime.of(7, 0)), result::set, {}) }
        capture("sleep-planner-light.png")
        click(app.getString(R.string.sleep_planner_save))
        assertEquals(480, result.get()?.sleepGoalMinutes)
        assertEquals(java.time.LocalTime.of(23, 0), result.get()?.bedtime)
        assertEquals(java.time.LocalTime.of(7, 0), result.get()?.wakeTime)
    }

    @Test fun breathingSessionStartsPausesAndResets() {
        launch(light = true)
        replaceScene { com.openfit.ui.stress.BreatheSession {} }
        click(app.getString(R.string.planner_less))
        click(app.getString(R.string.breathe_start))
        SystemClock.sleep(3_200)
        fun seconds(text: String) = text.split(':').let { it[0].toInt() * 60 + it[1].toInt() }
        val beforeRecreation = seconds(requireNotNull(timerText()))
        val companion = Class.forName("com.openfit.validation.ValidationActivity").getField("Companion").get(null)
        val activity = companion.javaClass.methods.single { it.name == "getCurrent" }.invoke(companion) as android.app.Activity
        instrumentation.runOnMainSync { activity.recreate() }
        SystemClock.sleep(600)
        instrumentation.waitForIdleSync()
        val afterRecreation = seconds(requireNotNull(timerText()))
        assertTrue("Timer jumped on recreation: $beforeRecreation -> $afterRecreation", beforeRecreation - afterRecreation in 0..2)
        capture("breathe-running.png")
        click(app.getString(R.string.breathe_pause))
        val paused = requireNotNull(timerText())
        assertTrue(paused != "1:00" && paused != "0:00")
        SystemClock.sleep(1_200)
        assertEquals(paused, timerText())
        click(app.getString(R.string.breathe_reset))
        assertNotNull(find("1:00"))
        capture("breathe-light.png")
    }

    @Test fun periodArrowsNameDayWeekMonthThreeMonthsAndYear() {
        launch(light = true)
        val period = androidx.compose.runtime.mutableStateOf(com.openfit.domain.ChartPeriod.Day)
        replaceScene { com.openfit.ui.detail.PeriodNavigator("Period", true, {}, period = period.value) }
        val labels = listOf(
            Triple(com.openfit.domain.ChartPeriod.Day, R.string.previous_day, R.string.next_day),
            Triple(com.openfit.domain.ChartPeriod.Week, R.string.previous_week, R.string.next_week),
            Triple(com.openfit.domain.ChartPeriod.Month, R.string.previous_month, R.string.next_month),
            Triple(com.openfit.domain.ChartPeriod.ThreeMonths, R.string.previous_three_months, R.string.next_three_months),
            Triple(com.openfit.domain.ChartPeriod.Year, R.string.previous_year, R.string.next_year),
        )
        labels.forEach { (selected, previous, next) ->
            instrumentation.runOnMainSync { period.value = selected }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(100)
            val deadline = SystemClock.uptimeMillis() + 3_000
            while (find(app.getString(previous), descriptionOnly = true) == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
            assertNotNull(app.getString(previous), find(app.getString(previous), descriptionOnly = true))
            assertNotNull(app.getString(next), find(app.getString(next), descriptionOnly = true))
        }
    }

    @Test fun presetSectionMoveHiddenCardAndAddedTabsAreSaved() {
        launch(light = true)
        scrollTo(app.getString(R.string.focus_get_active), horizontal = true, backward = true)
        click(app.getString(R.string.focus_get_active))
        val moveRecovery = app.getString(R.string.customize_move_down, app.getString(R.string.section_recovery))
        scrollTo(moveRecovery)
        click(moveRecovery)
        scrollTo(app.getString(R.string.tile_steps))
        click(app.getString(R.string.tile_steps))
        scrollTo(app.getString(R.string.customize_tabs))
        scrollTo(app.getString(R.string.tab_recovery))
        click(app.getString(R.string.tab_recovery))
        scrollTo(app.getString(R.string.tab_activity))
        click(app.getString(R.string.tab_activity))
        click(app.getString(R.string.save))
        val result = requireNotNull(saved.get())
        assertEquals(FocusPreset.GetActive, result.focus)
        assertEquals(TodaySection.Activity, result.order.first())
        assertTrue(TodayTile.Steps in result.hidden)
        assertEquals(3, result.tabs.size)
        assertTrue(com.openfit.ui.RootTab.Activity in result.tabs)
        assertTrue(com.openfit.ui.RootTab.Recovery in result.tabs)
    }

    @Test fun detailKeepsItsPeriodAfterAFailedLoad() = runBlocking {
        launch(light = true)
        val db = androidx.room.Room.inMemoryDatabaseBuilder(app, com.openfit.data.OpenFitDatabase::class.java).build()
        val fake = FakeHealthClient().apply { permissions = setOf(com.openfit.health.HealthConnectGateway.read(androidx.health.connect.client.records.StepsRecord::class)) }
        val gateway = com.openfit.health.HealthConnectGateway(app, { fake.client }, db, { androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE })
        val owner = androidx.lifecycle.ViewModelStore()
        lateinit var model: com.openfit.ui.goal.GoalMetricViewModel
        instrumentation.runOnMainSync {
            model = com.openfit.ui.goal.GoalMetricViewModel(com.openfit.ui.goal.GoalMetric.Steps, gateway, com.openfit.profile.ProfileStore(app), com.openfit.health.DayLoadCache(app, db), com.openfit.data.SnapshotStore(app, db))
            owner.put("goal", model)
            model.open(java.time.LocalDate.now())
        }
        try {
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (model.state.value !is com.openfit.ui.goal.GoalMetricUiState.Ready && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            assertTrue(model.state.value is com.openfit.ui.goal.GoalMetricUiState.Ready)
            replaceScene { com.openfit.ui.goal.GoalMetricRoute(model) {} }
            val corruptDay = java.time.LocalDate.now().withDayOfMonth(1)
            db.healthCache().putAggregate(com.openfit.data.AggregateEntity("day|steps|$corruptDay", System.currentTimeMillis(), 0,
                corruptDay.toString(), corruptDay.toString(), ",steps,", "invalid-number"))
            click(app.getString(R.string.cardio_tab_month))
            val failedBy = SystemClock.uptimeMillis() + 5_000
            while (model.state.value !is com.openfit.ui.goal.GoalMetricUiState.Failed && SystemClock.uptimeMillis() < failedBy) SystemClock.sleep(50)
            assertTrue(model.state.value is com.openfit.ui.goal.GoalMetricUiState.Failed)
            assertEquals(com.openfit.domain.ChartPeriod.Month, model.period)
        } finally { instrumentation.runOnMainSync { owner.clear() }; db.close() }
    }

    @Test fun detailBackTabSwitchAndRecreationRestoreNavigationAndScroll() = runBlocking {
        val db = com.openfit.data.OpenFitDatabase.get(app)
        db.clearAllTables()
        val fake = FakeHealthClient().apply { permissions = setOf(com.openfit.health.HealthConnectGateway.read(androidx.health.connect.client.records.StepsRecord::class)) }
        val gateway = com.openfit.health.HealthConnectGateway(app, { fake.client }, db, { androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE })
        com.openfit.health.HealthConnectGateway::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, gateway)
        val profile = com.openfit.profile.ProfileStore(app)
        profile.save(com.openfit.profile.Profile(name = "Navigation fixture", age = 35))
        val sync = com.openfit.health.HealthSync(app, gateway, com.openfit.health.DayLoadCache(app), com.openfit.health.SlotCache(app), profile)
        com.openfit.health.HealthSync::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, sync)
        app.getSharedPreferences("hc_import", 0).edit().putBoolean("imported", true).putInt("version", 3).putBoolean("older_done", true)
            .putStringSet("types", setOf("steps")).putLong("last_sync", System.currentTimeMillis()).commit()
        app.getSharedPreferences("google_health", 0).edit().clear().commit()
        com.openfit.ui.onboarding.OnboardingStore(app).done = true
        val day = java.time.LocalDate.now()
        val start = day.atTime(1, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        gateway.store.saveCloud(androidx.health.connect.client.records.StepsRecord::class, listOf(day), mapOf(day to listOf(com.openfit.health.SavedRecord("navigation", com.openfit.health.HealthStore.CLOUD_ORIGIN, start = start, end = start + 60_000, value = 5_000.0))))
        var activity = instrumentation.startActivitySync(Intent(app, com.openfit.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val readyBy = SystemClock.uptimeMillis() + 8_000
            while (find(app.getString(R.string.tile_steps)) == null && SystemClock.uptimeMillis() < readyBy) SystemClock.sleep(100)
            scrollTo(app.getString(R.string.tile_water))
            click(app.getString(R.string.tile_water))
            click(app.getString(R.string.cardio_tab_month))
            click(app.getString(R.string.back))
            assertNotNull(find(app.getString(R.string.tile_water)))
            click(app.getString(R.string.tab_you))
            click(app.getString(R.string.tab_today))
            assertNotNull(find(app.getString(R.string.tile_water)))
            click(app.getString(R.string.tab_you))
            val monitor = android.app.Instrumentation.ActivityMonitor(com.openfit.MainActivity::class.java.name, null, false)
            instrumentation.addMonitor(monitor)
            instrumentation.runOnMainSync { activity.recreate() }
            activity = requireNotNull(monitor.waitForActivityWithTimeout(5_000))
            instrumentation.removeMonitor(monitor)
            val restoredBy = SystemClock.uptimeMillis() + 5_000
            while (find("Navigation fixture") == null && SystemClock.uptimeMillis() < restoredBy) SystemClock.sleep(100)
            assertNotNull(find("Navigation fixture"))
            assertTrue(fake.reads.isEmpty())
        } finally {
            com.openfit.ui.onboarding.OnboardingStore(app).done = false
            instrumentation.runOnMainSync { activity.finish() }
            sync.stopAndJoin()
            com.openfit.health.SyncWorker.cancel(app)
        }
    }

    private inner class OnboardingFixture(status: Int, granted: Set<String>, quota: Boolean = false) {
        val finished = AtomicBoolean()
        val storeOpened = AtomicInteger()
        val requests = AtomicInteger()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(app, com.openfit.data.OpenFitDatabase::class.java).build()
        val fake = FakeHealthClient().apply { if (quota) failOnRead = 1 }
        val gateway = com.openfit.health.HealthConnectGateway(app, { fake.client }, db, { status })
        val profile = com.openfit.profile.ProfileStore(app)
        val loads = com.openfit.health.DayLoadCache(app, db)
        val slots = com.openfit.health.SlotCache(app, db)
        val sync: com.openfit.health.HealthSync
        val models = androidx.lifecycle.ViewModelStore()
        val today: com.openfit.ui.today.TodayViewModel
        val registry = object : androidx.activity.result.ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: androidx.activity.result.contract.ActivityResultContract<I, O>, input: I, options: androidx.core.app.ActivityOptionsCompat?) {
                requests.incrementAndGet()
                fake.permissions = granted
                android.os.Handler(android.os.Looper.getMainLooper()).post { dispatchResult(requestCode, granted) }
            }
        }

        init {
            listOf("hc_import", "hc_changes", "hc_budget", "profile", "google_health", "google_health_history").forEach {
                app.getSharedPreferences(it, 0).edit().clear().commit()
            }
            profile.save(com.openfit.profile.Profile(age = 35, birthDate = java.time.LocalDate.now().minusYears(35), sex = com.openfit.domain.Sex.Female))
            sync = com.openfit.health.HealthSync(app, gateway, loads, slots, profile)
            var created: com.openfit.ui.today.TodayViewModel? = null
            instrumentation.runOnMainSync {
                created = com.openfit.ui.today.TodayViewModel(gateway, profile, com.openfit.googlehealth.GoogleHealthAuth(app), loads, slots,
                    com.openfit.data.SnapshotStore(app, db), sync, com.openfit.googlehealth.GoogleHealthHistory.get(app), autoImport = { false })
                models.put("onboarding", requireNotNull(created))
            }
            today = requireNotNull(created)
            launch(light = true)
            val owner = object : androidx.activity.result.ActivityResultRegistryOwner { override val activityResultRegistry = registry }
            replaceScene {
                androidx.compose.runtime.CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides owner) {
                    com.openfit.ui.onboarding.OnboardingRoute(today, sync, profile, gateway, { storeOpened.incrementAndGet() }, { finished.set(true) })
                }
            }
            click(app.getString(R.string.onboarding_get_started))
            click(app.getString(R.string.onboarding_save_continue))
        }

        fun close() = runBlocking {
            // Dispose import effects before cancelling their longer-lived scope.
            replaceScene {}
            instrumentation.runOnMainSync { models.clear() }
            sync.stopAndJoin()
            db.close()
            this@CustomizeDeviceTest.finish()
        }
    }

    @Test fun onboardingHandlesMissingAndOutdatedHealthConnectAndNotNow() {
        listOf(androidx.health.connect.client.HealthConnectClient.SDK_UNAVAILABLE to R.string.hc_unavailable,
            androidx.health.connect.client.HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED to R.string.hc_update_required).forEach { (status, message) ->
            val fixture = OnboardingFixture(status, emptySet())
            try {
                scrollTo(app.getString(message))
                assertNotNull(find(app.getString(message)))
                scrollTo(app.getString(R.string.hc_open_store))
                click(app.getString(R.string.hc_open_store))
                assertEquals(1, fixture.storeOpened.get())
                assertEquals(0, fixture.requests.get())
                assertFalse(fixture.finished.get())
                scrollTo(app.getString(R.string.onboarding_not_now))
                click(app.getString(R.string.onboarding_not_now))
                assertTrue(fixture.finished.get())
            } finally { fixture.close() }
        }
    }

    @Test fun onboardingPermissionDenialLeavesReadsPageAvailable() {
        val fixture = OnboardingFixture(androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE, emptySet())
        try {
            scrollTo(app.getString(R.string.onboarding_connect))
            click(app.getString(R.string.onboarding_connect))
            assertEquals(1, fixture.requests.get())
            assertFalse(fixture.finished.get())
            assertTrue(fixture.fake.reads.isEmpty())
            scrollTo(app.getString(R.string.onboarding_not_now))
            click(app.getString(R.string.onboarding_not_now))
            assertTrue(fixture.finished.get())
        } finally { fixture.close() }
    }

    @Test fun onboardingQuotaPauseCanContinueWithoutStoppingImport() {
        val fixture = OnboardingFixture(androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE,
            setOf(com.openfit.health.HealthConnectGateway.read(androidx.health.connect.client.records.StepsRecord::class)), quota = true)
        try {
            scrollTo(app.getString(R.string.onboarding_connect))
            click(app.getString(R.string.onboarding_connect))
            click(app.getString(R.string.onboarding_continue_background))
            assertTrue(fixture.finished.get())
            assertTrue(fixture.sync.state.value is com.openfit.health.SyncState.Waiting)
            assertEquals(1, fixture.fake.reads.size)
        } finally { fixture.close() }
    }
}
