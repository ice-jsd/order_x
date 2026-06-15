async function callBackground(message) {
  const response = await chrome.runtime.sendMessage(message);
  if (!response?.ok) {
    throw new Error(response?.message || '未知错误');
  }
  return response;
}

function escapeHtml(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

const FLOW_STAGE_MAP = {
  claimed: 'claim',
  opening_page: 'open',
  page_ready: 'open',
  dispatching: 'open',
  entry_form_ready: 'fill',
  entry_submitted: 'fill',
  entry_submit_failed: 'fill',
  confirm_ready: 'confirm',
  confirm_submitted: 'confirm',
  confirm_submit_failed: 'confirm',
  success: 'report',
  failed: 'report',
  unknown_page: 'report'
};

const STAGE_META = {
  idle: { title: '等待领取', detail: '当前没有正在执行的表单任务。' },
  claim: { title: '任务已领取', detail: '扩展已经从后台接到这条表单执行单。' },
  open: { title: '正在接管页面', detail: '正在打开活动页并等待目标页面准备完成。' },
  fill: { title: '正在填写资料', detail: '姓名、片假名和邮箱会在页面上直接完成填写。' },
  confirm: { title: '正在确认提交', detail: '会点击绿色确认按钮并推进到最终提交。' },
  report: { title: '正在回写结果', detail: '正在抓取受付番号并把结果同步回后台。' },
  success: { title: '已完成回写', detail: '这条任务已经成功回写到后台执行记录。' },
  failed: { title: '执行失败', detail: '这条任务已经结束，并把失败结果回写给后台。' }
};

function statusMeta(status) {
  const map = {
    idle: { label: '待命', className: 'is-idle' },
    running: { label: '执行中', className: 'is-running' },
    success: { label: '已完成', className: 'is-success' },
    failed: { label: '失败', className: 'is-failed' },
    error: { label: '异常', className: 'is-error' }
  };
  return map[status] || { label: status || '未知', className: 'is-idle' };
}

function renderTaskCard(label, value, extraClass = '', wide = false) {
  const safeValue = value ? escapeHtml(value) : '—';
  return `
    <div class="task-card${wide ? ' wide' : ''}">
      <div class="task-card-label">${escapeHtml(label)}</div>
      <div class="task-card-value${extraClass ? ` ${extraClass}` : ''}">${safeValue}</div>
    </div>
  `;
}

function resolveCurrentStage(state) {
  const status = state.status || 'idle';
  if (status === 'success') {
    return { key: 'success', ...STAGE_META.success };
  }
  if (status === 'failed' || status === 'error') {
    return { key: 'failed', ...STAGE_META.failed };
  }
  const flowKey = FLOW_STAGE_MAP[state.flowStage || ''] || 'idle';
  return { key: flowKey, ...(STAGE_META[flowKey] || STAGE_META.idle) };
}

function renderCurrentStage(state) {
  const stage = resolveCurrentStage(state);
  const note = state.flowNote || state.lastMessage || stage.detail;
  return `
    <div class="stage-card is-${escapeHtml(stage.key)}">
      <div class="stage-kicker">当前阶段</div>
      <div class="stage-title">${escapeHtml(stage.title)}</div>
      <div class="stage-note">${escapeHtml(note)}</div>
    </div>
  `;
}

function renderTaskSnapshot(state) {
  const task = state.currentTask || state.lastTaskSnapshot;
  if (!task) {
    return `
      <div class="status-section">
        <div class="status-title">当前任务</div>
        <div class="empty-task">当前没有已领取的任务，领取后这里会显示任务资料。</div>
      </div>
    `;
  }

  return `
    <div class="status-section">
      <div class="status-title">当前任务</div>
      <div class="task-grid">
        ${renderTaskCard('执行编号', task.executionId ? String(task.executionId) : '', 'code')}
        ${renderTaskCard('任务名称', task.taskName || '')}
        ${renderTaskCard('场次标签', task.sessionLabel || '')}
        ${renderTaskCard('邮箱', task.email || '', 'code')}
        ${renderTaskCard('姓名 / 片假名', [task.fullName || '', task.furigana || ''].filter(Boolean).join(' / '))}
        ${renderTaskCard('活动链接', task.currentPageUrl || task.eventUrl || '', 'code', true)}
      </div>
    </div>
  `;
}

function renderStatus(state) {
  const meta = statusMeta(state.status);
  const autoPollEnabled = document.getElementById('autoPoll')?.checked;
  const statusHint = autoPollEnabled ? '自动轮询已开启' : '当前为手动领取';

  document.body.dataset.state = state.status || 'idle';
  document.getElementById('statusBox').innerHTML = `
    <div class="status-header">
      <div>
        <div class="status-title">运行快照</div>
        <div class="status-subtitle">${escapeHtml(statusHint)}</div>
      </div>
      <div class="status-pill ${meta.className}">${escapeHtml(meta.label)}</div>
    </div>
    ${renderCurrentStage(state)}
    ${renderTaskSnapshot(state)}
  `;
}

async function refresh() {
  const response = await callBackground({ type: 'hands-get-state' });
  const { config, state } = response;
  document.getElementById('backendBaseUrl').value = config.backendBaseUrl || '';
  document.getElementById('apiSecret').value = config.apiSecret || '';
  document.getElementById('pollSeconds').value = config.pollSeconds || 1;
  document.getElementById('autoPoll').checked = Boolean(config.autoPoll);
  renderStatus(state);
}

async function saveConfig() {
  await callBackground({
    type: 'hands-save-config',
    config: {
      backendBaseUrl: document.getElementById('backendBaseUrl').value.trim(),
      apiSecret: document.getElementById('apiSecret').value.trim(),
      pollSeconds: Number(document.getElementById('pollSeconds').value || 1),
      autoPoll: document.getElementById('autoPoll').checked
    }
  });
  await refresh();
}

async function claimTask() {
  await callBackground({ type: 'hands-claim-next' });
  await refresh();
}

async function clearTask() {
  await callBackground({ type: 'hands-clear-task' });
  await refresh();
}

document.getElementById('saveBtn').addEventListener('click', () => saveConfig().catch(alert));
document.getElementById('claimBtn').addEventListener('click', () => claimTask().catch(alert));
document.getElementById('clearBtn').addEventListener('click', () => clearTask().catch(alert));
document.getElementById('autoPoll').addEventListener('change', () => saveConfig().catch(alert));

const refreshTimer = window.setInterval(() => {
  refresh().catch(() => {});
}, 1000);

window.addEventListener('beforeunload', () => {
  window.clearInterval(refreshTimer);
});

refresh().catch(error => {
  document.getElementById('statusBox').textContent = error.message || String(error);
});
