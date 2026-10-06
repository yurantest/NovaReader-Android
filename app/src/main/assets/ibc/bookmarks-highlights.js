// bookmarks-highlights.js
// Закладки и текстовые подсветки (highlights) + заметки к ним: загрузка,
// рендер, добавление/удаление, панель со списком, диалог заметки.
//
// Модуль хранит СВОЁ состояние (bookmarks[], note-dialog state, filter),
// но не имеет прямого доступа к живым переменным reader.html (bridge, view,
// currentSection, currentBookPath, overlayer) — они там регулярно
// переприсваиваются (bridge при подключении WebChannel, view при инициализации,
// currentSection при каждой навигации), а ES-модули не позволяют
// переприсваивать чужой импортированный `let` напрямую.
// Поэтому все такие значения передаются через геттеры (() => currentValue),
// вызываемые в момент использования — модуль всегда видит актуальное
// значение без собственной устаревающей копии.
//
// createUserHighlight() и вся обработка live-выделения (currentRange,
// currentRangeDoc, currentSelection, initSelectionHandlers) НЕ включены сюда
// намеренно — это состояние принадлежит обработчику выделения текста в
// reader.html и слишком тесно связано с UI тулбара выделения, чтобы
// разделять безопасно в этом проходе.

let _deps = null;

/**
 * Инициализирует модуль. Вызывается один раз из reader.html после того,
 * как определены bridge/view/log/getOverlayer и т.д.
 * @param {object} deps
 * @param {() => object|null} deps.getBridge
 * @param {() => object|null} deps.getView
 * @param {() => number} deps.getCurrentSection
 * @param {() => string|null} deps.getCurrentBookPath
 * @param {() => object|null} deps.getOverlayer
 * @param {() => object|null} [deps.getBook]
 * @param {(msg: string, type?: string) => void} deps.log
 * @param {object} deps.HIGHLIGHT_COLORS - { colorName: '#hex' }
 * @param {object} deps.Overlayer - класс Overlayer из overlayer.js (highlight/underline/squiggly)
 * @param {object} deps.userHighlightMap - {pythonId: overlayerId}, живой объект (мутируется по ссылке — ок, объекты не переприсваиваются)
 * @param {(doc: Document, text: string) => Range|null} deps.findTextRange
 */
export function initBookmarksHighlights(deps) {
    _deps = deps;
}

// ── Внутреннее состояние модуля ─────────────────────────────────────────
let bookmarks = []; // кэш закладок текущей книги

// ── Цвета выделений (для панели заметок/подсветок) ───────────────────────
const HL_COLOR_MAP = {
    red: '#f28b82', violet: '#d7aefb', blue: '#aecbfa',
    green: '#ccff90', yellow: '#fdd663', cyan: '#80deea',
    default: '#aecbfa'
};

// ── Состояние диалога заметки ─────────────────────────────────────────
let _noteDialogHighlightId = null;
let _noteDialogNoteId      = null;  // null = новая заметка, string = редактирование
let _hlFilterActive = 'all';  // 'all' | 'highlight' | 'notes'

function _escHtml(s) {
    return (s || '').replace(/&/g,'&amp;').replace(/</g,'&lt;')
    .replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}
