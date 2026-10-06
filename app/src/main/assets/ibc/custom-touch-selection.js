// custom-touch-selection.js
//
// Заменяет нативное тач-выделение текста Android WebView (то, что вызывает
// системную панель ActionMode с Copy/Share/...) на выделение, полностью
// собранное вручную через JS. Идея:
//
//   1. Долгое нажатие пальцем НЕ передаётся в родной механизм выделения
//      Chromium (в этом и была проблема — тот механизм жёстко привязан
//      к показу ActionMode на тач-устройствах). Вместо этого мы сами
//      детектируем долгое нажатие через touchstart/таймер.
//   2. Стартовая и текущая точки выделения переводятся в Range через
//      document.caretRangeFromPoint(x, y) — стандартный (нестандартизованный,
//      но давно поддерживаемый WebKit/Blink) метод.
//   3. Range применяется к Selection ПРОГРАММНО через
//      selection.setBaseAndExtent(...) — таким образом Selection
//      создаётся не через "родной" тач-жест, а через скрипт, поэтому
//      Android/Blink не запускает ActionMode.
//   4. Дальше всё как раньше: #selectionToolbar (уже реализованный в
//      reader.html для мыши) просто реагирует на существующий
//      doc.getSelection() — эту часть трогать не нужно.
//
// Мышь (десктоп) не тронута: там выделение и так не вызывает ActionMode,
// поэтому этот модуль работает только при touch-событиях.

