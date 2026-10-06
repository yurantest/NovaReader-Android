// touch-gestures.js
// Мобильные жесты навигации, которых нет в desktop-версии (там панели
// показывались/скрывались по движению мыши — document.mousemove, что
// бессмысленно на тач-экране).
//
// ВАЖНО: перелистывание свайпом уже реализовано в paginator.js
// (#onTouchStart/#onTouchMove/#onTouchEnd → snap()) — это часть движка
// читалки, трогать/дублировать эту логику здесь не нужно и вредно
// (двойное перелистывание на один и тот же свайп). Этот файл добавляет
// только то, чего в движке не было вовсе:
//   - тап по левой ~25% ширины экрана   → предыдущая страница
//   - тап по правой ~25% ширины экрана  → следующая страница
//   - тап по центру                     → показать/скрыть панели
//   - если в момент тапа есть активное выделение текста —
//     ничего не делаем (не мешаем тулбару выделения из reader.html)
//   - тап по ссылке/кнопке внутри текста книги — не перехватываем

(function () {
    const TAP_MAX_MOVE = 12;     // px — больше этого — не тап (совпадает с порогом в paginator.js)
    const TAP_MAX_TIME = 300;    // ms
    const EDGE_ZONE = 0.25;      // левые/правые 25% ширины — зоны перелистывания
    const AUTO_HIDE_MS = 3000;   // панели, показанные тапом, скрываются через 3с

    let autoHideTimer = null;

    function getBars() {
        return {
            topBar: document.getElementById('topBar'),
            bottomNav: document.getElementById('bottomNav'),
        };
    }

    function anyModalOpen() {
        return !!(
            document.getElementById('ttsSettingsPanel')?.classList.contains('visible') ||
            document.getElementById('tocPanel')?.classList.contains('visible') ||
            document.getElementById('debugPanel')?.classList.contains('visible') ||
            document.getElementById('bookmarksPanel')?.classList.contains('visible')
        );
    }

    function showBars() {
        const { topBar, bottomNav } = getBars();
        if (!topBar || !bottomNav) return;
        topBar.classList.remove('hidden');
        bottomNav.classList.remove('hidden');
        clearTimeout(autoHideTimer);
        autoHideTimer = setTimeout(() => {
            if (!anyModalOpen()) hideBars();
        }, AUTO_HIDE_MS);
    }

    function hideBars() {
        const { topBar, bottomNav } = getBars();
        if (!topBar || !bottomNav) return;
        topBar.classList.add('hidden');
        bottomNav.classList.add('hidden');
    }

    function toggleBars() {
        const { topBar } = getBars();
        if (!topBar) return;
        if (topBar.classList.contains('hidden')) showBars();
        else {
            clearTimeout(autoHideTimer);
            hideBars();
        }
    }
    window.toggleReaderBars = toggleBars; // на случай если понадобится вызвать вручную

    function isInteractive(el) {
        return !!el?.closest?.('a, button, input, textarea, select, [role="button"], #selectionToolbar');
    }

    function attachGestures(doc) {
        if (!doc || doc.__novaGesturesAttached) return;
        doc.__novaGesturesAttached = true;

        let startX = 0, startY = 0, startT = 0;

        doc.addEventListener('pointerdown', (e) => {
            startX = e.clientX;
            startY = e.clientY;
            startT = Date.now();
        }, { passive: true });

        doc.addEventListener('pointerup', (e) => {
            const dx = e.clientX - startX;
            const dy = e.clientY - startY;
            const dt = Date.now() - startT;

            const isTap = Math.abs(dx) < TAP_MAX_MOVE && Math.abs(dy) < TAP_MAX_MOVE && dt < TAP_MAX_TIME;

            const viewerEl = document.getElementById('viewer');
            // ВАЖНО: doc.defaultView.innerWidth ненадёжен — у некоторых
            // секций (короткие: титульный лист, начало главы) iframe
            // отдаёт полную многоколоночную ширину ВСЕГО контента секции
            // вместо видимой ширины страницы. Берём ширину из #viewer в
            // главном документе — она не зависит от контента секции.
            const width = viewerEl?.clientWidth || window.innerWidth;

            // ВТОРАЯ проблема (отдельная от первой): сам e.clientX внутри
            // iframe тоже ненадёжен при листании ВНУТРИ секции — пагинатор
            // двигает iframe через CSS transform, а clientX считается
            // относительно НЕТРАНСФОРМИРОВАННОЙ системы координат iframe,
            // поэтому на 3-й/4-й/5-й странице секции он "убегает" на
            // 1-2-3 ширины экрана вперёд, хотя палец тапает в одно и то же
            // место на экране. Компенсируем через frameElement — экранное
            // положение iframe (в координатах #viewer) плюс внутренний
            // clientX даёт стабильную позицию независимо от сдвига.
            let relX;
            const frameEl = doc.defaultView?.frameElement;
            if (frameEl && viewerEl) {
                const frameRect = frameEl.getBoundingClientRect();
                const viewerRect = viewerEl.getBoundingClientRect();
                const screenX = (frameRect.left - viewerRect.left) + e.clientX;
                relX = screenX / width;
            } else {
                // fallback — прежняя (менее надёжная) формула
                relX = e.clientX / width;
            }

            const sel = doc.getSelection?.();
            const hasSelection = !!sel && sel.toString().trim().length > 0;
            const selectionInProgress = !!window.__novaSelectionActive;
            const interactive = isInteractive(e.target);

            let decision = 'ignore(swipe)';
            if (isTap) {
                if (hasSelection || selectionInProgress) decision = 'ignore(selection)';
                else if (interactive) decision = 'ignore(interactive)';
                else if (relX < EDGE_ZONE) decision = 'prev';
                else if (relX > 1 - EDGE_ZONE) decision = 'next';
                else decision = 'toggleBars';
            }

            window.AndroidBridge?.log?.(
                `[tap] dx=${dx} dy=${dy} dt=${dt} isTap=${isTap} clientX=${e.clientX} ` +
                `viewerWidth=${width} frameEl=${!!frameEl} relX=${relX.toFixed(3)} sel=${hasSelection} ` +
                `interactive=${interactive} -> ${decision}`
            );

            if (!isTap) return; // свайпы — забота paginator.js, сюда не лезем
            if (hasSelection || selectionInProgress) return; // приоритет тулбару выделения
            if (interactive) return; // ссылка/сноска/кнопка — не мешаем

            if (relX < EDGE_ZONE) {
                viewerEl?.prev?.();
            } else if (relX > 1 - EDGE_ZONE) {
                viewerEl?.next?.();
            } else {
                toggleBars();
            }
        }, { passive: true });
    }

    function attachToCurrentContents(viewerEl) {
        try {
            const contents = viewerEl.renderer?.getContents?.() || [];
            contents.forEach(c => { if (c?.doc) attachGestures(c.doc); });
            return contents.length > 0;
        } catch (e) {
            return false;
        }
    }

    function init() {
        const viewerEl = document.getElementById('viewer');
        if (!viewerEl) {
            // reader.html ещё не создал #viewer — подождём и попробуем снова
            setTimeout(init, 200);
            return;
        }

        // Каждая секция книги — новый iframe/doc, вешаем обработчики на каждый
        viewerEl.addEventListener('load', ({ detail }) => {
            if (detail?.doc) attachGestures(detail.doc);
        });

        // К моменту, когда этот (отдельный, подключаемый последним) скрипт
        // выполнится, первая секция книги обычно УЖЕ загружена и её 'load'
        // успел отработать без нас — поэтому просто ждать будущих 'load'
        // недостаточно, нужно опросить текущее состояние рендерера прямо
        // сейчас, с повторами, пока книга не откроется.
        let attempts = 0;
        const tryAttach = () => {
            if (attachToCurrentContents(viewerEl)) return;
            if (++attempts < 30) setTimeout(tryAttach, 300); // до ~9 секунд на открытие книги
        };
        tryAttach();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();

// ── Смахивание для закрытия боковых панелей (оглавление, закладки, ─────
//    выделения/заметки) ──────────────────────────────────────────────
// Раньше эти панели закрывались только по крестику или тапом по затемнению
// вокруг (#overlay) — стандартного для Android смахивания панели в сторону,
// откуда она выехала, не было. Добавляем его отдельно от paginator.js/
// attachGestures выше — те слушают жесты ВНУТРИ книги (iframe с текстом),
// а панели живут в главном документе.
(function () {
    const SWIPE_MIN_DX = 50;       // px — минимальный путь свайпа, чтобы не путать с тапом
    const SWIPE_MAX_OFF_AXIS = 60; // px — если по вертикали ушло больше — это скролл списка внутри панели, не закрытие
    const SWIPE_MAX_TIME = 600;    // ms

    // anchor — с какой стороны выезжает панель (см. transform: translateX
    // в CSS .toc-panel/.bookmarks-panel) → в эту же сторону её и смахиваем,
    // чтобы закрыть (как штору).
    const PANELS = [
        { id: 'tocPanel', anchor: 'left' },
        { id: 'bookmarksPanel', anchor: 'right' },
        { id: 'highlightsPanel', anchor: 'right' },
    ];

    function closePanel(panel) {
        panel.classList.remove('visible');
        document.getElementById('overlay')?.classList.remove('visible');
    }

    function attachSwipeClose(panel, anchor) {
        if (!panel || panel.__novaSwipeCloseAttached) return;
        panel.__novaSwipeCloseAttached = true;

        let startX = 0, startY = 0, startT = 0, tracking = false;

        panel.addEventListener('pointerdown', (e) => {
            startX = e.clientX;
            startY = e.clientY;
            startT = Date.now();
            tracking = true;
        }, { passive: true });

        panel.addEventListener('pointerup', (e) => {
            if (!tracking) return;
            tracking = false;
            const dx = e.clientX - startX;
            const dy = e.clientY - startY;
            const dt = Date.now() - startT;
            if (Math.abs(dy) > SWIPE_MAX_OFF_AXIS) return; // вертикальный скролл списка — не наш случай
            if (dt > SWIPE_MAX_TIME) return;

            const closingSwipe = anchor === 'left' ? dx < -SWIPE_MIN_DX : dx > SWIPE_MIN_DX;
            if (closingSwipe) closePanel(panel);
        }, { passive: true });

        panel.addEventListener('pointercancel', () => { tracking = false; }, { passive: true });
    }

    function init() {
        PANELS.forEach(({ id, anchor }) => attachSwipeClose(document.getElementById(id), anchor));
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
