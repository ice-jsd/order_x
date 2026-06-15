const CONFIG_KEY = 'handsFormHelperConfig';
const STATE_KEY = 'handsFormHelperState';
const POLL_ALARM_NAME = 'hands-form-helper-poll';
const DEFAULT_BACKEND_BASE_URL = 'http://62.234.211.209:8081';
const DEFAULT_CONFIG = {
  backendBaseUrl: DEFAULT_BACKEND_BASE_URL,
  apiSecret: 'change-me-hands-extension-secret',
  autoPoll: false,
  pollSeconds: 1
};
const SUCCESS_CODES = new Set(['200', '0000']);
const DEFAULT_STATE = {
  currentTask: null,
  lastTaskSnapshot: null,
  workerId: '',
  status: 'idle',
  lastMessage: '',
  flowStage: 'idle',
  flowNote: '',
  flowHistory: []
};

let pollTimer = null;
let pollInFlight = false;

function normalizePollSeconds(value) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    return 1;
  }
  return Math.max(1, Math.floor(parsed));
}

function clearPollTimer() {
  if (pollTimer !== null) {
    clearTimeout(pollTimer);
    pollTimer = null;
  }
}

async function getConfig() {
  const store = await chrome.storage.local.get(CONFIG_KEY);
  const config = { ...DEFAULT_CONFIG, ...(store[CONFIG_KEY] || {}) };
  return {
    ...config,
    pollSeconds: normalizePollSeconds(config.pollSeconds)
  };
}

async function setConfig(config) {
  const merged = { ...DEFAULT_CONFIG, ...config };
  await chrome.storage.local.set({
    [CONFIG_KEY]: {
      ...merged,
      pollSeconds: normalizePollSeconds(merged.pollSeconds)
    }
  });
}

async function migrateLegacyConfig() {
  const store = await chrome.storage.local.get(CONFIG_KEY);
  const raw = store[CONFIG_KEY] || {};
  const backendBaseUrl = String(raw.backendBaseUrl || '').trim();
  const shouldMigrateBackendBaseUrl = !backendBaseUrl
    || backendBaseUrl === 'http://127.0.0.1:8080'
    || backendBaseUrl === 'http://localhost:8080'
    || backendBaseUrl === 'http://127.0.0.1:8081'
    || backendBaseUrl === 'http://localhost:8081';
  if (
    !Object.prototype.hasOwnProperty.call(raw, 'pollSeconds')
    || Number(raw.pollSeconds) === 30
    || shouldMigrateBackendBaseUrl
  ) {
    await setConfig({
      ...raw,
      pollSeconds: 1,
      backendBaseUrl: shouldMigrateBackendBaseUrl ? DEFAULT_BACKEND_BASE_URL : backendBaseUrl
    });
  }
}

async function getState() {
  const store = await chrome.storage.local.get(STATE_KEY);
  return { ...DEFAULT_STATE, ...(store[STATE_KEY] || {}) };
}

async function setState(patch) {
  const current = await getState();
  const next = { ...current, ...patch };
  await chrome.storage.local.set({ [STATE_KEY]: next });
  return next;
}

async function ensureWorkerId() {
  const state = await getState();
  if (state.workerId) {
    return state.workerId;
  }
  const workerId = `hands-ext-${crypto.randomUUID()}`;
  await setState({ workerId });
  return workerId;
}

function flowTimestamp() {
  return new Date().toLocaleTimeString('zh-CN', {
    hour12: false,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  });
}

function nextFlowHistory(history, stage, note) {
  const items = Array.isArray(history) ? [...history] : [];
  const entry = {
    stage,
    note: note || '',
    at: Date.now(),
    time: flowTimestamp()
  };
  const last = items[items.length - 1];
  if (last && last.stage === entry.stage && last.note === entry.note) {
    items[items.length - 1] = entry;
  } else {
    items.push(entry);
  }
  return items.slice(-12);
}

async function updateFlow(stage, note, patch = {}) {
  const current = await getState();
  const nextPatch = {
    flowStage: stage,
    flowNote: note || '',
    lastMessage: note || current.lastMessage,
    flowHistory: nextFlowHistory(current.flowHistory, stage, note),
    ...patch
  };
  return setState(nextPatch);
}

async function apiRequest(path, options = {}) {
  const config = await getConfig();
  const url = `${String(config.backendBaseUrl || '').replace(/\/+$/, '')}${path}`;
  const response = await fetch(url, {
    method: options.method || 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Hands-Extension-Secret': config.apiSecret || '',
      ...(options.headers || {})
    },
    body: options.body ? JSON.stringify(options.body) : undefined
  });
  const json = await response.json();
  const code = String(json.code ?? '');
  if (!response.ok || !SUCCESS_CODES.has(code)) {
    throw new Error(json.msg || `HTTP ${response.status}`);
  }
  return json.data ?? null;
}

