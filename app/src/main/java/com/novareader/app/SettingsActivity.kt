package com.novareader.app

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.net.Uri
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Нативный экран настроек — перенос той части desktop settings_window.py,
 * которая имеет смысл на Android: размер шрифта, межстрочный интервал,
 * поля страницы, стиль выделения, цвет подсветки TTS, тема чтения (3
 * пресета + свой цвет фона/текста), режим разработчика.
 *
 * НЕ перенесено:
 * - автоскрытие курсора — на Android нет курсора мыши.
 *   (screen_inhibit с десктопа перенесён отдельно, в упрощённом виде —
 *   см. spinnerScreenWake / ScreenWakeManager: на Android для "не гасить
 *   экран" достаточно FLAG_KEEP_SCREEN_ON вместо D-Bus, а таймаут экрана
 *   по остальным поводам по-прежнему решает сам Android.)
 * - выбор голосов/движка TTS — теперь только через системные настройки
 *   Android (кнопка в самой панели TTS открывает их напрямую).
 * - язык интерфейса — этим занимается сам Android (Настройки → Язык),
 *   отдельный переключатель внутри приложения убран.
 * - импорт собственных шрифтов — не запрошено.
 * - режим разворота страниц (spread_mode) — актуален в основном для
 *   планшетов в альбомной ориентации, можно добавить отдельно.
 *
 * Каждое изменение применяется мгновенно в уже открытой книге через
 * window.applySettingFromPython(key, value) (уже реализовано в
 * reader.html для всех этих ключей) — саму настройку это не заменяет,
 * saveSetting() сохраняет её так же, как раньше.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: SettingsManager
    private lateinit var fonts: FontManager

    private val highlightStyles = listOf(
        "Заливка" to "highlight",
        "Подчёркивание" to "underline",
        "Волнистая" to "squiggly",
    )

    private val ttsColors = listOf(
        Triple("Голубой", "#00CED1", "cyan"),
        Triple("Красный", "#f28b82", "red"),
        Triple("Зелёный", "#81c995", "green"),
        Triple("Жёлтый", "#fdd66b", "yellow"),
        Triple("Розовый", "#ff8b8b", "pink"),
    )

    private val themePresets = listOf(
        Triple("Светлая", "#f4ecd8", "#5b4636"),
        Triple("Тёмная", "#1a1a1a", "#e0e0e0"),
        Triple("Сепия", "#fbf0d9", "#5f4b3a"),
    )

    // "Кофеин": таймер до авто-отключения "не гасить экран" во время
    // озвучки. 0 = выключено, -1 = бесконечно, иначе минуты.
    private val screenWakeOptions = listOf(
        "Выключено" to 0,
        "10 минут" to 10,
        "20 минут" to 20,
        "30 минут" to 30,
        "40 минут" to 40,
        "50 минут" to 50,
        "Бесконечно" to -1,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settings = SettingsManager(this)
        fonts = FontManager(this)
        val current = try { JSONObject(settings.getSettings()) } catch (_: Exception) { JSONObject() }

        setupFontSize(current)
        setupLineHeight(current)
        setupMargin(current)
        setupHighlightStyle(current)
        setupTtsColor(current)
        setupTtsCustomColor(current)
        setupScreenWakeTimer()
        setupTtsSplitStart(current)
        setupTheme()
        setupLibraryTheme(current)
        setupCustomTheme(current)
        setupAutoOpenLibrary()
        setupFontFamily(current)
        setupDevMode()
        setupBookSearch(current)
        ThemeUtils.applyInputCursors(findViewById(android.R.id.content), this)

        findViewById<TextView>(R.id.btnCloseSettings).setOnClickListener { finish() }
    }

    private fun saveSetting(key: String, value: Any) {
        settings.saveSetting(JSONObject().put("key", key).put("value", value).toString())
        applyLive(key, value)
    }

    /** window.applySettingFromPython уже реализован в reader.html для всех
     *  этих ключей (font_size, line_height, page_margin,
     *  default_highlight_style, tts_highlight_color, theme_bg, theme_text)
     *  — раньше просто не вызывался отсюда, поэтому настройки применялись
     *  только при следующей полной загрузке страницы. */
    private fun applyLive(key: String, value: Any) {
        val jsValue = when (value) {
            is String -> "'${value.replace("'", "\\'")}'"
            else -> value.toString()
        }
        MainActivity.currentWebView?.evaluateJavascript(
            "window.applySettingFromPython && window.applySettingFromPython('$key', $jsValue);",
            null
        )
    }

    // ── Размер шрифта: 12–36px, как в settings_window.py ────────────────
    private fun setupFontSize(current: JSONObject) {
        val seek = findViewById<SeekBar>(R.id.seekFontSize)
        val label = findViewById<TextView>(R.id.labelFontSize)
        val initial = current.optInt("font_size", 16).coerceIn(12, 36)
        seek.max = 24 // 12..36 -> смещение на 12
        seek.progress = initial - 12
        label.text = "${initial}px"
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = progress + 12
                label.text = "${v}px"
                if (fromUser) saveSetting("font_size", v)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    // ── Межстрочный интервал: 1.0–3.0 с шагом 0.1 (хранится ×10, как в
    //    desktop-версии — QSlider.setRange(10, 30)) ──────────────────────
    private fun setupLineHeight(current: JSONObject) {
        val seek = findViewById<SeekBar>(R.id.seekLineHeight)
        val label = findViewById<TextView>(R.id.labelLineHeight)
        val initialTenths = (current.optDouble("line_height", 1.5) * 10).toInt().coerceIn(10, 30)
        seek.max = 20 // 10..30 -> смещение на 10
        seek.progress = initialTenths - 10
        label.text = String.format("%.1f", initialTenths / 10.0)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val tenths = progress + 10
                val value = tenths / 10.0
                label.text = String.format("%.1f", value)
                if (fromUser) saveSetting("line_height", value)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    // ── Поля страницы: 0–120px ───────────────────────────────────────────
    private fun setupMargin(current: JSONObject) {
        val seek = findViewById<SeekBar>(R.id.seekMargin)
        val label = findViewById<TextView>(R.id.labelMargin)
        val initial = current.optInt("page_margin", 44).coerceIn(0, 120)
        seek.max = 120
        seek.progress = initial
        label.text = "${initial}px"
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                label.text = "${progress}px"
                if (fromUser) saveSetting("page_margin", progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    // Раньше оба спиннера ниже использовали android.R.layout.simple_spinner_
    // dropdown_item — системный layout без явных цветов, который берёт фон/
    // текст попапа из разрешённой Day/Night темы устройства. На части
    // прошивок (светная системная тема или OEM переопределяет стиль
    // попап-окна) попап рисовался белым и сливался с текстом — цвета не
    // зависят от собственной тёмной стилизации экрана настроек. Явные
    // тёмные layout'ы (spinner_item_dark/spinner_dropdown_item_dark) плюс
    // spinner_popup_bg на самом Spinner убирают эту зависимость.
    private fun styledSpinnerAdapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, R.layout.spinner_item_dark, items).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item_dark)
        }

    // ── Стиль выделения: highlight/underline/squiggly ───────────────────
    private fun setupHighlightStyle(current: JSONObject) {
        val spinner = findViewById<Spinner>(R.id.spinnerHighlightStyle)
        spinner.setPopupBackgroundResource(R.drawable.spinner_popup_bg)
        spinner.adapter = styledSpinnerAdapter(highlightStyles.map { it.first })
        val currentValue = current.optString("default_highlight_style", "highlight")
        val idx = highlightStyles.indexOfFirst { it.second == currentValue }.coerceAtLeast(0)
        spinner.setSelection(idx)
        spinner.post {
            spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    saveSetting("default_highlight_style", highlightStyles[position].second)
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
    }

    // ── Цвет подсветки TTS: 5 круглых кнопок-цветов, как на десктопе ────
    private fun setupTtsColor(current: JSONObject) {
        val row = findViewById<LinearLayout>(R.id.ttsColorRow)
        val currentValue = current.optString("tts_highlight_color", "cyan")
        val buttons = mutableListOf<Pair<View, String>>()

        ttsColors.forEach { (_, hex, key) ->
            val dot = View(this)
            val size = (40 * resources.displayMetrics.density).toInt()
            val params = LinearLayout.LayoutParams(size, size)
            params.marginEnd = (8 * resources.displayMetrics.density).toInt()
            dot.layoutParams = params
            dot.background = circleDrawable(hex, key == currentValue)
            dot.setOnClickListener {
                saveSetting("tts_highlight_color", key)
                buttons.forEach { (v, k) -> v.background = circleDrawable(ttsColors.first { it.third == k }.second, k == key) }
            }
            row.addView(dot)
            buttons.add(dot to key)
        }
    }

    private fun circleDrawable(hex: String, selected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(hex))
            if (selected) setStroke((3 * resources.displayMetrics.density).toInt(), Color.WHITE)
        }
    }

    // ── Тема чтения: 3 пресета (светлая/тёмная/сепия) ───────────────────
    private fun setupTheme() {
        val row = findViewById<LinearLayout>(R.id.themeRow)
        themePresets.forEachIndexed { index, (label, bg, fg) ->
            val chip = TextView(this).apply {
                text = label
                gravity = android.view.Gravity.CENTER
                setTextColor(Color.parseColor(fg))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12f * resources.displayMetrics.density
                    setColor(Color.parseColor(bg))
                    setStroke((1 * resources.displayMetrics.density).toInt().coerceAtLeast(1), 0x28FFFFFF)
                }
                isClickable = true
                isFocusable = true
                setPadding(8, 0, 8, 0)
                setOnClickListener { webAppApplyTheme(bg, fg) }
            }
            val params = LinearLayout.LayoutParams(0, (48 * resources.displayMetrics.density).toInt(), 1f)
            if (index < themePresets.lastIndex) params.marginEnd = (8 * resources.displayMetrics.density).toInt()
            chip.layoutParams = params
            row.addView(chip)
        }
    }

    /** Тема применяется мгновенно через тот же путь, что остальные настройки. */
    private fun webAppApplyTheme(bg: String, text: String) {
        saveSetting("theme_bg", bg)
        saveSetting("theme_text", text)
    }

    // ── Своя тема: свободный выбор цвета фона и текста ──────────────────
    private fun setupCustomTheme(current: JSONObject) {
        val bgSwatch = findViewById<View>(R.id.customBgSwatch)
        val textSwatch = findViewById<View>(R.id.customTextSwatch)

        var bgHex = current.optString("theme_bg", "#1a1a1a")
        var textHex = current.optString("theme_text", "#e0e0e0")
        bgSwatch.background = circleDrawable(bgHex, selected = false)
        textSwatch.background = circleDrawable(textHex, selected = false)

        bgSwatch.setOnClickListener {
            ColorPickerDialog.show(this, bgHex, "Цвет фона книги") { hex ->
                bgHex = hex
                bgSwatch.background = circleDrawable(hex, selected = false)
                webAppApplyTheme(bgHex, textHex)
            }
        }
        textSwatch.setOnClickListener {
            ColorPickerDialog.show(this, textHex, "Цвет текста книги") { hex ->
                textHex = hex
                textSwatch.background = circleDrawable(hex, selected = false)
                webAppApplyTheme(bgHex, textHex)
            }
        }
    }

    private fun setupAutoOpenLibrary() {
        val checkbox = findViewById<CheckBox>(R.id.checkboxAutoOpenLibrary)
        val prefs = getSharedPreferences("novareader_settings", MODE_PRIVATE)
        applyCheckboxAccent(checkbox)
        checkbox.isChecked = prefs.getBoolean("open_library_on_start", false)
        checkbox.setOnCheckedChangeListener { _, checked ->
            settings.saveSetting(
                JSONObject().put("key", "open_library_on_start").put("value", checked).toString()
            )
        }
    }

    private fun applyCheckboxAccent(checkBox: CheckBox) {
        val prefs = getSharedPreferences("novareader_settings", MODE_PRIVATE)
        val accent = try { Color.parseColor(prefs.getString("library_accent", "#5A4FCF") ?: "#5A4FCF") } catch (_: Exception) { Color.rgb(90,79,207) }
        checkBox.buttonTintList = android.content.res.ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_enabled),
                intArrayOf()
            ),
            intArrayOf(accent, 0x668A8B92, 0xB3A7A9B2.toInt())
        )
    }

    private fun setupDevMode() {
        val checkbox = findViewById<CheckBox>(R.id.checkboxDevMode)
        applyCheckboxAccent(checkbox)
        checkbox.isChecked = settings.devMode
        checkbox.setOnCheckedChangeListener { _, isChecked ->
            settings.devMode = isChecked
        }
    }

    // ── Шрифт книги: список встроенных + пользовательских, импорт своего ──

    private val importFontLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) handleFontImport(uri)
        }

    private fun handleFontImport(uri: Uri) {
        val displayName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        } ?: uri.lastPathSegment ?: "font"

        val result = fonts.importFont(this, uri, displayName)
        if (result != null) {
            android.widget.Toast.makeText(this, "Шрифт добавлен: $displayName", android.widget.Toast.LENGTH_SHORT).show()
            populateFontSpinner(currentFontFamily())
        } else {
            android.widget.Toast.makeText(this, "Не удалось добавить шрифт", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private var fontFamilies: List<JSONObject> = emptyList()

    private fun currentFontFamily(): String {
        val current = try { JSONObject(settings.getSettings()) } catch (_: Exception) { JSONObject() }
        return current.optString("reader_font_family", "")
    }


    private fun setupTtsCustomColor(current: JSONObject) {
        val swatch = findViewById<View>(R.id.ttsCustomColorSwatch)
        val opacitySeek = findViewById<SeekBar>(R.id.seekTtsOpacity)
        val opacityLabel = findViewById<TextView>(R.id.ttsOpacityLabel)
        var hex = current.optString("tts_highlight_color_hex", "#00CED1")
        var opacity = current.optDouble("tts_highlight_opacity", 0.70).coerceIn(0.05, 1.0)
        fun refresh() {
            swatch.background = circleDrawable(hex, false)
            opacitySeek.progress = ((opacity - 0.05) * 100).toInt().coerceIn(0, 95)
            opacityLabel.text = "${(opacity * 100).toInt()}%"
        }
        refresh()
        swatch.setOnClickListener {
            ColorPickerDialog.show(this, hex, "Цвет TTS-подсветки") { selected ->
                hex = selected
                saveTtsColor(hex, opacity)
                refresh()
            }
        }
        opacitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                opacity = (progress + 5) / 100.0
                opacityLabel.text = "${(opacity * 100).toInt()}%"
                if (fromUser) saveTtsColor(hex, opacity)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }


    // Старт TTS с разорванного предложения: true — читать видимую половину
    // текущего предложения; false — пропускать эту половину и начинать
    // со следующего полного предложения на странице.
    private fun setupTtsSplitStart(current: JSONObject) {
        val checkbox = findViewById<CheckBox>(R.id.checkboxTtsSplitStart)
        applyCheckboxAccent(checkbox)
        checkbox.isChecked = current.optBoolean("tts_start_split_from_visible", true)
        checkbox.setOnCheckedChangeListener { _, checked ->
            saveSetting("tts_start_split_from_visible", checked)
        }
    }

    // ── "Кофеин": не гасить экран во время озвучки ───────────────────────
    // Хранится отдельно через SettingsManager.ttsScreenWakeMinutes (не
    // через общий saveSetting/applyLive) — это чисто нативная Android-
    // настройка (FLAG_KEEP_SCREEN_ON), reader.html о ней ничего не знает
    // и применять её через window.applySettingFromPython незачем.
    private fun setupScreenWakeTimer() {
        val spinner = findViewById<Spinner>(R.id.spinnerScreenWake)
        spinner.setPopupBackgroundResource(R.drawable.spinner_popup_bg)
        spinner.adapter = styledSpinnerAdapter(screenWakeOptions.map { it.first })
        val currentValue = settings.ttsScreenWakeMinutes
        val idx = screenWakeOptions.indexOfFirst { it.second == currentValue }.coerceAtLeast(0)
        spinner.setSelection(idx)
        spinner.post {
            spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    settings.ttsScreenWakeMinutes = screenWakeOptions[position].second
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
    }

    private fun saveTtsColor(hex: String, opacity: Double) {
        saveSetting("tts_highlight_color_hex", hex)
        saveSetting("tts_highlight_opacity", opacity)
        val c = try { Color.parseColor(hex) } catch (_: Exception) { Color.CYAN }
        val rgba = "rgba(${Color.red(c)}, ${Color.green(c)}, ${Color.blue(c)}, ${"%.3f".format(java.util.Locale.US, opacity)})"
        saveSetting("tts_highlight_color_rgba", rgba)
    }

    private fun setupLibraryTheme(current: JSONObject) {
        var bg = current.optString("library_bg", "#1C1C1E")
        var card = current.optString("library_card", "#2C2C2E")
        var accent = current.optString("library_accent", "#5A4FCF")
        val bgView = findViewById<View>(R.id.libraryBgSwatch)
        val cardView = findViewById<View>(R.id.libraryCardSwatch)
        val accentView = findViewById<View>(R.id.libraryAccentSwatch)
        fun refresh() {
            bgView.background = circleDrawable(bg, false)
            cardView.background = circleDrawable(card, false)
            accentView.background = circleDrawable(accent, false)
        }
        refresh()
                bgView.setOnClickListener { ColorPickerDialog.show(this, bg, "Фон библиотеки") { v -> bg = v; saveSetting("library_bg", bg); refresh() } }
        cardView.setOnClickListener { ColorPickerDialog.show(this, card, "Цвет карточек") { v -> card = v; saveSetting("library_card", card); refresh() } }
        accentView.setOnClickListener { ColorPickerDialog.show(this, accent, "Акцент библиотеки") { v ->
            accent = v
            saveSetting("library_accent", accent)
            refresh()
            findViewById<CheckBox>(R.id.checkboxAutoOpenLibrary)?.let(::applyCheckboxAccent)
            findViewById<CheckBox>(R.id.checkboxDevMode)?.let(::applyCheckboxAccent)
            findViewById<CheckBox>(R.id.checkboxTtsSplitStart)?.let(::applyCheckboxAccent)
            findViewById<CheckBox>(R.id.checkboxBookSearchEnabled)?.let(::applyCheckboxAccent)
            findViewById<TextView>(R.id.btnImportFont)?.compoundDrawableTintList = ThemeUtils.accentTint(this)
            ThemeUtils.applyInputCursors(findViewById(android.R.id.content), this)
        } }
    }

    private fun setupFontFamily(current: JSONObject) {
        populateFontSpinner(current.optString("reader_font_family", ""))
        val importButton = findViewById<TextView>(R.id.btnImportFont)
        importButton.compoundDrawableTintList = ThemeUtils.accentTint(this)
        importButton.setOnClickListener {
            importFontLauncher.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/octet-stream", "*/*"))
        }
    }

    private fun populateFontSpinner(selectedFamily: String) {
        val faces = try { JSONArray(fonts.getFontFaces()) } catch (_: Exception) { JSONArray() }
        val list = mutableListOf<JSONObject>()
        for (i in 0 until faces.length()) list.add(faces.getJSONObject(i))
        // Уникальные семейства (variable-шрифт может дать несколько записей
        // с одинаковым family для разных начертаний — оставляем одну на UI).
        fontFamilies = list.distinctBy { it.optString("family") }

        val spinner = findViewById<Spinner>(R.id.spinnerFontFamily)
        spinner.setPopupBackgroundResource(R.drawable.spinner_popup_bg)
        val names = listOf("Стандартный") + fontFamilies.map { it.optString("display_name", it.optString("family")) }
        spinner.adapter = styledSpinnerAdapter(names)

        val idx = fontFamilies.indexOfFirst { it.optString("family") == selectedFamily }
        spinner.setSelection(if (idx >= 0) idx + 1 else 0)

        spinner.post {
            spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val family = if (position == 0) "" else fontFamilies[position - 1].optString("family")
                    saveSetting("reader_font_family", family)
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
    }

    // ── Поиск книг в интернете (выкл по умолчанию) ─────────────────────
    private fun setupBookSearch(current: JSONObject) {
        val chk = findViewById<CheckBox>(R.id.checkboxBookSearchEnabled)
        applyCheckboxAccent(chk)
        chk.isChecked = current.optBoolean("book_search_enabled", false)
        chk.setOnCheckedChangeListener { _, isChecked ->
            saveSetting("book_search_enabled", isChecked)
            settings.bookSearchEnabled = isChecked
        }

        // Домены-зеркала: SF и Flibusta иногда меняют рабочий домен
        // (блокировки/зеркала). Пустая строка = значение по умолчанию.
        val flibustaInput = findViewById<android.widget.EditText>(R.id.editFlibustaDomain)
        val sfInput = findViewById<android.widget.EditText>(R.id.editSearchfloorDomain)

        flibustaInput.setText(settings.getBookSearchFlibustaDomain())
        flibustaInput.hint = "https://flibusta.is (по умолчанию)"
        sfInput.setText(settings.getBookSearchSearchFloorDomain())
        sfInput.hint = "https://searchfloor.org (по умолчанию)"

        flibustaInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                settings.setBookSearchFlibustaDomain(flibustaInput.text.toString())
            }
        }
        sfInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                settings.setBookSearchSearchFloorDomain(sfInput.text.toString())
            }
        }
    }
}