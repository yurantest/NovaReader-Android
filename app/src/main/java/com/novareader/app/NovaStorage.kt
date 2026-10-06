package com.novareader.app

import android.content.Context
import java.io.File

/**
 * Единая точка для расположения данных приложения — Android-эквивалент
 * desktop-модели (config_dir для JSON + library_path для файлов книг,
 * см. config.py: `Documents/NovaReader Books` на Windows/Linux,
 * `~/Библиотека` на части систем).
 *
 * ВАЖНО: androidx.webkit.WebViewAssetLoader.InternalStoragePathHandler
 * (используется в MainActivity, чтобы reader.html грузил книгу через
 * https://appassets.androidplatform.net/books/... вместо file://)
 * ПРИНУДИТЕЛЬНО требует, чтобы обслуживаемая папка лежала внутри
 * internal data dir или cache dir приложения — внешнее хранилище там
 * выбрасывает IllegalArgumentException("...doesn't exist under an
 * allowed app internal storage directory"). Поэтому сами файлы книг
 * (booksDir) остаются в context.filesDir — их трогать нельзя.
 *
 * А вот каталог (library.json), прогресс (positions.json) и обложки —
 * то, что реально терялось у вас при перезапуске, — переезжают в
 * getExternalFilesDir(null)/NovaReader/: отдельную видимую папку
 * приложения во внешнем хранилище (Android/data/com.novareader.app/
 * files/NovaReader), которая не требует runtime-разрешений (это
 * приватная папка приложения, а не общее хранилище), не участвует в
 * системной чистке "внутреннего кэша" через настройки Android и видна
 * через любой файловый менеджер — ближе всего к тому, как это выглядело
 * на десктопе.
 *
 * Если getExternalFilesDir(null) недоступен (SD-карта размонтирована и
 * т.п. — редкий случай), используется приватный filesDir как фолбэк —
 * тогда данные не потеряются, просто останутся приватными для приложения.
 */
object NovaStorage {

    private const val APP_FOLDER_NAME = "NovaReader"

    /** Папка с JSON-каталогом/прогрессом/обложками — во внешнем хранилище. */
    fun rootDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, APP_FOLDER_NAME).apply { mkdirs() }
    }

    /** Файлы самих книг — ОБЯЗАНЫ оставаться в filesDir из-за ограничения
     *  WebViewAssetLoader.InternalStoragePathHandler (см. выше). */
    fun booksDir(context: Context): File = File(context.filesDir, "books").apply { mkdirs() }

    fun coversDir(context: Context): File = File(rootDir(context), "covers").apply { mkdirs() }

    fun libraryJsonFile(context: Context): File = File(rootDir(context), "library.json")

    fun positionsJsonFile(context: Context): File = File(rootDir(context), "positions.json")

    fun bookDataDir(context: Context): File = File(rootDir(context), "book_data").apply { mkdirs() }

    fun ttsCorrectionsJsonFile(context: Context): File =File(rootDir(context), "tts_corrections.json")
}
