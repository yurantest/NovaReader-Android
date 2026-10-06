package com.novareader.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        var currentWebView: WebView? = null
    }

    private lateinit var webView: WebView
    private lateinit var bridge: ReaderBridge
    private lateinit var screenWake: ScreenWakeManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val incomingRelativePath = intent?.getStringExtra(LibraryActivity.EXTRA_RELATIVE_PATH)
        val autoOpenLibrary = getSharedPreferences("novareader_settings", MODE_PRIVATE)
            .getBoolean("open_library_on_start", false)

        if (incomingRelativePath == null && !autoOpenLibrary) {
            val tempBookManager = BookManager(this)
            val tempLibraryManager = LibraryManager(this)
            val hasCurrentBook = try {
                org.json.JSONObject(tempBookManager.getBookData()).optString("path").isNotBlank()
            } catch (_: Exception) { false }
            val hasAnyBooks = try {
                org.json.JSONArray(tempLibraryManager.getBooks()).length() > 0
            } catch (_: Exception) { false }
            if (!hasCurrentBook && !hasAnyBooks) {
                startActivity(Intent(this, WelcomeActivity::class.java))
                finish()
                return
            }
        }

        setContentView(R.layout.activity_main)

        NovaLog.init(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }

        webView = findViewById(R.id.webView)
        currentWebView = webView
        bridge = ReaderBridge(this)
        screenWake = ScreenWakeManager(this)

        bridge.onTtsPlaybackChanged = { playing ->
            if (playing) {
                val minutes = getSharedPreferences("novareader_settings", MODE_PRIVATE)
                    .getInt("tts_screen_wake_minutes", 0)
                screenWake.start(minutes)
            } else {
                screenWake.stop()
            }
        }
        initialized = true

        if (incomingRelativePath != null) {
            val displayName = intent.getStringExtra(LibraryActivity.EXTRA_DISPLAY_NAME) ?: incomingRelativePath
            val format = intent.getStringExtra(LibraryActivity.EXTRA_FORMAT) ?: "unknown"
            bridge.bookManager().openExistingBook(incomingRelativePath, displayName, format)
        }

        val booksDir = NovaStorage.booksDir(this)
        val fontsServeDir = File(filesDir, "fonts_serve").apply { mkdirs() }
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/books/", WebViewAssetLoader.InternalStoragePathHandler(this, booksDir))
            .addPathHandler("/fonts/", WebViewAssetLoader.InternalStoragePathHandler(this, fontsServeDir))
            .build()

        webView.clearCache(true)

        // Не даём Chromium/WebView понижать приоритет renderer-процесса, когда
        // экран гаснет. TTS продолжается в foreground service, поэтому reader
        // тоже должен оставаться активным: переход на следующую страницу/секцию
        // выполняется из JS через callback TTS. Особенно важно на HyperOS.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(
                WebView.RENDERER_PRIORITY_IMPORTANT,
                false
            )
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
        }

        webView.addJavascriptInterface(bridge, "AndroidBridge")
        webView.setOnCreateContextMenuListener { menu, _, _ -> menu.close() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            webView.setTextClassifier(android.view.textclassifier.TextClassifier.NO_OP)
        }

        webView.isLongClickable = false
        webView.setOnLongClickListener { true }

        bridge.attachWebView(webView)
        bridge.onShowLibrary = { openLibraryLauncher.launch(Intent(this, LibraryActivity::class.java)) }
        bridge.onShowSettings = { openSettingsLauncher.launch(Intent(this, SettingsActivity::class.java)) }
        bridge.onShowTtsCorrection = {
            startActivity(Intent(this, TtsCorrectionActivity::class.java))
        }

        ReaderBridgeHolder.webView = webView

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: android.webkit.WebResourceRequest
            ) = assetLoader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (isFinishing || isDestroyed) return

                view.evaluateJavascript(
                    "window.setDevMode && window.setDevMode(${bridge.isDevMode()});",
                    null
                )

                val correctionsJson = bridge.getTTSCorrectionsCombined()
                view.evaluateJavascript(
                    "window.updateTTSCorrections && window.updateTTSCorrections($correctionsJson);",
                    null
                )
            }
        }

        webView.loadUrl("https://appassets.androidplatform.net/assets/ibc/reader.html")

        setupLogsOverlay()

        if (incomingRelativePath == null && autoOpenLibrary) {
            window.decorView.post {
                if (!isFinishing && !isDestroyed) {
                    openLibraryLauncher.launch(Intent(this, LibraryActivity::class.java))
                }
            }
        }
    }

    private val openSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (::webView.isInitialized && !isFinishing && !isDestroyed) {
                webView.evaluateJavascript(
                    "window.setDevMode && window.setDevMode(${bridge.isDevMode()});",
                    null
                )
            }
            if (::screenWake.isInitialized && screenWake.isActive) {
                val minutes = getSharedPreferences("novareader_settings", MODE_PRIVATE)
                    .getInt("tts_screen_wake_minutes", 0)
                screenWake.start(minutes)
            }
        }

    private val openLibraryLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data ?: return@registerForActivityResult
                val relativePath = data.getStringExtra(LibraryActivity.EXTRA_RELATIVE_PATH) ?: return@registerForActivityResult
                val displayName = data.getStringExtra(LibraryActivity.EXTRA_DISPLAY_NAME) ?: relativePath
                val format = data.getStringExtra(LibraryActivity.EXTRA_FORMAT) ?: "unknown"
                openExistingBook(relativePath, displayName, format)
            }
        }

    private fun openExistingBook(relativePath: String, displayName: String, format: String) {
        bridge.bookManager().openExistingBook(relativePath, displayName, format)
        webView.reload()
    }

    private fun setupLogsOverlay() {
        val overlay = findViewById<LinearLayout>(R.id.logsOverlay)
        val logsText = findViewById<TextView>(R.id.logsText)

        bridge.onShowNativeLogs = {
            logsText.text = NovaLog.getBufferText()
            overlay.visibility = android.view.View.VISIBLE
        }
        findViewById<Button>(R.id.btnLogsRefresh).setOnClickListener {
            logsText.text = NovaLog.getBufferText()
        }
        findViewById<Button>(R.id.btnLogsClose).setOnClickListener {
            overlay.visibility = android.view.View.GONE
        }
        findViewById<Button>(R.id.btnLogsShare).setOnClickListener {
            shareLogFile()
        }
    }

    private fun shareLogFile() {
        val file = NovaLog.getLogFile()
        if (file == null || !file.exists()) {
            Toast.makeText(this, "Файл лога пока пуст", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Поделиться логом"))
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось поделиться: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private var isFirstResume = true

    override fun onResume() {
        super.onResume()
        if (isFirstResume) {
            isFirstResume = false
            return
        }
        // Пересоздаём TTS только если пользователь ходил в системные
        // настройки синтеза (кнопка в панели TTS). Обычный onResume
        // (разблокировка экрана) — не трогаем, иначе речь обрывается.
        if (::bridge.isInitialized && bridge.consumeRecreateTtsRequest()) {
            NovaLog.d("NovaReader.MainActivity", "onResume: recreate TTS after system settings")
            bridge.recreateTts()
        }
    }

    private var initialized = false

    override fun onDestroy() {
        if (initialized) {
            if (currentWebView === webView) currentWebView = null
            if (ReaderBridgeHolder.webView === webView) ReaderBridgeHolder.webView = null
            screenWake.stop()

            // Tear down TTS/service only when Activity is really finishing,
            // not on rotation/recreate. Screen unlock is usually just
            // onPause/onResume; on some OEMs Activity may be destroyed but
            // isFinishing stays false — keep the service alive.
            if (isFinishing && !isChangingConfigurations) {
                bridge.onDestroy()
            }
        }
        super.onDestroy()
    }
}