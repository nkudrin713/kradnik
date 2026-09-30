'use strict';
const $ = id => document.getElementById(id);
const categories = {single: 'Обычные скачивания', playlist_audio: 'Плейлисты'};
const states = {IDLE: 'Свободен', BUSY: 'Занят', BACKOFF: 'Пауза после ошибки', STOPPED: 'Остановлен'};
const phases = {DOWNLOADING: 'Скачивание', PACKING: 'Упаковка', UPLOADING: 'Отправка'};
const errors = {METADATA: 'Подготовка меню', PLAYLIST_ITEM: 'Треки плейлистов', WORKER: 'Циклы воркеров', METADATA_REJECTED: 'Переполнение очереди меню'};
let latest, timer, inFlight = false, retry = 2000, csrfReady = false, csrfToken, backupEstimated = false;
let databaseSizeNextAt = 0, databaseSizeLoaded = false;
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
  text(id, available ? '' : `БД недоступна · последний снимок: ${date(at)}`);
  $(id).classList.toggle('warning', !available);
}
function meter(id, value, total) {
  const element = $(id);
  const available = value != null && total > 0;
  element.hidden = !available;
  if (!available) return;
  const percent = Math.min(100, Math.max(0, value / total * 100));
  element.firstElementChild.style.width = `${percent}%`;
  element.setAttribute('aria-valuenow', percent.toFixed(1));
  element.setAttribute('aria-valuetext', `${bytes(value)} из ${bytes(total)}`);
}
function sparkline(id, points, field) {
  const svg = $(id);
  const key = JSON.stringify(points.map(point => [point.at, point[field]]));
  if (svg.dataset.points === key) return;
  svg.dataset.points = key;
  svg.replaceChildren();
  const available = points.filter(point => point[field] != null && Number.isFinite(point[field]));
  if (available.length < 2) {
    svg.setAttribute('aria-label', `${svg.dataset.label}: история недоступна`);
    const label = document.createElementNS('http://www.w3.org/2000/svg', 'text');
    label.setAttribute('x', '2'); label.setAttribute('y', '26');
    label.textContent = 'История недоступна'; svg.append(label);
    return;
  }
  const ns = 'http://www.w3.org/2000/svg';
  const start = Date.parse(points[0].at), end = Date.parse(points[points.length - 1].at);
  const span = Math.max(60000, end - start);
  const values = available.map(point => point[field]);
  const min = Math.min(...values), max = Math.max(...values);
  const range = Math.max(1, max - min);
  let path = '', previous = null;
  points.forEach(point => {
    const value = point[field], at = Date.parse(point.at);
    if (value == null || !Number.isFinite(value)) { previous = null; return; }
    const x = 2 + (at - start) / span * 196;
    const y = 35 - (value - min) / range * 29;
    path += `${previous == null || at - previous > 90000 ? 'M' : 'L'}${x},${y} `;
    previous = at;
  });
  const line = document.createElementNS(ns, 'path');
  line.setAttribute('d', path);
  line.setAttribute('fill', 'none');
  line.setAttribute('stroke', 'currentColor');
  line.setAttribute('stroke-width', '2');
  line.setAttribute('stroke-linecap', 'round');
  line.setAttribute('stroke-linejoin', 'round');
  svg.append(line);
  const format = value => field === 'queued' ? value : bytes(value);
  svg.setAttribute('aria-label', `${svg.dataset.label}: минимум ${format(min)}, максимум ${format(max)} за последний час`);
}
function render(data) {
  text('version', `Версия ${data.version || 'неизвестна'}`);
  const sum = status => data.queues.filter(q => q.status === status).reduce((n, q) => n + q.count, 0);
  const queued = sum('queued'), processing = sum('processing');
  text('queued', data.queuesUpdatedAt ? queued : '—');
  text('processing', data.queuesUpdatedAt ? processing : '—');
  sparkline('queue-sparkline', data.history || [], 'queued');
  text('metadata', data.runtime.metadataQueued);
  const workers = data.runtime.workers;
  const busyWorkers = workers.filter(w => w.state === 'BUSY').length;
  text('worker-active', busyWorkers);
  text('worker-total', `из ${workers.length} заняты`);
  const slots = $('worker-slots');
  const workerStates = workers.map(worker => worker.state);
  if (slots.dataset.states !== workerStates.join(',')) {
    slots.dataset.states = workerStates.join(',');
    slots.replaceChildren(...workers.map(worker => {
      const segment = document.createElement('span');
      segment.className = `worker-slot ${worker.state.toLowerCase()}`;
      return segment;
    }));
  }
  slots.setAttribute('aria-label', `${busyWorkers} из ${workers.length} заняты; ${workers.filter(worker => worker.state === 'BACKOFF').length} в паузе; ${workers.filter(worker => worker.state === 'STOPPED').length} остановлены`);
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
  text('updated', date(data.generatedAt));
}
function renderOutcomes() {
  if (!latest) return;
  const minutes = Number($('window').value);
  table('outcomes', Object.entries(categories).map(([key, label]) => [label, ...['completed', 'failed', 'cancelled_by_user'].map(status => latest.outcomesUpdatedAt ? latest.outcomes.find(o => o.minutes === minutes && o.category === key && o.status === status)?.count || 0 : null)]));
  const count = (window, status, category) => latest.outcomes
    .filter(o => o.minutes === window && o.status === status && (!category || o.category === category))
    .reduce((total, o) => total + o.count, 0);
  const dayCompleted = count(1440, 'completed'), dayFailed = count(1440, 'failed');
  text('stat-completed', latest.outcomesUpdatedAt ? dayCompleted : '—');
  text('stat-success-rate', latest.outcomesUpdatedAt && dayCompleted + dayFailed > 0
    ? `${Math.round(dayCompleted / (dayCompleted + dayFailed) * 100)}%` : '—');
  const completed = count(minutes, 'completed'), failed = count(minutes, 'failed');
  const cancelled = count(minutes, 'cancelled_by_user'), total = completed + failed + cancelled;
  [['outcome-total', total], ['outcome-completed', completed], ['outcome-failed', failed], ['outcome-cancelled', cancelled]]
    .forEach(([id, value]) => text(id, latest.outcomesUpdatedAt ? value : '—'));
  [completed, failed, cancelled].forEach((value, index) => {
    $('outcome-stack').children[index].style.width = `${total && latest.outcomesUpdatedAt ? value / total * 100 : 0}%`;
  });
  $('outcome-stack').setAttribute('aria-label', latest.outcomesUpdatedAt
    ? `Завершено ${completed}, неуспешно ${failed}, отменено ${cancelled}` : 'Нет данных о результатах задач');
  const byCategory = Object.entries(categories).map(([category, label]) => ({label, values: ['completed', 'failed', 'cancelled_by_user'].map(status => count(minutes, status, category))}));
  const maxCategory = Math.max(1, ...byCategory.map(entry => entry.values.reduce((sum, value) => sum + value, 0)));
  const bars = byCategory.map(({label, values}) => {
    const row = document.createElement('div'); row.className = 'outcome-bar-row';
    const name = document.createElement('span'); name.textContent = label;
    const track = document.createElement('div'); track.className = 'bar-track'; track.setAttribute('role', 'img');
    track.setAttribute('aria-label', latest.outcomesUpdatedAt
      ? `${label}: завершено ${values[0]}, неуспешно ${values[1]}, отменено ${values[2]}` : `${label}: данные недоступны`);
    const sum = values.reduce((n, value) => n + value, 0);
    values.forEach((value, index) => {
      const segment = document.createElement('span');
      segment.className = ['completed', 'failed', 'cancelled'][index];
      segment.style.width = `${latest.outcomesUpdatedAt ? value / maxCategory * 100 : 0}%`;
      track.append(segment);
    });
    const amount = document.createElement('strong'); amount.textContent = latest.outcomesUpdatedAt ? sum : '—';
    const details = document.createElement('span'); details.className = 'outcome-bar-details';
    details.textContent = latest.outcomesUpdatedAt
      ? `${values[0]} завершено · ${values[1]} неуспешно · ${values[2]} отменено` : 'Данные недоступны';
    row.append(name, track, amount, details);
    return row;
  });
  $('outcome-bars').replaceChildren(...bars);
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
    box.classList.toggle('has-errors', Number(value) > 0);
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
    line.setAttribute('fill', 'none'); line.setAttribute('stroke', i ? '#79bbff' : '#8196ac'); line.setAttribute('stroke-width', '2');
    line.setAttribute('stroke-linecap', 'round'); line.setAttribute('stroke-linejoin', 'round');
    $('chart').append(line);
  });
  [0, max].forEach(value => { const label = document.createElementNS(ns, 'text'); label.setAttribute('x', '0'); label.setAttribute('y', value ? '24' : '124'); label.textContent = value; $('chart').append(label); });
}
function bytes(value) {
  if (value == null) return '—';
  return value >= 1024 ** 3
    ? `${(value / 1024 ** 3).toLocaleString('ru-RU', {minimumFractionDigits: 2, maximumFractionDigits: 2})} GiB`
    : `${(value / 1024 ** 2).toLocaleString('ru-RU', {minimumFractionDigits: 1, maximumFractionDigits: 1})} MiB`;
}
function renderMemory(memory) {
  if (!memory) return;
  const point = memory.current;
  text('memory-status', memory.available ? '' : `Измерение недоступно · ${date(point?.at)}`);
  $('memory-status').classList.toggle('warning', !memory.available);
  text('heap-used', bytes(point?.heapUsed));
  text('heap-limit', `Выделено ${bytes(point?.heapCommitted)} · лимит ${bytes(point?.heapMax)}`);
  text('container-used', bytes(point?.containerUsed));
  text('container-limit', point?.containerUsed == null ? 'Измерение недоступно'
    : point?.containerLimit == null ? 'Лимит не задан или недоступен' : `Лимит ${bytes(point.containerLimit)}`);
  const percent = (used, max) => used != null && max > 0
    ? `${(used / max * 100).toLocaleString('ru-RU', {minimumFractionDigits: 1, maximumFractionDigits: 1})}%` : '';
  text('heap-percent', point?.heapMax > 0 ? percent(point.heapUsed, point.heapMax) : '');
  text('container-percent', point?.containerLimit > 0 ? percent(point.containerUsed, point.containerLimit) : '');
  meter('heap-meter', point?.heapUsed, point?.heapMax);
  meter('container-meter', point?.containerUsed, point?.containerLimit);
  $('heap-used').closest('.technical-card').classList.toggle('is-stale', !memory.available);
  $('container-used').closest('.technical-card').classList.toggle('is-stale', !memory.available);
  text('nonheap-used', bytes(point?.nonHeapUsed));
  text('memory-pools', `Metaspace: ${bytes(point?.metaspaceUsed)} · Code cache: ${bytes(point?.codeCacheUsed)}`);
  text('gc-value', `${point?.gcCount ?? '—'} сборок · ${point?.gcTimeMillis ?? '—'} мс`);
  text('gc-window', point?.gcWindowSeconds > 0 ? `За ${point.gcWindowSeconds} с` : '');
  text('memory-history-status', memory.historyAvailable ? '' : 'История памяти недоступна');
  $('memory-history-status').classList.toggle('warning', !memory.historyAvailable);
  const points = memory.history || [];
  sparkline('heap-sparkline', points, 'heapUsed');
  sparkline('container-sparkline', points, 'containerUsed');
  memoryChart('heap-chart', points, 'heapUsed');
  memoryChart('container-chart', points, 'containerUsed');
}
function renderHealth(data) {
  const critical = [], warning = [];
  const stale = Date.now() - Date.parse(data.generatedAt) > 10000;
  document.body.classList.toggle('snapshot-stale', stale);
  if (stale) critical.push('снимок состояния не обновляется');
  if (!data.queuesAvailable || !data.outcomesAvailable) warning.push('статистика базы недоступна');
  if (!data.memory.available || !data.memory.current || Date.now() - Date.parse(data.memory.current.at) > 20000) warning.push('нет свежих данных о памяти');
  const memory = data.memory.current;
  for (const [name, used, limit] of [['heap', memory?.heapUsed, memory?.heapMax], ['память контейнера', memory?.containerUsed, memory?.containerLimit]]) {
    if (used == null || !limit) continue;
    const percent = Math.round(used / limit * 100);
    if (percent >= 90) critical.push(`${name} заполнен на ${percent}%`);
    else if (percent >= 80) warning.push(`${name} заполнен на ${percent}%`);
  }
  if (data.runtime.workers.some(w => w.state === 'STOPPED')) critical.push('есть остановленные воркеры');
  if (data.runtime.workers.some(w => w.state === 'BACKOFF')) warning.push('воркер ждёт после ошибки');
  if (!data.statistics.available || !data.historyAvailable || !data.memory.historyAvailable) warning.push('история метрик сохраняется не полностью');
  $('health-title').className = critical.length ? 'critical' : warning.length ? 'warning' : 'healthy';
  text('health-title', critical.length ? 'Требуется внимание' : warning.length ? 'Нужна проверка' : 'Работает нормально');
  text('health-details', [...critical, ...warning].join(' · '));
  text('connection', stale ? 'Снимок устарел' : '');
}
function renderBackup(status) {
  const descriptions = {
    idle: '',
    running: `Создаётся дамп с ${date(status.startedAt)}`,
    completed: `Готово: ${status.fileName} · ${bytes(status.sizeBytes)}`,
    failed: `Не удалось создать дамп (${date(status.finishedAt)})`
  };
  text('backup-status', descriptions[status.state] ?? 'Состояние неизвестно');
  $('backup').disabled = !csrfReady || status.state === 'running';
}
async function refreshDatabaseSize() {
  if (Date.now() < databaseSizeNextAt) return;
  databaseSizeNextAt = Date.now() + 30000;
  try {
    const result = await request('/admin/api/database-size');
    text('database-size', bytes(result.bytes));
    text('database-size-status', '');
    databaseSizeLoaded = true;
    databaseSizeNextAt = Date.now() + 300000;
  } catch (_) {
    text('database-size-status', databaseSizeLoaded ? 'Не удалось обновить' : 'Размер недоступен');
  }
}
function memoryChart(id, points, used) {
  const key = JSON.stringify(points.map(p => [p.at, p[used]]));
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
  const max = Math.max(1024 ** 2, ...points.map(p => p[used] || 0)) * 1.15;
  const end = Date.parse(points[points.length - 1].at), start = end - 3600000;
  let path = '', previous = null, last = null;
  points.forEach(p => {
    const at = Date.parse(p.at);
    if (p[used] == null) { previous = null; return; }
    const x = 60 + (at - start) / 3600000 * 530, y = 120 - p[used] / max * 100;
    path += `${previous == null || at - previous > 90000 ? 'M' : 'L'}${x},${y} `;
    previous = at;
    last = [x, y];
  });
  const line = document.createElementNS(ns, 'path'); line.setAttribute('d', path);
  line.setAttribute('fill', 'none'); line.setAttribute('stroke', '#79bbff'); line.setAttribute('stroke-width', '2');
  line.setAttribute('stroke-linecap', 'round'); line.setAttribute('stroke-linejoin', 'round');
  svg.append(line);
  if (last) {
    const dot = document.createElementNS(ns, 'circle');
    dot.setAttribute('cx', last[0]); dot.setAttribute('cy', last[1]); dot.setAttribute('r', '3'); dot.setAttribute('fill', '#79bbff'); svg.append(dot);
  }
  label((max / 1024 ** 2).toFixed(0), 0, 24); label('0', 0, 124);
  label('1 ч назад', 60, 138); label('сейчас', 546, 138);
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
      $('logout').append(input); $('logout').querySelector('button').disabled = false; csrfReady = true; csrfToken = csrf.token;
    }
    latest = await request('/admin/api/snapshot'); render(latest); renderHealth(latest);
    try { renderBackup(await request('/admin/api/backup')); }
    catch (_) { text('backup-status', 'Не удалось получить состояние дампа'); }
    await refreshDatabaseSize();
    retry = 2000;
  } catch (_) { document.body.classList.add('snapshot-stale'); text('connection', 'Связь потеряна'); text('health-title', 'Состояние бота неизвестно'); text('health-details', 'Нет связи с админкой'); $('health-title').className = 'critical'; retry = Math.min(retry * 2, 30000); }
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
$('backup').addEventListener('click', async () => {
  $('backup').disabled = true;
  try {
    if (!backupEstimated) {
      const estimate = await request('/admin/api/backup/estimate');
      text('backup-estimate', `Размер БД: ${bytes(estimate.databaseBytes)}. Свободно: ${bytes(estimate.freeBytes)}. Сжатый дамп может отличаться.${estimate.freeBytes < estimate.databaseBytes ? ' Места может не хватить.' : ''}`);
      $('backup-estimate').classList.toggle('warning', estimate.freeBytes < estimate.databaseBytes);
      backupEstimated = true;
      $('backup').textContent = 'Создать дамп';
      $('backup').disabled = false;
      return;
    }
    const response = await fetch('/admin/api/backup', {method: 'POST', headers: {'X-CSRF-TOKEN': csrfToken}, cache: 'no-store', signal: AbortSignal.timeout(5000)});
    if (response.status === 401 || response.status === 403) { location.assign('/login'); return; }
    if (!response.ok) throw new Error('http');
    backupEstimated = false;
    $('backup').textContent = 'Оценить размер';
    text('backup-estimate', '');
    renderBackup(await response.json());
  } catch (_) { text('backup-status', backupEstimated ? 'Не удалось запустить дамп' : 'Не удалось оценить размер базы'); $('backup').disabled = false; }
});
document.addEventListener('visibilitychange', () => { clearTimeout(timer); if (document.hidden) text('connection', 'Обновление приостановлено'); else poll(); });
poll();
