const FAILURE_MARKERS = [
  'Google reCAPTCHAの認証に失敗しました。',
  'reCAPTCHAの認証に失敗しました。',
  '認証に失敗しました。再度送信してください。'
];

const SUCCESS_MARKERS = [
  'お客様のお申し込みを受け付けました。',
  'ご入力いただいたメールアドレス宛に自動でメールをお送りしていますのでご確認ください。',
  'ご入力の内容は正常に送信されました。',
  '受付済み'
];

function pageText() {
  return document.body?.innerText || '';
}

function findOrderNumber() {
  const text = pageText();
  const patterns = [/登録番号[:：]?\s*([0-9]{4,})/, /受付番号[:：]?\s*([0-9]{4,})/, /申込番号[:：]?\s*([0-9]{4,})/];
  for (const pattern of patterns) {
    const match = text.match(pattern);
    if (match) {
      return match[1];
    }
  }
  return '';
}

function hasSuccessMarker() {
  const text = pageText();
  return SUCCESS_MARKERS.some(marker => text.includes(marker));
}

function hasFailureMarker() {
  const text = pageText();
  return FAILURE_MARKERS.some(marker => text.includes(marker));
}

async function reportProgress(stage, detail) {
  try {
    await chrome.runtime.sendMessage({
      type: 'hands-task-progress',
      stage,
      detail,
      url: location.href
    });
  } catch (_error) {
    // 忽略页面侧进度上报失败，避免打断表单操作
  }
}

function fillInput(name, value) {
  const input = document.querySelector(`input[name="${name}"]`);
  if (!input) {
    return false;
  }
  input.focus();
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
  input.dispatchEvent(new Event('change', { bubbles: true }));
  return true;
}

function getStageState(stageKey) {
  const raw = sessionStorage.getItem(stageKey) || '';
  if (!raw) {
    return { stage: '', timestamp: 0 };
  }
  const atIndex = raw.lastIndexOf('@');
  if (atIndex <= 0) {
    return { stage: raw, timestamp: 0 };
  }
  const stage = raw.slice(0, atIndex);
  const timestamp = Number(raw.slice(atIndex + 1)) || 0;
  return { stage, timestamp };
}

function markStage(stageKey, stage) {
  sessionStorage.setItem(stageKey, `${stage}@${Date.now()}`);
}

function shouldAttemptStage(stageKey, stage, cooldownMs) {
  const current = getStageState(stageKey);
  if (current.stage !== stage) {
    return true;
  }
  if (!current.timestamp) {
    return true;
  }
  return Date.now() - current.timestamp >= cooldownMs;
}

function formHasVisibleEditableFields(form) {
  if (!form) {
    return false;
  }
  return Array.from(form.querySelectorAll('input, textarea, select')).some(el => {
    const type = (el.getAttribute('type') || '').toLowerCase();
    if (type === 'hidden') {
      return false;
    }
    const rect = el.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0 && !el.disabled;
  });
}

function findSubmitLikeButton(root, preferredText) {
  const elements = Array.from((root || document).querySelectorAll('button, input[type="submit"], input[type="button"], a'));
  const byText = elements.find(el => (el.innerText || el.value || '').includes(preferredText));
  if (byText) {
    return byText;
  }
  return (
    elements.find(el => (el.getAttribute('name') || '') === 'Submit') ||
    elements.find(el => (el.getAttribute('type') || '').toLowerCase() === 'submit') ||
    null
  );
}

function findConfirmSubmitButton(form) {
  return (
    form.querySelector('button.-green[name="Submit"]') ||
    form.querySelector('button[name="Submit"]') ||
    findSubmitLikeButton(form, 'この内容で送信する')
  );
}

function triggerSubmit(form, button) {
  if (button && typeof button.scrollIntoView === 'function') {
    button.scrollIntoView({ block: 'center', inline: 'center' });
  }
  if (button && typeof button.focus === 'function') {
    button.focus();
  }
  if (form && typeof form.requestSubmit === 'function' && button && (button.tagName === 'BUTTON' || button.tagName === 'INPUT')) {
    form.requestSubmit(button);
    return true;
  }
  if (button && typeof button.click === 'function') {
    button.click();
    return true;
  }
  if (form && typeof form.requestSubmit === 'function') {
    form.requestSubmit();
    return true;
  }
  if (form) {
    form.submit();
    return true;
  }
  return false;
}

function isConfirmPage() {
  const form = document.querySelector('form#entryForm');
  if (!form) {
    return false;
  }
  const action = String(form.action || '');
  const text = pageText();
  const confirmButton = findConfirmSubmitButton(form);
  const hasConfirmCopy = text.includes('以下の内容で送信します。ご確認のうえ、「この内容で送信する」ボタンを押してください。');
  return (
    (Boolean(confirmButton) && (action.includes('/process') || hasConfirmCopy)) ||
    hasConfirmCopy ||
    (action.includes('/process') && !formHasVisibleEditableFields(form))
  );
}