async function scheduleAlarm() {
  const config = await getConfig();
  await chrome.alarms.clear(POLL_ALARM_NAME);
  if (!config.autoPoll) {
    return;
  }
  const periodInMinutes = Math.max(0.5, Number(config.pollSeconds || 1) / 60);
  chrome.alarms.create(POLL_ALARM_NAME, {
    delayInMinutes: Math.min(periodInMinutes, 0.5),
    periodInMinutes
  });
}

async function scheduleNextPoll(delayMs) {
  clearPollTimer();
  const config = await getConfig();
  if (!config.autoPoll) {
    return;
  }
  pollTimer = setTimeout(() => {
    pollTimer = null;
    void kickAutoPoll('loop');
  }, Math.max(1000, delayMs));
}

async function kickAutoPoll(reason = 'auto') {
  const config = await getConfig();
  if (!config.autoPoll) {
    clearPollTimer();
    return;
  }
  if (pollInFlight) {
    return;
  }
  const state = await getState();
  if (state.currentTask?.executionId) {
    clearPollTimer();
    return;
  }
  pollInFlight = true;
  try {
    const checkingMessage = reason === 'save' ? '自动轮询已启用，正在尝试领取' : '自动轮询检查中';
    await setState({ status: 'idle', lastMessage: checkingMessage });
    const task = await claimNextTask(false);
    if (!task) {
      await setState({ status: 'idle', lastMessage: '暂时没有新任务，1 秒后继续检查' });
      await scheduleNextPoll(config.pollSeconds * 1000);
    } else {
      clearPollTimer();
    }
  } catch (error) {
    await setState({ status: 'error', lastMessage: `自动轮询失败: ${error.message || error}` });
    await scheduleNextPoll(config.pollSeconds * 1000);
  } finally {
    pollInFlight = false;
  }
}

async function openTaskTab(task) {
  const tabs = await chrome.tabs.query({});
  const matched = tabs.find(tab => typeof tab.url === 'string' && tab.url.startsWith(task.eventUrl));
  if (matched?.id) {
    await chrome.tabs.update(matched.id, { active: true, url: task.eventUrl });
    return matched.id;
  }
  const created = await chrome.tabs.create({ url: task.eventUrl, active: true });
  return created.id;
}

async function closeTaskTab(tabId) {
  if (!tabId) {
    return;
  }
  try {
    await chrome.tabs.remove(tabId);
  } catch (error) {
    const message = String(error?.message || error || '');
    if (message.includes('No tab with id')) {
      return;
    }
    throw error;
  }
}

async function sendTaskToTab(tabId, task) {
  if (!tabId || !task) {
    return;
  }
  try {
    await updateFlow('dispatching', '已向页面发送执行指令', {
      currentTask: { ...task, tabId }
    });
    await chrome.tabs.sendMessage(tabId, { type: 'run-hands-task', task });
  } catch (error) {
    await setState({ lastMessage: `页面注入失败: ${error.message || error}` });
  }
}

async function claimNextTask(manual = false) {
  const state = await getState();
  if (state.currentTask) {
    return state.currentTask;
  }
  const workerId = await ensureWorkerId();
  try {
    const task = await apiRequest('/ticket/hands-form-extension/claim', {
      body: { workerId }
    });
    if (!task) {
      if (manual) {
        await setState({ status: 'idle', lastMessage: '当前没有可领取的表单任务' });
      }
      return null;
    }
    const tabId = await openTaskTab(task);
    const currentTask = { ...task, tabId, claimedAt: Date.now() };
    await setState({ currentTask, lastTaskSnapshot: currentTask, status: 'running', flowHistory: [] });
    await updateFlow('claimed', `已领取执行 ${task.executionId}`, {
      currentTask,
      lastTaskSnapshot: currentTask,
      status: 'running'
    });
    await updateFlow('opening_page', '正在打开目标页面', {
      currentTask,
      lastTaskSnapshot: currentTask,
      status: 'running'
    });
    return currentTask;
  } catch (error) {
    await setState({ status: 'error', lastMessage: `领取任务失败: ${error.message || error}` });
    throw error;
  }
}

async function heartbeatCurrentTask() {
  const state = await getState();
  const task = state.currentTask;
  if (!task?.executionId || !state.workerId) {
    return;
  }
  try {
    await apiRequest('/ticket/hands-form-extension/heartbeat', {
      body: {
        executionId: task.executionId,
        workerId: state.workerId
      }
    });
  } catch (error) {
    await setState({ status: 'error', lastMessage: `任务心跳失败: ${error.message || error}` });
  }
}

