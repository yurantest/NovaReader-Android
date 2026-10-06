// tts-highlight-color.js
// Цвет и прозрачность TTS-подсветки: разбор rgba() от Python и хранение
// текущих настроек. Вынесено из reader.html в отдельный модуль — не зависит
// от остального состояния читалки (view, overlayer и т.п.), поэтому
// безопасно для независимого использования/тестирования.

// Значения по умолчанию — должны совпадать с тем, что Python отдаёт как
// дефолт в config.py (get_tts_highlight_color / get_tts_highlight_opacity).
export const DEFAULT_TTS_HIGHLIGHT_COLOR = '#00CED1';
export const DEFAULT_TTS_HIGHLIGHT_OPACITY = 0.7;

let ttsHighlightColor = DEFAULT_TTS_HIGHLIGHT_COLOR;
let ttsHighlightOpacity = DEFAULT_TTS_HIGHLIGHT_OPACITY;

export function getTtsHighlightColor() {
    return ttsHighlightColor;
}

export function getTtsHighlightOpacity() {
    return ttsHighlightOpacity;
}

/**
 * Разбирает строку вида 'rgba(r, g, b, a)' (или 'rgb(r, g, b)'), пришедшую
 * из Python, в { color: '#rrggbb', opacity: 0..1 }. Возвращает null при
 * ошибке разбора (не rgba-строка / не распозналась).
 */
export function parseTtsRgba(rgbaStr) {
    if (!rgbaStr) return null;
    const m = /rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*([\d.]+)\s*)?\)/.exec(rgbaStr);
    if (!m) return null;
    const toHex = n => Math.max(0, Math.min(255, parseInt(n, 10))).toString(16).padStart(2, '0');
    const color = '#' + toHex(m[1]) + toHex(m[2]) + toHex(m[3]);
    const opacity = m[4] !== undefined ? parseFloat(m[4]) : 1;
    return { color, opacity: Math.min(1, Math.max(0.05, opacity)) };
}

/**
 * Обновляет модуль-локальное состояние цвета/прозрачности.
 * Сама перерисовка активной подсветки (если она сейчас на экране) остаётся
 * на стороне reader.html, так как требует доступа к overlayer/lastTtsRange —
 * эта функция только держит текущие значения цвета и опционально
 * возвращает применённые (min/max-clamped) значения для удобства вызова.
 */
export function setTtsHighlightColor(colorHex, opacity) {
    ttsHighlightColor = colorHex;
    if (opacity !== undefined && opacity !== null) {
        ttsHighlightOpacity = Math.min(1, Math.max(0.05, opacity));
    }
    return { color: ttsHighlightColor, opacity: ttsHighlightOpacity };
}