function isEntryPage() {
  const form = document.querySelector('form#entryForm');
  if (!form) {
    return false;
  }
  const action = String(form.action || '');
  if (action.includes('/process')) {
    return false;
  }
  return formHasVisibleEditableFields(form);
}

function submitEntryForm(task, stageKey) {
  const form = document.querySelector('form#entryForm');
  if (!form) {
    return false;
  }
  const filledName = fillInput('cname', task.fullName || '');
  const filledFurigana = fillInput('cname2', task.furigana || '');
  const filledMail = fillInput('mail', task.email || '');
  const filledMailConfirm = fillInput('mail_confirmation', task.email || '');
  const numberInput = form.querySelector('input[name="number"]');
  if (numberInput && !numberInput.value) {
    numberInput.value = '1';
  }
  const button = findSubmitLikeButton(form, '確認画面へ');
  markStage(stageKey, 'entry_submitted');
  return {
    submitted: triggerSubmit(form, button),
    filledName,
    filledFurigana,
    filledMail,
    filledMailConfirm
  };
}

function submitConfirmForm(task, stageKey) {
  const form =
    document.querySelector('form#entryForm') ||
    document.querySelector('form[action*="/process"]') ||
    Array.from(document.forms).find(item => String(item.action || '').includes('/process'));
  if (!form) {
    return false;
  }
  const button = findConfirmSubmitButton(form);
  markStage(stageKey, 'confirm_submitted');
  return triggerSubmit(form, button);
}

async function report(payload) {
  await chrome.runtime.sendMessage({
    type: 'hands-task-result',
    payload
  });
}

async function runTask(task) {
  const stageKey = `hands-task-stage:${task.executionId}`;
  const currentStage = getStageState(stageKey).stage;
  const orderId = findOrderNumber();
  if (orderId || hasSuccessMarker()) {
    sessionStorage.removeItem(stageKey);
    const successMessage = orderId ? `已识别成功页，受付番号 ${orderId}` : '已识别成功页，等待后台记录完成';
    await reportProgress('success', successMessage);
    await report({
      success: true,
      message: orderId ? `表单提交成功，受付番号 ${orderId}` : '表单提交成功',
      orderId,
      resultUrl: location.href,
      rawResult: {
        source: 'hands-form-extension',
        stage: currentStage || 'success',
        url: location.href,
        bodyText: pageText().slice(0, 1200)
      }
    });
    return;
  }
  if (hasFailureMarker()) {
    sessionStorage.removeItem(stageKey);
    await reportProgress('failed', '页面校验失败，未进入确认页');
    await report({
      success: false,
      message: '页面校验失败，未进入确认页',
      resultUrl: location.href,
      rawResult: {
        source: 'hands-form-extension',
        stage: currentStage || 'entry_failed',
        url: location.href,
        bodyText: pageText().slice(0, 2000)
      }
    });
    return;
  }
  if (isEntryPage()) {
    await reportProgress('entry_form_ready', '已识别填写页，准备写入资料');
    if (shouldAttemptStage(stageKey, 'entry_submitted', 3000)) {
      const result = submitEntryForm(task, stageKey);
      const fillSummary = [
        result.filledName ? '姓名' : '',
        result.filledFurigana ? '片假名' : '',
        result.filledMail ? '邮箱' : '',
        result.filledMailConfirm ? '确认邮箱' : ''
      ]
        .filter(Boolean)
        .join('、');
      await reportProgress(
        result.submitted ? 'entry_submitted' : 'entry_submit_failed',
        result.submitted
          ? `已填写并提交第一页${fillSummary ? `：${fillSummary}` : ''}`
          : '第一页提交触发失败'
      );
    }
    return;
  }
  if (isConfirmPage()) {
    await reportProgress('confirm_ready', '已进入确认页，准备点击绿色确认按钮');
    if (shouldAttemptStage(stageKey, 'confirm_submitted', 3000)) {
      const submitted = submitConfirmForm(task, stageKey);
      await reportProgress(submitted ? 'confirm_submitted' : 'confirm_submit_failed', submitted ? '已点击绿色确认按钮' : '确认页提交触发失败');
    }
    return;
  }
  await reportProgress('unknown_page', '页面结构未识别到填写页、确认页或成功页');
  await report({
    success: false,
    message: '页面结构未识别到填写页、确认页或成功页',
    resultUrl: location.href,
    rawResult: {
      source: 'hands-form-extension',
      stage: currentStage || 'unknown',
      url: location.href,
      bodyText: pageText().slice(0, 2000)
    }
  });
}

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (message?.type === 'run-hands-task' && message.task) {
    runTask(message.task)
      .then(() => sendResponse({ ok: true }))
      .catch(error => sendResponse({ ok: false, message: error.message || String(error) }));
    return true;
  }
  return false;
});

chrome.runtime.sendMessage({
  type: 'hands-page-ready',
  url: location.href
});
