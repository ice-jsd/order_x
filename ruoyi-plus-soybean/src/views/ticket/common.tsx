import { h } from 'vue';
import { NEllipsis, NTag } from 'naive-ui';

export const yesNoOptions = [
  { label: '启用', value: true },
  { label: '停用', value: false }
];

export const countryCodeOptions = [
  { label: '中国 +86', value: '+86' },
  { label: '日本 +81', value: '+81' }
];

export const phoneStatusOptions = [
  { label: '可用', value: 'available' },
  { label: '停用', value: 'disabled' },
  { label: '冻结', value: 'frozen' },
  { label: '失效', value: 'invalid' }
];

export const relationStatusOptions = [
  { label: '待注册', value: 'available' },
  { label: '注册中', value: 'registering' },
  { label: '已注册', value: 'registered' },
  { label: '注册失败', value: 'register_failed' },
  { label: '待验证', value: 'verification_pending' },
  { label: '已登录', value: 'logged_in' },
  { label: '登录失败', value: 'login_failed' },
  { label: '阻塞', value: 'blocked' }
];

export const accountStatusOptions = [
  { label: '已注册', value: 'registered' },
  { label: '停用', value: 'disabled' }
];

export const loginStatusOptions = [
  { label: '离线', value: 'offline' },
  { label: '已登录', value: 'logged_in' },
  { label: '登录失败', value: 'login_failed' }
];

export const batchStatusOptions = [
  { label: '草稿', value: 'draft' },
  { label: '执行中', value: 'executing' },
  { label: '完成', value: 'completed' },
  { label: '部分成功', value: 'partial' },
  { label: '阻塞', value: 'blocked' }
];

export const registerStepStatusOptions = [
  { label: '排队中', value: 'queued' },
  { label: '处理中', value: 'processing' },
  { label: '成功', value: 'success' },
  { label: '失败', value: 'failed' },
  { label: '跳过', value: 'skipped' },
  { label: '完成', value: 'completed' }
];

export const eventStatusOptions = [
  { label: '草稿', value: 'draft' },
  { label: '就绪', value: 'ready' },
  { label: '关闭', value: 'closed' }
];

export const taskModeOptions = [
  { label: '人工确认', value: 'manual_confirm' },
  { label: '自动提交', value: 'auto_submit' }
];

export const taskStatusOptions = [
  { label: '草稿', value: 'draft' },
  { label: '执行中', value: 'executing' },
  { label: '已完成', value: 'completed' },
  { label: '阻塞', value: 'blocked' }
];

export const executionStatusOptions = [
  { label: '成功', value: 'success' },
  { label: '阻塞', value: 'blocked' },
  { label: '失败', value: 'failed' }
];

export const auditStatusOptions = [
  { label: '成功', value: 'success' },
  { label: '失败', value: 'failed' },
  { label: '告警', value: 'warn' }
];

const phoneStatusLabelMap = Object.fromEntries(phoneStatusOptions.map(item => [String(item.value), item.label]));

const colorMap: Record<string, string> = {
  true: 'success',
  false: 'warning',
  available: 'success',
  disabled: 'default',
  frozen: 'error',
  invalid: 'error',
  registering: 'info',
  registered: 'success',
  register_failed: 'error',
  verification_pending: 'warning',
  logged_in: 'success',
  login_failed: 'error',
  offline: 'default',
  draft: 'default',
  executing: 'info',
  completed: 'success',
  partial: 'warning',
  blocked: 'error',
  ready: 'success',
  closed: 'default',
  success: 'success',
  failed: 'error',
  warn: 'warning',
  manual_confirm: 'warning',
  auto_submit: 'info',
  processing: 'info',
  skipped: 'warning',
  queued: 'default'
};

const labelMap: Record<string, string> = Object.fromEntries(
  [
    ...countryCodeOptions,
    ...phoneStatusOptions,
    ...relationStatusOptions,
    ...accountStatusOptions,
    ...loginStatusOptions,
    ...batchStatusOptions,
    ...registerStepStatusOptions,
    ...eventStatusOptions,
    ...taskModeOptions,
    ...taskStatusOptions,
    ...executionStatusOptions,
    ...auditStatusOptions
  ].map(item => [String(item.value), item.label])
);

labelMap.true = '启用';
labelMap.false = '停用';

export function renderTicketTag(value?: string | number | boolean | null) {
  const raw = value === null || value === undefined ? '' : String(value);
  return h(
    NTag,
    { size: 'small', type: (colorMap[raw] as NaiveUI.ThemeColor) || 'default', bordered: false },
    { default: () => labelMap[raw] || raw || '-' }
  );
}

export function renderPhoneStatusTag(value?: string | number | boolean | null) {
  const raw = value === null || value === undefined ? '' : String(value);
  return h(
    NTag,
    { size: 'small', type: (colorMap[raw] as NaiveUI.ThemeColor) || 'default', bordered: false },
    { default: () => phoneStatusLabelMap[raw] || raw || '-' }
  );
}

export function renderTicketEllipsis(value?: string | null) {
  return h(
    NEllipsis,
    { tooltip: true, style: { maxWidth: '280px' } },
    {
      default: () => value || '-'
    }
  );
}

export function renderCountryCode(value?: string | null) {
  return renderTicketTag(value);
}
