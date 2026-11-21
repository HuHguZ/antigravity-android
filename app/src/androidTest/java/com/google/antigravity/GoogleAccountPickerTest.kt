package com.google.antigravity

import android.accounts.AccountManager
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.antigravity.model.AgentExecutionTarget
import com.google.antigravity.ui.GoogleAccountPicker
import com.google.antigravity.viewmodel.AntigravityViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoogleAccountPickerTest {
    @Test fun onlyAcceptsAConfirmedGoogleAccount() {
        val picker = GoogleAccountPicker()
        val result = Intent().putExtra(AccountManager.KEY_ACCOUNT_NAME, "qa@example.test")
            .putExtra(AccountManager.KEY_ACCOUNT_TYPE, "com.google")
        assertEquals("qa@example.test", picker.parseResult(Activity.RESULT_OK, result))
        assertNull(picker.parseResult(Activity.RESULT_CANCELED, result))
        assertNull(picker.parseResult(Activity.RESULT_OK, Intent(result).putExtra(AccountManager.KEY_ACCOUNT_TYPE, "com.other")))
        assertNull(picker.parseResult(Activity.RESULT_OK, null))
    }

    @Test fun initialSelectionOpensPhoneModeAndSwitchKeepsTheCurrentRoute() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Owned QA emulator only" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val prefs = app.getSharedPreferences("antigravity_prefs_v4", Context.MODE_PRIVATE)
        val strings = listOf("user_email", "user_name", "active_execution_target").associateWith { prefs.getString(it, null) }
        val flags = listOf("is_signed_in", "is_signed_in_v2").associateWith { if (prefs.contains(it)) prefs.getBoolean(it, false) else null }
        val store = ViewModelStore()
        try {
            assertTrue(prefs.edit().putBoolean("is_signed_in_v2", false).commit())
            instrumentation.runOnMainSync {
                val factory = viewModelFactory { initializer { AntigravityViewModel(app) } }
                val model = ViewModelProvider(store, factory)[AntigravityViewModel::class.java]
                model.selectGoogleAccount("first@example.test")
                assertTrue(model.googleProfile.value.isSignedIn)
                assertEquals(AgentExecutionTarget.PHONE_LOCAL_RESOURCES, model.settings.value.executionTarget)
                assertEquals(AgentExecutionTarget.PHONE_LOCAL_RESOURCES.name, prefs.getString("active_execution_target", null))

                model.setExecutionTarget(AgentExecutionTarget.PC_REMOTE_SESSION)
                model.selectGoogleAccount("second@example.test")
                assertEquals(AgentExecutionTarget.PC_REMOTE_SESSION, model.settings.value.executionTarget)
                assertEquals("second@example.test", model.googleProfile.value.email)
                assertEquals("second@example.test", prefs.getString("user_email", null))
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            val restore = prefs.edit()
            strings.forEach { (key, value) -> if (value == null) restore.remove(key) else restore.putString(key, value) }
            flags.forEach { (key, value) -> if (value == null) restore.remove(key) else restore.putBoolean(key, value) }
            assertTrue(restore.commit())
        }
    }
}
