// fonts.js
// ── Пользовательские шрифты для Immortal Book Core ──────────────────────────
// Загрузка шрифтов ТОЛЬКО через FontFace API (никакого @font-face из Python,
// никакого QFontDatabase для веб-вью).
//
// ВАЖНО про архитектуру foliate-js: каждая секция книги рендерится в
// ОТДЕЛЬНОМ <iframe> (paginator.js, sandbox="allow-same-origin allow-scripts"),
// у которого свой независимый document. document.fonts главной страницы
// НЕ виден внутри iframe — это два разных документа с двумя разными наборами
// шрифтов. Поэтому FontFace, загруженный один раз на главной странице,
// дополнительно регистрируется (add()) в document.fonts КАЖДОГО нового
// iframe при его создании — см. window.__registerFontsInDoc(doc), вызываемую
// из reader.html в обработчике view.addEventListener('load', ...).
// Сам файл шрифта при этом с диска не перечитывается: FontFace — это уже
// загруженный (после face.load()) объект, add() лишь регистрирует один и тот
// же объект в дополнительном документе.
//
// ── УНИКАЛЬНЫЙ ПРЕФИКС СЕМЕЙСТВА ────────────────────────────────────────────
// Книги (особенно MOBI/Calibre-конвертированные) часто встраивают свой
// @font-face с именем вроде 'Literata', 'PT Serif', 'Georgia' — тем же
// самым, что и у пользовательского шрифта в настройках читалки. Если
// зарегистрировать свой FontFace под тем же именем, может выиграть
// книжный @font-face (например, если он добавляется позже), и тогда
// вместо выбранного пользователем шрифта отрисуется книжный.
//
// Решение: регистрируем пользовательский шрифт под УНИКАЛЬНЫМ именем
// '__NovaReader_<unique_family>'. Python (config.py) даёт каждому файлу
// unique_family = family + суффикс веса/стиля (например, 'KF Bitter' для
// Regular, 'KF Bitter 700' для Bold). Это гарантирует, что два начертания
// одного family НЕ смешаются в document.fonts — у каждого своё имя, и
// браузер никогда не подставит Bold-глифы вместо Regular.
//
// Порядок операций критичен:
//   1. await загрузка начертания через face.load()
//   2. применить font-family + font-weight + font-style к view
//   3. перепагинация (полный перерендер), с сохранением позиции чтения
// На время шага 3 показывается спиннер «Загрузка...» — переиспользуем
// showReturnOverlay/dismissReturnOverlay из reader.html.

const UNIQUE_PREFIX = '__NovaReader_';

// Кэш загруженных семейств: uniqueFamily → массив уже загруженных FontFace.
// Используется и чтобы не грузить файлы повторно, и чтобы регистрировать
// их в новых iframe без повторного обращения к диску.
const _loadedFaces = new Map(); // uniqueFamily -> FontFace[]

/**
 * Находит одну запись в fontMap по выбранному display_name/family/unique_family.
 * Возвращает { selected } — ровно ту запись, которую выбрал пользователь.
 *
 * ВАЖНО: раньше возвращались ВСЕ начертания одного family — это приводило к
 * тому, что два файла с одинаковым family ('KF Bitter' Regular и Bold)
 * регистрировались под одним именем FontFace, и браузер смешивал глифы.
 * Теперь возвращается только выбранная запись.
 *
 * @param {Array} fontMap - config.get_font_file_map() → приходит в JS как s.font_faces
 * @param {string} selectedDisplayName - reader_font_family из настроек
 * @returns {{family: string, selected: object} | null}
 */
function _resolveFamily(fontMap, selectedDisplayName) {
    if (!Array.isArray(fontMap) || !fontMap.length) return null;

    const rawName = String(selectedDisplayName || '').startsWith(UNIQUE_PREFIX)
        ? String(selectedDisplayName).slice(UNIQUE_PREFIX.length)
        : String(selectedDisplayName || '');

    // Настройки Android/Windows обычно хранят именно family. Старые версии
    // могли сохранить display_name/unique_family отдельного начертания —
    // поддерживаем и такой вариант для обратной совместимости.
    const selected =
        fontMap.find(f => f.family === rawName) ||
        fontMap.find(f => f.display_name === rawName) ||
        fontMap.find(f => f.unique_family === rawName);

    if (!selected) return null;

    const family = selected.family || rawName;
    const entries = fontMap.filter(f => (f.family || '') === family);
    return { family, selected, entries: entries.length ? entries : [selected] };
}

/**
 * Загружает ВСЕ файлы выбранного семейства через FontFace API.
 *
 * Важно: Regular.ttf и Bold.ttf — это не два разных CSS-семейства. Это одно
 * семейство с разными font-weight/font-style. Поэтому все отдельные файлы
 * одного family регистрируются под одним уникальным именем, а weight/style
 * остаются разными. Благодаря этому <b>/<strong> и заголовки книги получают
 * настоящий Bold-файл, если он существует.
 */