(function () {
    const LONG_PRESS_MS = 320;
    const MOVE_CANCEL_PX = 8; // если палец сдвинулся больше — не считаем это долгим нажатием, а скроллом/тапом

    let longPressTimer = null;
    let pendingStart = null;   // { x, y } — координаты touchstart, ждём таймер
    let selecting = false;     // true между стартом выделения и touchend
    let anchorRange = null;    // Range, зафиксированный в момент начала выделения (слово под пальцем)
    let longPressFired = false; // сработал ли таймер на этом касании — нужно, чтобы touch-gestures.js/paginator.js не перехватили это же касание как тап/свайп

    function clearLongPressTimer() {
        if (longPressTimer !== null) {
            clearTimeout(longPressTimer);
            longPressTimer = null;
        }
    }

    function rangeFromPoint(doc, x, y) {
        try {
            if (doc.caretRangeFromPoint) return doc.caretRangeFromPoint(x, y);
            // Firefox-совместимый путь (в Android WebView не нужен, но не помешает)
            if (doc.caretPositionFromPoint) {
                const pos = doc.caretPositionFromPoint(x, y);
                if (!pos) return null;
                const r = doc.createRange();
                r.setStart(pos.offsetNode, pos.offset);
                r.collapse(true);
                return r;
            }
        } catch (e) {}
        return null;
    }

    // Расширяет схлопнутый Range (точку) до границ слова под этой точкой —
    // так долгое нажатие сразу выделяет слово целиком, как в нативном UX.
    function expandRangeToWord(range) {
        if (!range) return null;
        const node = range.startContainer;
        if (node.nodeType !== Node.TEXT_NODE) return range;
        const text = node.textContent || '';
        let start = range.startOffset;
        let end = range.startOffset;
        const isWordChar = ch => ch && /[\p{L}\p{N}_]/u.test(ch);
        while (start > 0 && isWordChar(text[start - 1])) start--;
        while (end < text.length && isWordChar(text[end])) end++;
        if (start === end) return range; // не на слове (пробел/пунктуация) — оставляем точку
        const r = node.ownerDocument.createRange();
        r.setStart(node, start);
        r.setEnd(node, end);
        return r;
    }

    function applySelection(doc, startRange, endRange) {
        const sel = doc.getSelection?.();
        if (!sel || !startRange || !endRange) return;
        try {
            // setBaseAndExtent сам определяет направление (вперёд/назад) —
            // не нужно вручную решать, где "начало", а где "конец".
            sel.setBaseAndExtent(
                startRange.startContainer, startRange.startOffset,
                endRange.endContainer ?? endRange.startContainer,
                endRange.endOffset ?? endRange.startOffset,
            );
        } catch (e) {}
    }

    function dist(a, b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    function attachToDoc(doc) {
        if (!doc || doc.__novaTouchSelectionAttached) return;
        doc.__novaTouchSelectionAttached = true;

        // capture:true — этот обработчик должен увидеть касание РАНЬШЕ,
        // чем paginator.js/touch-gestures.js (те висят на bubble-фазе),
        // иначе долгое нажатие может быть съедено логикой свайпа/тапа
        // до того, как успеет сработать наш таймер.
        doc.addEventListener('touchstart', (e) => {
            if (e.touches.length !== 1) { clearLongPressTimer(); pendingStart = null; return; }
            const t = e.touches[0];
            pendingStart = { x: t.clientX, y: t.clientY };
            longPressFired = false;
            clearLongPressTimer();
            longPressTimer = setTimeout(() => {
                if (!pendingStart) return;
                const r = rangeFromPoint(doc, pendingStart.x, pendingStart.y);
                if (!r) return;
                anchorRange = expandRangeToWord(r);
                applySelection(doc, anchorRange, anchorRange);
                selecting = true;
                longPressFired = true;
                // Помечаем текущее касание как "съеденное" выделением —
                // touch-gestures.js/paginator.js проверяют этот флаг и не
                // должны трактовать те же пальцы как тап или свайп.
                window.__novaSelectionActive = true;
                // Даём вибро-отклик, как при нативном долгом нажатии, если доступно.
                try { window.navigator.vibrate?.(20); } catch (err) {}
            }, LONG_PRESS_MS);
        }, { capture: true, passive: true });

        doc.addEventListener('touchmove', (e) => {
            if (e.touches.length !== 1) return;
            const t = e.touches[0];
            if (pendingStart && !selecting && dist(pendingStart, { x: t.clientX, y: t.clientY }) > MOVE_CANCEL_PX) {
                // Палец сдвинулся раньше срабатывания таймера — это скролл/пролистывание,
                // а не намерение выделить текст. Отменяем долгое нажатие и отдаём
                // жест пагинатору/тап-обработчику как обычно.
                clearLongPressTimer();
                pendingStart = null;
                return;
            }
            if (selecting && anchorRange) {
                // Во время активного выделения блокируем скролл/пролистывание страницы —
                // палец теперь двигает край выделения, а не книгу.
                e.preventDefault();
                e.stopPropagation();
                const r = rangeFromPoint(doc, t.clientX, t.clientY);
                if (r) applySelection(doc, anchorRange, r);
            }
        }, { capture: true, passive: false });

        doc.addEventListener('touchend', (e) => {
            clearLongPressTimer();
            pendingStart = null;
            if (selecting) {
                // Не даём этому же touchend дойти до touch-gestures.js как "тап" —
                // тот и так проверяет hasSelection, но перестраховываемся на случай
                // гонки между обнулением selecting здесь и чтением selection там.
                e.stopPropagation();
                selecting = false;
                anchorRange = null;
                // Сообщаем остальной части reader.html, что выделение
                // зафиксировано — на это реагирует #selectionToolbar.
                doc.dispatchEvent(new Event('selectionchange'));
            }
            // Флаг снимаем с небольшой задержкой: touch-gestures.js читает
            // getSelection() в своём обработчике touchend/pointerup, который
            // может отработать позже этого — даём ему шанс увидеть активное
            // выделение и корректно проигнорировать тап.
            setTimeout(() => { window.__novaSelectionActive = false; }, 50);
        }, { capture: true, passive: true });

        doc.addEventListener('touchcancel', () => {
            clearLongPressTimer();
            pendingStart = null;
            selecting = false;
            anchorRange = null;
            window.__novaSelectionActive = false;
        }, { capture: true, passive: true });
    }


    function attachToCurrentContents(viewerEl) {
        try {
            const contents = viewerEl.renderer?.getContents?.() || [];
            contents.forEach(c => { if (c?.doc) attachToDoc(c.doc); });
            return contents.length > 0;
        } catch (e) {
            return false;
        }
    }

    function init() {
        const viewerEl = document.getElementById('viewer');
        if (!viewerEl) { setTimeout(init, 200); return; }

        viewerEl.addEventListener('load', ({ detail }) => {
            if (detail?.doc) attachToDoc(detail.doc);
        });

        let attempts = 0;
        const tryAttach = () => {
            if (attachToCurrentContents(viewerEl)) return;
            if (++attempts < 30) setTimeout(tryAttach, 300);
        };
        tryAttach();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
