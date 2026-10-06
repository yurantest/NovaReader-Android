// theme-css.js
// Генерация CSS темы читалки (цвета, размеры шрифта) и фильтр рекламных
// секций пиратских сайтов из содержимого книги. Вынесено из reader.html —
// единственные внешние зависимости (themeSettings, log) передаются явно,
// поэтому модуль не завязан на остальное состояние читалки.
//
// ПРИМЕЧАНИЕ: @font-face здесь больше НЕ генерируется. Пользовательские
// шрифты загружаются через FontFace API в fonts.js и регистрируются
// напрямую в document.fonts каждого iframe секции (см.
// window.__registerFontsInDoc в fonts.js и его вызов в reader.html на
// событии view 'load'). Эта CSS-строка лишь ссылается на font-family по
// имени — сам шрифт к этому моменту уже должен быть загружен и
// зарегистрирован в document.fonts текущего iframe.
//
// ── ПРИОРИТЕТ НАСТРОЕК ЧИТАЛКИ НАД СТИЛЯМИ КНИГИ ───────────────────────────
//   * font-family применяется через многократно усиленный селектор с
//     !important. Каждое повторение 'html' в цепочке добавляет +1 к
//     специфичности, что позволяет перебить book CSS-правила с высокой
//     специфичностью (напр. 'p.chapter.deep' при равных !important).
//   * Свой шрифт зарегистрирован под УНИКАЛЬНЫМ именем
//     '__NovaReader_<family>' (см. fonts.js). Книга не может подделать это
//     имя через свой @font-face, поэтому даже при совпадении исходных имён
//     ('Literata' / 'PT Serif') браузер возьмёт именно пользовательский
//     шрифт, а не книжный.
//   * fontWeight/fontStyle применяются ко ВСЕМ текстовым элементам — чтобы
//     выбранное пользователем начертание (Regular / Bold / Italic /
//     Bold Italic) отражалось во всём тексте книги. Исключение — <b>/<i>
//     и заголовки: они «возвращаются» к bold/italic отдельными правилами
//     с !important, чтобы сохранить смысловое выделение внутри текста.
//   * color:inherit + background-color:transparent на *, а конечные цвета
//     на html/body — книга не может перекрасить отдельные абзацы.
//   * background-image:none на типовых блочных элементах — убирает
//     книжные текстуры бумаги и декоративные фоны.
//   * Инлайн `style="font-family: X !important"` НЕ перебивается CSS в
//     принципе (спецификация CSS Cascade: инлайн-!important > любой
//     <style>-!important). Для этого случая в reader.html на событии
//     view 'load' есть JS-очистка инлайн-стилей font-family/color/background.
//
// ── ОТКЛЮЧЕНИЕ OpenType-ФИЧИ 'locl' (Localized Forms) ─────────────────────
// Chromium при рендеринге кириллицы учитывает атрибут `lang` документа и
// включает фичу `locl` в шрифте. Это заставляет болгарский текст
// отображаться «новым» стилем (т→m, д→g, и→u, я→зеркальная R), сербский —
// своим набором форм, македонский — своим. На ПК (QWebEngine) атрибут lang
// читается из DOM секции, на Android (WebView) — теряется при загрузке
// через FontFace API, из-за чего один и тот же шрифт рендерится по-разному.
// Чтобы обе платформы показывали одинаково (базовый, «старый» кириллический
// набор глифов), фича 'locl' отключается глобально.

/**
 * Генерирует CSS темы (цвета, размеры, font-family) на основе объекта
 * настроек темы.
 * @param {object} themeSettings - { bg, text, fontSize, lineHeight, fontFamily,
 *                                   fontWeight, fontStyle }
 */
