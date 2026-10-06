// webchannel-shim.js
// Эмулирует API QWebChannel (channel.objects.readerBridge с async-callback
// вызовами) поверх Android addJavascriptInterface (синхронные вызовы Kotlin).
// Позволяет использовать ibc/reader.html БЕЗ ИЗМЕНЕНИЙ на Android.
//
// Подключается ПЕРЕД reader.html вместо qrc:///qtwebchannel/qwebchannel.js

(function () {
    // Android WebView прокидывает Kotlin-объект сюда через
    // webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
    const native = window.AndroidBridge;

    if (!native) {
        console.warn('[shim] window.AndroidBridge не найден — мост не подключён');
        return;
    }

    // Список слотов, которые в Qt-коде объявлены как @pyqtSlot(result=...)
    // и в reader.html вызываются как bridge.method(cb) — их нужно оборачивать
    // так, чтобы синхронный возврат Kotlin превращался в callback(value).
    // Методы без result (void-слоты) просто дёргаются напрямую, без обёртки.
    const ASYNC_RESULT_METHODS = [
        'getBookData', 'getTheme', 'getLanguage', 'getAvailableEngines',
        'getEngineVoices', 'getVoicesDir', 'getSettings', 'getTTSCorrections',
        'getPosition', 'getBookmarks', 'getHighlights', 'getNotes',
        'getPiperVoices', 'getSystemEngines', 'checkVoiceAvailability',
        'getFontFaces'
    ];

    const bridgeProxy = {};

    // void-методы (fire-and-forget) — просто forward
    const VOID_METHODS = [
        'log', 'onSectionChanged', 'onBookReady', 'setPreferredEngine',
        'setVoice', 'setRate', 'onTTSText', 'prefetchTTS', 'stopTTS', 'pauseTTS', 'resumeTTS',
        'copyToClipboard', 'onMouseMove', 'showLibrary', 'showSettings',
        'showTTSCorrection', 'savePosition', 'saveBookmark', 'removeBookmark',
        'saveSetting', 'saveTTSCorrections', 'saveHighlight', 'removeHighlight',
        'saveNote', 'getNotes', 'removeNote', 'updateNote', 'saveQuoteImage',
        'downloadVoice', 'openSystemTtsSettings', 'importFont'
    ];

    ASYNC_RESULT_METHODS.forEach((name) => {
        bridgeProxy[name] = function (...args) {
            const cb = args.pop(); // последний аргумент — callback (стиль Qt)
            try {
                const result = native[name](...args); // синхронный вызов Kotlin
                // setTimeout(0) — чтобы поведение осталось асинхронным,
                // как ожидает остальной код reader.html (не блокирует стек)
                setTimeout(() => cb && cb(result), 0);
            } catch (e) {
                console.error(`[shim] ${name} failed:`, e);
                setTimeout(() => cb && cb(null), 0);
            }
        };
    });

    VOID_METHODS.forEach((name) => {
        if (bridgeProxy[name]) return; // уже определён выше (напр. getNotes)
        bridgeProxy[name] = function (...args) {
            try {
                return native[name](...args);
            } catch (e) {
                console.error(`[shim] ${name} failed:`, e);
            }
        };
    });

    // Эмулируем ровно тот же вход, что ждёт reader.html:
    // new QWebChannel(qt.webChannelTransport, (channel) => {...})
    window.qt = window.qt || {};
    window.qt.webChannelTransport = {}; // не используется реально, просто заглушка

    window.QWebChannel = function (transport, callback) {
        // channel.objects.readerBridge — именно так его достаёт reader.html
        callback({ objects: { readerBridge: bridgeProxy } });
    };
})();
