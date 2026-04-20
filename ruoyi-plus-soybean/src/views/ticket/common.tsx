import { h } from 'vue';
import { NEllipsis, NPopover, NTag } from 'naive-ui';

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

export const mailboxStatusOptions = [
  { label: '可用', value: 'available' },
  { label: '已使用', value: 'used' },
  { label: '创建失败', value: 'create_failed' },
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
  { label: '待执行', value: 'draft' },
  { label: '执行中', value: 'executing' },
  { label: '待支付', value: 'pending_payment' },
  { label: '已支付', value: 'paid' },
  { label: '部分完成', value: 'partial' },
  { label: '失败', value: 'failed' },
  { label: '阻塞', value: 'blocked' }
];

export const executionStatusOptions = [
  { label: '排队中', value: 'queued' },
  { label: '执行中', value: 'running' },
  { label: '已提交', value: 'submitted' },
  { label: '待支付', value: 'pending_payment' },
  { label: '已支付', value: 'paid' },
  { label: '阻塞', value: 'blocked' },
  { label: '失败', value: 'failed' },
  { label: '超时', value: 'timeout' }
];

export const purchaseTypeOptions = [
  { label: '抢票', value: 'flash_sale' },
  { label: '抽票', value: 'lottery' }
];

export const paymentStatusOptions = [
  { label: '待线上支付', value: 'pending_online' },
  { label: '待门店付款', value: 'offline_pending' },
  { label: '待人工处理', value: 'manual_pending' },
  { label: '已支付', value: 'paid' }
];

export const orderStepStatusOptions = [
  { label: '排队中', value: 'queued' },
  { label: '执行中', value: 'running' },
  { label: '成功', value: 'success' },
  { label: '失败', value: 'failed' }
];

export const orderCurrentStepOptions = [
  { label: '购物车处理', value: 'carting' },
  { label: '结算准备', value: 'checking_out' },
  { label: '履约选择', value: 'selecting_fulfillment' },
  { label: '支付方式选择', value: 'selecting_payment' },
  { label: '创建订单', value: 'creating_order' },
  { label: '等待支付', value: 'awaiting_payment' },
  { label: '已完成', value: 'completed' },
  { label: '排队中', value: 'queued' }
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
  used: 'info',
  create_failed: 'error',
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
  pending_payment: 'warning',
  paid: 'success',
  ready: 'success',
  closed: 'default',
  success: 'success',
  failed: 'error',
  timeout: 'warning',
  warn: 'warning',
  manual_confirm: 'warning',
  auto_submit: 'info',
  processing: 'info',
  skipped: 'warning',
  queued: 'default',
  running: 'info',
  submitted: 'success',
  flash_sale: 'info',
  lottery: 'warning',
  pending_online: 'warning',
  offline_pending: 'warning',
  manual_pending: 'default',
  carting: 'info',
  checking_out: 'info',
  selecting_fulfillment: 'warning',
  selecting_payment: 'warning',
  creating_order: 'info',
  awaiting_payment: 'warning'
};

const labelMap: Record<string, string> = Object.fromEntries(
  [
    ...countryCodeOptions,
    ...phoneStatusOptions,
    ...relationStatusOptions,
    ...accountStatusOptions,
    ...mailboxStatusOptions,
    ...loginStatusOptions,
    ...batchStatusOptions,
    ...registerStepStatusOptions,
    ...eventStatusOptions,
    ...taskModeOptions,
    ...taskStatusOptions,
    ...executionStatusOptions,
    ...purchaseTypeOptions,
    ...paymentStatusOptions,
    ...orderStepStatusOptions,
    ...orderCurrentStepOptions,
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

export function renderTicketEmail(value?: string | null) {
  const isLegacyEmail = !!value && value.includes('@invalid.local');

  return h(
    'div',
    {
      class: isLegacyEmail ? 'text-12px text-text-3' : 'text-12px text-text-2'
    },
    [
      h(
        NEllipsis,
        { tooltip: true, style: { maxWidth: isLegacyEmail ? '220px' : '240px' } },
        {
          default: () => value || '-'
        }
      )
    ]
  );
}

export function renderTicketJsonSummary(
  value?: string | null,
  preferredKeys: string[] = []
) {
  if (!value) {
    return '-';
  }

  let parsed: Record<string, unknown> | null = null;
  try {
    const raw = JSON.parse(value);
    if (raw && typeof raw === 'object' && !Array.isArray(raw)) {
      parsed = raw as Record<string, unknown>;
    }
  } catch {
    parsed = null;
  }

  if (!parsed) {
    return renderTicketEllipsis(value);
  }

  const keys = Object.keys(parsed);
  const summaryKeys = preferredKeys.filter(key => key in parsed).slice(0, 2);
  const fallbackKeys = keys.filter(key => !summaryKeys.includes(key)).slice(0, Math.max(0, 2 - summaryKeys.length));
  const displayKeys = [...summaryKeys, ...fallbackKeys];
  const previewItems = displayKeys.map(key => `${key}: ${String(parsed?.[key] ?? '-')}`);

  const prettyJson = JSON.stringify(parsed, null, 2);
  const summaryText = previewItems.length ? previewItems.join(' · ') : '查看详情';

  return h(
    NPopover,
    { trigger: 'hover', placement: 'left', width: 420 },
    {
      trigger: () =>
        h(
          'div',
          {
            class: 'max-w-220px cursor-help text-left'
          },
          [
            h(
              'div',
              {
                class: 'truncate text-12px leading-18px text-text-2'
              },
              summaryText
            ),
            h(
              'div',
              {
                class: 'text-12px leading-18px text-text-3'
              },
              `共 ${keys.length} 个字段`
            )
          ]
        ),
      default: () =>
        h(
          'pre',
          {
            class: 'max-w-380px overflow-auto whitespace-pre-wrap break-all rounded-8px bg-#f6f8fb p-12px text-12px leading-18px text-#334155'
          },
          prettyJson
        )
    }
  );
}

export function renderCountryCode(value?: string | null) {
  return renderTicketTag(value);
}