export function getThemeCSS(themeSettings) {
    // fontFamily приходит как УНИКАЛЬНОЕ имя '__NovaReader_<family>'
    // из fonts.js (applyReaderFont → applyCssFn(uniqueFamily)).
    const font = (themeSettings.fontFamily || '').trim();
    const bg   = themeSettings.bg   || '#f4ecd8';
    const text = themeSettings.text || '#5b4636';
    const fontSize   = themeSettings.fontSize   || 16;
    const lineHeight = themeSettings.lineHeight || 1.5;
    // fontWeight/fontStyle — какие настройки из TTF соответствуют выбранному
    // пользователем начертанию. Если он выбрал «Literata Bold» — 700 normal.
    // Если «Literata Italic» — 400 italic. Если «Literata» — 400 normal.
    const fontWeight = themeSettings.fontWeight ?? 400;
    const fontStyle  = themeSettings.fontStyle  || 'normal';

    // Правило шрифта — только если он реально выбран. Селектор многократно
    // усилен повторением 'html', чтобы перебить book CSS с высокой
    // специфичностью при одинаковом !important.
    //
    // font-weight и font-style задаются ЗДЕСЬ ЖЕ, в том же правиле — чтобы
    // выбранное пользователем начертание применялось ко ВСЕМУ тексту книги
    // (включая <p>, <div>, <span> и т.д.). Иначе браузер рендерил бы Regular
    // этого семейства, даже если пользователь выбрал Bold/Italic.
    const fontRule = font ? `
    /* Усиленный селектор: 6×html + body / body * / ::before / ::after.
     *          Специфичность ~(0,0,7) — перебивает book-селекторы вроде
     *          'p.chapter.deep' (0,1,2) даже когда у обоих !important.
     *          Отдельно — html/body и короткие формы для универсальности. */
    html html html html html html body,
    html html html html html html body *,
    html html html html html html body *::before,
    html html html html html html body *::after,
    html, body,
    html *, body *,
    html *::before, html *::after,
    *, *::before, *::after {
        font-family: '${font}', Georgia, serif !important;
        font-weight: ${fontWeight} !important;
        font-style:  ${fontStyle}  !important;
    }

    /* Возвращаем семантическим тегам их смысл: если пользователь выбрал
     *      Bold-начертание как основной шрифт, то <b> внутри текста не должен
     *      «пропадать» — но он и так Bold, так что визуально разницы не будет.
     *      Если пользователь выбрал Italic-начертание, то <i> тоже не должен
     *      «пропадать» — а <b> должен стать Bold+Italic (браузер сам подберёт
     *      BoldItalic-начертание, если оно зарегистрировано). */
    b, strong, h1, h2, h3, h4, h5, h6, th, caption {
        font-weight: bold !important;
    }
    em, i, cite, var, dfn {
        font-style: italic !important;
    }
    ` : '';

    return `
    /* ══════════════════════════════════════════════════════════════
     *          ОТКЛЮЧАЕМ OpenType-фичу 'locl' (Localized Forms).
     *          Без этого Chromium применяет болгарские/сербские/македонские
     *          альтернативные формы букв (т→m, д→g, и→u) — «новый» стиль.
     *          С "locl" 0 везде используется базовый (старый) кириллический
     *          набор глифов, одинаковый на ПК и Android.
     *
     *          Если вдруг латиница начнёт выглядеть странно — замените
     *          универсальный селектор на: :lang(bg) *, :lang(sr) *, :lang(mk) *
     *          ══════════════════════════════════════════════════════════════ */
    *, *::before, *::after {
        font-feature-settings: "locl" 0 !important;
        font-variant-east-asian: normal !important;
    }

    /* ══════════════════════════════════════════════════════════════
     *          БАЗОВЫЕ ЦВЕТА (перебивают book CSS)
     *          ══════════════════════════════════════════════════════════════ */
    html, body {
        color: ${text} !important;
        background: ${bg} !important;
        margin: 0 !important;
        padding: 0 !important;
        -webkit-font-smoothing: antialiased !important;
        -moz-osx-font-smoothing: grayscale !important;
        text-rendering: optimizeLegibility !important;
    }

    /* ══════════════════════════════════════════════════════════════
     *          ЦВЕТ ТЕКСТА, ФОН И РАМКИ НА ВСЕХ ЭЛЕМЕНТАХ
     *          ══════════════════════════════════════════════════════════════ */
    *, *::before, *::after {
        color: inherit !important;
        background-color: transparent !important;
        border: none !important;
        outline: none !important;
        box-shadow: none !important;
    }

    html, body {
        color: ${text} !important;
        background: ${bg} !important;
    }

    a, a:visited, a:active, a:hover {
        color: ${text} !important;
        text-decoration: underline !important;
    }

    /* ══════════════════════════════════════════════════════════════
     *          УБИРАЕМ КНИЖНЫЕ ФОНОВЫЕ КАРТИНКИ (текстура бумаги, декор)
     *          ══════════════════════════════════════════════════════════════ */
    img, image {
        background: transparent !important;
    }
    html, body, p, div, section, article, blockquote,
    figure, figcaption, aside, header, footer, main, nav {
        background-image: none !important;
    }

    /* ══════════════════════════════════════════════════════════════
     *          ШРИФТ (только если выбран в настройках читалки).
     *          Селектор усилен — перебивает книжные правила с высокой
     *          специфичностью при равных !important.
     *          font-weight и font-style применяются ко ВСЕМ элементам
     *          (не только к body) — так выбранное пользователем
     *          начертание рендерится во всём тексте.
     *          ══════════════════════════════════════════════════════════════ */
    ${fontRule}

    /* ══════════════════════════════════════════════════════════════
     *          РАЗМЕР И ИНТЕРВАЛ
     *          ══════════════════════════════════════════════════════════════ */
    html, body {
        font-size: ${fontSize}px !important;
    }
    p, li, dd, dt, blockquote, div, td, th, pre, code,
    span, em, i, b, strong, u, s, small, big, sup, sub,
    article, section, aside, header, footer, main, nav,
    figure, figcaption, table, caption, label {
        font-size: ${fontSize}px !important;
        line-height: ${lineHeight} !important;
    }

    /* ══════════════════════════════════════════════════════════════
     *          ЗАГОЛОВКИ
     *          ══════════════════════════════════════════════════════════════ */
    h1, h2, h3, h4, h5, h6 {
        color: ${text} !important;
        text-align: center !important;
        background: transparent !important;
        margin: 1em 0 !important;
        padding: 0 !important;
        border: none !important;
    }

    /* ══════════════════════════════════════════════════════════════
     *          КАРТИНКИ
     *          ══════════════════════════════════════════════════════════════ */
    img {
        max-width: 100%;
        height: auto;
    }

    /* ══════════════════════════════════════════════════════════════
     *          СКРЫТИЕ РЕКЛАМНЫХ СЕКЦИЙ (заготовка — реально применяется
     *          через JS в hideAdSections/hideFakeTOCLists)
     *          ══════════════════════════════════════════════════════════════ */
    section:has(> h1:first-child),
    section:has(> h2:first-child),
    section:has(> h3:first-child),
    div:has(> h1:first-child),
    div:has(> h2:first-child) {
        /* Применяется только через JS ниже — здесь заготовка */
    }
    `;
}