async function _loadFamily(family, entries, log) {
    const uniqueFamily = UNIQUE_PREFIX + family;
    if (_loadedFaces.has(uniqueFamily)) return uniqueFamily;

    // Если один и тот же weight/style почему-то описан несколькими файлами,
    // берём первый — иначе FontFace API получает конкурирующие лица.
    const seenVariants = new Set();
    const uniqueEntries = entries.filter(info => {
        const weight = info.variable
            ? `${info.wght_min ?? 400} ${info.wght_max ?? 400}`
            : String(info.weight ?? 400);
        const style = info.style || 'normal';
        const key = `${weight}|${style}`;
        if (seenVariants.has(key)) return false;
        seenVariants.add(key);
        return true;
    });

    const results = await Promise.allSettled(uniqueEntries.map(async (info) => {
        const weight = info.variable
            ? `${info.wght_min ?? 400} ${info.wght_max ?? 400}`
            : String(info.weight ?? 400);
        const style = info.style || 'normal';
        const src = info.file_url || ((window.themeSettings?.fontsBaseUrl || '') + info.file);

        const face = new FontFace(uniqueFamily, `url("${src}")`, { style, weight });
        await face.load();
        document.fonts.add(face);
        return face;
    }));

    const loadedFaces = [];
    results.forEach((r, i) => {
        if (r.status === 'fulfilled') {
            loadedFaces.push(r.value);
        } else {
            (log || console.warn)(
                ` Шрифт: не удалось загрузить начертание "${uniqueEntries[i].file}" ` +
                `семейства "${uniqueFamily}": ${r.reason?.message ?? r.reason}`,
                'warn'
            );
        }
    });

    if (loadedFaces.length === 0) return null;
    _loadedFaces.set(uniqueFamily, loadedFaces);
    return uniqueFamily;
}

/**
 * Регистрирует уже загруженные FontFace всех известных семейств в
 * document.fonts переданного document (новый iframe секции). Вызывается
 * из reader.html при каждом view 'load' событии — до пересчёта пагинации.
 */
window.__registerFontsInDoc = function (doc) {
    if (!doc?.fonts) return;
    for (const faces of _loadedFaces.values()) {
        for (const face of faces) {
            try { doc.fonts.add(face); } catch (e) { /* дубликат/недоступно — не критично */ }
        }
    }
};

/**
 * Сохраняет текущую позицию чтения перед перерендером (CFI).
 */
function _capturePosition(view) {
    try {
        return view?.lastLocation?.cfi ?? null;
    } catch (e) {
        return null;
    }
}

/**
 * Восстанавливает позицию чтения после перерендера.
 */
async function _restorePosition(view, cfi) {
    if (!cfi || !view?.goTo) return;
    try {
        await view.goTo(cfi);
    } catch (e) {
        console.warn(' Шрифт: не удалось восстановить позицию после смены шрифта:', e);
    }
}

/**
 * Показывает спиннер «Загрузка...» на время перепагинации.
 */
function _showFontSpinner() {
    if (typeof window.showReturnOverlay === 'function') {
        window.showReturnOverlay('Применяем шрифт…', '');
        return;
    }
    document.getElementById('fontApplySpinnerEl')?.remove();
    const div = document.createElement('div');
    div.id = 'fontApplySpinnerEl';
    div.style.cssText = [
        'position:fixed;inset:0;z-index:5000;',
        'background:var(--bg-color,#f4ecd8);',
        'display:flex;align-items:center;justify-content:center;',
    ].join('');
    div.innerHTML = `
    <div style="width:40px;height:40px;border:3px solid color-mix(in srgb, var(--text-color, #888) 18%, transparent);
    border-top-color:var(--spinner-color, var(--text-color, #888));border-radius:50%;
    animation:spin 0.8s linear infinite;"></div>`;
    document.getElementById('app')?.appendChild(div);
}

function _hideFontSpinner() {
    if (typeof window.dismissReturnOverlay === 'function') {
        window.dismissReturnOverlay();
        return;
    }
    document.getElementById('fontApplySpinnerEl')?.remove();
}

/**
 * Главная функция: применяет пользовательский шрифт к текущей книге.
 *
 * @param {object} view - foliate-js view (window.view в reader.html)
 * @param {Array} fontMap - settings.font_faces из Python
 * @param {string} selectedDisplayName - reader_font_family из настроек
 * @param {function} [applyCssFn] - колбэк (family, weight, style) — применяет
 *        начертание к теме и вызывает applyTheme().
 * @param {function} [log]
 */
export async function applyReaderFont(view, fontMap, selectedDisplayName, applyCssFn, log) {
    const resolved = _resolveFamily(fontMap, selectedDisplayName);
    if (!resolved) {
        (log || console.warn)(` Шрифт: запись для "${selectedDisplayName}" не найдена в fontMap`, 'warn');
        return false;
    }
    const { family, selected, entries } = resolved;
    // Все отдельные файлы одного family регистрируются как одно CSS-семейство
    // с разными weight/style: Regular 400, Bold 700, Italic 400 italic и т.д.
    const uniqueFamily = UNIQUE_PREFIX + family;

    if (!_loadedFaces.has(uniqueFamily)) {
        const loaded = await _loadFamily(family, entries, log);
        if (!loaded) {
            (log || console.warn)(` Шрифт: не удалось загрузить "${family}", остаёмся на прежнем шрифте`, 'warn');
            return false;
        }
    }

    // Регистрируем сразу и в текущем открытом iframe.
    try {
        const currentDoc = view?.renderer?.getContents?.()?.[0]?.doc;
        if (currentDoc) window.__registerFontsInDoc(currentDoc);
    } catch (e) { /* не критично */ }

    _showFontSpinner();
    const savedCfi = _capturePosition(view);

    try {
        if (typeof applyCssFn === 'function') {
            // Передаём УНИКАЛЬНОЕ имя + weight/style выбранного начертания.
            // Например:
            //   «Literata Bold»        → unique='__NovaReader_Literata 700', w=700, s=normal
            //   «Literata Italic»      → unique='__NovaReader_Literata Italic', w=400, s=italic
            //   «Literata»             → unique='__NovaReader_Literata', w=400, s=normal
            const chosenWeight = selected?.weight ?? 400;
            const chosenStyle  = selected?.style  ?? 'normal';
            applyCssFn(uniqueFamily, chosenWeight, chosenStyle);
        }
        if (savedCfi) {
            await _restorePosition(view, savedCfi);
        }
    } finally {
        setTimeout(_hideFontSpinner, 2200);
    }

    return true;
}
