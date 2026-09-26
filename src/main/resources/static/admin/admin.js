'use strict';
const $ = id => document.getElementById(id);
const categories = {single: 'Обычные скачивания', playlist_audio: 'Плейлисты'};
const states = {IDLE: 'Свободен', BUSY: 'Занят', BACKOFF: 'Пауза после ошибки', STOPPED: 'Остановлен'};
const phases = {DOWNLOADING: 'Скачивание', PACKING: 'Упаковка', UPLOADING: 'Отправка'};
const errors = {METADATA: 'Подготовка меню', PLAYLIST_ITEM: 'Треки плейлистов', WORKER: 'Циклы воркеров', METADATA_REJECTED: 'Переполнение очереди меню'};
let latest, timer, inFlight = false, retry = 2000, csrfReady = false;
const sorting = {};
const collator = new Intl.Collator('ru', {numeric: true, sensitivity: 'base'});
function text(id, value) { if ($(id).textContent !== String(value)) $(id).textContent = value; }
function date(value) { return value ? new Date(value).toLocaleString('ru-RU') : 'ещё нет данных'; }
function duration(since) {
  if (!since) return '—';
  const seconds = Math.max(0, Math.floor((Date.now() - Date.parse(since)) / 1000));
  return seconds < 60 ? `${seconds} с` : seconds < 3600 ? `${Math.floor(seconds / 60)} мин` : `${Math.floor(seconds / 3600)} ч ${Math.floor(seconds % 3600 / 60)} мин`;
}
function compareValues(a, b, direction) {
  if (a == null || b == null) return a == null ? (b == null ? 0 : 1) : -1;
  if (Array.isArray(a)) {
    for (let i = 0; i < a.length; i++) {
      const compared = compareValues(a[i], b[i], direction);
      if (compared) return compared;
    }
    return 0;
  }
  return direction * (typeof a === 'number' ? a - b : collator.compare(a, b));
}
function table(id, rows, values = rows) {
  const sort = sorting[id];
  if (sort) rows = rows.map((row, i) => ({row, values: values[i]}))
    .sort((a, b) => compareValues(a.values[sort.column], b.values[sort.column], sort.direction))
    .map(entry => entry.row);
  const key = JSON.stringify(rows);
  if ($(id).dataset.rows === key) return;
  $(id).dataset.rows = key;
  $(id).replaceChildren(...rows.map(row => {
    const tr = document.createElement('tr');
    row.forEach(value => {
      const td = document.createElement('td');
      if (value && typeof value === 'object') {
        const span = document.createElement('span');
        span.className = `badge ${value.state.toLowerCase()}`;
        span.textContent = states[value.state] || value.state;
        td.append(span);
      } else td.textContent = value ?? '—';
      tr.append(td);
    });
    return tr;
  }));
}
function sourceStatus(id, available, at) {
  text(id, `${available ? 'Данные БД' : 'БД недоступна · сохранён последний снимок'}: ${date(at)}`);
  $(id).classList.toggle('warning', !available);
}
function render(data) {
  const sum = status => data.queues.filter(q => q.status === status).reduce((n, q) => n + q.count, 0);
  text('queued', data.queuesUpdatedAt ? sum('queued') : '—');
  text('processing', data.queuesUpdatedAt ? sum('processing') : '—');
  text('metadata', data.runtime.metadataQueued);
  const workers = data.runtime.workers;
  text('items', `${workers.reduce((n, w) => n + w.activeItems, 0)} / ${workers.reduce((n, w) => n + w.waitingItems, 0)}`);
  table('workers', workers.map(w => [w.id, {state: w.state}, w.jobId == null ? '—' : `#${w.jobId}`, w.platform, phases[w.phase] || '—', duration(w.since), w.category === 'playlist' ? `${w.activeItems} / ${w.waitingItems}` : '—']),
    workers.map(w => [w.id, states[w.state] || w.state, w.jobId, w.platform, phases[w.phase], w.since ? -Date.parse(w.since) : null, w.category === 'playlist' ? [w.activeItems, w.waitingItems] : null]));
  const queueValues = [];
  table('queues', Object.entries(categories).map(([key, label]) => {
    const queued = data.queues.find(q => q.category === key && q.status === 'queued');
    const processing = data.queues.find(q => q.category === key && q.status === 'processing');
    const counts = [data.queuesUpdatedAt ? queued?.count || 0 : null, data.queuesUpdatedAt ? processing?.count || 0 : null];
    queueValues.push([label, ...counts, queued?.oldest ? -Date.parse(queued.oldest) : null]);
    return [label, ...counts, duration(queued?.oldest)];
  }), queueValues);
  sourceStatus('queue-status', data.queuesAvailable, data.queuesUpdatedAt);
  sourceStatus('outcome-status', data.outcomesAvailable, data.outcomesUpdatedAt);
  renderOutcomes();
  chart(data.history);
  renderMemory(data.memory);
  text('updated', `Снимок: ${date(data.generatedAt)}`);
  text('runtime-note', `Статистика сохраняется в PostgreSQL. ${data.statistics.restored ? "История ошибок восстановлена." : "История ошибок пока неполная."} ${data.statistics.available && data.historyAvailable ? "" : "Сохранение или загрузка статистики недоступны."} Сохранение счётчиков: ${date(data.statistics.updatedAt)}. Запуск процесса: ${date(data.runtime.startedAt)}.`);
}
function renderOutcomes() {
  if (!latest) return;
  const minutes = Number($('window').value);
  table('outcomes', Object.entries(categories).map(([key, label]) => [label, ...['completed', 'failed', 'cancelled_by_user'].map(status => latest.outcomesUpdatedAt ? latest.outcomes.find(o => o.minutes === minutes && o.category === key && o.status === status)?.count || 0 : null)]));
  const counts = latest.runtime.errors.find(e => e.minutes === minutes)?.counts || {};
  Object.entries(errors).forEach(([key, label]) => {
    let box = document.getElementById(`error-${key}`);
    if (!box) {
      box = document.createElement('div'); box.id = `error-${key}`;
      const caption = document.createElement('span'); caption.textContent = label;
      box.append(caption, document.createElement('strong')); $('errors').append(box);
    }
    const value = String(counts[key] || 0);
    if (box.lastChild.textContent !== value) box.lastChild.textContent = value;
  });
}
function chart(points) {
  if (!points.length) return;
  const key = points[points.length - 1].at;
  if ($('chart').dataset.last === key) return;
  $('chart').dataset.last = key;
  const ns = 'http://www.w3.org/2000/svg';
  const max = Math.max(1, ...points.map(p => Math.max(p.queued, p.processing)));
  const start = Date.parse(points[0].at), span = Math.max(5000, Date.parse(key) - start);
  $('chart').replaceChildren();
  ['queued', 'processing'].forEach((field, i) => {
    const line = document.createElementNS(ns, 'polyline');
    line.setAttribute('points', points.map(p => `${35 + (Date.parse(p.at) - start) / span * 555},${120 - p[field] / max * 100}`).join(' '));
    line.setAttribute('fill', 'none'); line.setAttribute('stroke', i ? '#72e0c0' : '#78a7ff'); line.setAttribute('stroke-width', '2');
    $('chart').append(line);
  });
  [0, max].forEach(value => { const label = document.createElementNS(ns, 'text'); label.setAttribute('x', '0'); label.setAttribute('y', value ? '24' : '124'); label.textContent = value; $('chart').append(label); });
  text('chart-range', `${date(points[0].at)} – ${date(key)}`);
}
function bytes(value) {
  if (value == null) return '—';
  return value >= 1024 ** 3 ? `${(value / 1024 ** 3).toFixed(2)} ГиБ` : `${(value / 1024 ** 2).toFixed(1)} МиБ`;
}
function renderMemory(memory) {
  if (!memory) return;
  const point = memory.current;
  text('memory-status', `${memory.available ? 'Измерение' : 'Измерение недоступно'}: ${date(point?.at)}`);
  $('memory-status').classList.toggle('warning', !memory.available);
  text('heap-used', bytes(point?.heapUsed));
  text('heap-limit', `Выделено: ${bytes(point?.heapCommitted)} / максимум: ${bytes(point?.heapMax)}`);
  text('container-used', point?.containerUsed == null ? 'Нет данных' : bytes(point.containerUsed));
  text('container-limit', point?.containerLimit == null ? 'Лимит не задан или недоступен' : `Лимит: ${bytes(point.containerLimit)}`);
  const percent = (used, max) => used != null && max > 0 ? `${(used / max * 100).toFixed(1)}%` : '';
  text('heap-percent', percent(point?.heapUsed, point?.heapMax));
  text('container-percent', percent(point?.containerUsed, point?.containerLimit));
  text('nonheap-used', bytes(point?.nonHeapUsed));
  text('memory-pools', `Metaspace: ${bytes(point?.metaspaceUsed)} · Code cache: ${bytes(point?.codeCacheUsed)}`);
  text('gc-value', `${point?.gcCount ?? '—'} сборок · ${point?.gcTimeMillis ?? '—'} мс`);
  text('gc-window', point?.gcWindowSeconds > 0 ? `За последние ${point.gcWindowSeconds} с · счётчики текущего процесса` : 'Ожидание второго измерения…');
  text('memory-history-status', memory.historyAvailable ? 'История памяти сохраняется раз в минуту.' : 'Сохранение или загрузка истории памяти недоступны.');
  $('memory-history-status').classList.toggle('warning', !memory.historyAvailable);
  const points = memory.history || [];
  memoryChart('heap-chart', points, 'heapUsed', 'heapMax');
  memoryChart('container-chart', points, 'containerUsed', 'containerLimit');
  text('memory-range', points.length ? `${date(points[0].at)} – ${date(points[points.length - 1].at)}` : 'История ещё не накоплена');
}
function memoryChart(id, points, used, limit) {
  const key = JSON.stringify(points.map(p => [p.at, p[used], p[limit]]));
  const svg = $(id);
  if (svg.dataset.points === key) return;
  svg.dataset.points = key;
  svg.replaceChildren();
  const ns = 'http://www.w3.org/2000/svg';
  function label(value, x, y) {
    const node = document.createElementNS(ns, 'text');
    node.textContent = value; node.setAttribute('x', x); node.setAttribute('y', y); svg.append(node);
  }
  if (!points.some(p => p[used] != null)) { label('Нет данных', 10, 70); return; }
  const max = Math.max(1, ...points.flatMap(p => [p[used] || 0, p[limit] || 0]));
  const end = Date.parse(points[points.length - 1].at), start = end - 3600000;
  [limit, used].forEach((field, index) => {
    let path = '', previous = null;
    points.forEach(p => {
      const at = Date.parse(p.at);
      if (p[field] == null) { previous = null; return; }
      const x = 60 + (at - start) / 3600000 * 530, y = 120 - p[field] / max * 100;
      path += `${previous == null || at - previous > 90000 ? 'M' : 'L'}${x},${y} `;
      previous = at;
      if (index === 1) {
        const dot = document.createElementNS(ns, 'circle');
        dot.setAttribute('cx', x); dot.setAttribute('cy', y); dot.setAttribute('r', '2'); dot.setAttribute('fill', '#72e0c0'); svg.append(dot);
      }
    });
    const line = document.createElementNS(ns, 'path'); line.setAttribute('d', path);
    line.setAttribute('fill', 'none'); line.setAttribute('stroke', index ? '#72e0c0' : '#7d91a7'); line.setAttribute('stroke-width', '2');
    if (!index) line.setAttribute('stroke-dasharray', '5 5');
    svg.append(line);
  });
  label((max / 1024 ** 2).toFixed(0), 0, 24); label('0', 0, 124);
}
async function request(path) {
  const response = await fetch(path, {cache: 'no-store', signal: AbortSignal.timeout(5000)});
  if (response.status === 401 || response.status === 403 || response.redirected) { location.assign('/login'); throw new Error('session'); }
  if (!response.ok) throw new Error('http');
  return response.json();
}
async function poll() {
  clearTimeout(timer);
  if (document.hidden || inFlight) return;
  inFlight = true;
  try {
    if (!csrfReady) {
      const csrf = await request('/admin/api/csrf');
      const input = document.createElement('input'); input.type = 'hidden'; input.name = csrf.parameterName; input.value = csrf.token;
      $('logout').append(input); $('logout').querySelector('button').disabled = false; csrfReady = true;
    }
    latest = await request('/admin/api/snapshot'); render(latest);
    const stale = Date.now() - Date.parse(latest.generatedAt) > 10000;
    text('connection', stale ? 'Сборщик не обновляет снимок' : latest.queuesAvailable && latest.outcomesAvailable && latest.statistics.restored && latest.statistics.available && latest.historyAvailable ? 'Данные обновляются' : 'Часть данных недоступна');
    $('connection').classList.toggle('warning', stale || !latest.queuesAvailable || !latest.outcomesAvailable || !latest.statistics.restored || !latest.statistics.available || !latest.historyAvailable);
    retry = 2000;
  } catch (_) { text('connection', 'Связь потеряна · показаны последние данные'); $('connection').classList.add('warning'); retry = Math.min(retry * 2, 30000); }
  finally { inFlight = false; if (!document.hidden) timer = setTimeout(poll, retry); }
}
['workers', 'queues', 'outcomes'].forEach(id => {
  const headers = $(id).closest('table').querySelectorAll('th');
  headers.forEach((header, column) => {
    const button = document.createElement('button');
    button.type = 'button'; button.className = 'sort-button';
    button.append(document.createTextNode(header.textContent));
    const arrow = document.createElement('span');
    arrow.className = 'sort-arrow'; arrow.setAttribute('aria-hidden', 'true'); arrow.textContent = '↕';
    button.append(arrow); header.replaceChildren(button); header.scope = 'col';
    button.addEventListener('click', () => {
      const previous = sorting[id];
      const direction = previous?.column === column ? -previous.direction : 1;
      sorting[id] = {column, direction};
      headers.forEach((item, index) => {
        if (index === column) item.setAttribute('aria-sort', direction === 1 ? 'ascending' : 'descending');
        else item.removeAttribute('aria-sort');
        item.querySelector('.sort-arrow').textContent = index === column ? (direction === 1 ? '↑' : '↓') : '↕';
      });
      if (latest) render(latest);
    });
  });
});
$('window').addEventListener('change', renderOutcomes);
document.addEventListener('visibilitychange', () => { clearTimeout(timer); if (document.hidden) text('connection', 'Обновление приостановлено'); else poll(); });
poll();
