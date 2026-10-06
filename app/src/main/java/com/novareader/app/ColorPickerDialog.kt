package com.novareader.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Полноценный HSV-микшер — как QColorDialog на десктопе (hue-полоса +
 * saturation/value квадрат), а не фиксированный набор готовых свотчей.
 * Плюс поле точного HEX для тех, кто знает конкретный код цвета.
 */
object ColorPickerDialog {

    fun show(context: Context, initialHex: String, title: String, onPicked: (String) -> Unit) {
        val density = context.resources.displayMetrics.density
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (4 * density).toInt())
        }

        val picker = HsvColorPickerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (260 * density).toInt()
            )
            setColorHex(initialHex)
        }
        root.addView(picker)

        val hexLabel = TextView(context).apply {
            text = "HEX:"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 12f
            setPadding(0, (14 * density).toInt(), 0, (4 * density).toInt())
        }
        root.addView(hexLabel)

        val hexRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val hexInput = EditText(context).apply {
            hint = "#RRGGBB"
            setText(picker.currentHex())
            setTextColor(Color.parseColor("#FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        hexRow.addView(hexInput)
        root.addView(hexRow)

        picker.onColorChanged = { color ->
            hexInput.setText(String.format("#%06X", 0xFFFFFF and color))
        }

        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(root)
            .setPositiveButton("Применить") { _, _ ->
                val text = hexInput.text.toString().trim()
                val normalized = if (text.startsWith("#")) text else "#$text"
                try {
                    Color.parseColor(normalized)
                    onPicked(normalized)
                } catch (_: IllegalArgumentException) {
                    onPicked(picker.currentHex())
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
