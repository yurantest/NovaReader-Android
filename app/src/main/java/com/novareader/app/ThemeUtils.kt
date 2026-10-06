package com.novareader.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.EditText

/** Общие утилиты для применения акцентного цвета библиотеки к нативным элементам UI. */
object ThemeUtils {

    private const val PREFS = "novareader_settings"
    private const val DEFAULT_ACCENT = "#5A4FCF"

    fun accentColor(context: Context): Int {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("library_accent", DEFAULT_ACCENT) ?: DEFAULT_ACCENT
        return try {
            Color.parseColor(value)
        } catch (_: IllegalArgumentException) {
            Color.parseColor(DEFAULT_ACCENT)
        }
    }

    fun accentTint(context: Context): ColorStateList = ColorStateList.valueOf(accentColor(context))

    /** Применяет акцентный цвет к курсорам всех EditText внутри указанного контейнера. */
    fun applyInputCursors(root: View?, context: Context) {
        if (root == null) return
        if (root is EditText) applyInputCursor(root, context)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                applyInputCursors(root.getChildAt(i), context)
            }
        }
    }

    fun applyInputCursor(editText: EditText, context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(accentColor(context))
                cornerRadius = 1f
                setSize(dp(context, 2), dp(context, 1))
            }
            editText.textCursorDrawable = drawable
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
}
