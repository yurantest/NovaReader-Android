// dom-text-utils.js
// Утилиты поиска текста в DOM документа секции и экранирования HTML.
// Вынесено из reader.html — все функции принимают нужные им doc/range/строку
// явными аргументами и не читают состояние читалки (view, bridge и т.п.).

/** Экранирует HTML-спецсимволы для безопасной вставки в innerHTML. */
export function escHtml(s) {
    return String(s)
        .replace(/&/g,'&amp;').replace(/</g,'&lt;')
        .replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}

/**
 * Ищет первое точное вхождение searchText в текстовых узлах документа
 * (в пределах одного текстового узла — не склеивает соседние узлы).
 * @returns {Range|null}
 */
export function findTextRange(doc, searchText) {
    if (!doc || !searchText) return null;

    const walker = doc.createTreeWalker(
        doc.body || doc.documentElement,
        NodeFilter.SHOW_TEXT,
        null
    );

    let node;
    while ((node = walker.nextNode())) {
        const text = node.textContent;
        if (text && text.includes(searchText)) {
            const startIdx = text.indexOf(searchText);
            const range = doc.createRange();
            range.setStart(node, startIdx);
            range.setEnd(node, startIdx + searchText.length);
            return range;
        }
    }
    return null;
}

/**
 * Ищет searchText в документе, СКЛЕИВАЯ соседние текстовые узлы в буфер —
 * находит совпадения, разорванные тегами (<b>, <i> и т.п.) между словами.
 * Нормализует пробелы перед поиском.
 * @returns {Range|null}
 */
export function findTextInDoc(doc, searchText) {
    // Нормализуем текст (убираем лишние пробелы)
    const normalizedSearch = searchText.trim().replace(/\s+/g, ' ');

    // Ищем по текстовым узлам
    const walker = doc.createTreeWalker(doc.body, NodeFilter.SHOW_TEXT);
    let node;
    let buffer = '';
    let startNode = null;

    while ((node = walker.nextNode())) {
        const text = node.textContent;
        buffer += text;

        if (!startNode) startNode = node;

        // Проверяем, есть ли искомый текст в буфере
        const index = buffer.indexOf(normalizedSearch);
        if (index !== -1) {
            // Нашли! Возвращаем Range
            const range = doc.createRange();

            // Нужно найти начальный и конечный узлы
            let charCount = 0;
            let foundStart = false;
            let foundEnd = false;

            const walker2 = doc.createTreeWalker(doc.body, NodeFilter.SHOW_TEXT);
            let n;

            while ((n = walker2.nextNode())) {
                const nodeLen = n.textContent.length;

                if (!foundStart && charCount + nodeLen >= index) {
                    range.setStart(n, index - charCount);
                    foundStart = true;
                }

                if (foundStart && !foundEnd && charCount + nodeLen >= index + normalizedSearch.length) {
                    range.setEnd(n, index + normalizedSearch.length - charCount);
                    foundEnd = true;
                    break;
                }

                charCount += nodeLen;
            }

            if (foundStart && foundEnd) {
                return range;
            }
        }

        // Ограничиваем размер буфера
        if (buffer.length > normalizedSearch.length + 100) {
            buffer = buffer.slice(-100);
            startNode = null;
        }
    }

    return null;
}

/** Прокручивает к range и временно выделяет его текстовым Selection (2 сек). */
export function highlightFoundText(range, doc) {
    // Прокручиваем к тексту
    const rect = range.getBoundingClientRect();
    const win = doc.defaultView;
    if (rect) {
        win?.scrollTo({
            top: rect.top + win.scrollY - 100,
            behavior: 'smooth'
        });
    }

    // Временная подсветка (желтым на 2 секунды)
    const selection = win.getSelection();
    selection.removeAllRanges();
    selection.addRange(range);

    setTimeout(() => {
        selection.removeAllRanges();
    }, 2000);
}
