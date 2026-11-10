const state = {
  records: [],
  profile: { height: null, targetWeight: null },
};

// ---------- 工具函数 ----------

const api = async (path, options) => {
  const res = await fetch(path, options);
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || '请求失败');
  return data;
};

const fmt = (n) => Number(n).toFixed(1);

const getToday = () => {
  const d = new Date();
  const month = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${month}-${day}`;
};

// ---------- 渲染 ----------

const renderStats = () => {
  const currentEl = document.querySelector('#stat-current');
  const changeEl = document.querySelector('#stat-change');
  const bmiEl = document.querySelector('#stat-bmi');
  const targetEl = document.querySelector('#stat-target');

  if (state.records.length === 0) {
    currentEl.textContent = '--';
    changeEl.textContent = '--';
    bmiEl.textContent = '--';
    targetEl.textContent = '--';
    return;
  }

  // records 按日期降序：第一条是最新，最后一条是最早
  const current = state.records[0];
  const first = state.records[state.records.length - 1];

  currentEl.textContent = `${fmt(current.weight)} kg`;

  const diff = current.weight - first.weight;
  changeEl.textContent = `${diff > 0 ? '+' : ''}${fmt(diff)} kg`;
  changeEl.classList.toggle('stat-card__value--good', diff < 0);
  changeEl.classList.toggle('stat-card__value--bad', diff > 0);

  const { height, targetWeight } = state.profile;
  bmiEl.textContent = height ? fmt(current.weight / Math.pow(height / 100, 2)) : '--';

  if (targetWeight) {
    const remain = current.weight - targetWeight;
    targetEl.textContent = remain <= 0 ? '已达成' : `${fmt(remain)} kg`;
  } else {
    targetEl.textContent = '--';
  }
};

const renderRecords = () => {
  const tbody = document.querySelector('#records-body');
  const empty = document.querySelector('#records-empty');
  tbody.innerHTML = '';
  empty.hidden = state.records.length > 0;

  state.records.forEach((record) => {
    const tr = document.createElement('tr');

    const tdDate = document.createElement('td');
    tdDate.textContent = record.date;

    const tdWeight = document.createElement('td');
    tdWeight.textContent = fmt(record.weight);

    const tdNote = document.createElement('td');
    tdNote.textContent = record.note || '—';

    const tdOp = document.createElement('td');
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'records__delete';
    btn.dataset.id = record.id;
    btn.textContent = '删除';
    tdOp.appendChild(btn);

    tr.append(tdDate, tdWeight, tdNote, tdOp);
    tbody.appendChild(tr);
  });
};

const renderChart = () => {
  const canvas = document.querySelector('#trend-chart');
  const ctx = canvas.getContext('2d');
  const dpr = window.devicePixelRatio || 1;
  const width = canvas.clientWidth;
  const height = canvas.clientHeight;

  canvas.width = width * dpr;
  canvas.height = height * dpr;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, width, height);

  const points = [...state.records].sort((a, b) => a.date.localeCompare(b.date));
  if (points.length === 0) {
    ctx.fillStyle = '#7a8580';
    ctx.font = '14px sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText('暂无数据', width / 2, height / 2);
    return;
  }

  const pad = { top: 20, right: 20, bottom: 30, left: 44 };
  const plotW = width - pad.left - pad.right;
  const plotH = height - pad.top - pad.bottom;

  const weights = points.map((p) => p.weight);
  let min = Math.min(...weights);
  let max = Math.max(...weights);
  if (min === max) {
    min -= 1;
    max += 1;
  } else {
    const margin = (max - min) * 0.2;
    min -= margin;
    max += margin;
  }

  const x = (i) =>
    points.length === 1 ? pad.left + plotW / 2 : pad.left + (i / (points.length - 1)) * plotW;
  const y = (w) => pad.top + (1 - (w - min) / (max - min)) * plotH;

  // 网格线与 Y 轴刻度
  ctx.strokeStyle = '#e3e8e5';
  ctx.fillStyle = '#7a8580';
  ctx.font = '11px sans-serif';
  ctx.textAlign = 'right';
  const gridCount = 4;
  for (let i = 0; i <= gridCount; i++) {
    const gy = pad.top + (i / gridCount) * plotH;
    const gv = max - (i / gridCount) * (max - min);
    ctx.beginPath();
    ctx.moveTo(pad.left, gy);
    ctx.lineTo(width - pad.right, gy);
    ctx.stroke();
    ctx.fillText(gv.toFixed(1), pad.left - 6, gy + 4);
  }

  // X 轴日期标签（最多 6 个）
  ctx.textAlign = 'center';
  const step = Math.max(1, Math.ceil(points.length / 6));
  points.forEach((p, i) => {
    if (i % step !== 0 && i !== points.length - 1) return;
    ctx.fillText(p.date.slice(5), x(i), height - 10);
  });

  // 目标体重参考线
  const target = state.profile.targetWeight;
  if (target && target >= min && target <= max) {
    ctx.strokeStyle = '#c0453e';
    ctx.setLineDash([5, 4]);
    ctx.beginPath();
    ctx.moveTo(pad.left, y(target));
    ctx.lineTo(width - pad.right, y(target));
    ctx.stroke();
    ctx.setLineDash([]);
  }

  // 折线
  ctx.strokeStyle = '#3d8a5f';
  ctx.lineWidth = 2;
  ctx.beginPath();
  points.forEach((p, i) => {
    if (i === 0) ctx.moveTo(x(i), y(p.weight));
    else ctx.lineTo(x(i), y(p.weight));
  });
  ctx.stroke();

  // 数据点
  ctx.fillStyle = '#3d8a5f';
  points.forEach((p, i) => {
    ctx.beginPath();
    ctx.arc(x(i), y(p.weight), 3.5, 0, Math.PI * 2);
    ctx.fill();
  });
};

const fillSettingsForm = () => {
  document.querySelector('#input-height').value = state.profile.height ?? '';
  document.querySelector('#input-target').value = state.profile.targetWeight ?? '';
};

const showMessage = (text, ok) => {
  const el = document.querySelector('#form-message');
  el.textContent = text;
  el.classList.toggle('form__message--ok', ok);
};

// ---------- 数据加载 ----------

const loadAll = async () => {
  const [records, profile] = await Promise.all([api('/api/records'), api('/api/profile')]);
  state.records = records;
  state.profile = profile;
  renderStats();
  renderRecords();
  renderChart();
  fillSettingsForm();
};

// ---------- 事件绑定 ----------

document.querySelector('#checkin-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const date = document.querySelector('#input-date').value;
  const weight = document.querySelector('#input-weight').value;
  const note = document.querySelector('#input-note').value.trim();

  try {
    await api('/api/records', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ date, weight, note }),
    });
    showMessage('打卡成功！', true);
    document.querySelector('#input-weight').value = '';
    document.querySelector('#input-note').value = '';
    await loadAll();
  } catch (err) {
    showMessage(err.message, false);
  }
});

document.querySelector('#settings-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  try {
    await api('/api/profile', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        height: document.querySelector('#input-height').value,
        targetWeight: document.querySelector('#input-target').value,
      }),
    });
    await loadAll();
    showMessage('设置已保存', true);
  } catch (err) {
    showMessage(err.message, false);
  }
});

// 删除记录（事件委托）
document.querySelector('#records-body').addEventListener('click', async (e) => {
  const btn = e.target.closest('.records__delete');
  if (!btn) return;
  if (!window.confirm('确定删除这条记录吗？')) return;
  await api(`/api/records/${btn.dataset.id}`, { method: 'DELETE' });
  await loadAll();
});

// 选择已有打卡的日期时自动回填，方便修改
document.querySelector('#input-date').addEventListener('change', (e) => {
  const record = state.records.find((r) => r.date === e.target.value);
  document.querySelector('#input-weight').value = record ? record.weight : '';
  document.querySelector('#input-note').value = record ? record.note : '';
});

window.addEventListener('resize', renderChart);

// ---------- 初始化 ----------

document.querySelector('#input-date').value = getToday();
loadAll();
