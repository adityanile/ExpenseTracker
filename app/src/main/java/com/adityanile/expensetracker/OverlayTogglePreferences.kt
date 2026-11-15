package com.adityanile.expensetracker

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.core.content.edit

object OverlayTogglePreferences {

    const val ACTION_OVERLAY_TOGGLE_CHANGED = "com.adityanile.expensetracker.action.OVERLAY_TOGGLE_CHANGED"
    const val ACTION_DISABLE_ACCESSIBILITY_SERVICE = "com.adityanile.expensetracker.action.DISABLE_ACCESSIBILITY_SERVICE"

    private const val PREFS_NAME = "overlay_preferences"
    private const val KEY_OVERLAY_ENABLED = "overlay_enabled"

    fun isOverlayEnabled(context: Context): Boolean {
        return context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_OVERLAY_ENABLED, true)
    }

    fun setOverlayEnabled(context: Context, enabled: Boolean) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                putBoolean(KEY_OVERLAY_ENABLED, enabled)
            }
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        val serviceComponentName = ComponentName(context, PaymentOverlayAccessibilityService::class.java)
            .flattenToString()

        return enabledServices
            .split(':')
            .any { enabledService ->
                enabledService.equals(serviceComponentName, ignoreCase = true)
            }
    }
}
