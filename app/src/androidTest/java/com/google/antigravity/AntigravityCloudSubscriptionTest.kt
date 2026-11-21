package com.google.antigravity

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.antigravity.model.AgentExecutionTarget
import com.google.antigravity.viewmodel.AntigravityViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AntigravityCloudSubscriptionTest {
    @Test fun cloudSelectionOpensOfficialSiteAndPreservesPhoneMode() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Owned QA emulator only" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val prefs = app.getSharedPreferences("antigravity_prefs_v4", 0)
        val previousTarget = prefs.getString("active_execution_target", null)
        val store = ViewModelStore()
        var launched: Intent? = null
        val browser = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_VIEW) return null
                launched = intent
                return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
            }
        }
        instrumentation.addMonitor(browser)
        try {
            instrumentation.runOnMainSync {
                val model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[AntigravityViewModel::class.java]
                model.setExecutionTarget(AgentExecutionTarget.PHONE_LOCAL_RESOURCES)
                model.setExecutionTarget(AgentExecutionTarget.GOOGLE_WEB_CONSOLE)
                assertEquals("https://antigravity.google.com/", launched?.dataString)
                assertNull(launched?.extras)
                assertEquals(AgentExecutionTarget.PHONE_LOCAL_RESOURCES, model.settings.value.executionTarget)
                assertEquals("PHONE_LOCAL_RESOURCES", prefs.getString("active_execution_target", null))
            }
        } finally {
            instrumentation.removeMonitor(browser)
            instrumentation.runOnMainSync { store.clear() }
            check(prefs.edit().putString("active_execution_target", previousTarget).commit())
        }
    }
}