/**
 * Вставляет заглушку "Конец книги" вместо скрытой рекламной секции.
 * @param {Document} doc - документ секции (iframe)
 * @param {string} [fontFamily] - шрифт для заглушки (опционально)
 */
export function injectEndOfBookMsg(doc, fontFamily) {
    if (doc.getElementById('nova-end-of-book')) return;
    const lang = doc.documentElement.lang ||
    navigator.language || 'ru';
    const msg  = lang.startsWith('en') ? 'End of book' : 'Конец книги';
    const div  = doc.createElement('div');
    div.id = 'nova-end-of-book';
    const _fontFamily = fontFamily ? `'${fontFamily}', Georgia, serif` : 'inherit';
    div.style.cssText = [
        'display:flex', 'align-items:center', 'justify-content:center',
        'height:80vh', 'width:100%',
        `font-family:${_fontFamily}`,
        'font-size:1.6em', 'opacity:0.45',
        'font-style:italic', 'letter-spacing:0.05em',
        'color:var(--text-color, inherit)',
        'pointer-events:none', 'user-select:none',
    ].join(';');
    div.textContent = '— ' + msg + ' —';
    Array.from(doc.body.children).forEach(el => { el.style.display = 'none'; });
    doc.body.appendChild(div);
}

/**
 * Скрывает встроенные "технические" списки оглавления.
 * @param {Document} doc - документ секции (iframe)
 * @param {(msg: string, type?: string) => void} [log] - логгер (опционально)
 */
