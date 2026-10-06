package com.novareader.app

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/**
 * Android-аналог desktop screen_inhibit.py ("кофеин"): не даёт экрану
 * гаснуть, пока идёт озвучка книги.
 *
 * На десктопе для этого нужен D-Bus (org.freedesktop.ScreenSaver.Inhibit
 * с fallback на portal/xset) — на Android для того же результата
 * достаточно WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON. Система сама
 * снимает этот флаг, когда Activity уходит в фон/уничтожается, поэтому
 * отдельно отслеживать сворачивание приложения не нужно — в отличие от
 * desktop-версии, где cookie/QDBusInterface нужно было держать живыми
 * вручную.
 *
 * minutesSetting (хранится в SettingsManager.ttsScreenWakeMinutes):
 *   0  — функция выключена, экран гаснет как обычно (поведение Android)
 *  -1  — «Бесконечно»: экран не гаснет всё время, пока идёт озвучка
 *  >0  — экран не гаснет, но не дольше N минут ПОДРЯД одного сеанса
 *        озвучки — страховка на случай, если слушатель уснул под чтение
 *        и иначе телефон жёг бы подсветку всю ночь. Таймер стартует один
 *        раз в начале сеанса (см. ReaderBridge.onTtsPlaybackChanged) и не
 *        перезапускается на каждой следующей фразе.
 */
class ScreenWakeManager(private val activity: Activity) {

    private val handler = Handler(Looper.getMainLooper())
    private var autoOffRunnable: Runnable? = null

    var isActive = false
        private set

    /** Вызывается при переходе "не играло -> играет" (начало сеанса). */
    fun start(minutesSetting: Int) {
        cancelAutoOff()
        if (minutesSetting == 0) return
        applyFlag(true)
        if (minutesSetting > 0) {
            val r = Runnable {
                NovaLog.d("NovaReader.ScreenWake", "Авто-отключение подсветки после ${minutesSetting} мин. озвучки")
                applyFlag(false)
            }
            autoOffRunnable = r
            handler.postDelayed(r, minutesSetting * 60_000L)
        }
    }

    /** Вызывается когда озвучка остановлена/поставлена на паузу, а также
     *  при закрытии книги/Activity — снимает запрет немедленно. */
    fun stop() {
        cancelAutoOff()
        applyFlag(false)
    }

    private fun applyFlag(keepOn: Boolean) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (keepOn) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        isActive = keepOn
    }

    private fun cancelAutoOff() {
        autoOffRunnable?.let { handler.removeCallbacks(it) }
        autoOffRunnable = null
    }
}
