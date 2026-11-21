package com.google.antigravity.ui

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract

internal class GoogleAccountPicker : ActivityResultContract<String?, String?>() {
    override fun createIntent(context: Context, input: String?): Intent = AccountManager.newChooseAccountIntent(
        input?.takeIf { it.isNotBlank() }?.let { Account(it, GOOGLE_ACCOUNT_TYPE) },
        null,
        arrayOf(GOOGLE_ACCOUNT_TYPE),
        "Выберите аккаунт Google для профиля Antigravity на телефоне.",
        null,
        null,
        null
    )

    override fun parseResult(resultCode: Int, intent: Intent?): String? {
        if (resultCode != Activity.RESULT_OK || intent?.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE) != GOOGLE_ACCOUNT_TYPE) {
            return null
        }
        return intent.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val GOOGLE_ACCOUNT_TYPE = "com.google"
    }
}