export function hideFakeTOCLists(doc, log) {
    if (!doc?.body) return;
    const _log = log || (() => {});
    try {
        const lists = doc.querySelectorAll('ul, ol');
        for (const list of lists) {
            if (list.dataset.novaFakeTocChecked) continue;
            list.dataset.novaFakeTocChecked = '1';
            const items = Array.from(list.children).filter(c => c.tagName === 'LI');
            if (items.length < 8) continue;
            let linkOnlyCount = 0;
            for (const li of items) {
                const links = li.querySelectorAll('a[href]');
                const text = (li.textContent || '').trim();
                const linkText = links.length === 1 ? (links[0].textContent || '').trim() : '';
                if (links.length === 1 && text.length > 0 &&
                    text.length <= linkText.length + 6) {
                    linkOnlyCount++;
                    }
            }
            if (linkOnlyCount / items.length >= 0.85) {
                list.style.display = 'none';
                _log(`[FakeTOC] Скрыт технический список оглавления (${items.length} пунктов)`, 'info');
                const parent = list.parentElement;
                if (parent && parent.children.length <= 2) {
                    const heading = parent.querySelector('h1, h2, h3, strong');
                    const headingText = (heading?.textContent || '').toLowerCase().trim();
                    if (/содержан|оглавлен|table of contents|^contents:?$/.test(headingText)) {
                        heading.style.display = 'none';
                    }
                }
            }
        }
    } catch (e) {
        _log(`[FakeTOC] Ошибка: ${e.message}`, 'error');
    }
}

/**
 * Скрывает рекламные секции пиратских сайтов в загруженном документе.
 * @param {Document} doc - документ секции (iframe)
 * @param {string} [fontFamily] - шрифт для заглушки "Конец книги" (опционально)
 * @param {(msg: string, type?: string) => void} [log] - логгер (опционально)
 */
export function hideAdSections(doc, fontFamily, log) {
    if (!doc?.body) return;
    const _log = log || (() => {});

    const HIDDEN_TITLES = [
        'nota bene', 'note bene', 'notabene',
        'от пирата', 'от пиратов', 'реклама',
    ];

    const HIDDEN_CONTENT = [
        'searchfloor.org',
        'цокольным этажом',
        'цокольный этаж',
        'книга предоставлена',
        'сайт заблокирован в России',
        'наградите автора лайком',
        'telegram-бот',
        'антизапрет',
        'censor tracker',
    ];

    function hideContainer(el, reason) {
        let parent = el.parentElement;
        while (parent && parent !== doc.body) {
            const tag = parent.tagName?.toLowerCase();
            if (tag === 'section' || tag === 'div' || tag === 'article') {
                parent.style.display = 'none';
                _log(`[AdFilter] Скрыта секция по: "${reason}"`, 'info');
                return true;
            }
            parent = parent.parentElement;
        }
        let sibling = el;
        while (sibling) {
            sibling.style.display = 'none';
            sibling = sibling.nextElementSibling;
        }
        return true;
    }

    const headings = doc.querySelectorAll('h1,h2,h3,h4,h5,h6,p.title,title');
    let wholeDocHidden = false;
    headings.forEach(h => {
        const text = h.textContent?.trim().toLowerCase() ?? '';
        if (HIDDEN_TITLES.some(s => text === s || text.startsWith(s))) {
            hideContainer(h, h.textContent?.trim());
            wholeDocHidden = true;
        }
    });
    if (wholeDocHidden) { injectEndOfBookMsg(doc, fontFamily); return; }

    const STRONG_MATCH = ['searchfloor.org', 'цокольным этажом', 'цокольный этаж'];
    const paragraphs = doc.querySelectorAll('p, div');
    paragraphs.forEach(p => {
        if (p.style.display === 'none') return;
        const text = p.textContent?.toLowerCase() ?? '';
        if (STRONG_MATCH.some(s => text.includes(s))) {
            hideContainer(p, text.substring(0, 40));
            injectEndOfBookMsg(doc, fontFamily);
            return;
        }
        const matches = HIDDEN_CONTENT.filter(s => text.includes(s));
        if (matches.length >= 2) {
            hideContainer(p, matches.join(', '));
            injectEndOfBookMsg(doc, fontFamily);
        }
    });
}

/**
 * Вычисляет относительную яркость hex-цвета (0 = чёрный, 1 = белый).
 * Использует формулу W3C WCAG 2.0.
 */
export function bgLuminance(hex) {
    try {
        const h = hex.replace('#', '');
        const r = parseInt(h.substring(0, 2), 16) / 255;
        const g = parseInt(h.substring(2, 4), 16) / 255;
        const b = parseInt(h.substring(4, 6), 16) / 255;
        const toLinear = c => c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
        return 0.2126 * toLinear(r) + 0.7152 * toLinear(g) + 0.0722 * toLinear(b);
    } catch(e) { return 0.5; }
}