async function reportTaskResult(payload) {
  const state = await getState();
  const task = state.currentTask;
  if (!task?.executionId) {
    return;
  }
  try {
    await apiRequest('/ticket/hands-form-extension/report', {
      body: {
        executionId: task.executionId,
        workerId: state.workerId,
        success: Boolean(payload.success),
        message: payload.message || '',
        orderId: payload.orderId || '',
        resultUrl: payload.resultUrl || '',
        rawResult: JSON.stringify(payload.rawResult || {})
      }
    });
    const nextStatus = payload.success ? 'success' : 'failed';
    const nextMessage = payload.success
      ? `执行 ${task.executionId} 已成功回写`
      : `执行 ${task.executionId} 已回写失败结果`;
    await closeTaskTab(task.tabId);
    await setState({
      currentTask: null,
      lastTaskSnapshot: task,
      status: nextStatus
    });
    await updateFlow(payload.success ? 'success' : 'failed', nextMessage, {
      currentTask: null,
      lastTaskSnapshot: task,
      status: nextStatus
    });
    const config = await getConfig();
    if (config.autoPoll) {
      await kickAutoPoll('after-report');
    }
  } catch (error) {
    await setState({ status: 'error', lastMessage: `结果回写失败: ${error.message || error}` });
  }
}

async function clearCurrentTask() {
  const state = await getState();
  await closeTaskTab(state.currentTask?.tabId);
  await setState({
    currentTask: null,
    lastTaskSnapshot: null,
    status: 'idle',
    lastMessage: '已清除当前任务',
    flowStage: 'idle',
    flowNote: '',
    flowHistory: []
  });
  clearPollTimer();
  await kickAutoPoll('clear');
}

async function maybeDispatchToTab(tabId, url) {
  const state = await getState();
  const task = state.currentTask;
  if (!task || !url || !String(url).startsWith('https://event.hands.net/')) {
    return;
  }
  const currentTask = { ...task, tabId, currentPageUrl: url };
  await updateFlow('page_ready', '目标页面已就绪，等待接管', {
    currentTask,
    lastTaskSnapshot: currentTask,
    status: 'running'
  });
  await sendTaskToTab(tabId, currentTask);
}

chrome.runtime.onInstalled.addListener(async () => {
  await migrateLegacyConfig();
  await ensureWorkerId();
  await scheduleAlarm();
  await kickAutoPoll('startup');
});

chrome.runtime.onStartup.addListener(async () => {
  await migrateLegacyConfig();
  await ensureWorkerId();
  await scheduleAlarm();
  await kickAutoPoll('startup');
});

chrome.alarms.onAlarm.addListener(async alarm => {
  if (alarm.name !== POLL_ALARM_NAME) {
    return;
  }
  const state = await getState();
  if (state.currentTask) {
    await heartbeatCurrentTask();
    const tabs = await chrome.tabs.query({});
    const exists = tabs.some(tab => tab.id === state.currentTask.tabId);
    if (!exists) {
      const tabId = await openTaskTab(state.currentTask);
      await setState({ currentTask: { ...state.currentTask, tabId } });
    }
    await sendTaskToTab(state.currentTask.tabId, state.currentTask);
    return;
  }
  await kickAutoPoll('alarm');
});

chrome.tabs.onUpdated.addListener(async (tabId, changeInfo, tab) => {
  if (changeInfo.status === 'complete') {
    await maybeDispatchToTab(tabId, tab.url || '');
  }
});

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  (async () => {
    switch (message?.type) {
      case 'hands-page-ready':
        await maybeDispatchToTab(sender.tab?.id, message.url || sender.tab?.url || '');
        sendResponse({ ok: true });
        return;
      case 'hands-task-result':
        await reportTaskResult(message.payload || {});
        sendResponse({ ok: true });
        return;
      case 'hands-task-progress': {
        const state = await getState();
        const currentTask = state.currentTask
          ? {
              ...state.currentTask,
              ...(message.url ? { currentPageUrl: message.url } : {})
            }
          : state.currentTask;
        await updateFlow(message.stage || 'running', message.detail || '', {
          currentTask,
          lastTaskSnapshot: currentTask || state.lastTaskSnapshot,
          status: state.status === 'idle' ? 'running' : state.status
        });
        sendResponse({ ok: true });
        return;
      }
      case 'hands-claim-next':
        await claimNextTask(true);
        sendResponse({ ok: true });
        return;
      case 'hands-clear-task':
        await clearCurrentTask();
        sendResponse({ ok: true });
        return;
      case 'hands-save-config':
        await setConfig(message.config || {});
        await scheduleAlarm();
        if (!message.config?.autoPoll) {
          clearPollTimer();
        }
        await kickAutoPoll('save');
        sendResponse({ ok: true });
        return;
      case 'hands-get-state':
        sendResponse({
          ok: true,
          config: await getConfig(),
          state: await getState()
        });
        return;
      default:
        sendResponse({ ok: false, message: 'unknown message' });
    }
  })().catch(async error => {
    await setState({ status: 'error', lastMessage: error.message || String(error) });
    sendResponse({ ok: false, message: error.message || String(error) });
  });
  return true;
});
