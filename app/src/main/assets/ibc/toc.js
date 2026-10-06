// toc.js
// Логика оглавления (TOC): фильтрация рекламных секций, определение —
// нужна ли иерархическая структура (сборники/части книги) или достаточно
// плоского списка глав, и рендер в DOM. Вынесено из reader.html — эти
// функции чистые (не читают/не пишут live-состояние читалки), кроме
// renderToc(), которому нужен только container-элемент и callback навигации,
// переданные явно.

// Заголовки, которые нужно скрывать в оглавлении и при рендере.
// Пираты вставляют рекламные секции с этими заголовками в конец книги.
export const HIDDEN_SECTIONS = [
    'nota bene', 'note bene', 'notabene',
    'от пирата', 'от пиратов', 'реклама',
];

export function isHiddenSection(label) {
    if (!label) return false;
    const l = label.trim().toLowerCase();
    return HIDDEN_SECTIONS.some(s => l === s || l.startsWith(s));
}

export function filterToc(items) {
    return items
        .filter(item => !isHiddenSection(item.label))
        .map(item => ({
            ...item,
            subitems: item.subitems ? filterToc(item.subitems) : [],
        }));
}

export function isGroupNode(item, depth) {
    if (!item.subitems?.length) return false;
    // Всегда группа если depth=0 и есть дети
    if (depth === 0) return true;
    // На глубине 1: группа если дочерние тоже имеют детей (составная структура)
    const childrenHaveChildren = item.subitems.some(c => c.subitems?.length > 0);
    return childrenHaveChildren;
}

// Детектируем: нужна ли вообще иерархия?
export function needsHierarchy(toc) {
    // Иерархия нужна если хотя бы один top-level узел имеет детей
    // И дети тоже имеют детей (значит это не просто "части с главами")
    // ИЛИ если top-level узлов с детьми > 1 (сборник из книг)
    const withChildren = toc.filter(n => n.subitems?.length > 0);
    if (withChildren.length === 0) return false;      // flat — нет подуровней
    // Проверяем глубину: если у детей тоже есть дети — точно нужна иерархия
    const deep = withChildren.some(n => n.subitems.some(c => c.subitems?.length > 0));
    if (deep) return true;
    // Или если несколько top-level групп (части книги)
    return withChildren.length >= 2;
}

/**
 * Рендерит оглавление в container.
 * @param {Array} toc - дерево оглавления (item.label, item.href, item.subitems)
 * @param {HTMLElement} container - контейнер, куда рендерить
 * @param {(href: string) => void} onNavigate - вызывается при клике на пункт/группу
 */
export function renderToc(toc, container, onNavigate) {
    if (!container) return;
    container.innerHTML = '';
    toc = filterToc(toc); // убираем рекламные секции

    if (!needsHierarchy(toc)) {
        // ── Плоский вид: обычная книга с главами ──────────────────
        function renderFlat(items, level) {
            items.forEach(item => {
                const div = document.createElement('div');
                div.className = 'toc-item level-' + level;
                div.textContent = item.label || '';
                div.dataset.href = item.href || '';
                div.onclick = () => onNavigate(item.href);
                container.appendChild(div);
                if (item.subitems?.length) renderFlat(item.subitems, level + 1);
            });
        }
        renderFlat(toc, 1);
        return;
    }

    // ── Иерархический вид: сборник или книга с частями ────────────
    function renderHierarchy(items, parent, depth) {
        items.forEach(item => {
            const hasKids = item.subitems?.length > 0;

            if (hasKids) {
                // Группа (книга / часть)
                const group = document.createElement('div');
                group.className = 'toc-group';

                const header = document.createElement('div');
                header.className = 'toc-group-header';
                // depth нужен для определения книги верхнего уровня при
                // подсветке активной главы в сборниках.
                header.dataset.depth = String(depth);
                if (depth > 0) header.style.paddingLeft = (14 + depth * 16) + 'px';

                const arrow = document.createElement('span');
                arrow.className = 'material-icons toc-arrow';
                arrow.textContent = 'chevron_right';

                const lbl = document.createElement('span');
                lbl.textContent = item.label || '';
                lbl.style.flex = '1';

                header.appendChild(arrow);
                header.appendChild(lbl);

                const children = document.createElement('div');
                children.className = 'toc-group-children';

                // Первая группа открыта по умолчанию
                if (depth === 0 && parent.children.length === 0) {
                    children.classList.add('open');
                    arrow.classList.add('open');
                }

                header.onclick = (e) => {
                    const isOpen = children.classList.toggle('open');
                    arrow.classList.toggle('open', isOpen);
                    // Клик по названию раздела тоже может быть навигацией
                };
                // Долгий тап / правый клик на заголовок — перейти к этой части
                lbl.ondblclick = () => onNavigate(item.href);

                group.appendChild(header);
                group.appendChild(children);
                parent.appendChild(group);
                renderHierarchy(item.subitems, children, depth + 1);
            } else {
                // Лист (глава)
                const div = document.createElement('div');
                div.className = 'toc-item';
                div.dataset.href = item.href || '';
                if (depth > 0) div.style.paddingLeft = (32 + depth * 16) + 'px';
                div.textContent = item.label || '';
                div.onclick = () => onNavigate(item.href);
                parent.appendChild(div);
            }
        });
    }
    renderHierarchy(toc, container, 0);
}