function _escAttr(s) {
    return (s || '').replace(/"/g,'&quot;').replace(/'/g,'&#39;');
}

/**
 * Показывает всплывающее уведомление внизу экрана на 2 секунды.
 * Генерическая утилита без внешних зависимостей — используется здесь
 * (addBookmarkWithText), но экспортирована на случай если reader.html
 * тоже захочет её использовать напрямую.
 */
export function showToast(message) {
    document.querySelector('.toast-notification')?.remove();

    const toast = document.createElement('div');
    toast.className = 'toast-notification';
    toast.textContent = message;
    toast.style.cssText = `
        position: fixed;
        bottom: 80px;
        left: 50%;
        transform: translateX(-50%);
        background: rgba(32, 33, 36, 0.95);
        color: white;
        padding: 12px 24px;
        border-radius: 8px;
        font-size: 14px;
        z-index: 10000;
        box-shadow: 0 4px 16px rgba(0,0,0,0.3);
        animation: fadeIn 0.3s ease;
    `;

    document.body.appendChild(toast);
    setTimeout(() => {
        toast.style.opacity = '0';
        toast.style.transition = 'opacity 0.3s ease';
        setTimeout(() => toast.remove(), 300);
    }, 2000);
}

// ══════════════════════════ ЗАКЛАДКИ ══════════════════════════════════

export function loadBookmarks() {
    const bridge = _deps.getBridge();
    if (!bridge) return;
    bridge.getBookmarks(json => {
        try {
            bookmarks = JSON.parse(json) || [];
            let migrated = false;
            bookmarks.forEach(bm => {
                if (bm.position?.cfi) {
                    // CFI уже есть, ничего не делаем
                } else if (bm.position?.fraction) {
                    bm.position.cfi = null;
                }
                if (bm.position?.text) {
                    delete bm.position.text;
                }
            });
            if (migrated) {
                _deps.log(' Миграция закладок: text удалён, fraction сохранён', 'info');
            }
            renderBookmarks();
            updateBookmarkButton();
        } catch(e) {}
    });
}

export function renderBookmarks() {
    const list = document.getElementById('bookmarksList');
    if (!list) return;
    if (!bookmarks.length) {
        list.innerHTML = '<div style="padding:16px;color:#888;text-align:center;">Нет закладок</div>';
        return;
    }
    list.innerHTML = '';
    bookmarks.forEach(bm => {
        const div = document.createElement('div');
        div.className = 'bookmark-item';
        const isHighlight = bm.label?.startsWith('');
        div.innerHTML = `
            <span class="material-icons" style="color:${isHighlight ? '#81c995' : '#d4a800'};font-size:18px;">${isHighlight ? 'border_color' : 'bookmark'}</span>
            <div style="flex:1;min-width:0">
                <div class="bm-label">${bm.label}</div>
                <div class="bm-progress">${Math.round((bm.progress||0)*100)}%</div>
            </div>
            <button class="bm-del" data-id="${bm.id}" title="Удалить">
                <span class="material-icons" style="font-size:18px;">delete_outline</span>
            </button>
        `;
        div.querySelector('.bm-del').addEventListener('click', e => {
            e.stopPropagation();
            const bridge = _deps.getBridge();
            if (bridge) {
                bridge.removeBookmark(bm.id);
                bookmarks = bookmarks.filter(b => b.id !== bm.id);
                renderBookmarks();
                updateBookmarkButton();
            }
        });
        div.addEventListener('click', async () => {
            const view = _deps.getView();
            try {
                const bmSection = bm.position?.section;
                const bmCfi = bm.position?.cfi;
                const bmFraction = bm.position?.fraction;

                if (bmSection !== undefined) {
                    _deps.log(` Переход по закладке: ${bm.label}`, 'info');
                    if (bmCfi && view) {
                        _deps.log(`   переход по CFI`, 'info');
                        await view.goTo(bmCfi);
                    } else if (bmFraction !== undefined && view) {
                        _deps.log(`   переход по fraction ${bmFraction}`, 'info');
                        await view.goToFraction(bmFraction);
                    } else {
                        await view.goTo(bmSection);
                    }
                    setTimeout(() => {
                        loadHighlights();
                        _deps.log(' Подсветки загружены после перехода', 'info');
                    }, 500);
                }
            } catch(e) {
                _deps.log(' Ошибка перехода: ' + e.message, 'error');
            }
            setTimeout(() => {
                document.getElementById('bookmarksPanel').classList.remove('visible');
                document.getElementById('overlay').classList.remove('visible');
            }, 100);
        });
        list.appendChild(div);
    });
}

export function toggleBookmark() {
    const view = _deps.getView();
    const bridge = _deps.getBridge();
    const currentPosition0 = 0;
    let currentPosition = currentPosition0;

    try {
        if (view?.lastLocation) {
            currentPosition = view.lastLocation.index || 0;
        }
    } catch(e) {}

    const existing = bookmarks.find(b => b.position?.position === currentPosition);

    if (existing) {
        if (bridge) bridge.removeBookmark(existing.id);
        bookmarks = bookmarks.filter(b => b.id !== existing.id);
        renderBookmarks();
    } else {
        addCurrentBookmark();
    }
    updateBookmarkButton();
}

export function addCurrentBookmark() {
    const view = _deps.getView();
    const bridge = _deps.getBridge();
    const section = _deps.getCurrentSection();

    let cfi = null;
    let fraction = 0;

    try {
        if (view?.lastLocation) {
            cfi = view.lastLocation.cfi || null;
            fraction = view.lastLocation.fraction || 0;
        }
    } catch(e) {
        _deps.log(' Не удалось получить CFI: ' + e.message, 'warn');
    }

    const label = `Позиция ${Math.round(fraction * 100) + 1}`;
    const pos = JSON.stringify({ section, cfi, fraction });

    const book = _deps.getBook ? _deps.getBook() : null;
    const totalSections = book?.sections?.length || 1;
    const progress = totalSections > 1 ? section / (totalSections - 1) : 0;

    if (bridge) {
        bridge.saveBookmark(label, Math.max(0, Math.min(1, progress)), pos);
        setTimeout(() => {
            loadBookmarks();
            updateBookmarkButton();
        }, 150);
        _deps.log(` Закладка: секция=${section}, cfi=${cfi ? 'да' : 'нет'}`, 'info');
    }
}

export function addBookmarkWithText(text, range) {
    const view = _deps.getView();
    const bridge = _deps.getBridge();
    const section = _deps.getCurrentSection();

    let cfi = null;
    let fraction = 0;

    try {
        if (range && view) {
            cfi = view.getCFI(section, range);
        }
        if (view?.lastLocation) {
            fraction = view.lastLocation.fraction || 0;
        }
    } catch(e) {
        _deps.log(' Не удалось получить CFI: ' + e.message, 'warn');
    }

    const textPreview = text.substring(0, 50).replace(/\s+/g, ' ').trim();
    const label = textPreview.length >= 50 ? textPreview + '...' : textPreview;
    const pos = JSON.stringify({ section, cfi, fraction, text });

    const book = _deps.getBook ? _deps.getBook() : null;
    const totalSections = book?.sections?.length || 1;
    const progress = totalSections > 1 ? section / (totalSections - 1) : 0;

    if (bridge) {
        bridge.saveBookmark(label, Math.max(0, Math.min(1, progress)), pos);
        setTimeout(() => {
            loadBookmarks();
            updateBookmarkButton();
        }, 150);
        _deps.log(` Закладка с текстом: "${label}"`, 'bookmark');
        showToast(` Закладка добавлена: "${label}"`);
    }
}

export function updateBookmarkButton() {
    const btn = document.getElementById('bookmarkBtn');
    if (!btn) return;
    const currentSection = _deps.getCurrentSection();
    const has = bookmarks.some(b => b.position?.section === currentSection);
    btn.innerHTML = has
        ? '<span class="material-icons bookmark-active">bookmark</span>'
        : '<span class="material-icons">bookmark_border</span>';
}

// ══════════════════════════ ПОДСВЕТКИ ══════════════════════════════════

export function loadHighlights() {
    const bridge = _deps.getBridge();
    const view = _deps.getView();
    if (!bridge || !view) return;

    bridge.getHighlights(function(result) {
        try {
            const highlights = JSON.parse(result) || [];
            if (!highlights.length) {
                _deps.log(`ℹ Нет сохранённых подсветок`, 'highlight');
                return;
            }
            window.savedHighlights = highlights;
            _deps.log(` Загружено ${highlights.length} подсветок`, 'highlight');
            applyHighlightsForCurrentSection();
        } catch (e) {
            _deps.log(` Ошибка загрузки подсветок: ${e.message}`, 'error');
        }
    });
}

export function applyHighlightsForCurrentSection() {
    const view = _deps.getView();
    if (!window.savedHighlights || !view) return;

    const ov = _deps.getOverlayer();
    if (!ov) {
        _deps.log(' Overlayer недоступен', 'error');
        return;
    }
    const currentSection = _deps.getCurrentSection();

    window.savedHighlights.forEach(h => {
        try { ov.remove(h.id); } catch(e) {}
    });

    let applied = 0;
    for (const h of window.savedHighlights) {
        try {
            const highlightData = JSON.parse(h.cfi || '{}');
            const section = highlightData.section;

            if (section === currentSection) {
                const text = h.text;

                if (text && view.renderer) {
                    const doc = view.renderer.getContents()[0]?.doc;
                    if (doc) {
                        const range = _deps.findTextRange(doc, text);
                        if (range) {
                            const hexColor = _deps.HIGHLIGHT_COLORS[h.color] || '#aecbfa';
                            const overlayerId = h.id || ('user-' + Date.now());

                            const style = h.style || 'highlight';
                            const styleMethod = {
                                'highlight': _deps.Overlayer.highlight,
                                'underline': _deps.Overlayer.underline,
                                'squiggly': _deps.Overlayer.squiggly,
                            }[style] || _deps.Overlayer.highlight;

                            ov.add(overlayerId, range, styleMethod, { color: hexColor + 'b8' });
                            _deps.userHighlightMap[h.id] = overlayerId;
                            applied++;
                            _deps.log(` Подсветка применена: "${text.substring(0, 30)}..."`, 'highlight');
                        } else {
                            _deps.log(` Текст не найден в секции ${section}`, 'warn');
                        }
                    }
                }
            }
        } catch (e) {
            _deps.log(` Не удалось применить одсветку: ${e.message}`, 'warn');
        }
    }

    if (applied > 0) {
        _deps.log(` Применено ${applied} подсветок в текущей секции`, 'highlight');
    }
}

/**
 * @param {string} currentSelection - выделенный пользователем текст
 * @param {function(object|null)} callback - {id, highlight} или null
 */
export function findHighlightForCurrentSelection(currentSelection, callback) {
    const bridge = _deps.getBridge();
    const currentBookPath = _deps.getCurrentBookPath();
    if (!currentSelection || !bridge || !currentBookPath) {
        callback(null);
        return;
    }

    try {
        bridge.getHighlights(function(result) {
            try {
                const highlights = JSON.parse(result) || [];
                const searchText = currentSelection.trim().replace(/\s+/g, ' ');

                if (!searchText) {
                    callback(null);
                    return;
                }

                for (const h of highlights) {
                    const highlightText = (h.text || '').trim().replace(/\s+/g, ' ');
                    if (highlightText === searchText) {
                        _deps.log(` Найдена подсветка (точное совпадение): "${searchText}"`, 'highlight');
                        callback({ id: h.id, highlight: h });
                        return;
                    }
                }

                for (const h of highlights) {
                    const highlightText = (h.text || '').trim().replace(/\s+/g, ' ');
                    if (highlightText && searchText &&
                        (highlightText.includes(searchText) || searchText.includes(highlightText))) {
                        _deps.log(` Найдео частичное совпадение: "${searchText}" ↔ "${highlightText}"`, 'highlight');
                        callback({ id: h.id, highlight: h });
                        return;
                    }
                }

                callback(null);
            } catch(e) {
                _deps.log(` Ошибка парсинга подсветок: ${e.message}`, 'error');
                callback(null);
            }
        });
    } catch (e) {
        _deps.log(` Ошибка поиска подсветки: ${e.message}`, 'error');
        callback(null);
    }
}

/** @param {string} currentSelection */
export function updateHighlightButtonIcon(currentSelection) {
    const highlightBtn = document.getElementById('highlightBtn');
    if (!highlightBtn) return;

    const iconSpan = highlightBtn.querySelector('span.material-icons');

    findHighlightForCurrentSelection(currentSelection, function(existingHighlight) {
        if (existingHighlight) {
            iconSpan.textContent = 'delete';
            highlightBtn.title = 'Удалить подветку';
        } else {
            iconSpan.textContent = 'border_color';
            highlightBtn.title = 'Выделить';
        }
    });
}

/** @param {string} currentSelection */
export function deleteCurrentHighlight(currentSelection) {
    const bridge = _deps.getBridge();
    const currentBookPath = _deps.getCurrentBookPath();
    if (!bridge || !currentBookPath) {
        _deps.log(' Нет моста или книги для удаления подсветки', 'error');
        return false;
    }

    findHighlightForCurrentSelection(currentSelection, function(existingHighlight) {
        if (!existingHighlight) {
            _deps.log(' Подсветка для текущего выделения не найдена', 'warn');
            return;
        }

        try {
            const pythonId = existingHighlight.id;
            _deps.log(` Удаляем подсветку ID=${pythonId}`, 'highlight');

            bridge.removeHighlight(pythonId);

            const ov = _deps.getOverlayer();
            if (ov) {
                try {
                    ov.remove(pythonId);
                    _deps.log(` Подсветка удална из Overlayer`, 'highlight');
                } catch(e) {
                    _deps.log(` Ошиба удаления из Overlayer: ${e.message}`, 'warn');
                }
            }

            delete _deps.userHighlightMap[pythonId];

            const highlightText = existingHighlight.highlight?.text || '';
            bridge.getBookmarks(function(result) {
                try {
                    const bms = JSON.parse(result);
                    for (const bm of bms) {
                        if (bm.label && bm.label.startsWith('') && highlightText && bm.label.includes(highlightText)) {
                            bridge.removeBookmark(bm.id);
                            _deps.log(` Закладка удалена: ${bm.id}`, 'highlight');
                            break;
                        }
                    }
                } catch(e) {
                    _deps.log(` Ошибка получения закладок: ${e.message}`, 'error');
                }
            });

            setTimeout(() => { loadBookmarks(); updateBookmarkButton(); }, 200);
            updateHighlightButtonIcon(currentSelection);

            _deps.log(` Подсветка удалена: ${existingHighlight.highlight.text?.substring(0, 40)}`, 'highlight');

        } catch (e) {
            _deps.log(` Ошибка удаления подсветки: ${e.message}`, 'error');
            console.error(e);
        }
    });

    return true;
}

// ══════════════════════════ ЗАМЕТКИ ═══════════════════════════════════

export function openNoteDialog(highlightId, quoteText, existingNote, cfiJson) {
    _noteDialogHighlightId = highlightId || '';
    _noteDialogNoteId      = existingNote ? existingNote.id : null;

    const overlay   = document.getElementById('noteDialogOverlay');
    const titleEl   = document.getElementById('noteDialogTitle');
    const quoteEl   = document.getElementById('noteDialogQuote');
    const textareaEl= document.getElementById('noteDialogText');

    titleEl.textContent    = existingNote ? 'Редактировать заметку' : 'Новая заметка';
    quoteEl.textContent    = quoteText || '';
    textareaEl.value       = existingNote ? (existingNote.text || '') : '';
    overlay.dataset.cfi    = cfiJson ? JSON.stringify(cfiJson) : '';

    overlay.classList.add('visible');
    setTimeout(() => textareaEl.focus(), 60);
}

export function closeNoteDialog() {
    document.getElementById('noteDialogOverlay').classList.remove('visible');
    _noteDialogHighlightId = null;
    _noteDialogNoteId      = null;
}

export function saveNoteFromDialog() {
    const bridge = _deps.getBridge();
    const text = document.getElementById('noteDialogText').value.trim();
    if (!text) { closeNoteDialog(); return; }
    if (!bridge) { closeNoteDialog(); return; }

    const cfiRaw = document.getElementById('noteDialogOverlay').dataset.cfi || '';

    if (_noteDialogNoteId) {
        bridge.updateNote(_noteDialogNoteId, text);
        _deps.log(' Заметка обновлена', 'info');
    } else {
        bridge.saveNote(_noteDialogHighlightId, text, cfiRaw);
        _deps.log(' Заметка сохранена', 'info');
    }

    closeNoteDialog();
    if (document.getElementById('highlightsPanel').classList.contains('visible')) {
        renderHighlightsPanel();
    }
}

// ══════════════════════════ ПАНЕЛЬ ВЫДЕЛЕНИЙ/ЗАМЕТОК ══════════════════

export function setHighlightsFilter(filter) {
    _hlFilterActive = filter;
}

export function getHighlightsFilter() {
    return _hlFilterActive;
}

export async function renderHighlightsPanel() {
    const bridge = _deps.getBridge();
    const view = _deps.getView();
    const listEl = document.getElementById('highlightsList');
    if (!listEl || !bridge) return;

    const [hlJson, notesJson] = await Promise.all([
        new Promise(r => bridge.getHighlights(r)),
        new Promise(r => bridge.getNotes(r)),
    ]);

    let highlights = [];
    let notes = [];
    try { highlights = JSON.parse(hlJson) || []; } catch(e) {}
    try { notes      = JSON.parse(notesJson) || []; } catch(e) {}

    const notesByHl = {};
    const freeNotes = [];
    for (const n of notes) {
        if (n.highlight_id) {
            if (!notesByHl[n.highlight_id]) notesByHl[n.highlight_id] = [];
            notesByHl[n.highlight_id].push(n);
        } else {
            freeNotes.push(n);
        }
    }

    let items = [];
    if (_hlFilterActive === 'all') {
        items = [
            ...highlights.map(h => ({ type: 'highlight', data: h, ts: h.timestamp || '' })),
            ...freeNotes.map(n => ({ type: 'note', data: n, ts: n.timestamp || '' })),
        ].sort((a, b) => b.ts.localeCompare(a.ts));
    } else if (_hlFilterActive === 'highlight') {
        items = highlights.map(h => ({ type: 'highlight', data: h, ts: h.timestamp || '' }))
            .sort((a, b) => b.ts.localeCompare(a.ts));
    } else {
        items = notes.map(n => ({ type: 'note', data: n, ts: n.timestamp || '' }))
            .sort((a, b) => b.ts.localeCompare(a.ts));
    }

    if (!items.length) {
        listEl.innerHTML = '<div class="hl-empty">Нет выделений и заметок</div>';
        return;
    }

    listEl.innerHTML = '';

    for (const item of items) {
        const el = document.createElement('div');
        el.className = 'hl-item';

        if (item.type === 'highlight') {
            const h = item.data;
            const color  = HL_COLOR_MAP[h.color] || HL_COLOR_MAP.default;
            const linked = notesByHl[h.id] || [];
            const dateStr = h.timestamp ? new Date(h.timestamp)
                .toLocaleDateString('ru-RU', {day:'numeric',month:'short'}) : '';

            let noteHtml = '';
            for (const n of linked) {
                noteHtml += `<div class="hl-item-note">${_escHtml(n.text)
                    }<span class="hl-action-btn" style="float:right;opacity:.7;"
                    data-edit-note="${n.id}"
                    data-hl-id="${h.id}"
                    data-hl-text="${_escAttr(h.text || '')}"
                    title="Редактировать">
                    <span class="material-icons" style="font-size:12px;">edit</span>
                    </span></div>`;
            }

            el.innerHTML = `
                  <div style="display:flex;gap:8px;align-items:flex-start;">
                <div class="hl-item-color-bar" style="background:${color};min-height:40px;"></div>
                <div class="hl-item-body">
                  <div class="hl-item-text">${_escHtml(h.text || '')}</div>
                  ${noteHtml}
                    <button class="hl-action-btn" data-add-note="${h.id}"
                        data-hl-text="${_escAttr(h.text||'')}">
                      <span class="material-icons">edit_note</span>Заметка
                    </button>
                    <button class="hl-action-btn" data-copy-text="${_escAttr(h.text||'')}">
                      <span class="material-icons">content_copy</span>
                    </button>
                    <button class="hl-action-btn" data-delete-hl="${h.id}"
                        style="margin-left:auto;color:#f28b82;">
                      <span class="material-icons">delete_outline</span>
                    </button>
                  </div>
                </div>
                  </div>`;

        } else {
            const n = item.data;
            const dateStr = n.timestamp ? new Date(n.timestamp)
                .toLocaleDateString('ru-RU', {day:'numeric',month:'short'}) : '';
            el.innerHTML = `
                  <div style="display:flex;gap:8px;align-items:flex-start;">
                <div class="hl-item-color-bar"
                     style="background:#7ecfff;min-height:40px;"></div>
                <div class="hl-item-body">
                  <div class="hl-item-text" style="color:#7ecfff;"> Заметка</div>
                  <div class="hl-item-note">${_escHtml(n.text)}</div>
                  <div class="hl-item-meta">${dateStr}</div>
                  <div class="hl-item-actions">
                    <button class="hl-action-btn" data-edit-note="${n.id}"
                        data-hl-text="">
                      <span class="material-icons">edit</span>Изменить
                    </button>
                    <button class="hl-action-btn" data-delete-note="${n.id}"
                        style="margin-left:auto;color:#f28b82;">
                      <span class="material-icons">delete_outline</span>
                    </button>
                  </div>
                </div>
                  </div>`;
        }

        listEl.appendChild(el);
    }

    listEl.onclick = (e) => {
        const btn = e.target.closest('[data-goto-cfi],[data-add-note],[data-delete-hl],[data-copy-text],[data-edit-note],[data-delete-note]');
        if (!btn) return;

        if (btn.dataset.gotoCfi !== undefined) {
            const cfiRaw = btn.dataset.gotoCfi;
            if (cfiRaw) {
                try {
                    const cfiObj = JSON.parse(cfiRaw);
                    if (cfiObj.section !== undefined && cfiObj.cfi) {
                        view.goToSection(cfiObj.section)
                            .then(() => view.goTo(cfiObj.cfi))
                            .catch(() => {});
                    } else if (cfiObj.cfi) {
                        view.goTo(cfiObj.cfi).catch(() => {});
                    }
                } catch(e) {}
            }
            document.getElementById('highlightsPanel').classList.remove('visible');
            document.getElementById('overlay').classList.remove('visible');
        }

        if (btn.dataset.addNote !== undefined) {
            openNoteDialog(btn.dataset.addNote, btn.dataset.hlText || '', null, null);
        }

        if (btn.dataset.editNote !== undefined) {
            bridge.getNotes(njson => {
                try {
                    const allNotes = JSON.parse(njson) || [];
                    const found = allNotes.find(n => n.id === btn.dataset.editNote);
                    openNoteDialog(
                        btn.dataset.hlId || '',
                        btn.dataset.hlText || '',
                        found || null, null);
                } catch(e) {}
            });
        }

        if (btn.dataset.deleteHl !== undefined) {
            if (confirm('Удалить выделение?')) {
                bridge.removeHighlight(btn.dataset.deleteHl);
                try { _deps.getOverlayer()?.remove(btn.dataset.deleteHl); } catch(e) {}
                renderHighlightsPanel();
            }
        }

        if (btn.dataset.deleteNote !== undefined) {
            if (confirm('Удалить заметку?')) {
                bridge.removeNote(btn.dataset.deleteNote);
                renderHighlightsPanel();
            }
        }

        if (btn.dataset.copyText !== undefined) {
            if (bridge) bridge.copyToClipboard(btn.dataset.copyText);
        }
    };
}
