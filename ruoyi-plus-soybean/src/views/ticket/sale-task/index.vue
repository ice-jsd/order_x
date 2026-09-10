<script setup lang="tsx">
import { computed, h, onBeforeUnmount, onMounted, ref, watch, type Component } from 'vue';
import { NButton, NPopconfirm, NTag, NPopover, type DataTableColumns } from 'naive-ui';
import { useAuth } from '@/hooks/business/auth';
import { defaultTransform, useNaivePaginatedTable } from '@/hooks/common/table';
import IconEpCopyDocument from '~icons/ep/copy-document';
import IconMaterialSymbolsCancelOutline from '~icons/material-symbols/cancel-outline';
import IconMaterialSymbolsDeleteOutline from '~icons/material-symbols/delete-outline';
import IconMaterialSymbolsDriveFileRenameOutlineOutline from '~icons/material-symbols/drive-file-rename-outline-outline';
import IconMaterialSymbolsFactCheckOutlineRounded from '~icons/material-symbols/fact-check-outline-rounded';
import IconMaterialSymbolsPlayCircleOutlineRounded from '~icons/material-symbols/play-circle-outline-rounded';
import {
  fetchCancelTicketSaleTask,
  fetchCreateTicketSaleTask,
  fetchDeleteTicketSaleTask,
  fetchExecuteNowTicketSaleTask,
  fetchGetJumpShopProductInfo,
  fetchGetTicketAccountList,
  fetchGetTicketSelectableAccountIds,
  fetchGetTicketJumpShopProfileOptions,
  fetchGetTicketLotteryEventParseRecord,
  fetchGetTicketLotteryEventHistory,
  fetchGetTicketPlatformList,
  fetchGetTicketPurchaseTemplate,
  fetchGetTicketSaleTask,
  fetchGetTicketSaleTaskProcess,
  fetchGetTicketSaleTaskProcessExecutions,
  fetchGetTicketSaleTaskList,
  fetchParseTicketLotteryEvent,
  fetchPreviewLivePocketQuestionnaire,
  fetchRetryFailedTicketLotteryTask,
  fetchUpdateTicketSaleTask
} from '@/service/api/ticket';
import { useAppStore } from '@/store/modules/app';
import { handleCopy } from '@/utils/copy';
import {
  purchaseTypeOptions,
  executionStatusOptions,
  lotteryResultStatusOptions,
  orderCurrentStepOptions,
  orderStepStatusOptions,
  paymentStatusOptions,
  renderTicketEmail,
  shouldRenderTicketPaymentStatus,
  renderTicketTag,
  taskStatusOptions
} from '../common';

defineOptions({
  name: 'TicketSaleTaskList'
});

const MAX_TASK_NAME_LENGTH = 80;
const ACCOUNT_OPTIONS_INITIAL_PAGE_SIZE = 50;
const JAPAN_TIME_ZONE = 'Asia/Tokyo';
const JAPAN_TIME_OFFSET_MS = 9 * 60 * 60 * 1000;
const japanDateTimeFormatter = new Intl.DateTimeFormat('en-GB', {
  timeZone: JAPAN_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
  hourCycle: 'h23'
});

const appStore = useAppStore();
const { hasAuth } = useAuth();

interface PlatformOption {
  label: string;
  value: CommonType.IdType;
  platformCode: string;
  adapterType?: string;
}

function createSearchParams(): Api.Ticket.SaleTaskSearchParams {
  return {
    pageNum: 1,
    pageSize: 10,
    taskId: null,
    platformId: null,
    purchaseType: null,
    taskName: null,
    taskStatus: null,
    params: {}
  };
}

function createFormModel(): Api.Ticket.SaleTaskOperateParams {
  return {
    taskId: undefined,
    platformId: undefined,
    taskName: '',
    taskStatus: 'draft',
    purchaseType: 'flash_sale',
    configSchemaKey: '',
    warmupTime: null,
    scheduledTime: null,
    purchaseQuantity: 1,
    taskOptions: '{\n  \n}',
    lotteryEventUrl: '',
    selectedSessions: [],
    accountIds: [],
    lotterySchedules: [],
    remark: ''
  };
}

const searchParams = ref<Api.Ticket.SaleTaskSearchParams>(createSearchParams());
const searchTaskId = ref('');
const checkedRowKeys = ref<CommonType.IdType[]>([]);
const modalVisible = ref(false);
const operateType = ref<NaiveUI.TableOperateType>('add');
const activeTaskMode = ref<'flash_sale' | 'lottery'>('flash_sale');
const modalShaking = ref(false);
const lotteryStep = ref(1);
const formModel = ref<Api.Ticket.SaleTaskOperateParams>(createFormModel());
const platformOptions = ref<PlatformOption[]>([]);
const accountTableData = ref<Api.Ticket.Account[]>([]);
const accountOptionsLoading = ref(false);
const accountOptionsLoadSeq = ref(0);
const accountOptionsTotal = ref(0);
const accountOptionsLoadedAll = ref(false);
const templateLoading = ref(false);
const lotteryEventParsing = ref(false);
const lotteryEventInfo = ref<Api.Ticket.LotteryEventInfo | null>(null);
const lotteryCollectionEventInfo = ref<Api.Ticket.LotteryEventInfo | null>(null);
const jumpShopProductInfo = ref<Api.Ticket.JumpShopProductInfo | null>(null);
const jumpShopProductParsing = ref(false);
const jumpShopProfileOptions = ref<Api.Ticket.JumpShopProfile[]>([]);
const selectedJumpShopProfileId = ref<CommonType.IdType | null>(null);
const lotteryParseHistory = ref<Api.Ticket.LotteryEventInfo[]>([]);
const lotteryParsePollTimer = ref<number | null>(null);
const lastLotteryParseInputUrl = ref('');
const selectedLotteryHistoryUrl = ref<CommonType.IdType | null>(null);
const selectedLotteryCollectionEventUrl = ref<string | null>(null);
const lotteryCollectionSourceUrl = ref<string | null>(null);
const selectedLotteryReceptionKey = ref<string | null>(null);
const lotteryCollectionResolving = ref(false);
const questionnairePreviewLoading = ref(false);
const questionnairePreviewAccountId = ref<CommonType.IdType | null>(null);
const questionnairePreviewSessionId = ref<string | null>(null);
const questionnairePreviewConfig = ref<Api.Ticket.LivePocketQuestionnaireConfig | null>(null);
const questionnairePreviewResult = ref<Api.Ticket.LivePocketQuestionnairePreviewResult | null>(null);
const questionnaireInvalidationReason = ref('');
const accountSearchKeyword = ref('');
const lastLoadedTemplateText = ref('{\\n  \\n}');
const pauseSelectionWatcher = ref(false);
const preservedLotteryUrlOnPlatformSwitch = ref<string | null>(null);
const preservedLotteryCollectionUrlOnPlatformSwitch = ref<string | null>(null);
const processDrawerVisible = ref(false);
const processLoading = ref(false);
const processData = ref<Api.Ticket.SaleTaskProcess | null>(null);
const processPollTimer = ref<number | null>(null);
const processJsonVisible = ref(false);
const processJsonTitle = ref('');
const processJsonContent = ref('');
const processExecutionStatusFilter = ref('total');
const processExecutionLoading = ref(false);
const processExecutions = ref<Api.Ticket.OrderExecution[]>([]);
const processExecutionTotal = ref(0);
const processExecutionPagination = ref({ pageNum: 1, pageSize: 30 });
const retryFailedLotteryLoading = ref(false);
const executeNowLoadingTaskId = ref<CommonType.IdType | null>(null);

const ticketLabelMap = Object.fromEntries(
  [
    ...purchaseTypeOptions,
    ...taskStatusOptions,
    ...executionStatusOptions,
    ...lotteryResultStatusOptions,
    ...orderCurrentStepOptions,
    ...orderStepStatusOptions,
    ...paymentStatusOptions
  ].map(item => [String(item.value), item.label])
);

const ticketTagTypeMap: Record<string, NaiveUI.ThemeColor> = {
  draft: 'default',
  flash_sale: 'info',
  lottery: 'warning',
  queued: 'default',
  running: 'info',
  executing: 'info',
  submitted: 'success',
  pending_payment: 'warning',
  pending_online: 'warning',
  offline_pending: 'warning',
  lottery_waiting_result: 'info',
  manual_pending: 'default',
  not_required: 'default',
  paid: 'success',
  partial: 'warning',
  failed: 'error',
  blocked: 'error',
  timeout: 'error',
  cancelled: 'default',
  success: 'success',
  completed: 'success',
  selected: 'success'
};

const saleTaskSummaryKeys = [
  { key: 'total', label: '总' },
  { key: 'queued', label: '排队' },
  { key: 'running', label: '运行' },
  { key: 'submitted', label: '提交' },
  { key: 'blocked', label: '阻塞' },
  { key: 'failed', label: '失败' }
];

const saleTaskCancelableStatuses = new Set(['draft', 'executing', 'partial']);
const saleTaskDeletableStatuses = new Set(['draft', 'cancelled', 'completed', 'paid', 'failed', 'blocked']);
const saleTaskExecuteNowStatuses = new Set(['draft']);

function normalizePlatformRoutingCode(platform?: { platformCode?: string | null; adapterType?: string | null } | null) {
  return String(platform?.adapterType || platform?.platformCode || '')
    .trim()
    .toLowerCase();
}

const livePocketPlatformId = computed(() => {
  const platform = platformOptions.value.find(item => normalizePlatformRoutingCode(item) === 'livepocket');
  return platform?.value || null;
});
const handsFormPlatformId = computed(() => {
  const platform = platformOptions.value.find(item => normalizePlatformRoutingCode(item) === 'hands-form');
  return platform?.value || null;
});
const jumpShopPlatformId = computed(() => {
  const platform = platformOptions.value.find(item => ['jump-shop', 'jump-shop-online'].includes(normalizePlatformRoutingCode(item)));
  return platform?.value || null;
});
const selectedPlatform = computed(() => platformOptions.value.find(item => item.value === formModel.value.platformId) || null);
const isHandsFormTask = computed(() => normalizePlatformRoutingCode(selectedPlatform.value) === 'hands-form');
const isJumpShopTask = computed(
  () =>
    formModel.value.purchaseType === 'flash_sale' &&
    ['jump-shop', 'jump-shop-online'].includes(normalizePlatformRoutingCode(selectedPlatform.value))
);
const isLivePocketFlashSaleTask = computed(
  () => formModel.value.purchaseType === 'flash_sale' && normalizePlatformRoutingCode(selectedPlatform.value) === 'livepocket'
);
const directLivePocketTicketsUrl = computed(() => {
  if (!isLivePocketFlashSaleTask.value) return '';
  const options = parseTaskJson(formModel.value.taskOptions) || {};
  return firstLivePocketTicketsUrl(
    formModel.value.lotteryEventUrl,
    options.ticketEntryUrl,
    options.ticketsUrl,
    options.lotteryTicketsUrl,
    options.lotteryEntryUrl,
    options.eventUrl,
    lastLotteryParseInputUrl.value
  );
});
const livePocketFlashSaleEventUrl = computed(() => {
  if (!isLivePocketFlashSaleTask.value) return '';
  const options = parseTaskJson(formModel.value.taskOptions) || {};
  return firstLivePocketEventUrl(
    formModel.value.lotteryEventUrl,
    options.eventUrl,
    options.lotteryEventUrl,
    options.lotteryEntryUrl,
    lastLotteryParseInputUrl.value
  );
});
const usesDirectLivePocketTicketsUrl = computed(() => Boolean(directLivePocketTicketsUrl.value));
const usesLivePocketFlashSaleQuickPath = computed(() =>
  Boolean(directLivePocketTicketsUrl.value || livePocketFlashSaleEventUrl.value)
);

const modalTitle = computed(() => {
  const actionText = operateType.value === 'add' ? '新增' : '编辑';
  return `${actionText}${activeTaskMode.value === 'lottery' ? '抽票' : '抢票'}任务`;
});
const selectedAccountCount = computed(() => formModel.value.accountIds?.length || 0);
const accountLoadedCountText = computed(() =>
  accountOptionsTotal.value > accountTableData.value.length
    ? `${accountTableData.value.length} / ${accountOptionsTotal.value}`
    : String(accountTableData.value.length)
);
const isLotteryTask = computed(() => formModel.value.purchaseType === 'lottery');
const isLivePocketLotteryTask = computed(
  () => isLotteryTask.value && normalizePlatformRoutingCode(selectedPlatform.value) === 'livepocket'
);
const guidedTaskLabel = computed(() => (isLotteryTask.value ? '抽票' : isJumpShopTask.value ? '下单' : '抢票'));
const guidedSessionLabel = computed(() => (isLotteryTask.value ? '场次' : isJumpShopTask.value ? '资料' : '票种'));
const usesGuidedTaskFlow = computed(
  () =>
    isLotteryTask.value ||
    (formModel.value.purchaseType === 'flash_sale' &&
      (!formModel.value.platformId || isLivePocketFlashSaleTask.value || isJumpShopTask.value))
);
const lotteryLinkLabel = computed(() => {
  if (isJumpShopTask.value) {
    return '商品链接';
  }
  if (isHandsFormTask.value) {
    return 'Hands 抽票链接';
  }
  return isLotteryTask.value ? '抽票链接' : '活动 / 抢票链接';
});
const isLotteryParsePending = computed(() => {
  const parseStatus = lotteryEventInfo.value?.parseStatus;
  return lotteryEventParsing.value || parseStatus === 'queued' || parseStatus === 'running';
});
const lotteryUsesCollectionFlow = computed(() => Boolean(lotteryCollectionSourceUrl.value || lotteryCollectionEventInfo.value));
const lotteryStepItems = computed(() =>
  isJumpShopTask.value
    ? [
        { title: '输入链接' },
        { title: '解析商品' },
        { title: '选择账号' },
        { title: '选择资料' },
        { title: '数量与时间' }
      ]
    : usesLivePocketFlashSaleQuickPath.value
    ? [
        { title: usesDirectLivePocketTicketsUrl.value ? '输入 tickets 链接' : '输入活动链接' },
        { title: '选择账号' },
        { title: '开抢时间' }
      ]
    : lotteryUsesCollectionFlow.value
    ? [
        { title: '输入链接' },
        { title: '选择活动' },
        { title: isLotteryTask.value ? '解析场次' : '解析票种' },
        { title: '选择受付' },
        { title: '选择账号' },
        { title: isLotteryTask.value ? '选择场次' : '选择票种' },
        { title: isLotteryTask.value ? '分时段保存' : '数量与时间' }
      ]
    : [
        { title: '输入链接' },
        { title: '解析活动' },
        { title: '选择受付' },
        { title: '选择账号' },
        { title: isLotteryTask.value ? '选择场次' : '选择票种' },
        { title: isLotteryTask.value ? '分时段保存' : '开抢时间' }
      ]
);
const lotteryCollectionTableScrollX = 980;
const lotterySessionTableScrollX = 980;
const lotteryMaxStep = computed(() => lotteryStepItems.value.length);
const skippedLotteryStepIndex = 99;
const lotteryCollectionStepIndex = computed(() => (usesLivePocketFlashSaleQuickPath.value ? skippedLotteryStepIndex : 2));
const lotteryParseStepIndex = computed(() =>
  isJumpShopTask.value ? 2 : usesLivePocketFlashSaleQuickPath.value ? skippedLotteryStepIndex : lotteryUsesCollectionFlow.value ? 3 : 2
);
const lotteryReceptionStepIndex = computed(() =>
  usesLivePocketFlashSaleQuickPath.value ? skippedLotteryStepIndex : lotteryUsesCollectionFlow.value ? 4 : 3
);
const lotteryAccountStepIndex = computed(() =>
  isJumpShopTask.value ? 3 : usesLivePocketFlashSaleQuickPath.value ? 2 : lotteryUsesCollectionFlow.value ? 5 : 4
);
const lotterySessionStepIndex = computed(() =>
  isJumpShopTask.value ? 4 : usesLivePocketFlashSaleQuickPath.value ? skippedLotteryStepIndex : lotteryUsesCollectionFlow.value ? 6 : 5
);
const lotteryScheduleStepIndex = computed(() =>
  isJumpShopTask.value ? 5 : usesLivePocketFlashSaleQuickPath.value ? 3 : lotteryUsesCollectionFlow.value ? 7 : 6
);
const isLotteryCollectionResult = computed(() => isLotteryCollectionEventInfo(lotteryEventInfo.value));
const lotteryCollectionDisplayInfo = computed(
  () => lotteryCollectionEventInfo.value || (isLotteryCollectionEventInfo(lotteryEventInfo.value) ? lotteryEventInfo.value : null)
);
const lotteryReceptionGroups = computed(() => {
  const groups = new Map<string, {
    key: string;
    receptionId?: string;
    receptionTitle?: string;
    salesType?: string;
    sessionCount: number;
    sessions: Api.Ticket.LotteryEventSession[];
    label: string;
  }>();
  for (const item of getGuidedEligibleSessions(lotteryEventInfo.value?.sessions || [])) {
    const key = buildLotteryReceptionKey(item);
    const existing = groups.get(key);
    if (existing) {
      existing.sessionCount += 1;
      existing.sessions.push(item);
      continue;
    }
    groups.set(key, {
      key,
      receptionId: item.receptionId || undefined,
      receptionTitle: item.receptionTitle || undefined,
      salesType: item.salesType || undefined,
      sessionCount: 1,
      sessions: [item],
      label: formatLotteryReceptionLabel(item)
    });
  }
  return Array.from(groups.values());
});
const selectedLotteryReceptionGroup = computed(
  () => lotteryReceptionGroups.value.find(item => item.key === selectedLotteryReceptionKey.value) || null
);
const currentLotteryReceptionSessions = computed(
  () => selectedLotteryReceptionGroup.value?.sessions || []
);
const isLotteryNextDisabled = computed(() => {
  if (!usesGuidedTaskFlow.value || lotteryStep.value >= lotteryMaxStep.value) {
    return false;
  }
  if (isJumpShopTask.value) {
    return lotteryStep.value === lotteryParseStepIndex.value && jumpShopProductParsing.value;
  }
  if (lotteryUsesCollectionFlow.value && lotteryStep.value === lotteryCollectionStepIndex.value) {
    return true;
  }
  return lotteryStep.value === lotteryParseStepIndex.value && isLotteryParsePending.value;
});
const processTask = computed(() => processData.value?.task || null);
const selectedJumpShopProfile = computed(
  () => jumpShopProfileOptions.value.find(item => item.profileId === selectedJumpShopProfileId.value) || null
);
const processPlanCount = computed(() => {
  if (!processData.value) return 0;
  if (processData.value.task?.purchaseType === 'lottery') {
    return processData.value.schedules?.length || 0;
  }
  return processData.value.task?.scheduledTime ? 1 : 0;
});
const processSummaryItems = computed(() => {
  const summary = processData.value?.executionSummary || {};
  const items = [
    { key: 'total', label: '总数', value: summary.total || 0 },
    { key: 'queued', label: '排队', value: summary.queued || 0 },
    { key: 'running', label: '运行中', value: summary.running || 0 },
    { key: 'submitted', label: '已提交', value: summary.submitted || 0 },
    { key: 'pending_payment', label: '待支付', value: summary.pending_payment || 0 },
    { key: 'paid', label: '已支付', value: summary.paid || 0 },
    { key: 'failed', label: '失败', value: summary.failed || 0 },
    { key: 'blocked', label: '阻塞', value: summary.blocked || 0 },
    { key: 'cancelled', label: '取消', value: summary.cancelled || 0 }
  ];
  return items;
});
const processFilteredExecutions = computed(() => {
  return processExecutions.value;
});
const processActiveSummary = computed(
  () => processSummaryItems.value.find(item => item.key === processExecutionStatusFilter.value) || processSummaryItems.value[0]
);
const retryableLotteryExecutionCount = computed(() => {
  if (processData.value?.task?.purchaseType !== 'lottery') return 0;
  const summary = processData.value.executionSummary || {};
  return Number(summary.failed || 0) + Number(summary.blocked || 0);
});
const lotteryScheduleAccountTotal = computed(() =>
  (formModel.value.lotterySchedules || []).reduce((total, item) => total + Number(item.accountCount || 0), 0)
);
const lotteryEventInputUrl = computed({
  get: () =>
    lotteryUsesCollectionFlow.value ? lotteryCollectionSourceUrl.value || formModel.value.lotteryEventUrl : formModel.value.lotteryEventUrl,
  set: value => {
    const normalizedValue = value || '';
    if (lotteryUsesCollectionFlow.value || isLotteryCollectionUrl(normalizedValue)) {
      lotteryCollectionSourceUrl.value = normalizedValue || null;
    }
    formModel.value.lotteryEventUrl = normalizedValue;
  }
});
const lotterySessionOptions = computed(() =>
  (formModel.value.selectedSessions?.length
    ? formModel.value.selectedSessions
    : currentLotteryReceptionSessions.value
  ).map(item => ({
    label: formatLotterySessionOptionLabel(item),
    value: item.sessionId
  }))
);

function isLotteryDrawSession(item?: Api.Ticket.LotteryEventSession | null) {
  return String(item?.salesType || '').includes('抽選');
}

function isFlashSaleEligibleSession(item?: Api.Ticket.LotteryEventSession | null) {
  if (!item || isLotteryCollectionSession(item)) return false;
  return !isLotteryDrawSession(item);
}

function getLotteryDrawSessions(sessions?: Api.Ticket.LotteryEventSession[] | null) {
  return (sessions || []).filter(isLotteryDrawSession);
}

function getGuidedEligibleSessions(sessions?: Api.Ticket.LotteryEventSession[] | null) {
  const allSessions = sessions || [];
  return isLotteryTask.value ? allSessions.filter(isLotteryDrawSession) : allSessions.filter(isFlashSaleEligibleSession);
}

function isLotteryCollectionSession(item?: Api.Ticket.LotteryEventSession | null) {
  return Boolean(item?.eventUrl && String(item.sessionId || '').startsWith('event:'));
}

function isLotteryCollectionEventInfo(info?: Api.Ticket.LotteryEventInfo | null) {
  if (!info) return false;
  return /\/t\/[^/?#]+/.test(info.eventUrl || '') || (info.sessions || []).some(isLotteryCollectionSession);
}

function isLotteryCollectionUrl(url?: string | null) {
  return /\/t\/[^/?#]+/.test(url || '');
}

function buildLotteryReceptionKey(item?: Api.Ticket.LotteryEventSession | null) {
  if (!item) return '';
  const receptionId = String(item.receptionId || '').trim();
  if (receptionId) {
    return `reception:${receptionId}`;
  }
  return `missing:${String(item.receptionTitle || item.salesType || item.sessionId || '').trim()}`;
}

function formatLotteryReceptionLabel(item?: Api.Ticket.LotteryEventSession | null) {
  if (!item) return '-';
  const parts = [item.salesType, item.receptionTitle].filter(Boolean);
  return parts.length ? parts.join(' / ') : '未识别受付';
}

function syncLotteryReceptionSelection() {
  const groups = lotteryReceptionGroups.value;
  if (!groups.length) {
    selectedLotteryReceptionKey.value = null;
    formModel.value.selectedSessions = [];
    formModel.value.lotterySchedules = [];
    invalidateQuestionnairePreview('已更改活动受付，请重新读取问卷');
    return;
  }
  const exists = groups.some(item => item.key === selectedLotteryReceptionKey.value);
  if (exists) {
    return;
  }
  selectedLotteryReceptionKey.value = groups.length === 1 ? groups[0].key : null;
  formModel.value.selectedSessions = [];
  formModel.value.lotterySchedules = [];
  invalidateQuestionnairePreview('已更改活动受付，请重新读取问卷');
  if (isLivePocketFlashSaleTask.value && groups.length === 1 && groups[0].sessions.length === 1) {
    formModel.value.selectedSessions = [groups[0].sessions[0]];
    applyFlashSaleSelectionDefaults(groups[0].sessions[0]);
  }
  syncQuestionnairePreviewSelections();
}

function formatLotterySessionOptionLabel(item?: Api.Ticket.LotteryEventSession | null) {
  if (!item) return '';
  const label = item.sessionLabel || item.sessionId || '';
  const parts = [item.salesType, item.receptionTitle].filter(Boolean);
  const suffix = parts.join(' / ');
  return suffix ? `${label}｜${suffix}` : label;
}

function normalizeLotterySession(item: Api.Ticket.LotteryEventSession) {
  return {
    sessionId: String(item.sessionId || ''),
    sessionLabel: item.sessionLabel || String(item.sessionId || ''),
    eventUrl: item.eventUrl || undefined,
    receptionId: item.receptionId || undefined,
    ticketId: item.ticketId || undefined,
    ticketField: item.ticketField || undefined,
    receptionTitle: item.receptionTitle || undefined,
    salesType: item.salesType || undefined,
    notes: item.notes || undefined,
    maxPurchaseQuantity: item.maxPurchaseQuantity || undefined
  };
}

function applyFlashSaleSelectionDefaults(selectedSession?: Api.Ticket.LotteryEventSession | null) {
  if (!isLivePocketFlashSaleTask.value || !selectedSession) return;
  formModel.value.purchaseQuantity = 1;
}
const filteredLotteryParseHistory = computed(() => {
  const platformId = formModel.value.platformId;
  if (!platformId) {
    return lotteryParseHistory.value;
  }
  const matched = lotteryParseHistory.value.filter(item => !item.platformId || item.platformId === platformId);
  return matched.length ? matched : lotteryParseHistory.value;
});

function getLotteryHistoryValue(item: Api.Ticket.LotteryEventInfo): CommonType.IdType {
  return item.recordId || item.eventUrl;
}

function isSameLotteryHistoryValue(item: Api.Ticket.LotteryEventInfo, value: CommonType.IdType | null) {
  if (value === null || value === undefined) return false;
  return String(getLotteryHistoryValue(item)) === String(value);
}

function findLotteryHistoryValueByEventUrl(eventUrl?: string | null) {
  if (!eventUrl) return null;
  const record = lotteryParseHistory.value.find(item => item.eventUrl === eventUrl);
  return record ? getLotteryHistoryValue(record) : null;
}

const lotteryParseHistoryOptions = computed(() =>
  filteredLotteryParseHistory.value.map(item => ({
    label: `${normalizeTaskName(item.eventTitle || item.eventUrl)}｜${item.entryStartTime || '-'} ~ ${item.entryEndTime || '-'}`,
    value: getLotteryHistoryValue(item)
  }))
);
const checkedLotterySessionRowKeys = computed<string[]>({
  get() {
    return (formModel.value.selectedSessions || []).map(item => item.sessionId);
  },
  set(value) {
    const previousSessionIds = (formModel.value.selectedSessions || []).map(item => item.sessionId);
    const allSessions = currentLotteryReceptionSessions.value;
    const nextValue = isLivePocketFlashSaleTask.value && value.length > 1 ? value.slice(-1) : value;
    formModel.value.selectedSessions = allSessions.filter(item => nextValue.includes(item.sessionId));
    if (isLivePocketFlashSaleTask.value) {
      applyFlashSaleSelectionDefaults(formModel.value.selectedSessions?.[0]);
    }
    const validSessionIds = new Set(nextValue);
    formModel.value.lotterySchedules = (formModel.value.lotterySchedules || []).map(item => {
      if (item.sessionId && validSessionIds.has(item.sessionId)) {
        return item;
      }
      const firstSession = formModel.value.selectedSessions?.[0];
      return {
        ...item,
        sessionId: firstSession?.sessionId,
        sessionLabel: firstSession?.sessionLabel
      };
    });
    if (previousSessionIds.join(',') !== nextValue.join(',')) {
      invalidateQuestionnairePreview('已更改参与场次，请重新读取问卷', false);
    }
    syncQuestionnairePreviewSelections();
  }
});
const selectedAccountPreview = computed(() =>
  accountTableData.value.filter(item => formModel.value.accountIds?.includes(item.accountId)).slice(0, 3)
);
const questionnairePreviewAccountOptions = computed(() =>
  accountTableData.value
    .filter(item => formModel.value.accountIds?.includes(item.accountId))
    .map(item => ({
      label: item.email || `账号 ${item.accountId}`,
      value: item.accountId
    }))
);
const questionnairePreviewSessionOptions = computed(() =>
  (formModel.value.selectedSessions || []).map(item => ({
    label: item.sessionLabel || item.sessionId || '-',
    value: String(item.sessionId || '')
  }))
);
const hasQuestionnairePreviewConfig = computed(() => Boolean(questionnairePreviewConfig.value?.questions?.length));
const checkedAccountRowKeys = computed<CommonType.IdType[]>({
  get() {
    return formModel.value.accountIds || [];
  },
  set(value) {
    const previewAccountChanged =
      questionnairePreviewAccountId.value !== null && !value.includes(questionnairePreviewAccountId.value);
    formModel.value.accountIds = value;
    if (previewAccountChanged) {
      invalidateQuestionnairePreview('预读账号已变更，请重新读取问卷', false);
    }
    syncQuestionnairePreviewSelections();
  }
});

const selectableAccountIds = computed(() =>
  accountTableData.value.filter(item => !item.lotteryLinkOccupied).map(item => item.accountId)
);

const isAllLoadedSelectableAccountsChecked = computed(() => {
  const ids = selectableAccountIds.value;
  if (!ids.length) return false;
  const checkedIds = new Set(formModel.value.accountIds || []);
  return ids.every(accountId => checkedIds.has(accountId));
});

const isSelectAllAccountsDisabled = computed(() =>
  !formModel.value.platformId || accountOptionsLoading.value || (accountOptionsLoadedAll.value && isAllLoadedSelectableAccountsChecked.value)
);

const filteredAccountTableData = computed(() => {
  const keyword = accountSearchKeyword.value.trim().toLowerCase();
  if (!keyword) {
    return accountTableData.value;
  }
  return accountTableData.value.filter(item =>
    [String(item.accountId), item.email, item.phoneNumber, item.platformName]
      .filter(Boolean)
      .some(text => String(text).toLowerCase().includes(keyword))
  );
});

async function handleSelectAllAccounts() {
  if (!formModel.value.platformId || accountOptionsLoading.value) {
    return;
  }
  accountOptionsLoading.value = true;
  try {
    const { data: result, error } = await fetchGetTicketSelectableAccountIds(
      buildAccountSearchParams(formModel.value.platformId)
    );
    if (error || !result) {
      return;
    }
    const nextIds = new Set(formModel.value.accountIds || []);
    for (const accountId of result.accountIds || []) {
      nextIds.add(accountId);
    }
    formModel.value.accountIds = Array.from(nextIds);
    accountOptionsTotal.value = Number(result.totalCount || accountOptionsTotal.value || 0);
    syncQuestionnairePreviewSelections();
  } finally {
    accountOptionsLoading.value = false;
  }
}

function handleClearSelectedAccounts() {
  formModel.value.accountIds = [];
  invalidateQuestionnairePreview('预读账号已变更，请重新读取问卷', false);
  syncQuestionnairePreviewSelections();
}

function renderOperateIconButton(label: string, icon: Component, type: NaiveUI.ThemeColor, onClick?: () => void) {
  return (
    <span class="sale-task-operate__trigger" title={label} aria-label={label}>
      <NButton circle quaternary size="small" type={type} class="sale-task-operate__button" onClick={onClick}>
        {{
          icon: () => h(icon, { class: 'sale-task-operate__icon' })
        }}
      </NButton>
    </span>
  );
}

const accountTableColumns = computed<DataTableColumns<Api.Ticket.Account>>(() => [
  { type: 'selection', align: 'center', width: 48, disabled: row => !!row.lotteryLinkOccupied },
  {
    key: 'email',
    title: '账号',
    align: 'left',
    minWidth: 260,
    render: row => renderAccountIdentity(row)
  },
  {
    key: 'accountInfo',
    title: '资料摘要',
    align: 'left',
    minWidth: 240,
    render: row => renderAccountProfile(row)
  },
  {
    key: 'reqData',
    title: '登录态',
    align: 'left',
    minWidth: 260,
    render: row => renderAccountLoginState(row)
  }
]);

const lotteryEventSessionColumns = computed<DataTableColumns<Api.Ticket.LotteryEventSession>>(() => [
  {
    key: 'salesType',
    title: '类型',
    align: 'center',
    width: 82,
    render: row =>
      row.salesType
        ? h(NTag, { size: 'small', bordered: false, type: row.salesType === '抽選' ? 'warning' : 'info' }, { default: () => row.salesType })
        : '-'
  },
  {
    key: 'receptionTitle',
    title: '受付',
    align: 'left',
    minWidth: 220,
    render: row => h('span', { title: row.receptionTitle || '' }, row.receptionTitle || '-')
  },
  {
    key: 'sessionLabel',
    title: '票卡 / 场次',
    align: 'left',
    minWidth: 260,
    render: row => h('span', { title: row.sessionLabel || row.sessionId }, row.sessionLabel || row.sessionId)
  },
  {
    key: 'eventUrl',
    title: '活动',
    align: 'left',
    minWidth: 160,
    ellipsis: { tooltip: true },
    render: row => row.eventUrl || '-'
  },
  {
    key: 'notes',
    title: '说明',
    align: 'left',
    minWidth: 220,
    ellipsis: { tooltip: true },
    render: row => {
      const parts = [row.notes, row.ticketField, row.ticketId].filter(
        Boolean
      );
      return parts.join(' / ') || '-';
    }
  }
]);

const lotteryCollectionEventColumns = computed<DataTableColumns<Api.Ticket.LotteryEventSession>>(() => [
  {
    key: 'receptionTitle',
    title: '状态',
    align: 'center',
    width: 86,
    render: row =>
      row.receptionTitle
        ? h(NTag, { size: 'small', bordered: false, type: row.receptionTitle === '販売中' ? 'success' : 'default' }, { default: () => row.receptionTitle })
        : '-'
  },
  {
    key: 'sessionLabel',
    title: '活动',
    align: 'left',
    minWidth: 320,
    ellipsis: { tooltip: true },
    render: row => row.sessionLabel || row.eventUrl || '-'
  },
  {
    key: 'eventUrl',
    title: '链接',
    align: 'left',
    minWidth: 190,
    ellipsis: { tooltip: true },
    render: row => row.eventUrl || '-'
  },
  {
    key: 'notes',
    title: '说明',
    align: 'left',
    minWidth: 180,
    ellipsis: { tooltip: true },
    render: row => row.notes || '-'
  },
  {
    key: 'action',
    title: '操作',
    align: 'center',
    width: 112,
    render: row =>
      h(
        NButton,
        {
          size: 'tiny',
          type: selectedLotteryCollectionEventUrl.value === row.eventUrl ? 'primary' : 'default',
          ghost: selectedLotteryCollectionEventUrl.value !== row.eventUrl,
          loading: lotteryCollectionResolving.value && selectedLotteryCollectionEventUrl.value === row.eventUrl,
          disabled: lotteryEventParsing.value && selectedLotteryCollectionEventUrl.value !== row.eventUrl,
          onClick: (event: MouseEvent) => {
            event.stopPropagation();
            void handleSelectLotteryCollectionEvent(row);
          }
        },
        {
          default: () =>
            selectedLotteryCollectionEventUrl.value === row.eventUrl
              ? lotteryCollectionResolving.value
                ? '解析中'
                : '已选择'
              : '选择活动'
        }
      )
  }
]);

const lotterySelectableSessionColumns = computed<DataTableColumns<Api.Ticket.LotteryEventSession>>(() => [
  {
    type: 'selection',
    multiple: !isLivePocketFlashSaleTask.value,
    align: 'center',
    width: 48,
    disabled: row => !getGuidedEligibleSessions([row]).length
  },
  ...lotteryEventSessionColumns.value
]);

function handleSelectLotteryReception(receptionKey: string) {
  if (!receptionKey || receptionKey === selectedLotteryReceptionKey.value) return;
  selectedLotteryReceptionKey.value = receptionKey;
  formModel.value.selectedSessions = [];
  formModel.value.lotterySchedules = [];
  invalidateQuestionnairePreview('已切换受付，请重新读取问卷', false);
  if (isLivePocketFlashSaleTask.value) {
    const matchedGroup = lotteryReceptionGroups.value.find(item => item.key === receptionKey);
    if (matchedGroup?.sessions?.length === 1) {
      formModel.value.selectedSessions = [matchedGroup.sessions[0]];
      applyFlashSaleSelectionDefaults(matchedGroup.sessions[0]);
    }
  }
  syncQuestionnairePreviewSelections();
}

const { columns, columnChecks, data, getData, getDataByPage, loading, mobilePagination, scrollX } =
  useNaivePaginatedTable({
    api: () => fetchGetTicketSaleTaskList(searchParams.value),
    transform: response => defaultTransform(response),
    onPaginationParamsChange: params => {
      searchParams.value.pageNum = params.page;
      searchParams.value.pageSize = params.pageSize;
    },
    columns: () => [
      { type: 'selection', align: 'center', width: 48 },
      {
        key: 'taskName',
        title: '任务名称',
        align: 'left',
        minWidth: 260,
        render: row => renderSaleTaskTitle(row)
      },
      {
        key: 'platformInfo',
        title: '平台 / 类型',
        align: 'left',
        minWidth: 160,
        render: row => renderSaleTaskPlatformInfo(row)
      },
      {
        key: 'taskConfig',
        title: '链接 / 配置',
        align: 'left',
        minWidth: 220,
        render: row => renderSaleTaskConfig(row)
      },
      {
        key: 'taskAccounts',
        title: '账号',
        align: 'center',
        width: 120,
        render: row => renderSaleTaskAccounts(row)
      },
      {
        key: 'lotterySchedules',
        title: '抽票时段',
        align: 'left',
        minWidth: 260,
        render: row => renderSaleTaskScheduleInfo(row)
      },
      {
        key: 'taskStatus',
        title: '状态',
        align: 'left',
        minWidth: 250,
        render: row => renderSaleTaskStatus(row)
      },
      {
        key: 'operate',
        title: '操作',
        align: 'center',
        fixed: 'right',
        width: 150,
        render: row => (
          <div class="sale-task-operate">
            {hasAuth('ticket:saleTask:query') && (
              renderOperateIconButton('查看结果', IconMaterialSymbolsFactCheckOutlineRounded, 'primary', () => openTaskResult(row))
            )}
            {hasAuth('ticket:saleTask:execute') && canExecuteNowSaleTask(row) && (
              <NPopconfirm
                disabled={executeNowLoadingTaskId.value === row.taskId}
                onPositiveClick={() => handleExecuteNow(row)}
              >
                {{
                  trigger: () => (
                    renderOperateIconButton('立即执行', IconMaterialSymbolsPlayCircleOutlineRounded, 'success')
                  ),
                  default: () => '确认立即执行该任务吗？不会预热账号；有登录上下文就直接用，没有或失效则现场登录。'
                }}
              </NPopconfirm>
            )}
            {hasAuth('ticket:saleTask:edit') && canEditSaleTask(row) && (
              renderOperateIconButton('编辑', IconMaterialSymbolsDriveFileRenameOutlineOutline, 'primary', () => handleEdit(row))
            )}
            {hasAuth('ticket:saleTask:edit') && canCancelSaleTask(row) && (
              <NPopconfirm onPositiveClick={() => handleCancel(row)}>
                {{
                  trigger: () => (
                    renderOperateIconButton('取消', IconMaterialSymbolsCancelOutline, 'warning')
                  ),
                  default: () => '确认取消该任务吗？未开始的执行计划会停止，已提交的结果仍会保留。'
                }}
              </NPopconfirm>
            )}
            {hasAuth('ticket:saleTask:remove') && canDeleteSaleTask(row) && (
              <NPopconfirm onPositiveClick={() => handleDelete([row.taskId])}>
                {{
                  trigger: () => (
                    renderOperateIconButton('删除', IconMaterialSymbolsDeleteOutline, 'error')
                  ),
                  default: () => '确认删除该抢购任务吗？'
                }}
              </NPopconfirm>
            )}
          </div>
        )
      }
    ]
  });

watch(
  () => [formModel.value.platformId, formModel.value.purchaseType] as const,
  async ([platformId, purchaseType], [previousPlatformId, previousPurchaseType]) => {
    if (pauseSelectionWatcher.value || !modalVisible.value) return;
    if (platformId === previousPlatformId && purchaseType === previousPurchaseType) return;
    const preservedLotteryUrl = preservedLotteryUrlOnPlatformSwitch.value;
    const preservedLotteryCollectionUrl = preservedLotteryCollectionUrlOnPlatformSwitch.value;
    preservedLotteryUrlOnPlatformSwitch.value = null;
    preservedLotteryCollectionUrlOnPlatformSwitch.value = null;

    formModel.value.accountIds = [];
    formModel.value.lotteryEventUrl = preservedLotteryUrl || '';
    formModel.value.selectedSessions = [];
    formModel.value.lotterySchedules = [];
    lotteryEventInfo.value = null;
    lotteryCollectionEventInfo.value = null;
    jumpShopProductInfo.value = null;
    selectedJumpShopProfileId.value = null;
    selectedLotteryHistoryUrl.value = null;
    selectedLotteryCollectionEventUrl.value = null;
    lotteryCollectionSourceUrl.value = preservedLotteryCollectionUrl;
    selectedLotteryReceptionKey.value = null;
    lotteryCollectionResolving.value = false;
    clearQuestionnairePreviewState('', true);
    accountSearchKeyword.value = '';
    await loadAccountOptions(platformId);

    if (!platformId || !purchaseType) return;
    if (purchaseType === 'lottery') {
      await loadLotteryParseHistory(platformId);
    }
    if (purchaseType === 'flash_sale' && ['jump-shop', 'jump-shop-online'].includes(normalizePlatformRoutingCode(selectedPlatform.value))) {
      await loadJumpShopProfileOptions();
    }

    const currentTaskOptions = formModel.value.taskOptions || '';
    const shouldPrompt =
      !!previousPlatformId &&
      !!previousPurchaseType &&
      currentTaskOptions.trim() &&
      currentTaskOptions.trim() !== lastLoadedTemplateText.value.trim();

    if (shouldPrompt) {
      const confirmed = window.confirm('切换平台或抢购类型会覆盖当前未保存的预设配置，是否继续？');
      if (!confirmed) {
        pauseSelectionWatcher.value = true;
        formModel.value.platformId = previousPlatformId;
        formModel.value.purchaseType = String(previousPurchaseType || 'flash_sale') as Api.Ticket.SaleTaskOperateParams['purchaseType'];
        pauseSelectionWatcher.value = false;
        await loadAccountOptions(previousPlatformId);
        return;
      }
    }

    await applyPurchaseTemplate(platformId, purchaseType, true);
  }
);

onMounted(() => {
  void loadPlatformOptions();
  void getData();
});

watch(processDrawerVisible, visible => {
  if (!visible) {
    stopProcessPolling();
  }
});

onBeforeUnmount(() => {
  stopProcessPolling();
  stopLotteryParsePolling();
});

function parseTaskJson(value?: string | null) {
  if (!value) return null;
  try {
    return JSON.parse(value) as Record<string, unknown>;
  } catch {
    return null;
  }
}

function formatTaskOptions(value: Record<string, unknown>) {
  return JSON.stringify(value, null, 2);
}

function deepClone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

function readQuestionnaireConfigFromTaskOptions(
  options: Record<string, unknown> | null
): Api.Ticket.LivePocketQuestionnaireConfig | null {
  const config = options?.questionnaireConfig;
  if (!config || typeof config !== 'object' || Array.isArray(config)) {
    return null;
  }
  const raw = config as Record<string, unknown>;
  return {
    enabled: raw.enabled !== false,
    mode: String(raw.mode || 'strict'),
    previewAccountId: raw.previewAccountId as CommonType.IdType | undefined,
    previewSessionId: raw.previewSessionId ? String(raw.previewSessionId) : undefined,
    previewAt: raw.previewAt ? String(raw.previewAt) : undefined,
    schemaSignature: raw.schemaSignature ? String(raw.schemaSignature) : undefined,
    questions: Array.isArray(raw.questions) ? (deepClone(raw.questions) as Api.Ticket.LivePocketQuestionnaireQuestion[]) : [],
    answers:
      raw.answers && typeof raw.answers === 'object' && !Array.isArray(raw.answers)
        ? (deepClone(raw.answers) as Record<string, string | string[]>)
        : {}
  };
}

function writeQuestionnaireConfigToTaskOptions(config: Api.Ticket.LivePocketQuestionnaireConfig | null) {
  const options = parseTaskJson(formModel.value.taskOptions) || {};
  if (config && config.questions?.length) {
    options.questionnaireConfig = deepClone(config);
  } else {
    delete options.questionnaireConfig;
  }
  formModel.value.taskOptions = formatTaskOptions(options);
}

function syncQuestionnairePreviewSelections() {
  const selectedAccountOptions = accountTableData.value
    .filter(item => formModel.value.accountIds?.includes(item.accountId))
    .map(item => item.accountId);
  if (selectedAccountOptions.length) {
    if (!selectedAccountOptions.includes(questionnairePreviewAccountId.value as CommonType.IdType)) {
      questionnairePreviewAccountId.value = selectedAccountOptions[0];
    }
  } else {
    questionnairePreviewAccountId.value = null;
  }

  const selectedSessionOptions = (formModel.value.selectedSessions || []).map(item => String(item.sessionId || ''));
  if (selectedSessionOptions.length) {
    if (!selectedSessionOptions.includes(String(questionnairePreviewSessionId.value || ''))) {
      questionnairePreviewSessionId.value = selectedSessionOptions[0];
    }
  } else {
    questionnairePreviewSessionId.value = null;
  }
}

function clearQuestionnairePreviewState(reason = '', resetSelectors = false) {
  questionnairePreviewConfig.value = null;
  questionnairePreviewResult.value = null;
  questionnaireInvalidationReason.value = reason;
  if (resetSelectors) {
    questionnairePreviewAccountId.value = null;
    questionnairePreviewSessionId.value = null;
  }
}

function syncQuestionnairePreviewStateFromTaskOptions(options?: Record<string, unknown> | null) {
  const nextConfig = readQuestionnaireConfigFromTaskOptions(options ?? parseTaskJson(formModel.value.taskOptions));
  questionnairePreviewConfig.value = nextConfig ? deepClone(nextConfig) : null;
  questionnairePreviewResult.value = nextConfig
    ? {
        questionnaireConfig: deepClone(nextConfig),
        questionCount: nextConfig.questions?.length || 0
      }
    : null;
  questionnaireInvalidationReason.value = '';
  if (nextConfig?.previewAccountId) {
    questionnairePreviewAccountId.value = nextConfig.previewAccountId;
  }
  if (nextConfig?.previewSessionId) {
    questionnairePreviewSessionId.value = nextConfig.previewSessionId;
  }
  syncQuestionnairePreviewSelections();
}

function invalidateQuestionnairePreview(reason: string, resetSelectors = false) {
  const hadConfig = Boolean(questionnairePreviewConfig.value?.questions?.length);
  writeQuestionnaireConfigToTaskOptions(null);
  clearQuestionnairePreviewState(hadConfig ? reason : '', resetSelectors);
  syncQuestionnairePreviewSelections();
}

function setQuestionnairePreviewConfig(
  config: Api.Ticket.LivePocketQuestionnaireConfig | null,
  result?: Api.Ticket.LivePocketQuestionnairePreviewResult | null
) {
  questionnairePreviewConfig.value = config ? deepClone(config) : null;
  questionnairePreviewResult.value = result ? deepClone(result) : config ? { questionnaireConfig: deepClone(config) } : null;
  questionnaireInvalidationReason.value = '';
  writeQuestionnaireConfigToTaskOptions(questionnairePreviewConfig.value);
  syncQuestionnairePreviewSelections();
}

function getQuestionnaireAnswer(questionName: string) {
  return questionnairePreviewConfig.value?.answers?.[questionName];
}

function getQuestionnaireTextAnswer(questionName: string) {
  const value = getQuestionnaireAnswer(questionName);
  return Array.isArray(value) ? '' : String(value || '');
}

function getQuestionnaireMultiAnswer(questionName: string) {
  const value = getQuestionnaireAnswer(questionName);
  return Array.isArray(value) ? [...value] : [];
}

function updateQuestionnaireAnswer(questionName: string, value: string | string[]) {
  if (!questionnairePreviewConfig.value) return;
  questionnairePreviewConfig.value = {
    ...questionnairePreviewConfig.value,
    answers: {
      ...(questionnairePreviewConfig.value.answers || {}),
      [questionName]: Array.isArray(value) ? [...value] : value
    }
  };
  writeQuestionnaireConfigToTaskOptions(questionnairePreviewConfig.value);
}

function buildQuestionnaireAnswerSeed(
  questions: Api.Ticket.LivePocketQuestionnaireQuestion[],
  previousAnswers?: Record<string, string | string[]>
) {
  const seed: Record<string, string | string[]> = {};
  for (const question of questions || []) {
    const previousValue = previousAnswers?.[question.name];
    if (question.type === 'checkbox') {
      seed[question.name] = Array.isArray(previousValue) ? [...previousValue] : [];
      continue;
    }
    seed[question.name] = Array.isArray(previousValue) ? '' : String(previousValue || '');
  }
  return seed;
}

function isLivePocketTicketsUrl(value: unknown) {
  const url = String(value || '');
  return url.includes('livepocket.jp/e/') && url.includes('/receptions/') && url.includes('/tickets');
}

function firstLivePocketTicketsUrl(...values: unknown[]) {
  return String(values.find(isLivePocketTicketsUrl) || '');
}

function isLivePocketEventUrl(value: unknown) {
  const url = String(value || '').trim();
  return /livepocket\.jp\/e\/[^/?#]+\/?(?:[?#].*)?$/.test(url) && !url.includes('/receptions/');
}

function firstLivePocketEventUrl(...values: unknown[]) {
  return String(values.find(isLivePocketEventUrl) || '');
}

function resolveLotteryTicketEntryUrl(options: Record<string, unknown> | null) {
  if (!options) return '';
  return firstLivePocketTicketsUrl(
    options.ticketEntryUrl,
    options.ticketsUrl,
    options.lotteryTicketsUrl,
    options.lotteryEntryUrl,
    options.eventUrl
  );
}

function resolveLivePocketFlashSaleEntryUrl(options: Record<string, unknown> | null) {
  if (!options) return '';
  return resolveLotteryTicketEntryUrl(options) || firstLivePocketEventUrl(options.eventUrl, options.lotteryEventUrl, options.lotteryEntryUrl);
}

function formatDateTime(date = new Date()) {
  const parts = japanDateTimeFormatter.formatToParts(date).reduce<Record<string, string>>((acc, part) => {
    if (part.type !== 'literal') {
      acc[part.type] = part.value;
    }
    return acc;
  }, {});
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`;
}

function parseDateTime(value?: string | null) {
  if (!value) return null;
  const match = value
    .trim()
    .match(/^(\d{4})-(\d{2})-(\d{2})(?:[ T](\d{2}):(\d{2})(?::(\d{2}))?)?$/);
  if (!match) return null;
  const [, year, month, day, hour = '00', minute = '00', second = '00'] = match;
  const time =
    Date.UTC(
      Number(year),
      Number(month) - 1,
      Number(day),
      Number(hour),
      Number(minute),
      Number(second)
    ) - JAPAN_TIME_OFFSET_MS;
  return Number.isFinite(time) ? time : null;
}

function getDefaultScheduleTime() {
  return formatDateTime(new Date(Date.now() + 30 * 60 * 1000));
}

const saleTaskEditLockLeadMs = 5 * 60 * 1000;

function getSaleTaskNextExecutionTime(row: Api.Ticket.SaleTask) {
  if (row.purchaseType === 'lottery') {
    const times = (row.lotterySchedules || [])
      .map(item => parseDateTime(item.scheduledTime))
      .filter((time): time is number => time !== null)
      .sort((left, right) => left - right);
    return times[0] ?? null;
  }
  return parseDateTime(row.scheduledTime) ?? Date.now();
}

function isSaleTaskEditTimeLocked(row: Api.Ticket.SaleTask) {
  const nextExecutionTime = getSaleTaskNextExecutionTime(row);
  return nextExecutionTime !== null && nextExecutionTime - Date.now() <= saleTaskEditLockLeadMs;
}

function buildLotteryEventInfoFromOptions(options: Record<string, unknown> | null) {
  if (!options?.eventUrl) return null;
  return {
    eventUrl: String(options.eventUrl || ''),
    ticketEntryUrl: resolveLotteryTicketEntryUrl(options),
    eventTitle: String(options.eventTitle || ''),
    entryStartTime: String(options.entryStartTime || ''),
    entryEndTime: String(options.entryEndTime || ''),
    sessions: Array.isArray(options.selectedSessions) ? (options.selectedSessions as Api.Ticket.LotteryEventSession[]) : [],
    rawSummary: String(options.rawSummary || ''),
    cacheHit: false
  } satisfies Api.Ticket.LotteryEventInfo;
}

function buildJumpShopProductInfoFromOptions(options: Record<string, unknown> | null) {
  const variantId = Number(options?.variantId || 0);
  const productId = Number(options?.productId || 0);
  const sectionId = String(options?.sectionId || '').trim();
  if (!options?.productUrl || !variantId || !productId || !sectionId) return null;
  return {
    productUrl: String(options.productUrl || ''),
    title: String(options.productTitle || ''),
    imageUrl: String(options.imageUrl || ''),
    variantId,
    productId,
    sectionId,
    available: options.available !== false,
    maxQuantity: Number(options.quantity || 10) || 10,
    currency: String(options.currency || 'JPY'),
    purchaseMode: String(options.purchaseMode || 'cart_checkout'),
    paymentMode: String(options.paymentMode || 'credit_card')
  } satisfies Api.Ticket.JumpShopProductInfo;
}

function normalizeTaskName(value?: string | null) {
  const text = (value || '').replace(/\s+/g, ' ').trim();
  return text.length > MAX_TASK_NAME_LENGTH ? text.slice(0, MAX_TASK_NAME_LENGTH) : text;
}

function applyLotteryEventInfo(eventInfo: Api.Ticket.LotteryEventInfo, fromHistory = false) {
  const availableSessions = getGuidedEligibleSessions(eventInfo.sessions || []);
  const ticketEntryUrl = firstLivePocketTicketsUrl(
    eventInfo.ticketEntryUrl,
    formModel.value.lotteryEventUrl,
    eventInfo.eventUrl,
    lastLotteryParseInputUrl.value
  );
  lotteryEventInfo.value = eventInfo;
  formModel.value.lotteryEventUrl = eventInfo.eventUrl;
  formModel.value.selectedSessions = [];
  formModel.value.lotterySchedules = [];
  invalidateQuestionnairePreview('活动已重新解析，请重新读取问卷', false);
  selectedLotteryHistoryUrl.value = fromHistory ? getLotteryHistoryValue(eventInfo) : null;
  syncLotteryReceptionSelection();
  if (!formModel.value.taskName && eventInfo.eventTitle) {
    formModel.value.taskName = normalizeTaskName(eventInfo.eventTitle);
  }
  if (!availableSessions.length) {
    window.$message?.warning(
      isLotteryTask.value ? '当前链接没有抽選受付，抽票任务只能使用抽選链接' : '当前链接没有可用的先着票卡，请重新解析活动'
    );
  }
  if (availableSessions.length === 1) {
    selectedLotteryReceptionKey.value = buildLotteryReceptionKey(availableSessions[0]);
    formModel.value.selectedSessions = [availableSessions[0]];
    applyFlashSaleSelectionDefaults(availableSessions[0]);
  }
  const options = {
    eventUrl: eventInfo.eventUrl,
    ticketEntryUrl,
    lotteryEntryUrl: ticketEntryUrl,
    eventTitle: eventInfo.eventTitle,
    entryStartTime: eventInfo.entryStartTime,
    entryEndTime: eventInfo.entryEndTime,
    selectedSessions: [],
    entryQuantity: 1,
    quantityMode: isLivePocketFlashSaleTask.value ? 'auto_max' : undefined,
    successKeywords: ['申込', '応募', '完了'],
    failureKeywords: ['エラー', '失敗', '入力してください'],
    rawSummary: eventInfo.rawSummary
  };
  formModel.value.taskOptions = formatTaskOptions(options);
  lastLoadedTemplateText.value = formModel.value.taskOptions;
  syncQuestionnairePreviewSelections();
}

async function loadLotteryParseHistory(platformId?: CommonType.IdType | null) {
  const { data: records, error } = await fetchGetTicketLotteryEventHistory(platformId);
  if (!error) {
    lotteryParseHistory.value = records || [];
  }
}

async function handleCompletedLotteryEventInfo(eventInfo: Api.Ticket.LotteryEventInfo, successMessage = '活动信息解析成功') {
  lotteryEventInfo.value = eventInfo;
  if (isLotteryCollectionEventInfo(eventInfo)) {
    lotteryCollectionSourceUrl.value = eventInfo.eventUrl;
    lotteryCollectionEventInfo.value = eventInfo;
    lotteryEventInfo.value = null;
    selectedLotteryReceptionKey.value = null;
    formModel.value.lotteryEventUrl = eventInfo.eventUrl;
    formModel.value.selectedSessions = [];
    formModel.value.lotterySchedules = [];
    selectedLotteryCollectionEventUrl.value = null;
    invalidateQuestionnairePreview('活动已切换为集合页，请重新读取问卷', false);
    lotteryEventParsing.value = false;
    lotteryCollectionResolving.value = false;
    await loadLotteryParseHistory(formModel.value.platformId);
    window.$message?.success(`已解析到 ${eventInfo.sessions?.length || 0} 个活动，请选择一个具体活动`);
    return;
  }

  if (!lotteryUsesCollectionFlow.value) {
    lotteryCollectionSourceUrl.value = null;
    lotteryCollectionEventInfo.value = null;
  }
  applyLotteryEventInfo(eventInfo);
  await loadAccountOptions(formModel.value.platformId);
  await loadLotteryParseHistory(formModel.value.platformId);
  lotteryEventParsing.value = false;
  lotteryCollectionResolving.value = false;
  window.$message?.success(successMessage);
}

async function handleSelectLotteryParseHistory(historyValue: CommonType.IdType | null) {
  selectedLotteryHistoryUrl.value = historyValue;
  if (historyValue === null || historyValue === undefined) return;
  const record = lotteryParseHistory.value.find(item => isSameLotteryHistoryValue(item, historyValue));
  if (!record) return;
  const eventInfo = record.recordId
    ? (await fetchGetTicketLotteryEventParseRecord(record.recordId)).data
    : record;
  if (!eventInfo) return;
  const previousPlatformId = formModel.value.platformId;
  pauseSelectionWatcher.value = true;
  if (eventInfo.platformId) {
    formModel.value.platformId = eventInfo.platformId;
  }
  pauseSelectionWatcher.value = false;
  if (formModel.value.platformId && formModel.value.platformId !== previousPlatformId) {
    await loadAccountOptions(formModel.value.platformId);
  }
  if (isLotteryCollectionEventInfo(eventInfo)) {
    lotteryCollectionSourceUrl.value = eventInfo.eventUrl;
    lotteryCollectionEventInfo.value = eventInfo;
    lotteryEventInfo.value = null;
    selectedLotteryReceptionKey.value = null;
    formModel.value.lotteryEventUrl = eventInfo.eventUrl;
    formModel.value.selectedSessions = [];
    formModel.value.lotterySchedules = [];
    selectedLotteryCollectionEventUrl.value = null;
    invalidateQuestionnairePreview('已载入新的活动集合，请重新读取问卷', false);
    window.$message?.success('已载入活动集合，请选择一个具体活动');
    return;
  }
  lotteryCollectionSourceUrl.value = null;
  lotteryCollectionEventInfo.value = null;
  applyLotteryEventInfo(eventInfo, true);
  await loadAccountOptions(formModel.value.platformId);
  window.$message?.success('已使用历史解析记录，无需重新解析');
}

function getSaleTaskDisplayMeta(row: Api.Ticket.SaleTask) {
  if (row.eventTitle || row.lotteryEventUrl || !row.taskOptions) {
    return {
      eventTitle: String(row.eventTitle || ''),
      eventUrl: String(row.lotteryEventUrl || '')
    };
  }
  const parsedOptions = parseTaskJson(row.taskOptions);
  const eventTitle = typeof parsedOptions?.eventTitle === 'string' ? parsedOptions.eventTitle : String(row.eventTitle || '');
  const eventUrl = row.lotteryEventUrl || (typeof parsedOptions?.eventUrl === 'string' ? parsedOptions.eventUrl : '');
  return { eventTitle, eventUrl };
}

function renderSaleTaskTitle(row: Api.Ticket.SaleTask) {
  const { eventTitle } = getSaleTaskDisplayMeta(row);
  const taskName = row.taskName || '-';
  const taskIdText = row.taskId === null || row.taskId === undefined ? '-' : String(row.taskId);
  const shouldShowEventTitle = !!eventTitle && eventTitle.trim() !== taskName.trim();
  return h('div', { class: 'sale-task-cell' }, [
    h(
      NPopover,
      { trigger: 'hover', placement: 'right', width: 360 },
      {
        trigger: () => h('span', { class: 'sale-task-title-clamp' }, taskName),
        default: () =>
          h('div', { class: 'sale-task-title-popover' }, [
            h('div', { class: 'sale-task-title-popover__main' }, taskName),
            h('div', { class: 'sale-task-title-popover__sub' }, `任务ID ${taskIdText}`),
            shouldShowEventTitle ? h('div', { class: 'sale-task-title-popover__sub' }, eventTitle) : null
          ])
      }
    ),
    h('div', { class: 'sale-task-title-meta' }, [
      h('span', { class: 'sale-task-title-id' }, `ID ${taskIdText}`),
      row.taskId !== null && row.taskId !== undefined
        ? h(
            NButton,
            {
              text: true,
              size: 'tiny',
              focusable: false,
              class: 'sale-task-title-copy',
              title: `复制任务ID ${taskIdText}`,
              onClick: async (event: MouseEvent) => {
                event.preventDefault();
                event.stopPropagation();
                await handleCopy(taskIdText);
              }
            },
            { icon: () => h(IconEpCopyDocument, { class: 'text-13px' }) }
          )
        : null
    ])
  ]);
}

function renderSaleTaskPlatformInfo(row: Api.Ticket.SaleTask) {
  return h('div', { class: 'sale-task-platform-info' }, [
    h('div', { class: 'sale-task-platform-info__name' }, row.platformName || '-'),
    h('div', { class: 'sale-task-overview__meta' }, [
      renderTicketTag(row.purchaseType)
    ])
  ]);
}

function renderSaleTaskConfig(row: Api.Ticket.SaleTask) {
  const { eventUrl } = getSaleTaskDisplayMeta(row);
  return h('div', { class: 'sale-task-config' }, [
    eventUrl
      ? h('div', { class: 'sale-task-config__link-row' }, [
          h(
            'a',
            {
              class: 'sale-task-config__url',
              href: eventUrl,
              target: '_blank',
              rel: 'noopener noreferrer',
              title: eventUrl
            },
            eventUrl
          ),
          h(
            NButton,
            {
              text: true,
              size: 'tiny',
              focusable: false,
              class: 'sale-task-config__copy',
              title: '复制链接',
              onClick: async (event: MouseEvent) => {
                event.preventDefault();
                event.stopPropagation();
                await handleCopy(eventUrl);
              }
            },
            { icon: () => h(IconEpCopyDocument, { class: 'text-14px' }) }
          )
        ])
      : h('div', { class: 'sale-task-cell__sub' }, '-')
  ]);
}

function renderAccountIdentity(row: Api.Ticket.Account) {
  return h('div', { class: 'lottery-account-cell lottery-account-cell--identity' }, [
    h('div', { class: 'lottery-account-email' }, [renderTicketEmail(row.email)]),
    h('div', { class: 'lottery-account-meta' }, [
      h('span', null, row.phoneNumber || '无来源号码'),
      h('span', null, `ID ${row.accountId}`)
    ])
  ]);
}

function normalizeAccountProfileText(value: unknown) {
  return typeof value === 'string' ? value.replace(/\s+/g, ' ').trim() : '';
}

function parseAccountProfileRecord(value?: string | null) {
  if (!value) return null;
  try {
    const parsed = JSON.parse(value);
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return parsed as Record<string, unknown>;
    }
  } catch {
    return null;
  }
  return null;
}

function buildAccountProfileSummary(value?: string | null) {
  if (!value) {
    return { primary: '未填写资料', secondary: '' };
  }

  const parsed = parseAccountProfileRecord(value);
  if (!parsed) {
    return { primary: '未填写资料', secondary: '资料格式异常' };
  }

  const fullName = normalizeAccountProfileText(parsed['fullName']);
  const furigana = normalizeAccountProfileText(parsed['furigana']);
  const familyName = normalizeAccountProfileText(parsed['familyName']);
  const givenName = normalizeAccountProfileText(parsed['givenName']);
  const nickname = normalizeAccountProfileText(parsed['nickname']);
  const source = normalizeAccountProfileText(parsed['source']);
  const primary = fullName || [familyName, givenName].filter(Boolean).join(' ') || nickname || source || '未填写资料';

  const secondaryParts: string[] = [];
  if (furigana) {
    secondaryParts.push(`フリガナ ${furigana}`);
  }
  if (nickname && nickname !== primary) {
    secondaryParts.push(`昵称 ${nickname}`);
  }
  if (source && source !== primary) {
    secondaryParts.push(`来源 ${source}`);
  }

  return {
    primary,
    secondary: secondaryParts.join(' · ')
  };
}

function renderAccountProfile(row: Api.Ticket.Account) {
  const profileSummary = buildAccountProfileSummary(row.accountInfo);
  return h('div', { class: 'lottery-account-cell lottery-account-cell--profile' }, [
    h('div', { class: 'lottery-account-profile' }, [
      h('div', {
        class: 'lottery-account-profile__headline',
        style: { display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'nowrap', minWidth: 0 }
      }, [
        h('span', {
          class: 'lottery-account-mini-tag',
          style: { display: 'inline-flex', alignItems: 'center', whiteSpace: 'nowrap', flex: '0 0 auto' }
        }, [renderTicketTag(row.accountStatus)]),
        h('span', {
          class: 'lottery-account-profile__primary',
          title: profileSummary.primary,
          style: {
            minWidth: 0,
            overflow: 'hidden',
            flex: '1 1 auto',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
            display: 'block'
          }
        }, profileSummary.primary)
      ]),
      profileSummary.secondary
        ? h('div', { class: 'lottery-account-profile__secondary', title: profileSummary.secondary }, profileSummary.secondary)
        : null
    ]),
    row.lotteryLinkOccupied ? renderAccountOccupiedState(row) : null
  ]);
}

function renderAccountOccupiedState(row: Api.Ticket.Account) {
  const taskId = row.lotteryLinkOccupiedTaskId ? String(row.lotteryLinkOccupiedTaskId) : '';
  return h('div', {
    class: 'lottery-account-occupied',
    style: {
      display: 'flex',
      flexDirection: 'column',
      alignItems: 'flex-start',
      gap: '6px',
      marginTop: '2px'
    }
  }, [
    h(
      'span',
      {
        class: 'lottery-account-occupied__badge',
        style: {
          display: 'inline-flex',
          alignItems: 'center',
          justifyContent: 'center',
          borderRadius: '999px',
          background: 'linear-gradient(180deg, rgba(255, 247, 237, 0.96) 0%, rgba(255, 237, 213, 0.96) 100%)',
          padding: '2px 9px',
          color: '#c56a16',
          fontSize: '11px',
          lineHeight: '16px',
          boxShadow: 'inset 0 0 0 1px rgba(245, 158, 11, 0.16)'
        }
      },
      '同链接已提交'
    ),
    taskId
      ? h(
          'button',
          {
            type: 'button',
            class: 'lottery-account-occupied__copy',
            title: `复制任务ID ${taskId}`,
            style: {
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: '5px',
              border: '1px solid rgba(245, 158, 11, 0.18)',
              borderRadius: '999px',
              padding: '2px 10px',
              background: 'rgba(255, 255, 255, 0.96)',
              color: '#a85d11',
              cursor: 'pointer',
              boxShadow: '0 1px 2px rgba(15, 23, 42, 0.03)'
            },
            onClick: async (event: MouseEvent) => {
              event.preventDefault();
              event.stopPropagation();
              await handleCopy(taskId);
            }
          },
          [
            h('span', { class: 'lottery-account-occupied__copy-text', style: { fontSize: '11px', lineHeight: '16px', fontWeight: 600 } }, '复制任务ID'),
            h(IconEpCopyDocument, { class: 'lottery-account-occupied__icon', style: { fontSize: '11px', flexShrink: 0 } })
          ]
        )
      : null,
    row.lotteryLinkOccupiedAt
      ? h(
          'span',
          {
            class: 'lottery-account-occupied__time',
            title: row.lotteryLinkOccupiedAt,
            style: { paddingLeft: '2px', color: '#94a3b8', fontSize: '11px', lineHeight: '16px' }
          },
          `提交于 ${row.lotteryLinkOccupiedAt}`
        )
      : null
  ]);
}

function renderAccountLoginState(row: Api.Ticket.Account) {
  return h('div', { class: 'lottery-account-cell lottery-account-cell--login' }, [
    h('div', { class: 'lottery-account-login-line' }, [
      renderTicketTag(row.loginStatus),
      h('span', { class: 'lottery-account-login-time' }, row.lastLoginTime || '未记录登录时间')
    ])
  ]);
}

function renderSaleTaskAccounts(row: Api.Ticket.SaleTask) {
  const count = Number(row.boundAccountCount || 0);
  return h('div', { class: 'sale-task-cell sale-task-cell--accounts' }, [
    count
      ? h('div', { class: 'sale-task-account-pill' }, [
          h('span', { class: 'sale-task-account-count__value' }, String(count)),
          h('span', { class: 'sale-task-account-count__label' }, '个账号')
        ])
      : h('div', { class: 'sale-task-cell__sub' }, '未绑定账号')
  ]);
}

function renderSaleTaskScheduleInfo(row: Api.Ticket.SaleTask) {
  if (row.purchaseType === 'lottery') {
    return renderLotterySchedules(row);
  }
  return h('div', { class: 'sale-task-plan__time' }, [
    h('span', { class: 'sale-task-plan__label' }, '计划时间'),
    h('span', { class: 'sale-task-plan__value' }, row.scheduledTime || '保存后立即排队')
  ]);
}

function renderSaleTaskStatus(row: Api.Ticket.SaleTask) {
  return h('div', { class: 'sale-task-status' }, [
    h('div', { class: 'sale-task-status__tag' }, renderTicketTag(row.taskStatus)),
    renderSaleTaskExecutionSummary(row),
    h('div', { class: 'sale-task-status__line' }, [
      h('span', { class: 'sale-task-status__label' }, '计划'),
      h('span', { class: 'sale-task-status__value' }, row.scheduledTime || '立即排队')
    ]),
    h('div', { class: 'sale-task-status__line' }, [
      h('span', { class: 'sale-task-status__label' }, '最近'),
      h('span', { class: 'sale-task-status__value' }, row.lastExecutedTime || '未执行')
    ])
  ]);
}

function renderSaleTaskExecutionSummary(row: Api.Ticket.SaleTask) {
  const summary = row.executionSummary || {};
  const total = Number(summary.total || 0);
  if (!total) {
    return h('div', { class: 'sale-task-status__summary sale-task-status__summary--empty' }, '执行 0');
  }
  return h(
    'div',
    { class: 'sale-task-status__summary' },
    saleTaskSummaryKeys
      .filter(item => item.key === 'total' || Number(summary[item.key] || 0) > 0)
      .map(item =>
        h('span', { class: `sale-task-status__count is-${item.key}` }, [
          h('b', null, item.label),
          h('em', null, String(summary[item.key] || 0))
        ])
      )
  );
}

function renderLotterySchedules(row: Api.Ticket.SaleTask) {
  if (row.purchaseType !== 'lottery') {
    return h('div', { class: 'sale-task-cell__sub' }, '-');
  }
  const schedules = row.lotterySchedules || [];
  const totalCount = Number(row.lotteryScheduleCount || schedules.length || 0);
  if (!totalCount) {
    return h('div', { class: 'sale-task-cell__sub' }, '未配置时段');
  }
  const preview = schedules.slice(0, 2);
  return h('div', { class: 'sale-task-cell sale-task-cell--schedules' }, [
    ...preview.map(item =>
      h('div', { class: 'sale-task-schedule-card' }, [
        h('div', { class: 'sale-task-schedule-card__top' }, [
          h('span', { class: 'sale-task-schedule-card__session' }, item.sessionLabel || item.sessionId || '-'),
          h(
            NTag,
            {
              size: 'small',
              round: true,
              bordered: false,
              type: item.scheduleStatus === 'completed' ? 'success' : 'info'
            },
            { default: () => `${item.accountCount || 0} 个` }
          )
        ]),
        h('div', { class: 'sale-task-schedule-card__time' }, item.scheduledTime || '-')
      ])
    ),
    totalCount > preview.length
      ? h('div', { class: 'sale-task-cell__sub' }, `还有 ${totalCount - preview.length} 个时段`)
      : null
  ]);
}

async function loadPlatformOptions() {
  const { data: response, error } = await fetchGetTicketPlatformList({ pageNum: 1, pageSize: 200, enabled: true });
  if (error) {
    return;
  }
  const rows = response?.rows || [];
  platformOptions.value = rows.map((item: Api.Ticket.Platform) => ({
    label: item.platformName,
    value: item.platformId,
    platformCode: item.platformCode,
    adapterType: item.adapterType
  }));
  await applyDefaultLivePocketPlatform();
}

function detectLotteryUrlPlatformCode(eventUrl?: string | null) {
  const value = String(eventUrl || '').trim().toLowerCase();
  if (!value) return '';
  if (value.includes('event.hands.net/')) {
    return 'hands-form';
  }
  if (value.includes('livepocket.jp/')) {
    return 'livepocket';
  }
  if (value.includes('jumpshop-benelic.com/')) {
    return 'jump-shop';
  }
  return '';
}

async function ensureLotteryPlatformMatchesUrl(eventUrl?: string | null) {
  const targetCode = detectLotteryUrlPlatformCode(eventUrl);
  if (!targetCode) return true;
  if (normalizePlatformRoutingCode(selectedPlatform.value) === targetCode) {
    return true;
  }
  const matchedPlatformId =
    targetCode === 'hands-form'
      ? handsFormPlatformId.value
      : targetCode === 'jump-shop'
        ? jumpShopPlatformId.value
        : livePocketPlatformId.value;
  if (!matchedPlatformId) {
    const platformLabel = targetCode === 'hands-form' ? 'Hands Form' : targetCode === 'jump-shop' ? 'Jump Shop' : 'LivePocket';
    window.$message?.error(`当前系统未配置 ${platformLabel} 平台，请先在平台管理里补齐配置`);
    return false;
  }
  preservedLotteryUrlOnPlatformSwitch.value = String(eventUrl || '').trim();
  preservedLotteryCollectionUrlOnPlatformSwitch.value = isLotteryCollectionUrl(eventUrl)
    ? String(eventUrl || '').trim()
    : null;
  formModel.value.platformId = matchedPlatformId;
  await loadAccountOptions(matchedPlatformId);
  if (targetCode === 'jump-shop') {
    await loadJumpShopProfileOptions();
  }
  if (usesGuidedTaskFlow.value) {
    await loadLotteryParseHistory(matchedPlatformId);
  }
  const platformLabel = targetCode === 'hands-form' ? 'Hands Form' : targetCode === 'jump-shop' ? 'Jump Shop' : 'LivePocket';
  window.$message?.info(`已根据链接自动切换到 ${platformLabel} 平台`);
  return true;
}

async function applyDefaultLivePocketPlatform() {
  if (!modalVisible.value || formModel.value.platformId || !livePocketPlatformId.value) {
    return;
  }
  formModel.value.platformId = livePocketPlatformId.value;
  await loadAccountOptions(livePocketPlatformId.value);
  if (usesGuidedTaskFlow.value) {
    await loadLotteryParseHistory(livePocketPlatformId.value);
  }
}

function buildAccountSearchParams(platformId: CommonType.IdType): Api.Ticket.AccountSearchParams {
  const guidedEventUrl = usesGuidedTaskFlow.value
    ? lotteryEventInfo.value?.eventUrl || formModel.value.lotteryEventUrl || String(parseTaskJson(formModel.value.taskOptions)?.eventUrl || '')
    : undefined;
  return {
    platformId,
    accountStatus: isJumpShopTask.value ? 'registered' : usesGuidedTaskFlow.value ? 'activated' : undefined,
    loginStatus: usesGuidedTaskFlow.value ? undefined : 'logged_in',
    purchaseType: usesGuidedTaskFlow.value ? formModel.value.purchaseType : undefined,
    lotteryEventUrl: isLotteryTask.value ? guidedEventUrl || undefined : undefined
  };
}

async function loadAccountOptions(platformId?: CommonType.IdType | null) {
  const loadSeq = accountOptionsLoadSeq.value + 1;
  accountOptionsLoadSeq.value = loadSeq;
  if (!platformId) {
    accountTableData.value = [];
    accountOptionsTotal.value = 0;
    accountOptionsLoadedAll.value = false;
    accountOptionsLoading.value = false;
    return false;
  }
  const pageSize = ACCOUNT_OPTIONS_INITIAL_PAGE_SIZE;
  accountOptionsLoading.value = true;
  try {
    const baseParams: Api.Ticket.AccountSearchParams = {
      ...buildAccountSearchParams(platformId),
      pageNum: 1,
      pageSize
    };
    const { data: response, error } = await fetchGetTicketAccountList(baseParams);
    if (error || accountOptionsLoadSeq.value !== loadSeq) {
      return false;
    }
    const rows = response?.rows || [];
    const total = Number(response?.total || 0);
    if (accountOptionsLoadSeq.value !== loadSeq) {
      return false;
    }
    accountTableData.value = rows;
    accountOptionsTotal.value = total || rows.length;
    accountOptionsLoadedAll.value = total > 0 ? rows.length >= total : rows.length < pageSize;
    if (isLotteryTask.value && formModel.value.accountIds?.length) {
      const occupiedIds = new Set(rows.filter(item => item.lotteryLinkOccupied).map(item => item.accountId));
      formModel.value.accountIds = (formModel.value.accountIds || []).filter(accountId => !occupiedIds.has(accountId));
    }
    syncQuestionnairePreviewSelections();
    return true;
  } finally {
    if (accountOptionsLoadSeq.value === loadSeq) {
      accountOptionsLoading.value = false;
    }
  }
}

async function loadJumpShopProfileOptions() {
  const { data: profiles, error } = await fetchGetTicketJumpShopProfileOptions();
  if (error) return;
  jumpShopProfileOptions.value = profiles || [];
}

async function handleParseJumpShopProduct() {
  if (!formModel.value.lotteryEventUrl) {
    window.$message?.error('请输入 Jump Shop 商品链接');
    return;
  }
  if (!(await ensureLotteryPlatformMatchesUrl(formModel.value.lotteryEventUrl))) {
    return;
  }
  if (!formModel.value.platformId) {
    window.$message?.error('请先选择目标平台');
    return;
  }
  jumpShopProductParsing.value = true;
  try {
    const { data: productInfo, error } = await fetchGetJumpShopProductInfo(formModel.value.platformId, formModel.value.lotteryEventUrl);
    if (error || !productInfo) return;
    jumpShopProductInfo.value = productInfo;
    formModel.value.purchaseQuantity = Math.min(Math.max(Number(productInfo.maxQuantity || 10), 1), 10);
    const taskOptions = parseTaskJson(formModel.value.taskOptions) || {};
    formModel.value.taskOptions = formatTaskOptions({
      ...taskOptions,
      productUrl: productInfo.productUrl,
      productTitle: productInfo.title,
      imageUrl: productInfo.imageUrl || '',
      variantId: productInfo.variantId,
      productId: productInfo.productId,
      sectionId: productInfo.sectionId,
      quantity: formModel.value.purchaseQuantity,
      available: productInfo.available,
      currency: productInfo.currency || 'JPY',
      purchaseMode: 'cart_checkout',
      paymentMode: 'credit_card',
      configSchemaKey: 'jump-shop-cart-checkout-v1'
    });
    if (!formModel.value.taskName && productInfo.title) {
      formModel.value.taskName = normalizeTaskName(productInfo.title);
    }
    await loadAccountOptions(formModel.value.platformId);
    await loadJumpShopProfileOptions();
    window.$message?.success('Jump Shop 商品解析成功');
  } finally {
    jumpShopProductParsing.value = false;
  }
}

async function applyPurchaseTemplate(platformId: CommonType.IdType, purchaseType: string, force = false) {
  if (!platformId || !purchaseType) return;
  const currentTaskOptions = formModel.value.taskOptions || '';
  if (!force && currentTaskOptions.trim() && currentTaskOptions.trim() !== lastLoadedTemplateText.value.trim()) {
    return;
  }
  templateLoading.value = true;
  try {
    const { data: template, error } = await fetchGetTicketPurchaseTemplate(platformId, purchaseType);
    if (error || !template) {
      return;
    }
    formModel.value.configSchemaKey = template.configSchemaKey;
    formModel.value.taskOptions = formatTaskOptions(template.configTemplate || {});
    lastLoadedTemplateText.value = formModel.value.taskOptions || '{\n  \n}';
  } finally {
    templateLoading.value = false;
  }
}

async function handleParseLotteryEvent() {
  if (!formModel.value.lotteryEventUrl) {
    window.$message?.error(`请输入${guidedTaskLabel.value}链接`);
    return;
  }
  if (!(await ensureLotteryPlatformMatchesUrl(formModel.value.lotteryEventUrl))) {
    return;
  }
  if (!formModel.value.platformId) {
    window.$message?.error('请先选择目标平台');
    return;
  }
  selectedLotteryCollectionEventUrl.value = null;
  selectedLotteryReceptionKey.value = null;
  if (isLotteryCollectionUrl(formModel.value.lotteryEventUrl)) {
    lotteryCollectionSourceUrl.value = formModel.value.lotteryEventUrl;
  } else {
    lotteryCollectionSourceUrl.value = null;
    lotteryCollectionEventInfo.value = null;
  }
  await parseLotteryEventUrl(formModel.value.lotteryEventUrl, {
    successMessage: '活动信息解析成功',
    queuedMessage: '活动解析已排队，请稍候',
    failedMessage: '活动解析失败'
  });
}

async function handleReparseSelectedLotteryEvent() {
  if (!formModel.value.lotteryEventUrl) {
    window.$message?.error('请先选择一个具体活动');
    return;
  }
  if (!(await ensureLotteryPlatformMatchesUrl(formModel.value.lotteryEventUrl))) {
    return;
  }
  if (!formModel.value.platformId) {
    window.$message?.error('请先选择目标平台');
    return;
  }
  await parseLotteryEventUrl(formModel.value.lotteryEventUrl, {
    successMessage: isLotteryTask.value ? '场次解析成功' : '票种解析成功',
    queuedMessage: isLotteryTask.value ? '场次解析已排队，请稍候' : '票种解析已排队，请稍候',
    failedMessage: isLotteryTask.value ? '场次解析失败' : '票种解析失败'
  });
}

function handleBackToLotteryCollection() {
  if (!lotteryUsesCollectionFlow.value) return;
  lotteryStep.value = lotteryCollectionStepIndex.value;
}

async function parseLotteryEventUrl(
  eventUrl: string,
  options: {
    successMessage?: string;
    queuedMessage?: string;
    failedMessage?: string;
  } = {}
) {
  const platformId = formModel.value.platformId;
  if (!platformId) {
    window.$message?.error('请先选择目标平台');
    return;
  }
  const successMessage = options.successMessage || '活动信息解析成功';
  const queuedMessage = options.queuedMessage || '活动解析已排队，请稍候';
  const failedMessage = options.failedMessage || '活动解析失败';
  lastLotteryParseInputUrl.value = eventUrl;
  lotteryEventParsing.value = true;
  stopLotteryParsePolling();
  try {
    const { data: eventInfo, error } = await fetchParseTicketLotteryEvent({
      platformId,
      eventUrl
    });
    if (error || !eventInfo) {
      lotteryEventParsing.value = false;
      lotteryCollectionResolving.value = false;
      return;
    }
    lotteryEventInfo.value = eventInfo;
    if (eventInfo.parseStatus === 'completed') {
      await handleCompletedLotteryEventInfo(eventInfo, eventInfo.cacheHit ? '已从缓存加载活动信息' : successMessage);
      return;
    }
    if (!eventInfo.recordId) {
      lotteryEventParsing.value = false;
      lotteryCollectionResolving.value = false;
      window.$message?.error('活动解析记录ID缺失');
      return;
    }
    window.$message?.info(eventInfo.parseMessage || queuedMessage);
    startLotteryParsePolling(eventInfo.recordId, successMessage, failedMessage);
  } catch {
    lotteryEventParsing.value = false;
    lotteryCollectionResolving.value = false;
  }
}

function stopLotteryParsePolling() {
  if (lotteryParsePollTimer.value) {
    window.clearInterval(lotteryParsePollTimer.value);
    lotteryParsePollTimer.value = null;
  }
}

function startLotteryParsePolling(
  recordId: CommonType.IdType,
  successMessage = '活动信息解析成功',
  failedMessage = '活动解析失败'
) {
  let attempts = 0;
  lotteryParsePollTimer.value = window.setInterval(async () => {
    attempts += 1;
    try {
      const { data: eventInfo, error } = await fetchGetTicketLotteryEventParseRecord(recordId);
      if (error || !eventInfo) {
        return;
      }
      lotteryEventInfo.value = eventInfo;
      if (eventInfo.parseStatus === 'completed') {
        stopLotteryParsePolling();
        await handleCompletedLotteryEventInfo(eventInfo, successMessage);
        return;
      }
      if (eventInfo.parseStatus === 'failed') {
        stopLotteryParsePolling();
        lotteryEventParsing.value = false;
        lotteryCollectionResolving.value = false;
        window.$message?.error(eventInfo.parseMessage || failedMessage);
        return;
      }
      if (attempts >= 60) {
        stopLotteryParsePolling();
        lotteryEventParsing.value = false;
        lotteryCollectionResolving.value = false;
        window.$message?.warning('活动解析仍在进行，请稍后从历史记录查看');
      }
    } catch {
      if (attempts >= 60) {
        stopLotteryParsePolling();
        lotteryEventParsing.value = false;
        lotteryCollectionResolving.value = false;
        window.$message?.warning('活动解析仍在进行，请稍后从历史记录查看');
      }
    }
  }, 1000);
}

async function handleSelectLotteryCollectionEvent(row: Api.Ticket.LotteryEventSession) {
  if (!row.eventUrl) {
    window.$message?.error('该活动缺少链接，无法解析');
    return;
  }
  selectedLotteryCollectionEventUrl.value = row.eventUrl;
  lotteryCollectionResolving.value = false;
  selectedLotteryReceptionKey.value = null;
  formModel.value.selectedSessions = [];
  formModel.value.lotterySchedules = [];
  formModel.value.lotteryEventUrl = row.eventUrl;
  invalidateQuestionnairePreview('已切换具体活动，请重新读取问卷', false);
  lotteryEventInfo.value = null;
  lotteryStep.value = lotteryParseStepIndex.value;
  window.$message?.success(`已选择活动，请点击“解析${guidedSessionLabel.value}”继续`);
}

function getLotteryCollectionRowProps(row: Api.Ticket.LotteryEventSession) {
  return {
    class: selectedLotteryCollectionEventUrl.value === row.eventUrl ? 'is-selected-collection-event' : '',
    onClick: () => handleSelectLotteryCollectionEvent(row)
  };
}

function createLotterySchedule(): Api.Ticket.SaleTaskSchedule {
  return {
    scheduledTime: getDefaultScheduleTime(),
    sessionId: lotterySessionOptions.value[0]?.value,
    sessionLabel: lotterySessionOptions.value[0]?.label,
    accountCount: 1,
    scheduleStatus: 'pending'
  };
}

function addLotterySchedule() {
  formModel.value.lotterySchedules = [...(formModel.value.lotterySchedules || []), createLotterySchedule()];
}

function removeLotterySchedule(index: number) {
  formModel.value.lotterySchedules = (formModel.value.lotterySchedules || []).filter((_, itemIndex) => itemIndex !== index);
}

function autoAssignLotterySchedules() {
  const sessions = formModel.value.selectedSessions || [];
  const accountCount = selectedAccountCount.value;
  if (!sessions.length) {
    window.$message?.error('请先选择参与场次');
    return;
  }
  if (!accountCount) {
    window.$message?.error('请先选择执行账号');
    return;
  }
  const baseCount = Math.floor(accountCount / sessions.length);
  const remainder = accountCount % sessions.length;
  const defaultScheduleTime = getDefaultScheduleTime();
  formModel.value.lotterySchedules = sessions
    .map((session, index) => ({
      scheduledTime: defaultScheduleTime,
      sessionId: session.sessionId,
      sessionLabel: session.sessionLabel || session.sessionId,
      accountCount: baseCount + (index < remainder ? 1 : 0),
      scheduleStatus: 'pending'
    }))
    .filter(schedule => schedule.accountCount > 0);
  window.$message?.success(`已按 ${formModel.value.lotterySchedules.length} 个场次自动分配 ${accountCount} 个账号`);
}

function handleQuestionnairePreviewAccountChange(value: CommonType.IdType | null) {
  const previousValue = questionnairePreviewAccountId.value;
  questionnairePreviewAccountId.value = value;
  if (
    questionnairePreviewConfig.value?.questions?.length &&
    previousValue !== null &&
    value !== null &&
    String(previousValue) !== String(value)
  ) {
    invalidateQuestionnairePreview('预读账号已变更，请重新读取问卷', false);
  }
}

function handleQuestionnairePreviewSessionChange(value: string | null) {
  const previousValue = questionnairePreviewSessionId.value;
  questionnairePreviewSessionId.value = value;
  if (
    questionnairePreviewConfig.value?.questions?.length &&
    previousValue &&
    value &&
    String(previousValue) !== String(value)
  ) {
    invalidateQuestionnairePreview('预读场次已变更，请重新读取问卷', false);
  }
}

async function handlePreviewLivePocketQuestionnaire() {
  if (!isLivePocketLotteryTask.value) return;
  if (!formModel.value.platformId) {
    window.$message?.error('请先选择目标平台');
    return;
  }
  if (!formModel.value.accountIds?.length) {
    window.$message?.error('请先选择执行账号');
    return;
  }
  if (!formModel.value.selectedSessions?.length) {
    window.$message?.error('请先选择至少一个抽選场次');
    return;
  }
  const previewAccountId = questionnairePreviewAccountId.value || questionnairePreviewAccountOptions.value[0]?.value;
  const previewSessionId = questionnairePreviewSessionId.value || questionnairePreviewSessionOptions.value[0]?.value;
  if (!previewAccountId) {
    window.$message?.error('请选择一个预读账号');
    return;
  }
  if (!previewSessionId) {
    window.$message?.error('请选择一个预读场次');
    return;
  }

  questionnairePreviewLoading.value = true;
  try {
    const { data: result, error } = await fetchPreviewLivePocketQuestionnaire({
      platformId: formModel.value.platformId,
      accountId: previewAccountId,
      lotteryEventUrl: formModel.value.lotteryEventUrl || String(parseTaskJson(formModel.value.taskOptions)?.eventUrl || ''),
      selectedSessionId: previewSessionId,
      taskOptions: formModel.value.taskOptions || '{}'
    });
    if (error || !result) {
      return;
    }
    const nextConfig = result.questionnaireConfig ? deepClone(result.questionnaireConfig) : null;
    if (!nextConfig || !nextConfig.questions?.length) {
      questionnairePreviewResult.value = result;
      invalidateQuestionnairePreview('', false);
      window.$message?.success('确认页没有自定义问答，无需额外配置');
      return;
    }
    const previousConfig = questionnairePreviewConfig.value;
    const previousAnswers =
      previousConfig?.schemaSignature && previousConfig.schemaSignature === nextConfig.schemaSignature ? previousConfig.answers : undefined;
    nextConfig.enabled = true;
    nextConfig.mode = 'strict';
    nextConfig.previewAccountId = previewAccountId;
    nextConfig.previewSessionId = previewSessionId;
    nextConfig.answers = buildQuestionnaireAnswerSeed(nextConfig.questions || [], previousAnswers);
    questionnairePreviewAccountId.value = previewAccountId;
    questionnairePreviewSessionId.value = previewSessionId;
    setQuestionnairePreviewConfig(nextConfig, result);
    window.$message?.success(`已读取确认页问卷，共 ${nextConfig.questions.length} 题`);
  } finally {
    questionnairePreviewLoading.value = false;
  }
}

function resetOperateState(purchaseType: 'flash_sale' | 'lottery') {
  operateType.value = 'add';
  activeTaskMode.value = purchaseType;
  pauseSelectionWatcher.value = true;
  formModel.value = {
    ...createFormModel(),
    purchaseType
  };
  pauseSelectionWatcher.value = false;
  lastLoadedTemplateText.value = formModel.value.taskOptions || '{\n  \n}';
  accountSearchKeyword.value = '';
  accountTableData.value = [];
  lotteryEventInfo.value = null;
  lotteryCollectionEventInfo.value = null;
  jumpShopProductInfo.value = null;
  jumpShopProfileOptions.value = [];
  selectedJumpShopProfileId.value = null;
  selectedLotteryHistoryUrl.value = null;
  selectedLotteryCollectionEventUrl.value = null;
  lotteryCollectionSourceUrl.value = null;
  selectedLotteryReceptionKey.value = null;
  lotteryCollectionResolving.value = false;
  clearQuestionnairePreviewState('', true);
  lotteryStep.value = 1;
}

function openAddFlashSale() {
  resetOperateState('flash_sale');
  modalVisible.value = true;
  void applyDefaultLivePocketPlatform();
}

function openAddLottery() {
  resetOperateState('lottery');
  modalVisible.value = true;
  void applyDefaultLivePocketPlatform();
}

function handleModalMaskClick() {
  modalShaking.value = false;
  window.requestAnimationFrame(() => {
    modalShaking.value = true;
    window.setTimeout(() => {
      modalShaking.value = false;
    }, 360);
  });
}

function canEditSaleTask(row: Api.Ticket.SaleTask) {
  return row.taskStatus === 'draft' && !isSaleTaskEditTimeLocked(row);
}

function getSaleTaskEditDisabledMessage(row: Api.Ticket.SaleTask) {
  if (row.taskStatus !== 'draft') {
    return '任务已开始，不能再编辑，请取消后重新创建任务';
  }
  if (isSaleTaskEditTimeLocked(row)) {
    return '距离执行时间不足 5 分钟，不能再编辑，请取消后重新创建任务';
  }
  return '当前任务不能编辑';
}

function canCancelSaleTask(row: Api.Ticket.SaleTask) {
  return saleTaskCancelableStatuses.has(row.taskStatus);
}

function canDeleteSaleTask(row: Api.Ticket.SaleTask) {
  return saleTaskDeletableStatuses.has(row.taskStatus);
}

function canExecuteNowSaleTask(row: Api.Ticket.SaleTask) {
  return saleTaskExecuteNowStatuses.has(row.taskStatus);
}

async function handleEdit(row: Api.Ticket.SaleTask) {
  if (!canEditSaleTask(row)) {
    window.$message?.warning(getSaleTaskEditDisabledMessage(row));
    return;
  }
  const { data: detailRow, error } = await fetchGetTicketSaleTask(row.taskId);
  if (error || !detailRow) {
    window.$message?.error('加载任务详情失败，请稍后重试');
    return;
  }
  operateType.value = 'edit';
  activeTaskMode.value = detailRow.purchaseType === 'lottery' ? 'lottery' : 'flash_sale';
  const taskOptions = parseTaskJson(detailRow.taskOptions);
  const restoredLotteryEventInfo = buildLotteryEventInfoFromOptions(taskOptions);
  pauseSelectionWatcher.value = true;
  formModel.value = {
    taskId: detailRow.taskId,
    platformId: detailRow.platformId,
    taskName: detailRow.taskName,
    taskStatus: detailRow.taskStatus,
    purchaseType: detailRow.purchaseType,
    configSchemaKey: detailRow.configSchemaKey,
    warmupTime: detailRow.warmupTime || null,
    scheduledTime: detailRow.scheduledTime || null,
    purchaseQuantity: detailRow.purchaseQuantity,
    taskOptions: detailRow.taskOptions || '{\n  \n}',
    lotteryEventUrl: detailRow.lotteryEventUrl || String(taskOptions?.eventUrl || ''),
    selectedSessions: detailRow.selectedSessions?.length
      ? detailRow.selectedSessions
      : restoredLotteryEventInfo?.sessions || [],
    accountIds: detailRow.accountIds || [],
    lotterySchedules: detailRow.lotterySchedules?.length
      ? detailRow.lotterySchedules.map(item => ({
          ...item,
          scheduledTime: item.scheduledTime || null,
          sessionId: item.sessionId || undefined,
          sessionLabel: item.sessionLabel || undefined
        }))
      : [],
    remark: detailRow.remark || ''
  };
  pauseSelectionWatcher.value = false;
  lastLoadedTemplateText.value = formModel.value.taskOptions || '{\n  \n}';
  accountSearchKeyword.value = '';
  lotteryEventInfo.value = restoredLotteryEventInfo;
  lotteryCollectionEventInfo.value = null;
  jumpShopProductInfo.value = buildJumpShopProductInfoFromOptions(taskOptions);
  selectedJumpShopProfileId.value = Number(taskOptions?.profileId || 0) || null;
  selectedLotteryCollectionEventUrl.value = null;
  lotteryCollectionSourceUrl.value = null;
  selectedLotteryReceptionKey.value = detailRow.selectedSessions?.[0] ? buildLotteryReceptionKey(detailRow.selectedSessions[0]) : null;
  lotteryCollectionResolving.value = false;
  syncQuestionnairePreviewStateFromTaskOptions(taskOptions);
  lotteryStep.value = usesGuidedTaskFlow.value ? lotteryScheduleStepIndex.value : 1;
  if (usesGuidedTaskFlow.value) {
    await loadLotteryParseHistory(detailRow.platformId);
    selectedLotteryHistoryUrl.value = findLotteryHistoryValueByEventUrl(formModel.value.lotteryEventUrl);
  } else {
    selectedLotteryHistoryUrl.value = null;
  }
  await loadAccountOptions(detailRow.platformId);
  if (isJumpShopTask.value) {
    await loadJumpShopProfileOptions();
  }
  if (
    detailRow.purchaseType === 'flash_sale' &&
    !isJumpShopTask.value &&
    !detailRow.selectedSessions?.length &&
    !resolveLivePocketFlashSaleEntryUrl(taskOptions)
  ) {
    lotteryStep.value = 1;
    window.$message?.warning('该普通抢票任务仍是旧版配置，请重新解析活动并选择票种后再保存');
  }
  modalVisible.value = true;
}

function validateLotteryStep(step = lotteryStep.value) {
  if (step >= 1) {
    if (!formModel.value.platformId) {
      window.$message?.error('请先选择目标平台');
      return false;
    }
    if (!formModel.value.lotteryEventUrl) {
      window.$message?.error(`请输入${guidedTaskLabel.value}链接`);
      return false;
    }
  }
  if (isJumpShopTask.value) {
    if (step >= lotteryParseStepIndex.value && !jumpShopProductInfo.value) {
      window.$message?.error('请先解析 Jump Shop 商品信息');
      return false;
    }
    if (step >= lotteryAccountStepIndex.value && !formModel.value.accountIds?.length) {
      window.$message?.error('请至少选择一个执行账号');
      return false;
    }
    if (step >= lotterySessionStepIndex.value && !selectedJumpShopProfileId.value) {
      window.$message?.error('请选择一份 Jump Shop 结算资料');
      return false;
    }
    if (step >= lotteryScheduleStepIndex.value) {
      const quantity = Number(formModel.value.purchaseQuantity || 0);
      if (!Number.isFinite(quantity) || quantity < 1 || quantity > 10) {
        window.$message?.error('Jump Shop 购买数量必须在 1-10 之间');
        return false;
      }
    }
    return true;
  }
  if (usesLivePocketFlashSaleQuickPath.value) {
    if (step >= lotteryAccountStepIndex.value && !formModel.value.accountIds?.length) {
      window.$message?.error('请至少选择一个执行账号');
      return false;
    }
    return true;
  }
  if (lotteryUsesCollectionFlow.value && step >= lotteryCollectionStepIndex.value) {
    const collectionInfo = lotteryCollectionDisplayInfo.value;
    if (!collectionInfo?.sessions?.length) {
      window.$message?.error('请先解析活动集合');
      return false;
    }
  }
  if (lotteryUsesCollectionFlow.value && step >= lotteryParseStepIndex.value && !selectedLotteryCollectionEventUrl.value) {
    window.$message?.error('请先选择一个具体活动');
    return false;
  }
  if (step >= lotteryParseStepIndex.value) {
    const eventInfo = lotteryEventInfo.value || buildLotteryEventInfoFromOptions(parseTaskJson(formModel.value.taskOptions));
    if (isLotteryParsePending.value) {
      window.$message?.warning('活动仍在解析中，请等待解析完成后再下一步');
      return false;
    }
    if (!eventInfo?.eventUrl || !eventInfo.sessions?.length) {
      window.$message?.error(`请先解析活动，并确认解析到了${guidedSessionLabel.value}`);
      return false;
    }
    if (isLotteryCollectionEventInfo(eventInfo)) {
      window.$message?.error('请先选择一个具体活动');
      return false;
    }
    if (!getGuidedEligibleSessions(eventInfo.sessions).length) {
      window.$message?.error(isLotteryTask.value ? '当前链接没有抽選受付，抽票任务只能选择抽選链接' : '当前链接没有可用的先着票卡');
      return false;
    }
  }
  if (step >= lotteryReceptionStepIndex.value && !selectedLotteryReceptionGroup.value) {
    window.$message?.error('请先选择一个受付');
    return false;
  }
  if (step >= lotteryAccountStepIndex.value && !formModel.value.accountIds?.length) {
    window.$message?.error('请至少选择一个执行账号');
    return false;
  }
  if (step >= lotterySessionStepIndex.value && !formModel.value.selectedSessions?.length) {
    window.$message?.error(isLotteryTask.value ? '请至少选择一个参与场次' : '请先选择一个票种');
    return false;
  }
  if (step >= lotterySessionStepIndex.value) {
    const selectedSessions = formModel.value.selectedSessions || [];
    if (isLotteryTask.value && selectedSessions.some(item => !isLotteryDrawSession(item))) {
      window.$message?.error('抽票任务只能选择抽選场次');
      return false;
    }
    if (isLivePocketFlashSaleTask.value) {
      if (selectedSessions.length !== 1) {
        window.$message?.error('普通抢票任务只能选择一个票种');
        return false;
      }
      if (selectedSessions.some(item => !isFlashSaleEligibleSession(item))) {
        window.$message?.error('普通抢票只能选择非抽選票卡');
        return false;
      }
    }
  }
  return true;
}

function nextLotteryStep() {
  if (!validateLotteryStep(lotteryStep.value)) return;
  lotteryStep.value = Math.min(lotteryMaxStep.value, lotteryStep.value + 1);
}

function previousLotteryStep() {
  lotteryStep.value = Math.max(1, lotteryStep.value - 1);
}

async function handleSubmit() {
  formModel.value.taskName = normalizeTaskName(formModel.value.taskName);

  if (!formModel.value.platformId) {
    window.$message?.error('请选择目标平台');
    return;
  }
  if (!formModel.value.purchaseType) {
    window.$message?.error('请选择抢购类型');
    return;
  }
  if (!formModel.value.accountIds?.length) {
    window.$message?.error('请至少选择一个执行账号');
    return;
  }
  if (isLotteryTask.value) {
    formModel.value.purchaseQuantity = 1;
    formModel.value.remark = '';
    const schedules = formModel.value.lotterySchedules || [];
    const lotteryOptions = parseTaskJson(formModel.value.taskOptions) || {};
    const eventInfo = lotteryEventInfo.value || buildLotteryEventInfoFromOptions(lotteryOptions);
    if (!formModel.value.lotteryEventUrl && !eventInfo?.eventUrl) {
      window.$message?.error('请先解析抽票链接');
      return;
    }
    if (!lotterySessionOptions.value.length) {
      window.$message?.error('当前活动没有可选场次，请重新解析票链接');
      return;
    }
    if (!schedules.length) {
      window.$message?.error('抽票任务请至少配置一个执行时段');
      return;
    }
    const invalidSchedule = schedules.some(item => !item.scheduledTime || !item.sessionId || !Number(item.accountCount || 0));
    if (invalidSchedule) {
      window.$message?.error('请完整填写抽票场次、时段和账号数量');
      return;
    }
    const entryStart = parseDateTime(eventInfo?.entryStartTime);
    const entryEnd = parseDateTime(eventInfo?.entryEndTime);
    const invalidTimeRange = schedules.some(item => {
      const scheduleTime = parseDateTime(item.scheduledTime);
      if (!scheduleTime) return true;
      if (entryStart && scheduleTime < entryStart) return true;
      if (entryEnd && scheduleTime > entryEnd) return true;
      return false;
    });
    if (invalidTimeRange) {
      window.$message?.error('抽票触发时间必须在受付时间范围内');
      return;
    }
    if (lotteryScheduleAccountTotal.value > selectedAccountCount.value) {
      window.$message?.error('抽票时段账号总数不能超过已选择账号数');
      return;
    }
    const selectedSessions = (formModel.value.selectedSessions || [])
      .filter(isLotteryDrawSession)
      .map(item => normalizeLotterySession(item))
      .filter((item, index, array) => item.sessionId && array.findIndex(target => target.sessionId === item.sessionId) === index);
    if (!selectedSessions.length) {
      window.$message?.error('抽票任务只能选择抽選场次');
      return;
    }
    const selectedReceptionIds = Array.from(new Set(selectedSessions.map(item => String(item.receptionId || '').trim()).filter(Boolean)));
    if (!selectedReceptionIds.length) {
      window.$message?.error('当前场次缺少受付标识，请重新解析活动');
      return;
    }
    if (selectedReceptionIds.length > 1) {
      window.$message?.error('一个抽票任务只能选择同一个受付下的场次');
      return;
    }
    formModel.value.selectedSessions = selectedSessions;
    formModel.value.taskOptions = formatTaskOptions({
      ...lotteryOptions,
      eventUrl: eventInfo?.eventUrl || formModel.value.lotteryEventUrl,
      ticketEntryUrl: eventInfo?.ticketEntryUrl || '',
      lotteryEntryUrl: eventInfo?.ticketEntryUrl || '',
      receptionId: selectedReceptionIds[0],
      receptionTitle: selectedSessions[0]?.receptionTitle,
      eventTitle: eventInfo?.eventTitle,
      entryStartTime: eventInfo?.entryStartTime,
      entryEndTime: eventInfo?.entryEndTime,
      selectedSessions,
      entryQuantity: formModel.value.purchaseQuantity || 1
    });
    formModel.value.lotterySchedules = schedules.map(item => ({
      scheduleId: item.scheduleId,
      taskId: item.taskId,
      scheduledTime: item.scheduledTime,
      sessionId: item.sessionId,
      sessionLabel:
        lotterySessionOptions.value.find(option => option.value === item.sessionId)?.label || item.sessionLabel || item.sessionId,
      accountCount: Number(item.accountCount || 0),
      scheduleStatus: item.scheduleStatus || 'pending',
      resultMessage: item.resultMessage
    }));
  } else if (isJumpShopTask.value) {
    const jumpShopOptions = parseTaskJson(formModel.value.taskOptions) || {};
    const productInfo = jumpShopProductInfo.value || buildJumpShopProductInfoFromOptions(jumpShopOptions);
    if (!productInfo) {
      window.$message?.error('请先解析 Jump Shop 商品链接');
      return;
    }
    if (!selectedJumpShopProfileId.value) {
      window.$message?.error('请选择 Jump Shop 结算资料');
      return;
    }
    const quantity = Number(formModel.value.purchaseQuantity || 0);
    if (!Number.isFinite(quantity) || quantity < 1 || quantity > 10) {
      window.$message?.error('Jump Shop 购买数量必须在 1-10 之间');
      return;
    }
    formModel.value.selectedSessions = [];
    formModel.value.lotterySchedules = [];
    formModel.value.purchaseQuantity = quantity;
    formModel.value.taskOptions = formatTaskOptions({
      ...jumpShopOptions,
      productUrl: productInfo.productUrl,
      variantId: productInfo.variantId,
      productId: productInfo.productId,
      sectionId: productInfo.sectionId,
      productTitle: productInfo.title,
      imageUrl: productInfo.imageUrl || '',
      available: productInfo.available,
      currency: productInfo.currency || 'JPY',
      quantity,
      profileId: selectedJumpShopProfileId.value,
      purchaseMode: 'cart_checkout',
      paymentMode: 'credit_card',
      configSchemaKey: 'jump-shop-cart-checkout-v1'
    });
  } else if (isLivePocketFlashSaleTask.value) {
    const flashSaleOptions = parseTaskJson(formModel.value.taskOptions) || {};
    const eventInfo = lotteryEventInfo.value || buildLotteryEventInfoFromOptions(flashSaleOptions);
    const ticketEntryUrl = firstLivePocketTicketsUrl(
      eventInfo?.ticketEntryUrl,
      flashSaleOptions.ticketEntryUrl,
      flashSaleOptions.ticketsUrl,
      flashSaleOptions.lotteryTicketsUrl,
      flashSaleOptions.lotteryEntryUrl,
      formModel.value.lotteryEventUrl,
      eventInfo?.eventUrl,
      lastLotteryParseInputUrl.value
    );
    const livePocketEventUrl = firstLivePocketEventUrl(
      eventInfo?.eventUrl,
      formModel.value.lotteryEventUrl,
      flashSaleOptions.eventUrl,
      flashSaleOptions.lotteryEventUrl,
      flashSaleOptions.lotteryEntryUrl,
      lastLotteryParseInputUrl.value
    );
    if (!livePocketEventUrl && !ticketEntryUrl) {
      window.$message?.error('请输入 LivePocket 活动页或 tickets 链接');
      return;
    }
    const selectedSessions = (formModel.value.selectedSessions || [])
      .filter(isFlashSaleEligibleSession)
      .map(item => normalizeLotterySession(item))
      .filter((item, index, array) => item.sessionId && array.findIndex(target => target.sessionId === item.sessionId) === index);
    if (!selectedSessions.length && (ticketEntryUrl || livePocketEventUrl)) {
      const placeholderQuantity = 1;
      formModel.value.purchaseQuantity = placeholderQuantity;
      formModel.value.selectedSessions = [];
      formModel.value.lotterySchedules = [];
      formModel.value.taskOptions = formatTaskOptions({
        ...flashSaleOptions,
        eventUrl: livePocketEventUrl || ticketEntryUrl,
        ticketEntryUrl: ticketEntryUrl || undefined,
        lotteryEntryUrl: ticketEntryUrl || undefined,
        eventTitle: eventInfo?.eventTitle,
        entryStartTime: eventInfo?.entryStartTime,
        entryEndTime: eventInfo?.entryEndTime,
        quantityMode: 'auto_max',
        purchaseQuantity: placeholderQuantity,
        ticketQuantity: placeholderQuantity,
        entryQuantity: placeholderQuantity,
        paymentMode: 'cod_store',
        paymentProvider: 'lawson',
        paymentMethod: 'cvs',
        sbpsWebCvsType: '002',
        selectedSessions: []
      });
    } else if (selectedSessions.length !== 1) {
      window.$message?.error('普通抢票任务只能选择一个票种');
      return;
    } else {
      const selectedSession = selectedSessions[0];
      const placeholderQuantity = 1;
      formModel.value.purchaseQuantity = placeholderQuantity;
      formModel.value.selectedSessions = selectedSessions;
      formModel.value.lotterySchedules = [];
      formModel.value.taskOptions = formatTaskOptions({
        ...flashSaleOptions,
        eventUrl: eventInfo?.eventUrl || formModel.value.lotteryEventUrl,
        ticketEntryUrl,
        lotteryEntryUrl: ticketEntryUrl,
        eventTitle: eventInfo?.eventTitle,
        entryStartTime: eventInfo?.entryStartTime,
        entryEndTime: eventInfo?.entryEndTime,
        quantityMode: 'auto_max',
        purchaseQuantity: placeholderQuantity,
        ticketQuantity: placeholderQuantity,
        entryQuantity: placeholderQuantity,
        paymentMode: 'cod_store',
        paymentProvider: 'lawson',
        paymentMethod: 'cvs',
        sbpsWebCvsType: '002',
        receptionId: selectedSession.receptionId,
        receptionTitle: selectedSession.receptionTitle,
        ticketId: selectedSession.ticketId,
        ticketField: selectedSession.ticketField,
        salesType: selectedSession.salesType,
        sessionId: selectedSession.sessionId,
        sessionLabel: selectedSession.sessionLabel,
        maxPurchaseQuantity: selectedSession.maxPurchaseQuantity || undefined,
        selectedSessions
      });
    }
  } else {
    formModel.value.lotterySchedules = [];
  }

  let normalizedTaskOptions = '{}';
  try {
    normalizedTaskOptions = JSON.stringify(JSON.parse(formModel.value.taskOptions || '{}'));
  } catch {
    window.$message?.error('平台预设配置必须是合法 JSON');
    return;
  }

  const requestPayload: Api.Ticket.SaleTaskOperateParams = {
    taskId: formModel.value.taskId,
    platformId: formModel.value.platformId,
    taskName: formModel.value.taskName,
    taskStatus: formModel.value.taskStatus,
    purchaseType: formModel.value.purchaseType,
    configSchemaKey: formModel.value.configSchemaKey,
    warmupTime: formModel.value.warmupTime,
    scheduledTime: formModel.value.scheduledTime,
    purchaseQuantity: formModel.value.purchaseQuantity,
    taskOptions: normalizedTaskOptions,
    lotteryEventUrl: formModel.value.lotteryEventUrl,
    remark: formModel.value.remark,
    accountIds: [...(formModel.value.accountIds || [])],
    selectedSessions: (formModel.value.selectedSessions || []).map(item => ({
      sessionId: item.sessionId,
      receptionId: item.receptionId,
      receptionTitle: item.receptionTitle,
      ticketId: item.ticketId,
      ticketField: item.ticketField,
      salesType: item.salesType,
      sessionLabel: item.sessionLabel,
      notes: item.notes,
      maxPurchaseQuantity: item.maxPurchaseQuantity
    })),
    lotterySchedules: (formModel.value.lotterySchedules || []).map(item => ({
      scheduleId: item.scheduleId,
      taskId: item.taskId,
      scheduledTime: item.scheduledTime,
      sessionId: item.sessionId,
      sessionLabel: item.sessionLabel,
      accountCount: item.accountCount,
      scheduleStatus: item.scheduleStatus,
      resultMessage: item.resultMessage
    }))
  };

  const requestFn = operateType.value === 'add' ? fetchCreateTicketSaleTask : fetchUpdateTicketSaleTask;
  await requestFn(requestPayload);
  window.$message?.success(formModel.value.scheduledTime ? '任务已排队，系统将按计划时间自动执行' : '任务已排队，系统将立即执行');
  modalVisible.value = false;
  await getData();
}

async function handleDelete(taskIds: CommonType.IdType[]) {
  if (!taskIds.length) return;
  await fetchDeleteTicketSaleTask(taskIds);
  checkedRowKeys.value = checkedRowKeys.value.filter(item => !taskIds.includes(item));
  window.$message?.success('删除成功');
  await getData();
}

async function handleCancel(row: Api.Ticket.SaleTask) {
  await fetchCancelTicketSaleTask(row.taskId);
  checkedRowKeys.value = checkedRowKeys.value.filter(item => item !== row.taskId);
  window.$message?.success('任务已取消');
  await getData();
}

async function handleExecuteNow(row: Api.Ticket.SaleTask) {
  executeNowLoadingTaskId.value = row.taskId;
  try {
    const { error } = await fetchExecuteNowTicketSaleTask(row.taskId);
    if (error) return;
    window.$message?.success('已提交立即执行');
    await getData();
    if (processDrawerVisible.value && processTask.value?.taskId === row.taskId) {
      await Promise.all([loadTaskProcess(row.taskId, true), loadTaskProcessExecutions(row.taskId, true)]);
    }
  } finally {
    executeNowLoadingTaskId.value = null;
  }
}

async function handleRetryFailedLotteryTask() {
  const taskId = processTask.value?.taskId;
  if (!taskId) return;
  retryFailedLotteryLoading.value = true;
  try {
    const { data, error } = await fetchRetryFailedTicketLotteryTask(taskId);
    if (error) return;
    const count = Number(data || 0);
    window.$message?.success(count > 0 ? `已重新提交 ${count} 个未成功账号` : '没有可重新提交的未成功账号');
    await Promise.all([getData(), loadTaskProcess(taskId, true), loadTaskProcessExecutions(taskId, true)]);
  } finally {
    retryFailedLotteryLoading.value = false;
  }
}

function getTicketLabel(value?: string | number | null) {
  const raw = value === null || value === undefined ? '' : String(value);
  if (raw === 'timeout') return '失败';
  if (raw === 'pending') return '待执行';
  return ticketLabelMap[raw] || raw || '-';
}

function getTicketTagType(value?: string | number | null) {
  const raw = value === null || value === undefined ? '' : String(value);
  return ticketTagTypeMap[raw] || 'default';
}

function getExecutionMessage(row: Api.Ticket.OrderExecution) {
  return row.resultMessage || row.rawResult || '等待执行器回写结果';
}

function getExecutionTime(row: Api.Ticket.OrderExecution) {
  return row.executedAt || row.startedAt || row.heartbeatAt || '尚未开始';
}

function setProcessExecutionStatusFilter(status: string) {
  processExecutionStatusFilter.value = status;
  processExecutionPagination.value.pageNum = 1;
  void loadTaskProcessExecutions(processTask.value?.taskId);
}

function handleProcessExecutionPageChange(page: number) {
  processExecutionPagination.value.pageNum = page;
  void loadTaskProcessExecutions(processTask.value?.taskId);
}

function handleProcessExecutionPageSizeChange(pageSize: number) {
  processExecutionPagination.value.pageSize = pageSize;
  processExecutionPagination.value.pageNum = 1;
  void loadTaskProcessExecutions(processTask.value?.taskId);
}

function formatProcessJson(value: unknown) {
  if (value === null || value === undefined || value === '') {
    return '-';
  }
  if (typeof value === 'string') {
    try {
      return JSON.stringify(JSON.parse(value), null, 2);
    } catch {
      return value;
    }
  }
  return JSON.stringify(value, null, 2);
}

function buildExecutionDetail(row: Api.Ticket.OrderExecution) {
  return {
    executionId: row.executionId,
    accountId: row.accountId,
    email: row.email,
    executionStatus: row.executionStatus,
    currentStep: row.currentStep,
    stepStatus: row.stepStatus,
    paymentStatus: row.paymentStatus,
    orderNo: row.orderNo,
    lotteryResultStatus: row.lotteryResultStatus,
    lotteryResultMailRecordId: row.lotteryResultMailRecordId,
    lotteryResultAt: row.lotteryResultAt,
    resultMessage: row.resultMessage,
    stepTrace: row.stepTrace ? tryParseJson(row.stepTrace) : null,
    rawResult: row.rawResult ? tryParseJson(row.rawResult) : null,
    configSnapshot: row.configSnapshot ? tryParseJson(row.configSnapshot) : null
  };
}

function tryParseJson(value: string) {
  try {
    return JSON.parse(value);
  } catch {
    return value;
  }
}

function openProcessJson(title: string, value: unknown) {
  processJsonTitle.value = title;
  processJsonContent.value = formatProcessJson(value);
  processJsonVisible.value = true;
}

async function loadTaskProcess(taskId?: CommonType.IdType, silent = false) {
  if (!taskId) return;
  if (!silent) {
    processLoading.value = true;
  }
  try {
    const { data: response, error } = await fetchGetTicketSaleTaskProcess(taskId);
    if (!error && response) {
      processData.value = response;
    }
  } finally {
    if (!silent) {
      processLoading.value = false;
    }
  }
}

async function loadTaskProcessExecutions(taskId?: CommonType.IdType, silent = false) {
  if (!taskId) return;
  if (!silent) {
    processExecutionLoading.value = true;
  }
  try {
    const status = processExecutionStatusFilter.value === 'total' ? undefined : processExecutionStatusFilter.value;
    const params: Api.Ticket.SaleTaskProcessExecutionSearchParams = {
      pageNum: processExecutionPagination.value.pageNum,
      pageSize: processExecutionPagination.value.pageSize
    };
    if (status) {
      params.status = status;
    }
    const { data: response, error } = await fetchGetTicketSaleTaskProcessExecutions(taskId, params);
    if (!error && response) {
      processExecutions.value = response.rows || [];
      processExecutionTotal.value = Number(response.total || 0);
    } else if (!silent) {
      window.$message?.error('执行明细加载失败');
    }
  } finally {
    if (!silent) {
      processExecutionLoading.value = false;
    }
  }
}

function startProcessPolling(taskId: CommonType.IdType) {
  stopProcessPolling();
  let tick = 0;
  processPollTimer.value = window.setInterval(() => {
    tick += 1;
    void loadTaskProcess(taskId, true);
    if (tick % 2 === 0) {
      void loadTaskProcessExecutions(taskId, true);
    }
  }, 3000);
}

function stopProcessPolling() {
  if (processPollTimer.value) {
    window.clearInterval(processPollTimer.value);
    processPollTimer.value = null;
  }
}

function openTaskProcess(row: Api.Ticket.SaleTask, initialFilter = 'total') {
  processData.value = null;
  processExecutions.value = [];
  processExecutionTotal.value = 0;
  processExecutionPagination.value.pageNum = 1;
  processExecutionStatusFilter.value = initialFilter;
  processDrawerVisible.value = true;
  void loadTaskProcess(row.taskId);
  void loadTaskProcessExecutions(row.taskId);
  startProcessPolling(row.taskId);
}

function openTaskResult(row: Api.Ticket.SaleTask) {
  openTaskProcess(row, 'total');
}

function resetSearch() {
  searchParams.value = createSearchParams();
  searchTaskId.value = '';
  checkedRowKeys.value = [];
  void getDataByPage();
}

function handleSearch() {
  const normalizedTaskId = searchTaskId.value.replace(/\D+/g, '').trim();
  searchTaskId.value = normalizedTaskId;
  searchParams.value.taskId = normalizedTaskId || null;
  searchParams.value.pageNum = 1;
  void getDataByPage();
}
</script>

<template>
  <div class="h-full flex-col-stretch gap-16px overflow-hidden">
    <NCard title="任务管理筛选" :bordered="false" size="small" class="card-wrapper">
      <NForm inline :label-width="80">
        <NFormItem label="平台">
          <NSelect
            v-model:value="searchParams.platformId"
            clearable
            filterable
            :options="platformOptions"
            placeholder="请选择平台"
            class="w-180px"
          />
        </NFormItem>
        <NFormItem label="抢购类型">
          <NSelect
            v-model:value="searchParams.purchaseType"
            clearable
            :options="purchaseTypeOptions"
            placeholder="请选择抢购类型"
            class="w-160px"
          />
        </NFormItem>
        <NFormItem label="任务ID">
          <NInput
            v-model:value="searchTaskId"
            clearable
            placeholder="请输入任务ID"
            class="w-180px"
            @keydown.enter.prevent="handleSearch"
          />
        </NFormItem>
        <NFormItem label="任务名称">
          <NInput v-model:value="searchParams.taskName" clearable placeholder="请输入任务名称" @keydown.enter.prevent="handleSearch" />
        </NFormItem>
        <NFormItem label="任务状态">
          <NSelect
            v-model:value="searchParams.taskStatus"
            clearable
            :options="taskStatusOptions"
            placeholder="请选择状态"
            class="w-160px"
          />
        </NFormItem>
        <NFormItem>
          <NSpace>
            <NButton type="primary" @click="handleSearch">查询</NButton>
            <NButton @click="resetSearch">重置</NButton>
          </NSpace>
        </NFormItem>
      </NForm>
    </NCard>

    <NCard title="任务管理" :bordered="false" size="small" class="card-wrapper sm:flex-1-hidden">
      <template #header-extra>
        <TableHeaderOperation
          v-model:columns="columnChecks"
          :disabled-delete="checkedRowKeys.length === 0"
          :loading="loading"
          :show-add="false"
          :show-delete="hasAuth('ticket:saleTask:remove')"
          @delete="handleDelete(checkedRowKeys)"
          @refresh="getData"
        >
          <template #prefix>
            <NButton v-if="hasAuth('ticket:saleTask:add')" size="small" ghost type="primary" @click="openAddFlashSale">
              <template #icon>
                <icon-material-symbols-add class="text-icon" />
              </template>
              新增抢票
            </NButton>
            <NButton v-if="hasAuth('ticket:saleTask:add')" size="small" ghost type="warning" @click="openAddLottery">
              <template #icon>
                <icon-material-symbols-add class="text-icon" />
              </template>
              新增抽票
            </NButton>
          </template>
        </TableHeaderOperation>
      </template>
      <NDataTable
        v-model:checked-row-keys="checkedRowKeys"
        :columns="columns"
        :data="data"
        size="small"
        remote
        :loading="loading"
        :flex-height="!appStore.isMobile"
        :scroll-x="scrollX"
        :row-key="row => row.taskId"
        :pagination="mobilePagination"
        class="sale-task-table sm:h-full"
      />
    </NCard>

    <NDrawer v-model:show="processDrawerVisible" :width="780" placement="right" :trap-focus="false">
      <NDrawerContent title="任务结果" closable>
        <NSpin :show="processLoading">
          <NEmpty v-if="!processData" description="正在加载任务结果" />
          <div v-else class="task-process">
            <div class="task-process__hero">
              <div class="min-w-0">
                <div class="task-process__title">{{ processTask?.taskName || '-' }}</div>
                <div class="task-process__meta">
                  <NTag size="small" :bordered="false">{{ processTask?.platformName || '-' }}</NTag>
                  <NTag size="small" :bordered="false" :type="getTicketTagType(processTask?.purchaseType)">
                    {{ getTicketLabel(processTask?.purchaseType) }}
                  </NTag>
                  <NTag size="small" :bordered="false" :type="getTicketTagType(processTask?.taskStatus)">
                    {{ getTicketLabel(processTask?.taskStatus) }}
                  </NTag>
                </div>
              </div>
              <NButton size="small" :loading="processLoading" @click="loadTaskProcess(processTask?.taskId)">
                刷新
              </NButton>
            </div>

            <div class="task-process__stats">
              <div class="task-process__stat-card">
                <span>账号数量</span>
                <strong>{{ processTask?.boundAccountCount || 0 }}</strong>
              </div>
              <div class="task-process__stat-card">
                <span>执行记录</span>
                <strong>{{ processData.executionSummary?.total || 0 }}</strong>
              </div>
              <div class="task-process__stat-card">
                <span>{{ processTask?.purchaseType === 'lottery' ? '抽票时段' : '抢票计划' }}</span>
                <strong>{{ processPlanCount }}</strong>
              </div>
              <div class="task-process__stat-card">
                <span>最近执行</span>
                <strong class="task-process__stat-text">{{ processTask?.lastExecutedTime || '未执行' }}</strong>
              </div>
            </div>

            <NCard title="执行安排" size="small" :bordered="false" class="task-process__card">
              <div v-if="processData.schedules?.length" class="task-process__schedule-grid">
                <div v-for="schedule in processData.schedules" :key="schedule.scheduleId" class="task-process__schedule-card">
                  <div>
                    <strong>{{ schedule.sessionLabel || schedule.sessionId || '抽票时段' }}</strong>
                    <div class="task-process__muted">{{ schedule.scheduledTime || '-' }}</div>
                  </div>
                  <div class="task-process__schedule-side">
                    <NTag size="small" :bordered="false" :type="getTicketTagType(schedule.scheduleStatus)">
                      {{ getTicketLabel(schedule.scheduleStatus || 'pending') }}
                    </NTag>
                    <span>{{ schedule.accountCount || 0 }} 个账号</span>
                  </div>
                </div>
              </div>
              <div v-else class="task-process__plan-card">
                <div>
                  <strong>抢票计划</strong>
                  <div class="task-process__muted">
                    {{ processTask?.scheduledTime || '保存后立即排队' }}
                  </div>
                </div>
                <NTag size="small" :bordered="false" :type="getTicketTagType(processTask?.taskStatus)">
                  {{ getTicketLabel(processTask?.taskStatus) }}
                </NTag>
              </div>
            </NCard>

            <NCard title="执行状态" size="small" :bordered="false" class="task-process__card">
              <div class="task-process__summary-grid">
                <button
                  v-for="item in processSummaryItems"
                  :key="item.key"
                  type="button"
                  class="task-process__summary-card"
                  :class="{ 'is-active': processExecutionStatusFilter === item.key }"
                  @click="setProcessExecutionStatusFilter(item.key)"
                >
                  <span>{{ item.label }}</span>
                  <strong>{{ item.value }}</strong>
                </button>
              </div>
            </NCard>

            <NCard title="执行明细" size="small" :bordered="false" class="task-process__card">
              <div class="task-process__execution-filter">
                <div>
                  <strong>{{ processActiveSummary?.label || '总数' }}</strong>
                  <span>{{ processExecutionTotal }} 个账号</span>
                </div>
                <NSpace :size="8">
                  <NPopconfirm
                    v-if="processTask?.purchaseType === 'lottery' && hasAuth('ticket:saleTask:execute')"
                    :disabled="retryableLotteryExecutionCount === 0 || retryFailedLotteryLoading"
                    @positive-click="handleRetryFailedLotteryTask"
                  >
                    <template #trigger>
                      <NButton
                        size="tiny"
                        type="warning"
                        ghost
                        :loading="retryFailedLotteryLoading"
                        :disabled="retryableLotteryExecutionCount === 0"
                      >
                        重新提交未成功账号
                      </NButton>
                    </template>
                    只会重提失败或阻塞的账号，心跳超时会作为失败原因展示，不会重提交已提交账号。
                  </NPopconfirm>
                  <NButton
                    v-if="processExecutionStatusFilter !== 'total'"
                    size="tiny"
                    quaternary
                    type="primary"
                    @click="setProcessExecutionStatusFilter('total')"
                  >
                    查看全部
                  </NButton>
                </NSpace>
              </div>
              <NSpin :show="processExecutionLoading">
                <NEmpty
                  v-if="!processFilteredExecutions.length"
                  :description="processExecutionTotal ? '当前页暂无账号' : '这个状态下暂无账号'"
                  class="task-process__empty"
                />
                <div v-else class="task-process__execution-list">
                  <div
                    v-for="execution in processFilteredExecutions"
                    :key="execution.executionId"
                    class="task-process__execution-card"
                    :class="`is-${execution.executionStatus || 'unknown'}`"
                  >
                    <div class="task-process__execution-main">
                      <div class="task-process__execution-account">
                        <strong :title="execution.email || '-'">{{ execution.email || '-' }}</strong>
                        <span>#{{ execution.executionId }}</span>
                      </div>
                      <div class="task-process__execution-tags">
                        <NTag size="small" :bordered="false" :type="getTicketTagType(execution.executionStatus)">
                          {{ getTicketLabel(execution.executionStatus) }}
                        </NTag>
                        <NTag
                          size="small"
                          :bordered="false"
                          :type="getTicketTagType(execution.currentStep || execution.stepStatus)"
                        >
                          {{ getTicketLabel(execution.currentStep || execution.stepStatus) }}
                        </NTag>
                        <NTag
                          v-if="execution.lotteryResultStatus"
                          size="small"
                          :bordered="false"
                          :type="getTicketTagType(execution.lotteryResultStatus)"
                        >
                          {{ getTicketLabel(execution.lotteryResultStatus) }}
                        </NTag>
                        <NTag
                          v-if="shouldRenderTicketPaymentStatus(execution)"
                          size="small"
                          :bordered="false"
                          :type="getTicketTagType(execution.paymentStatus)"
                        >
                          {{ getTicketLabel(execution.paymentStatus) }}
                        </NTag>
                      </div>
                    </div>

                    <div class="task-process__execution-message">
                      {{ getExecutionMessage(execution) }}
                    </div>

                    <div class="task-process__execution-footer">
                      <div class="task-process__execution-meta">
                        <span>{{ getExecutionTime(execution) }}</span>
                        <span v-if="execution.attemptCount">重试 {{ execution.attemptCount }}</span>
                        <span v-if="execution.workerId" :title="execution.workerId">worker {{ execution.workerId }}</span>
                      </div>
                      <div class="task-process__execution-actions">
                        <NButton
                          size="tiny"
                          quaternary
                          type="primary"
                          :disabled="!execution.stepTrace"
                          @click="openProcessJson('步骤轨迹', execution.stepTrace)"
                        >
                          轨迹
                        </NButton>
                        <NButton
                          size="tiny"
                          quaternary
                          type="primary"
                          @click="openProcessJson('执行详情', buildExecutionDetail(execution))"
                        >
                          详情
                        </NButton>
                      </div>
                    </div>
                  </div>
                </div>
                <div v-if="processExecutionTotal > processExecutionPagination.pageSize" class="task-process__pagination">
                  <NPagination
                    :page="processExecutionPagination.pageNum"
                    :page-size="processExecutionPagination.pageSize"
                    :item-count="processExecutionTotal"
                    :page-sizes="[30, 50, 100]"
                    show-size-picker
                    @update:page="handleProcessExecutionPageChange"
                    @update:page-size="handleProcessExecutionPageSizeChange"
                  />
                </div>
              </NSpin>
            </NCard>
          </div>
        </NSpin>
      </NDrawerContent>
    </NDrawer>

    <NModal v-model:show="processJsonVisible" preset="card" :title="processJsonTitle" class="w-720px">
      <NInput :value="processJsonContent" type="textarea" :rows="18" readonly />
    </NModal>

    <NModal
      v-model:show="modalVisible"
      preset="card"
      :title="modalTitle"
      :mask-closable="false"
      :style="{ width: 'min(960px, calc(100vw - 48px))' }"
      class="purchase-task-modal"
      :class="{ 'purchase-task-modal--shake': modalShaking }"
      @mask-click="handleModalMaskClick"
    >
      <NForm v-if="!usesGuidedTaskFlow" label-placement="top" :model="formModel">
        <NGrid :cols="24" :x-gap="16">
          <NFormItemGi :span="12" label="目标平台">
            <NSelect v-model:value="formModel.platformId" filterable :options="platformOptions" />
          </NFormItemGi>
          <NFormItemGi :span="12" label="任务名称">
            <NInput v-model:value="formModel.taskName" :maxlength="MAX_TASK_NAME_LENGTH" show-count />
          </NFormItemGi>
          <NFormItemGi :span="12" label="单账号数量">
            <NInputNumber v-model:value="formModel.purchaseQuantity" class="w-full" :min="1" />
          </NFormItemGi>
          <NFormItemGi :span="12" label="计划抢购时间">
            <NDatePicker
              v-model:formatted-value="formModel.scheduledTime"
              type="datetime"
              value-format="yyyy-MM-dd HH:mm:ss"
              clearable
              class="w-full"
              placeholder="不填则保存后自动立即抢购"
            />
            <template #feedback>
              系统会自动在计划时间前 10 分钟进入预热；如果保存时已进入预热窗口，会立即开始预热。
            </template>
          </NFormItemGi>
          <NFormItemGi :span="24" label="指定账号">
            <div class="account-picker w-full">
              <div class="account-picker__header">
                <div>
                  <div class="account-picker__title">已登录账号池</div>
                  <div class="account-picker__hint">
                    <span v-if="formModel.platformId">先筛选，再勾选需要执行任务的账号</span>
                    <span v-else>选择目标平台后，这里会自动加载可用账号</span>
                  </div>
                </div>
                <div class="account-picker__meta">
                  <div class="account-picker__meta-item">
                    <span class="account-picker__meta-label">已加载</span>
                    <strong>{{ accountLoadedCountText }}</strong>
                  </div>
                  <div class="account-picker__meta-item account-picker__meta-item--active">
                    <span class="account-picker__meta-label">已选择</span>
                    <strong>{{ selectedAccountCount }}</strong>
                  </div>
                </div>
              </div>
              <div class="account-picker__toolbar">
                <NInput
                  v-model:value="accountSearchKeyword"
                  clearable
                  placeholder="搜索邮箱 / 来源号码 / 账号ID"
                  class="account-picker__search"
                  :disabled="!formModel.platformId"
                />
                <NSpace :wrap="false">
                  <NButton
                    size="small"
                    type="primary"
                    ghost
                    :loading="accountOptionsLoading"
                    :disabled="isSelectAllAccountsDisabled"
                    @click="handleSelectAllAccounts"
                  >
                    全选可用
                  </NButton>
                  <NButton size="small" quaternary :disabled="!selectedAccountCount" @click="handleClearSelectedAccounts">
                    清空
                  </NButton>
                </NSpace>
              </div>
              <div v-if="selectedAccountPreview.length" class="account-picker__summary">
                <span class="account-picker__summary-label">当前已选</span>
                <NTag
                  v-for="item in selectedAccountPreview"
                  :key="item.accountId"
                  size="small"
                  round
                  type="success"
                  :bordered="false"
                >
                  {{ item.email }}
                </NTag>
                <span v-if="selectedAccountCount > selectedAccountPreview.length" class="account-picker__summary-more">
                  +{{ selectedAccountCount - selectedAccountPreview.length }}
                </span>
              </div>
              <div v-if="!formModel.platformId" class="account-picker__empty">
                <div class="account-picker__empty-title">还没有加载账号</div>
                <div class="account-picker__empty-text">先选择目标平台，再从已登录账号里勾选参与任务的账号。</div>
              </div>
              <div v-else class="account-picker__table">
                <NDataTable
                  v-model:checked-row-keys="checkedAccountRowKeys"
                  :columns="accountTableColumns"
                  :data="filteredAccountTableData"
                  size="small"
                  max-height="300"
                  :bordered="false"
                  :loading="accountOptionsLoading"
                  :row-key="row => row.accountId"
                  :single-line="false"
                />
              </div>
            </div>
          </NFormItemGi>
          <NFormItemGi :span="24" label="平台预设配置">
            <div class="flex-col gap-8px">
              <div class="text-12px text-text-3">
                {{ templateLoading ? '正在加载平台预设配置...' : `当前配置方案：${formModel.configSchemaKey || '未生成'}` }}
              </div>
              <NInput
                v-model:value="formModel.taskOptions"
                type="textarea"
                :rows="8"
                placeholder="例如：{&quot;ticketsPageUrl&quot;:&quot;https://livepocket.jp/e/.../tickets&quot;,&quot;ticketQuantity&quot;:1}"
              />
            </div>
          </NFormItemGi>
          <NFormItemGi :span="24" label="备注">
            <NInput v-model:value="formModel.remark" type="textarea" :rows="3" />
          </NFormItemGi>
        </NGrid>
      </NForm>

      <div v-else class="lottery-wizard">
        <NSteps :current="lotteryStep" size="small" class="lottery-wizard__steps">
          <NStep
            v-for="(item, index) in lotteryStepItems"
            :key="`${item.title}-${index}`"
            :title="item.title"
          />
        </NSteps>
        <div class="lottery-wizard__steps-hint">
          <template v-if="isJumpShopTask">
            解析商品后再选择账号、结算资料，并设置数量和执行时间。
          </template>
          <template v-else-if="usesLivePocketFlashSaleQuickPath">
            {{
              usesDirectLivePocketTicketsUrl
                ? 'tickets 链接会直接进入账号选择，不需要解析活动。'
                : '活动页链接会在执行时自动发现第一个可购 tickets 链接。'
            }}
          </template>
          <template v-else-if="lotteryUsesCollectionFlow">
            集合页先选具体活动，再解析该活动的{{ isLotteryTask ? '抽選场次' : '可售票种' }}。
          </template>
          <template v-else>
            {{ isLotteryTask ? '解析完成后再选择账号、参与场次和执行时段。' : '解析完成后再选择账号、票种，并设置数量和开抢时间。' }}
          </template>
        </div>

        <NForm label-placement="top" :model="formModel" class="lottery-wizard__body">
          <div v-show="lotteryStep === 1" class="lottery-wizard__panel">
            <NGrid :cols="24" :x-gap="16">
              <NFormItemGi :span="12" label="目标平台">
                <NSelect v-model:value="formModel.platformId" filterable :options="platformOptions" />
              </NFormItemGi>
              <NFormItemGi :span="12" label="任务名称">
                <NInput
                  v-model:value="formModel.taskName"
                  :maxlength="MAX_TASK_NAME_LENGTH"
                  show-count
                  placeholder="解析活动后可自动回填"
                />
              </NFormItemGi>
              <NFormItemGi :span="24" :label="lotteryLinkLabel">
                <NInput
                  v-model:value="lotteryEventInputUrl"
                  clearable
                  :placeholder="
                    isJumpShopTask
                      ? '例如：https://jumpshop-benelic.com/collections/all/products/18068391'
                      : '例如：https://event.hands.net/.../segment/...、https://livepocket.jp/e/... 或 https://livepocket.jp/e/.../receptions/.../tickets'
                  "
                />
                <template #feedback>
                  {{
                    isJumpShopTask
                      ? '公开商品页无需登录，下一步会触发 Python 自动读取 Jump Shop 商品信息和 Shopify 参数。'
                      : isLotteryTask
                        ? '公开抽票页面无需登录，下一步会触发 Python 自动读取活动信息和场次。'
                        : usesLivePocketFlashSaleQuickPath
                          ? usesDirectLivePocketTicketsUrl
                            ? 'tickets 链接会直接进入账号选择，不需要解析活动。'
                            : '活动页链接会在执行时自动发现第一个可购 tickets 链接。'
                          : '公开售票页面无需登录，下一步会触发 Python 自动读取活动信息和票种。'
                  }}
                </template>
              </NFormItemGi>
              <NFormItemGi v-if="lotteryParseHistoryOptions.length" :span="24" label="历史解析记录">
                <NSelect
                  v-model:value="selectedLotteryHistoryUrl"
                  clearable
                  filterable
                  :options="lotteryParseHistoryOptions"
                  placeholder="选择之前解析过的活动，直接回填信息"
                  @update:value="handleSelectLotteryParseHistory"
                />
                <template #feedback>
                  {{ isLotteryTask ? '选择历史记录会直接带出活动标题、受付时间和场次，不会触发 Python 重新解析。' : '选择历史记录会直接带出活动标题、受付时间和票种，不会触发 Python 重新解析。' }}
                </template>
              </NFormItemGi>
            </NGrid>
          </div>

          <div
            v-show="!lotteryUsesCollectionFlow && !usesLivePocketFlashSaleQuickPath && lotteryStep === 2"
            class="lottery-wizard__panel"
          >
            <div class="lottery-event-parser w-full">
              <div v-if="!isJumpShopTask && lotteryParseHistoryOptions.length" class="lottery-event-parser__history">
                <NSelect
                  v-model:value="selectedLotteryHistoryUrl"
                  clearable
                  filterable
                  :options="lotteryParseHistoryOptions"
                  placeholder="从历史解析记录中选择"
                  @update:value="handleSelectLotteryParseHistory"
                />
              </div>
              <div class="lottery-event-parser__input">
                <NInput v-model:value="lotteryEventInputUrl" clearable />
                <NButton
                  type="primary"
                  :loading="isJumpShopTask ? jumpShopProductParsing : lotteryEventParsing"
                  @click="isJumpShopTask ? handleParseJumpShopProduct() : handleParseLotteryEvent()"
                >
                  {{ isJumpShopTask ? '解析商品' : '解析活动' }}
                </NButton>
              </div>
              <div v-if="isJumpShopTask && jumpShopProductInfo" class="lottery-event-parser__result">
                <div class="lottery-event-parser__title">{{ jumpShopProductInfo.title || '未识别商品标题' }}</div>
                <div class="lottery-event-parser__meta">
                  Variant {{ jumpShopProductInfo.variantId }} / Product {{ jumpShopProductInfo.productId }} / Section
                  {{ jumpShopProductInfo.sectionId }}
                  <NTag size="small" :bordered="false" :type="jumpShopProductInfo.available ? 'success' : 'error'">
                    {{ jumpShopProductInfo.available ? '可购买' : '不可购买' }}
                  </NTag>
                </div>
                <div class="lottery-session-picker__hint">
                  当前页面解析到的数量上限参考值：{{ jumpShopProductInfo.maxQuantity }}，任务默认数量会按 1-10 规则回填。
                </div>
                <div v-if="jumpShopProductInfo.imageUrl" class="mt-12px">
                  <img :src="jumpShopProductInfo.imageUrl" alt="jump-shop-product" class="h-120px w-auto rounded-12px object-cover" />
                </div>
              </div>
              <div v-if="lotteryEventInfo" class="lottery-event-parser__result">
                <div class="lottery-event-parser__title">{{ lotteryEventInfo.eventTitle || '未识别活动标题' }}</div>
                <div class="lottery-event-parser__meta">
                  <template v-if="isLotteryCollectionResult">
                    已解析到 {{ lotteryEventInfo.sessions?.length || 0 }} 个活动，请选择一个具体活动继续。
                  </template>
                  <template v-else>受付时间：{{ lotteryEventInfo.entryStartTime || '-' }} ~ {{ lotteryEventInfo.entryEndTime || '-' }}</template>
                  <NTag v-if="lotteryEventInfo.cacheHit" size="small" type="info" :bordered="false">缓存</NTag>
                  <NTag v-if="lotteryEventInfo.parseStatus === 'queued'" size="small" type="warning" :bordered="false">排队中</NTag>
                  <NTag v-if="lotteryEventInfo.parseStatus === 'running'" size="small" type="info" :bordered="false">解析中</NTag>
                  <NTag v-if="lotteryEventInfo.parseStatus === 'failed'" size="small" type="error" :bordered="false">
                    {{ lotteryEventInfo.parseMessage || '解析失败' }}
                  </NTag>
                </div>
                <NDataTable
                  :columns="isLotteryCollectionResult ? lotteryCollectionEventColumns : lotteryEventSessionColumns"
                  :data="lotteryEventInfo.sessions || []"
                  size="small"
                  :bordered="false"
                  :pagination="false"
                  :scroll-x="isLotteryCollectionResult ? lotteryCollectionTableScrollX : lotterySessionTableScrollX"
                  :max-height="isLotteryCollectionResult ? 340 : 300"
                  :row-props="isLotteryCollectionResult ? getLotteryCollectionRowProps : undefined"
                  class="lottery-event-parser__table mt-10px"
                />
              </div>
              <div v-else-if="!isJumpShopTask" class="lottery-event-parser__empty">
                点击“解析活动”后，这里会展示活动标题、受付时间和{{ guidedSessionLabel }}。
              </div>
              <div v-else class="lottery-event-parser__empty">点击“解析商品”后，这里会展示 Jump Shop 商品标题、Shopify 参数和可售状态。</div>
            </div>
          </div>

          <div v-show="lotteryUsesCollectionFlow && lotteryStep === lotteryCollectionStepIndex" class="lottery-wizard__panel">
            <div class="lottery-event-parser w-full">
              <div v-if="lotteryParseHistoryOptions.length" class="lottery-event-parser__history">
                <NSelect
                  v-model:value="selectedLotteryHistoryUrl"
                  clearable
                  filterable
                  :options="lotteryParseHistoryOptions"
                  placeholder="从历史解析记录中选择"
                  @update:value="handleSelectLotteryParseHistory"
                />
              </div>
              <div class="lottery-event-parser__input">
                <NInput v-model:value="lotteryEventInputUrl" clearable />
                <NButton type="primary" :loading="lotteryEventParsing" @click="handleParseLotteryEvent">
                  解析活动
                </NButton>
              </div>
              <div v-if="lotteryCollectionDisplayInfo" class="lottery-event-parser__result">
                <div class="lottery-event-parser__title">{{ lotteryCollectionDisplayInfo.eventTitle || '未识别活动标题' }}</div>
                <div class="lottery-event-parser__meta">
                  已解析到 {{ lotteryCollectionDisplayInfo.sessions?.length || 0 }} 个活动，请先选择一个具体活动。
                  <NTag v-if="lotteryCollectionDisplayInfo.cacheHit" size="small" type="info" :bordered="false">缓存</NTag>
                  <NTag v-if="lotteryEventParsing" size="small" type="info" :bordered="false">解析中</NTag>
                </div>
                <NDataTable
                  :columns="lotteryCollectionEventColumns"
                  :data="lotteryCollectionDisplayInfo.sessions || []"
                  size="small"
                  :bordered="false"
                  :pagination="false"
                  :scroll-x="lotteryCollectionTableScrollX"
                  :max-height="320"
                  :row-props="getLotteryCollectionRowProps"
                  class="lottery-event-parser__table mt-10px"
                />
              </div>
              <div v-else class="lottery-event-parser__empty">点击“解析活动”后，这里会展示活动集合里的具体活动。</div>
            </div>
          </div>

          <div v-show="lotteryUsesCollectionFlow && lotteryStep === lotteryParseStepIndex" class="lottery-wizard__panel">
            <div class="lottery-event-parser w-full">
              <div class="lottery-event-parser__selection">
                <div class="lottery-event-parser__selection-main">
                  <div class="lottery-event-parser__selection-label">当前活动</div>
                  <div
                    class="lottery-event-parser__selection-value"
                    :title="selectedLotteryCollectionEventUrl || formModel.lotteryEventUrl || undefined"
                  >
                    {{ selectedLotteryCollectionEventUrl || formModel.lotteryEventUrl || '-' }}
                  </div>
                </div>
                <NSpace>
                  <NButton ghost @click="handleBackToLotteryCollection">返回活动列表</NButton>
                  <NButton type="primary" ghost :loading="lotteryEventParsing" @click="handleReparseSelectedLotteryEvent">
                    {{ isLotteryTask ? '解析场次' : '解析票种' }}
                  </NButton>
                </NSpace>
              </div>
              <div v-if="lotteryEventInfo" class="lottery-event-parser__result">
                <div class="lottery-event-parser__title">{{ lotteryEventInfo.eventTitle || '未识别活动标题' }}</div>
                <div class="lottery-event-parser__meta">
                  受付时间：{{ lotteryEventInfo.entryStartTime || '-' }} ~ {{ lotteryEventInfo.entryEndTime || '-' }}
                  <NTag v-if="lotteryEventInfo.cacheHit" size="small" type="info" :bordered="false">缓存</NTag>
                  <NTag v-if="lotteryEventInfo.parseStatus === 'queued'" size="small" type="warning" :bordered="false">排队中</NTag>
                  <NTag v-if="lotteryEventInfo.parseStatus === 'running'" size="small" type="info" :bordered="false">解析中</NTag>
                  <NTag v-if="lotteryEventInfo.parseStatus === 'failed'" size="small" type="error" :bordered="false">
                    {{ lotteryEventInfo.parseMessage || '解析失败' }}
                  </NTag>
                </div>
                <NDataTable
                  :columns="lotteryEventSessionColumns"
                  :data="lotteryEventInfo.sessions || []"
                  size="small"
                  :bordered="false"
                  :pagination="false"
                  :scroll-x="lotterySessionTableScrollX"
                  :max-height="300"
                  class="lottery-event-parser__table mt-10px"
                />
              </div>
              <div v-else class="lottery-event-parser__empty">
                选择活动后，这里会展示真实{{ isLotteryTask ? '抽選票卡和受付时间' : '可售票卡和受付时间' }}。
              </div>
            </div>
          </div>

          <div v-show="!isJumpShopTask && lotteryStep === lotteryReceptionStepIndex" class="lottery-wizard__panel">
            <div class="lottery-reception-picker">
              <div class="lottery-session-picker__header">
                <div>
                  <div class="lottery-session-picker__title">选择受付</div>
                  <div class="lottery-session-picker__hint">
                    {{ isLotteryTask ? '一个抽票任务只能选择同一个受付下的场次。' : '一个普通抢票任务只能选择同一个受付下的一个票种。' }}
                  </div>
                </div>
                <NTag type="info" :bordered="false">共 {{ lotteryReceptionGroups.length }} 个受付</NTag>
              </div>
              <div v-if="!lotteryReceptionGroups.length" class="lottery-scheduler__empty">
                请先解析活动{{ isLotteryTask ? '场次' : '票种' }}。
              </div>
              <div v-else class="lottery-reception-picker__list">
                <button
                  v-for="group in lotteryReceptionGroups"
                  :key="group.key"
                  type="button"
                  class="lottery-reception-picker__item"
                  :class="{ 'is-active': selectedLotteryReceptionKey === group.key }"
                  @click="handleSelectLotteryReception(group.key)"
                >
                  <div class="lottery-reception-picker__item-title">{{ group.label }}</div>
                  <div class="lottery-reception-picker__item-meta">
                    <span>{{ group.sessionCount }} 个{{ guidedSessionLabel }}</span>
                    <span>{{ group.sessions[0]?.sessionLabel || '-' }}</span>
                  </div>
                </button>
              </div>
            </div>
          </div>

          <div v-show="lotteryStep === lotteryAccountStepIndex" class="lottery-wizard__panel">
            <div class="account-picker w-full">
              <div class="account-picker__header">
                <div>
                  <div class="account-picker__title">选择参与{{ guidedTaskLabel }}的账号</div>
                  <div class="account-picker__hint">
                    {{
                      isJumpShopTask
                        ? '默认加载 Jump Shop 下已注册账号；执行时会优先复用登录上下文，没有或失效时再现场登录。'
                        : isLotteryTask
                          ? '只加载目标平台下已激活账号；同链接已提交账号会自动标记并禁选。'
                          : '只加载目标平台下已激活账号；普通抢票可复用登录上下文，也可现场用平台密码登录。'
                    }}
                  </div>
                </div>
                <div class="account-picker__meta">
                  <div class="account-picker__meta-item">
                    <span class="account-picker__meta-label">已加载</span>
                    <strong>{{ accountLoadedCountText }}</strong>
                  </div>
                  <div class="account-picker__meta-item account-picker__meta-item--active">
                    <span class="account-picker__meta-label">已选择</span>
                    <strong>{{ selectedAccountCount }}</strong>
                  </div>
                </div>
              </div>
              <div class="account-picker__toolbar">
                <NInput
                  v-model:value="accountSearchKeyword"
                  clearable
                  placeholder="搜索邮箱 / 来源号码 / 账号ID"
                  class="account-picker__search"
                />
                <NSpace :wrap="false">
                  <NButton
                    size="small"
                    type="primary"
                    ghost
                    :loading="accountOptionsLoading"
                    :disabled="isSelectAllAccountsDisabled"
                    @click="handleSelectAllAccounts"
                  >
                    全选可用
                  </NButton>
                  <NButton size="small" quaternary :disabled="!selectedAccountCount" @click="handleClearSelectedAccounts">
                    清空
                  </NButton>
                </NSpace>
              </div>
              <div v-if="selectedAccountPreview.length" class="account-picker__summary">
                <span class="account-picker__summary-label">当前已选</span>
                <NTag
                  v-for="item in selectedAccountPreview"
                  :key="item.accountId"
                  size="small"
                  round
                  type="success"
                  :bordered="false"
                >
                  {{ item.email }}
                </NTag>
                <span v-if="selectedAccountCount > selectedAccountPreview.length" class="account-picker__summary-more">
                  +{{ selectedAccountCount - selectedAccountPreview.length }}
                </span>
              </div>
              <div class="account-picker__table">
                <NDataTable
                  v-model:checked-row-keys="checkedAccountRowKeys"
                  :columns="accountTableColumns"
                  :data="filteredAccountTableData"
                  size="small"
                  :max-height="400"
                  :bordered="false"
                  :loading="accountOptionsLoading"
                  :row-key="row => row.accountId"
                  :single-line="false"
                />
              </div>
            </div>
          </div>

          <div v-show="!isJumpShopTask && lotteryStep === lotterySessionStepIndex" class="lottery-wizard__panel">
            <div class="lottery-session-picker">
              <div class="lottery-session-picker__header">
                <div>
                  <div class="lottery-session-picker__title">选择{{ guidedSessionLabel }}</div>
                  <div class="lottery-session-picker__hint">
                    {{
                      selectedLotteryReceptionGroup?.label
                        || (isLotteryTask ? '抽票任务只允许选择当前受付下的抽選场次。' : '普通抢票只允许选择当前受付下的一个非抽選票种。')
                    }}
                  </div>
                </div>
                <NTag type="info" :bordered="false">已选 {{ formModel.selectedSessions?.length || 0 }} {{ guidedSessionLabel }}</NTag>
              </div>
              <NDataTable
                v-model:checked-row-keys="checkedLotterySessionRowKeys"
                :columns="lotterySelectableSessionColumns"
                :data="currentLotteryReceptionSessions"
                size="small"
                :bordered="false"
                :pagination="false"
                :scroll-x="lotterySessionTableScrollX"
                :row-key="row => row.sessionId"
              />
            </div>
          </div>

          <div v-show="isJumpShopTask && lotteryStep === lotterySessionStepIndex" class="lottery-wizard__panel">
            <div class="lottery-session-picker">
              <div class="lottery-session-picker__header">
                <div>
                  <div class="lottery-session-picker__title">选择 Jump Shop 结算资料</div>
                  <div class="lottery-session-picker__hint">下单时会使用这里的联系人、收件地址和信用卡信息。</div>
                </div>
                <NTag type="info" :bordered="false">共 {{ jumpShopProfileOptions.length }} 份</NTag>
              </div>
              <div v-if="!jumpShopProfileOptions.length" class="lottery-scheduler__empty">
                还没有可用的 Jump Shop 资料，请先到“Jump Shop 资料”页面创建并启用一份结算资料。
              </div>
              <NRadioGroup v-else v-model:value="selectedJumpShopProfileId" class="w-full">
                <NSpace vertical size="large" class="w-full">
                  <label
                    v-for="profile in jumpShopProfileOptions"
                    :key="profile.profileId"
                    class="lottery-reception-picker__item"
                    :class="{ 'is-active': selectedJumpShopProfileId === profile.profileId }"
                  >
                    <div class="flex items-start justify-between gap-12px">
                      <div class="flex-1">
                        <div class="lottery-reception-picker__item-title">{{ profile.profileName }}</div>
                        <div class="lottery-reception-picker__item-meta">
                          <span>{{ [profile.lastName, profile.firstName].filter(Boolean).join('') || '-' }}</span>
                          <span>{{ profile.phone || '-' }}</span>
                        </div>
                        <div class="lottery-reception-picker__item-meta">
                          <span>{{ [profile.countryCode, profile.province, profile.city, profile.address1, profile.address2].filter(Boolean).join(' ') }}</span>
                        </div>
                        <div class="lottery-reception-picker__item-meta">
                          <span>{{ profile.cardHolderName || '-' }}</span>
                          <span>{{ profile.cardNumberMasked || '-' }}</span>
                        </div>
                      </div>
                      <NRadio :value="profile.profileId" />
                    </div>
                  </label>
                </NSpace>
              </NRadioGroup>
            </div>
          </div>

          <div v-show="lotteryStep === lotteryScheduleStepIndex" class="lottery-wizard__panel">
            <div v-if="isLotteryTask" class="lottery-scheduler w-full">
              <div v-if="isLivePocketLotteryTask" class="questionnaire-builder">
                <div class="questionnaire-builder__header">
                  <div>
                    <div class="questionnaire-builder__title">确认页问卷</div>
                    <div class="questionnaire-builder__hint">
                      用 1 个已登录账号先走到 LivePocket 确认页，只读取真实问卷结构，不提交最终抽票。
                    </div>
                  </div>
                  <NTag v-if="hasQuestionnairePreviewConfig" type="success" :bordered="false">
                    已读取 {{ questionnairePreviewConfig?.questions?.length || 0 }} 题
                  </NTag>
                  <NTag v-else type="default" :bordered="false">未读取</NTag>
                </div>

                <div class="questionnaire-builder__toolbar">
                  <NSelect
                    :value="questionnairePreviewAccountId"
                    :options="questionnairePreviewAccountOptions"
                    placeholder="选择预读账号"
                    class="questionnaire-builder__selector"
                    @update:value="handleQuestionnairePreviewAccountChange"
                  />
                  <NSelect
                    :value="questionnairePreviewSessionId"
                    :options="questionnairePreviewSessionOptions"
                    placeholder="选择预读场次"
                    class="questionnaire-builder__selector"
                    @update:value="handleQuestionnairePreviewSessionChange"
                  />
                  <NButton
                    type="primary"
                    ghost
                    :loading="questionnairePreviewLoading"
                    :disabled="!questionnairePreviewAccountOptions.length || !questionnairePreviewSessionOptions.length"
                    @click="handlePreviewLivePocketQuestionnaire"
                  >
                    读取问卷
                  </NButton>
                </div>

                <div v-if="(formModel.selectedSessions?.length || 0) > 1" class="questionnaire-builder__tips">
                  多场次任务共用一套问卷答案；如果不同场次的确认页题目不同，执行时会严格失败并提示重新读取。
                </div>
                <div v-if="questionnaireInvalidationReason" class="questionnaire-builder__warning">
                  {{ questionnaireInvalidationReason }}
                </div>

                <div v-if="hasQuestionnairePreviewConfig" class="questionnaire-builder__list">
                  <div
                    v-for="question in questionnairePreviewConfig?.questions || []"
                    :key="question.name"
                    class="questionnaire-builder__item"
                  >
                    <div class="questionnaire-builder__item-head">
                      <div class="questionnaire-builder__item-title">{{ question.label || question.name }}</div>
                      <div class="questionnaire-builder__item-meta">
                        <NTag size="small" type="info" :bordered="false">{{ question.type }}</NTag>
                        <NTag v-if="question.required" size="small" type="warning" :bordered="false">必填</NTag>
                      </div>
                    </div>

                    <NInput
                      v-if="question.type === 'text'"
                      :value="getQuestionnaireTextAnswer(question.name)"
                      placeholder="请输入答案"
                      @update:value="value => updateQuestionnaireAnswer(question.name, value || '')"
                    />
                    <NInput
                      v-else-if="question.type === 'textarea'"
                      type="textarea"
                      :value="getQuestionnaireTextAnswer(question.name)"
                      :autosize="{ minRows: 2, maxRows: 5 }"
                      placeholder="请输入答案"
                      @update:value="value => updateQuestionnaireAnswer(question.name, value || '')"
                    />
                    <NSelect
                      v-else-if="question.type === 'select'"
                      :value="getQuestionnaireTextAnswer(question.name)"
                      :options="question.options.map(option => ({ label: option.label, value: option.value }))"
                      placeholder="请选择"
                      @update:value="value => updateQuestionnaireAnswer(question.name, String(value || ''))"
                    />
                    <NRadioGroup
                      v-else-if="question.type === 'radio'"
                      :value="getQuestionnaireTextAnswer(question.name)"
                      @update:value="value => updateQuestionnaireAnswer(question.name, String(value || ''))"
                    >
                      <NSpace vertical size="small">
                        <NRadio v-for="option in question.options" :key="option.value" :value="option.value">
                          {{ option.label }}
                        </NRadio>
                      </NSpace>
                    </NRadioGroup>
                    <NCheckboxGroup
                      v-else-if="question.type === 'checkbox'"
                      :value="getQuestionnaireMultiAnswer(question.name)"
                      @update:value="value => updateQuestionnaireAnswer(question.name, (value || []).map(item => String(item)))"
                    >
                      <NSpace vertical size="small">
                        <NCheckbox v-for="option in question.options" :key="option.value" :value="option.value">
                          {{ option.label }}
                        </NCheckbox>
                      </NSpace>
                    </NCheckboxGroup>
                    <div v-else class="questionnaire-builder__unsupported">
                      当前题型 {{ question.type }} 暂未识别，请重新读取确认页或直接检查平台页面结构。
                    </div>

                    <div class="questionnaire-builder__field">{{ question.name }}</div>
                  </div>
                </div>
              </div>

              <div class="lottery-scheduler__header">
                <div>
                  <div class="lottery-scheduler__title">按时间分批执行</div>
                  <div class="lottery-scheduler__hint">例如 17:00 抽 10 个账号，19:00 抽 15 个账号；同一任务内账号不会重复使用。</div>
                </div>
                <NSpace>
                  <NButton size="small" type="primary" ghost @click="autoAssignLotterySchedules">自动分配</NButton>
                  <NButton size="small" type="primary" ghost @click="addLotterySchedule">新增时段</NButton>
                </NSpace>
              </div>
              <div v-if="!formModel.lotterySchedules?.length" class="lottery-scheduler__empty">
                还没有配置时段，点击“新增时段”开始安排抽票批次。
              </div>
              <div v-else class="lottery-scheduler__list">
                <div v-for="(schedule, index) in formModel.lotterySchedules" :key="index" class="lottery-scheduler__row">
                  <div class="lottery-scheduler__index">{{ index + 1 }}</div>
                  <NSelect
                    v-model:value="schedule.sessionId"
                    :options="lotterySessionOptions"
                    class="lottery-scheduler__session"
                    placeholder="选择场次"
                    @update:value="
                      value => {
                        schedule.sessionLabel = lotterySessionOptions.find(item => item.value === value)?.label;
                      }
                    "
                  />
                  <NDatePicker
                    v-model:formatted-value="schedule.scheduledTime"
                    type="datetime"
                    value-format="yyyy-MM-dd HH:mm:ss"
                    clearable
                    class="lottery-scheduler__time"
                    placeholder="选择执行时间"
                  />
                  <NInputNumber
                    v-model:value="schedule.accountCount"
                    :min="1"
                    class="lottery-scheduler__count"
                    placeholder="账号数量"
                  />
                  <NButton text type="error" @click="removeLotterySchedule(index)">移除</NButton>
                </div>
              </div>
              <div class="lottery-scheduler__footer">
                <span>已安排 {{ lotteryScheduleAccountTotal }} 个账号</span>
                <span>已选择 {{ selectedAccountCount }} 个账号</span>
              </div>
            </div>
            <div v-else class="lottery-scheduler w-full">
              <div class="lottery-scheduler__header">
                <div>
                  <div class="lottery-scheduler__title">{{ isJumpShopTask ? '设置数量与执行时间' : '设置开抢时间' }}</div>
                  <div class="lottery-scheduler__hint">
                    {{
                      isJumpShopTask
                        ? 'Jump Shop 会先加购，再跳转 Shopify checkout 并填写资料页中的信用卡信息；命中 3DS 会直接判失败。'
                        : '普通抢票只允许一个票种，系统执行时会自动取当前最大可选数量并提交，并固定使用 Lawson 便利店支付。'
                    }}
                  </div>
                </div>
              </div>
              <NGrid :cols="24" :x-gap="16">
                <NFormItemGi
                  :span="24"
                  :label="
                    isJumpShopTask
                      ? '当前商品'
                      : usesLivePocketFlashSaleQuickPath
                        ? usesDirectLivePocketTicketsUrl
                          ? '当前购票页'
                          : '当前活动页'
                        : '当前票种'
                  "
                >
                  <div class="lottery-session-picker__hint">
                    {{
                      isJumpShopTask
                        ? jumpShopProductInfo?.title || formModel.taskName || '-'
                        : usesLivePocketFlashSaleQuickPath
                          ? directLivePocketTicketsUrl || livePocketFlashSaleEventUrl || formModel.lotteryEventUrl || '-'
                          : formModel.selectedSessions?.[0]?.sessionLabel || formModel.selectedSessions?.[0]?.sessionId || '-'
                    }}
                  </div>
                </NFormItemGi>
                <NFormItemGi v-if="isJumpShopTask" :span="12" label="购买数量">
                  <NInputNumber v-model:value="formModel.purchaseQuantity" :min="1" :max="10" class="w-full" />
                  <template #feedback>Jump Shop v1 限制 1-10，默认按商品解析结果回填。</template>
                </NFormItemGi>
                <NFormItemGi v-if="isJumpShopTask" :span="12" label="结算资料">
                  <div class="lottery-session-picker__hint">
                    {{ selectedJumpShopProfile?.profileName || '请先回到上一步选择结算资料' }}
                  </div>
                </NFormItemGi>
                <NFormItemGi v-else :span="24" label="数量策略">
                  <div class="lottery-session-picker__hint">系统执行时会自动读取 LivePocket tickets 页当前最大可选数量并提交。</div>
                </NFormItemGi>
                <NFormItemGi :span="24" label="开抢时间">
                  <NDatePicker
                    v-model:formatted-value="formModel.scheduledTime"
                    type="datetime"
                    value-format="yyyy-MM-dd HH:mm:ss"
                    clearable
                    class="w-full"
                    placeholder="不填则保存后立即执行"
                  />
                  <template #feedback>{{ isJumpShopTask ? '不填则保存后立即进入 Jump Shop Python 下单队列。' : '不填则保存后立即进入 Python 抢票队列。' }}</template>
                </NFormItemGi>
              </NGrid>
            </div>
          </div>
        </NForm>
      </div>

      <template #footer>
        <div class="flex justify-end gap-12px">
          <NButton @click="modalVisible = false">取消</NButton>
          <template v-if="usesGuidedTaskFlow">
            <NButton :disabled="lotteryStep <= 1" @click="previousLotteryStep">上一步</NButton>
            <NButton v-if="lotteryStep < lotteryMaxStep" type="primary" :disabled="isLotteryNextDisabled" @click="nextLotteryStep">
              下一步
            </NButton>
            <NButton v-else type="primary" @click="handleSubmit">保存{{ guidedTaskLabel }}任务</NButton>
          </template>
          <NButton v-else type="primary" @click="handleSubmit">保存抢票任务</NButton>
        </div>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.purchase-task-modal :deep(.n-card) {
  border-radius: 20px;
  overflow: hidden;
}

.purchase-task-modal :deep(.n-card-header) {
  padding: 22px 24px 0;
}

.purchase-task-modal :deep(.n-card-header__main) {
  color: #172033;
  font-size: 16px;
  font-weight: 600;
}

.purchase-task-modal :deep(.n-card__content) {
  max-height: calc(100vh - 170px);
  overflow-y: auto;
  padding: 14px 22px 0;
}

.purchase-task-modal :deep(.n-card__footer) {
  padding: 14px 22px 18px;
}

.purchase-task-modal--shake :deep(.n-card) {
  animation: purchase-task-modal-shake 0.34s ease-out;
}

@keyframes purchase-task-modal-shake {
  0% {
    transform: translateX(0);
  }

  18% {
    transform: translateX(-8px);
  }

  36% {
    transform: translateX(7px);
  }

  54% {
    transform: translateX(-5px);
  }

  72% {
    transform: translateX(3px);
  }

  100% {
    transform: translateX(0);
  }
}

.questionnaire-builder {
  margin-bottom: 18px;
  border: 1px solid #d9e6ff;
  border-radius: 16px;
  background: linear-gradient(180deg, #f8fbff 0%, #fdfefe 100%);
  padding: 18px;
}

.questionnaire-builder__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.questionnaire-builder__title {
  color: #172033;
  font-size: 15px;
  font-weight: 600;
}

.questionnaire-builder__hint {
  margin-top: 6px;
  color: #5f6b85;
  font-size: 12px;
  line-height: 1.6;
}

.questionnaire-builder__toolbar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto;
  gap: 12px;
  margin-top: 14px;
}

.questionnaire-builder__selector {
  min-width: 0;
}

.questionnaire-builder__tips,
.questionnaire-builder__warning {
  margin-top: 12px;
  border-radius: 12px;
  padding: 10px 12px;
  font-size: 12px;
  line-height: 1.6;
}

.questionnaire-builder__tips {
  background: #eef5ff;
  color: #46618f;
}

.questionnaire-builder__warning {
  background: #fff7e8;
  color: #9a6a17;
}

.questionnaire-builder__list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 14px;
}

.questionnaire-builder__item {
  border: 1px solid #e7edf8;
  border-radius: 14px;
  background: #fff;
  padding: 14px;
}

.questionnaire-builder__item-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.questionnaire-builder__item-title {
  color: #1d2b45;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.6;
}

.questionnaire-builder__item-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.questionnaire-builder__field {
  margin-top: 10px;
  color: #8a97b2;
  font-size: 11px;
  word-break: break-all;
}

.questionnaire-builder__unsupported {
  border-radius: 10px;
  background: #f6f8fc;
  color: #6a7792;
  font-size: 12px;
  line-height: 1.6;
  padding: 10px 12px;
}

@media (max-width: 900px) {
  .questionnaire-builder__toolbar {
    grid-template-columns: 1fr;
  }
}

.sale-task-table :deep(.n-data-table-th) {
  background: #f8fbff;
  color: #31415c;
  font-size: 12px;
}

.sale-task-table :deep(.n-data-table-td) {
  vertical-align: top;
}

:deep(.sale-task-cell) {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding-right: 8px;
  min-width: 0;
}

:deep(.sale-task-cell__title) {
  font-size: 13px;
  line-height: 20px;
  font-weight: 600;
  color: #243247;
}

:deep(.sale-task-overview__meta) {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

:deep(.sale-task-meta-dot) {
  border-radius: 999px;
  background: #f1f5f9;
  padding: 2px 8px;
  color: #475569;
  font-size: 12px;
  line-height: 18px;
}

:deep(.sale-task-title-clamp) {
  display: -webkit-box;
  max-width: 240px;
  overflow: hidden;
  cursor: help;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

:deep(.sale-task-title-popover) {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

:deep(.sale-task-title-popover__main) {
  color: #172033;
  font-size: 13px;
  font-weight: 600;
  line-height: 20px;
}

:deep(.sale-task-title-meta) {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 2px;
}

:deep(.sale-task-title-id) {
  display: inline-flex;
  align-items: center;
  border-radius: 999px;
  background: #f8fbff;
  padding: 1px 8px;
  color: #64748b;
  font-size: 11px;
  line-height: 16px;
  box-shadow: inset 0 0 0 1px rgba(148, 163, 184, 0.16);
}

:deep(.sale-task-title-copy) {
  color: #64748b;
}

:deep(.sale-task-title-copy:hover) {
  color: #1d4ed8;
}

:deep(.sale-task-title-popover__sub),
:deep(.sale-task-title-popover__url) {
  word-break: break-all;
  color: #64748b;
  font-size: 12px;
  line-height: 18px;
}

:deep(.sale-task-platform-info),
:deep(.sale-task-config) {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 7px;
}

:deep(.sale-task-platform-info__name) {
  overflow: hidden;
  color: #243247;
  font-size: 13px;
  line-height: 20px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

:deep(.sale-task-config__link-row) {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 6px;
}

:deep(.sale-task-config__url) {
  max-width: 210px;
  overflow: hidden;
  color: #1d4ed8;
  font-size: 12px;
  line-height: 18px;
  text-overflow: ellipsis;
  text-decoration: none;
  white-space: nowrap;
}

:deep(.sale-task-config__copy) {
  flex: 0 0 auto;
  color: #64748b;
}

:deep(.sale-task-config__copy:hover) {
  color: #1d4ed8;
}

:deep(.sale-task-config__url:hover) {
  color: #0f3fb6;
  text-decoration: underline;
}

:deep(.sale-task-cell__sub) {
  font-size: 12px;
  line-height: 18px;
  color: #6b7a90;
}

:deep(.sale-task-cell--accounts) {
  gap: 8px;
}

:deep(.sale-task-plan__time) {
  border-radius: 12px;
  background: #f8fbff;
  padding: 9px 10px;
  box-shadow: inset 0 0 0 1px rgba(148, 163, 184, 0.14);
}

:deep(.sale-task-plan__label) {
  display: block;
  margin-bottom: 2px;
  color: #64748b;
  font-size: 12px;
  line-height: 18px;
}

:deep(.sale-task-plan__value) {
  display: block;
  overflow: hidden;
  color: #334155;
  font-size: 12px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

:deep(.sale-task-account-pill) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  min-width: 78px;
  border-radius: 999px;
  background: #eef6ff;
  padding: 6px 10px;
  box-shadow: inset 0 0 0 1px rgba(59, 130, 246, 0.16);
}

:deep(.sale-task-account-count__value) {
  font-size: 18px;
  line-height: 20px;
  font-weight: 700;
  color: #1d4ed8;
}

:deep(.sale-task-account-count__label) {
  font-size: 12px;
  color: #6b7a90;
}

:deep(.sale-task-cell--schedules) {
  gap: 6px;
}

:deep(.sale-task-status) {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

:deep(.sale-task-status__tag) {
  margin-bottom: 2px;
}

:deep(.sale-task-status__summary) {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-bottom: 2px;
}

:deep(.sale-task-status__summary--empty) {
  color: #94a3b8;
  font-size: 12px;
  line-height: 18px;
}

:deep(.sale-task-status__count) {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border-radius: 999px;
  background: #f1f5f9;
  padding: 2px 7px;
  color: #475569;
  font-size: 12px;
  line-height: 18px;
}

:deep(.sale-task-status__count b) {
  font-weight: 500;
}

:deep(.sale-task-status__count em) {
  color: #0f3fb6;
  font-style: normal;
  font-weight: 700;
}

:deep(.sale-task-status__count.is-submitted) {
  background: #dcfce7;
  color: #166534;
}

:deep(.sale-task-status__count.is-blocked),
:deep(.sale-task-status__count.is-failed) {
  background: #fee2e2;
  color: #991b1b;
}

:deep(.sale-task-status__count.is-running) {
  background: #dbeafe;
  color: #1d4ed8;
}

:deep(.sale-task-status__line) {
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr);
  gap: 8px;
  align-items: center;
}

:deep(.sale-task-status__label) {
  color: #94a3b8;
  font-size: 12px;
  line-height: 18px;
}

:deep(.sale-task-status__value) {
  overflow: hidden;
  color: #334155;
  font-size: 12px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

:deep(.sale-task-operate) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}

:deep(.sale-task-operate__button) {
  width: 28px;
  height: 28px;
}

:deep(.sale-task-operate__trigger) {
  display: inline-flex;
}

:deep(.sale-task-operate__icon) {
  width: 16px;
  height: 16px;
}

:deep(.sale-task-schedule-card) {
  border-radius: 10px;
  background: #f8fbff;
  padding: 7px 9px;
  box-shadow: inset 0 0 0 1px rgba(148, 163, 184, 0.14);
}

:deep(.sale-task-schedule-card__top) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

:deep(.sale-task-schedule-card__time) {
  overflow: hidden;
  margin-top: 2px;
  color: #64748b;
  font-size: 12px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

:deep(.sale-task-schedule-card__session) {
  max-width: 130px;
  overflow: hidden;
  color: #1d4ed8;
  font-size: 12px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-picker {
  display: flex;
  flex-direction: column;
  gap: 14px;
  border: 1px solid #d9e5f6;
  border-radius: 16px;
  background: linear-gradient(180deg, #fcfdff 0%, #f8fbff 100%);
  padding: 18px;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.9);
}

.account-picker__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.account-picker__title {
  font-size: 14px;
  font-weight: 600;
  color: #243247;
}

.account-picker__hint {
  margin-top: 4px;
  font-size: 12px;
  color: #6b7a90;
}

.account-picker__meta {
  display: flex;
  gap: 12px;
}

.account-picker__meta-item {
  min-width: 72px;
  border-radius: 12px;
  background: #fff;
  padding: 10px 12px;
  box-shadow: inset 0 0 0 1px rgba(148, 163, 184, 0.16);
  text-align: center;
}

.account-picker__meta-item--active {
  background: #eef4ff;
  box-shadow: inset 0 0 0 1px rgba(59, 130, 246, 0.2);
}

.account-picker__meta-label {
  display: block;
  margin-bottom: 4px;
  font-size: 11px;
  color: #6b7a90;
}

.account-picker__toolbar,
.account-picker__summary {
  display: flex;
  align-items: center;
  gap: 8px;
}

.account-picker__toolbar {
  flex-wrap: wrap;
}

.account-picker__search {
  min-width: 220px;
  flex: 1 1 280px;
}

.account-picker__summary {
  flex-wrap: wrap;
}

.account-picker__summary-label,
.account-picker__summary-more {
  font-size: 12px;
  color: #6b7a90;
}

.account-picker__table {
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.92);
  padding: 10px;
  box-shadow: inset 0 0 0 1px rgba(219, 234, 254, 0.9);
}

.account-picker__table :deep(.n-data-table-wrapper) {
  overflow: hidden;
  border-radius: 12px;
}

.account-picker__table :deep(.n-data-table-th) {
  background: #f8fbff;
  color: #32465f;
  font-size: 12px;
}

.account-picker__table :deep(.n-data-table-td) {
  vertical-align: top;
}

.account-picker__table :deep(.n-data-table-th:first-child),
.account-picker__table :deep(.n-data-table-td:first-child) {
  vertical-align: middle;
}

.account-picker__table :deep(.n-data-table-th:first-child .n-data-table-th__title),
.account-picker__table :deep(.n-data-table-td:first-child .n-checkbox) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.lottery-account-cell {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.lottery-account-cell--identity {
  gap: 4px;
}

.lottery-account-cell--profile {
  gap: 7px;
}

.lottery-account-email {
  max-width: 220px;
}

.lottery-account-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #718096;
  font-size: 12px;
  line-height: 18px;
}

.lottery-account-profile {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.lottery-account-profile__headline {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
  flex-wrap: nowrap;
}

.lottery-account-profile__primary {
  min-width: 0;
  overflow: hidden;
  flex: 1 1 auto;
  color: #243247;
  font-size: 13px;
  line-height: 20px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lottery-account-profile__secondary {
  overflow: hidden;
  color: #718096;
  font-size: 12px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lottery-account-mini-tag {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  white-space: nowrap;
}

.lottery-account-mini-tag :deep(.n-tag) {
  display: inline-flex;
  align-items: center;
  white-space: nowrap;
}

.lottery-account-occupied {
  display: flex;
  min-width: 0;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  margin-top: 2px;
}

.lottery-account-occupied__main {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.lottery-account-occupied__badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  background: linear-gradient(180deg, rgba(255, 247, 237, 0.96) 0%, rgba(255, 237, 213, 0.96) 100%);
  padding: 2px 9px;
  color: #c56a16;
  font-size: 11px;
  line-height: 16px;
  box-shadow: inset 0 0 0 1px rgba(245, 158, 11, 0.16);
}

.lottery-account-occupied__copy {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  max-width: 100%;
  border: 1px solid rgba(245, 158, 11, 0.18);
  border-radius: 999px;
  padding: 2px 10px;
  background: rgba(255, 255, 255, 0.96);
  color: #a85d11;
  cursor: pointer;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.03);
  transition:
    background-color 0.16s ease,
    border-color 0.16s ease,
    color 0.16s ease,
    box-shadow 0.16s ease;
}

.lottery-account-occupied__copy:hover {
  border-color: rgba(245, 158, 11, 0.26);
  background: #fff7ed;
  color: #8f4f0f;
  box-shadow: 0 2px 6px rgba(245, 158, 11, 0.08);
}

.lottery-account-occupied__copy-text {
  flex-shrink: 0;
  font-size: 11px;
  line-height: 16px;
  font-weight: 600;
}

.lottery-account-occupied__icon {
  flex-shrink: 0;
  font-size: 11px;
}

.lottery-account-occupied__time {
  padding-left: 2px;
  color: #94a3b8;
  font-size: 11px;
  line-height: 16px;
}

.lottery-account-cell--login {
  justify-content: center;
}

.lottery-account-login-line {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.lottery-account-login-time {
  min-width: 0;
  overflow: hidden;
  color: #718096;
  font-size: 12px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-picker__empty {
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.86);
  padding: 24px;
  text-align: center;
}

.account-picker__empty-title {
  font-size: 14px;
  font-weight: 600;
  color: #243247;
}

.account-picker__empty-text {
  margin-top: 6px;
  font-size: 12px;
  color: #6b7a90;
}

.lottery-event-parser {
  display: flex;
  flex-direction: column;
  gap: 10px;
  border: 1px solid #e0f2fe;
  border-radius: 16px;
  background: linear-gradient(180deg, #f7fcff 0%, #ffffff 100%);
  padding: 14px;
}

.lottery-event-parser__input {
  display: flex;
  gap: 10px;
}

.lottery-event-parser__history {
  border-radius: 12px;
  background: rgba(239, 246, 255, 0.72);
  padding: 10px;
}

.lottery-event-parser__selection {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-radius: 12px;
  background: rgba(239, 246, 255, 0.72);
  padding: 10px 12px;
}

.lottery-event-parser__selection-main {
  min-width: 0;
}

.lottery-event-parser__selection-label {
  font-size: 12px;
  color: #5f7288;
}

.lottery-event-parser__selection-value {
  overflow: hidden;
  margin-top: 4px;
  color: #19324d;
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lottery-event-parser__result,
.lottery-event-parser__empty {
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.9);
  overflow: hidden;
  padding: 10px;
  box-shadow: inset 0 0 0 1px rgba(125, 211, 252, 0.22);
}

.lottery-event-parser__title {
  overflow: hidden;
  font-size: 14px;
  font-weight: 700;
  color: #19324d;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lottery-event-parser__meta,
.lottery-event-parser__empty {
  margin-top: 6px;
  font-size: 12px;
  color: #5f7288;
}

.lottery-event-parser__table :deep(.n-data-table-tr) {
  cursor: pointer;
}

.lottery-event-parser__table :deep(.n-data-table-tr:hover .n-data-table-td) {
  background: #f0f7ff;
}

.lottery-event-parser__table :deep(.n-data-table-tr.is-selected-collection-event .n-data-table-td) {
  background: #eaf3ff;
  box-shadow: inset 3px 0 0 #1d4ed8;
}

.lottery-wizard {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.lottery-wizard__steps {
  border-radius: 18px;
  background: linear-gradient(135deg, #f8fbff 0%, #eef6ff 100%);
  padding: 12px 14px;
  box-shadow: inset 0 0 0 1px rgba(219, 234, 254, 0.9);
}

.lottery-wizard__steps-hint {
  margin-top: -4px;
  font-size: 12px;
  color: #6b7a90;
  padding: 0 4px;
}

.lottery-wizard__body {
  min-height: 360px;
}

.lottery-wizard__panel {
  animation: wizard-panel-in 0.18s ease-out;
}

.lottery-session-picker {
  display: flex;
  flex-direction: column;
  gap: 14px;
  border: 1px solid #dbeafe;
  border-radius: 16px;
  background: linear-gradient(180deg, #f8fbff 0%, #fff 100%);
  overflow: hidden;
  padding: 16px;
}

.lottery-reception-picker {
  display: flex;
  flex-direction: column;
  gap: 14px;
  border: 1px solid #dbeafe;
  border-radius: 16px;
  background: linear-gradient(180deg, #f8fbff 0%, #fff 100%);
  padding: 16px;
}

.lottery-reception-picker__list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 12px;
}

.lottery-reception-picker__item {
  border: 1px solid #dbeafe;
  border-radius: 14px;
  background: #fff;
  padding: 14px;
  text-align: left;
  cursor: pointer;
  transition: all 0.18s ease;
}

.lottery-reception-picker__item:hover {
  border-color: #93c5fd;
  background: #f8fbff;
}

.lottery-reception-picker__item.is-active {
  border-color: #2563eb;
  background: #eff6ff;
  box-shadow: inset 0 0 0 1px rgba(37, 99, 235, 0.15);
}

.lottery-reception-picker__item-title {
  color: #243247;
  font-size: 13px;
  font-weight: 600;
  line-height: 20px;
}

.lottery-reception-picker__item-meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 8px;
  color: #6b7a90;
  font-size: 12px;
}

.lottery-session-picker__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.lottery-session-picker__title {
  font-size: 14px;
  font-weight: 600;
  color: #243247;
}

.lottery-session-picker__hint {
  margin-top: 4px;
  font-size: 12px;
  color: #6b7a90;
}

@keyframes wizard-panel-in {
  from {
    opacity: 0;
    transform: translateY(4px);
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.lottery-scheduler {
  display: flex;
  flex-direction: column;
  gap: 12px;
  border: 1px solid #dbeafe;
  border-radius: 16px;
  background: linear-gradient(180deg, #f8fbff 0%, #ffffff 100%);
  padding: 16px;
}

.lottery-scheduler__header,
.lottery-scheduler__row,
.lottery-scheduler__footer {
  display: flex;
  align-items: center;
  gap: 12px;
}

.lottery-scheduler__header {
  justify-content: space-between;
}

.lottery-scheduler__title {
  font-size: 14px;
  font-weight: 600;
  color: #243247;
}

.lottery-scheduler__hint,
.lottery-scheduler__footer,
.lottery-scheduler__empty {
  font-size: 12px;
  color: #6b7a90;
}

.lottery-scheduler__list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.lottery-scheduler__row {
  border-radius: 12px;
  background: #fff;
  padding: 10px;
  box-shadow: inset 0 0 0 1px rgba(148, 163, 184, 0.16);
}

.lottery-scheduler__index {
  display: grid;
  width: 26px;
  height: 26px;
  place-items: center;
  border-radius: 999px;
  background: #eff6ff;
  color: #1d4ed8;
  font-size: 12px;
  font-weight: 700;
}

.lottery-scheduler__time {
  flex: 1;
}

.lottery-scheduler__session {
  width: 190px;
}

.lottery-scheduler__count {
  width: 140px;
}

.lottery-scheduler__footer {
  justify-content: flex-end;
}

.task-process {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.task-process__hero {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  border: 1px solid #dbeafe;
  border-radius: 18px;
  background: linear-gradient(135deg, #f8fbff 0%, #eef6ff 100%);
  padding: 16px;
}

.task-process__title {
  overflow: hidden;
  color: #10233f;
  font-size: 16px;
  font-weight: 800;
  line-height: 24px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-process__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.task-process__stats,
.task-process__summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.task-process__stat-card,
.task-process__summary-card {
  border: 1px solid #edf2f7;
  border-radius: 14px;
  background: #fff;
  padding: 12px;
}

.task-process__summary-card {
  appearance: none;
  cursor: pointer;
  text-align: left;
  transition:
    border-color 0.16s ease,
    background 0.16s ease,
    box-shadow 0.16s ease;
}

.task-process__summary-card:hover,
.task-process__summary-card.is-active {
  border-color: #93c5fd;
  background: #f8fbff;
  box-shadow: inset 0 0 0 1px rgba(59, 130, 246, 0.08);
}

.task-process__summary-card.is-active strong {
  color: #0f3fb6;
}

.task-process__stat-card span,
.task-process__summary-card span,
.task-process__muted {
  color: #718096;
  font-size: 12px;
}

.task-process__stat-card strong,
.task-process__summary-card strong {
  display: block;
  margin-top: 6px;
  color: #174ea6;
  font-size: 20px;
  line-height: 24px;
}

.task-process__stat-card .task-process__stat-text {
  color: #22324a;
  font-size: 12px;
  line-height: 18px;
}

.task-process__card {
  border: 1px solid #edf2f7;
  border-radius: 16px;
  background: #fff;
}

.task-process__schedule-grid {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.task-process__schedule-card,
.task-process__plan-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-radius: 12px;
  background: #f8fbff;
  padding: 12px;
}

.task-process__schedule-side {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 8px;
  color: #4a5f78;
  font-size: 12px;
}

.task-process__empty {
  padding: 28px 0;
}

.task-process__execution-filter {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  border-radius: 8px;
  background: #f8fbff;
  padding: 10px 12px;
}

.task-process__execution-filter strong {
  color: #10233f;
  font-size: 13px;
  line-height: 20px;
}

.task-process__execution-filter span {
  margin-left: 8px;
  color: #64748b;
  font-size: 12px;
}

.task-process__execution-list {
  display: flex;
  max-height: 420px;
  flex-direction: column;
  gap: 8px;
  overflow: auto;
  padding-right: 4px;
}

.task-process__pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.task-process__execution-card {
  position: relative;
  flex: 0 0 auto;
  overflow: hidden;
  border: 1px solid #edf2f7;
  border-radius: 8px;
  background: #fff;
  padding: 12px 12px 10px 14px;
}

.task-process__execution-card::before {
  position: absolute;
  top: 10px;
  bottom: 10px;
  left: 0;
  width: 3px;
  border-radius: 0 4px 4px 0;
  background: #cbd5e1;
  content: '';
}

.task-process__execution-card.is-queued::before {
  background: #94a3b8;
}

.task-process__execution-card.is-running::before {
  background: #2563eb;
}

.task-process__execution-card.is-submitted::before,
.task-process__execution-card.is-completed::before,
.task-process__execution-card.is-success::before {
  background: #16a34a;
}

.task-process__execution-card.is-blocked::before,
.task-process__execution-card.is-failed::before,
.task-process__execution-card.is-timeout::before {
  background: #ef4444;
}

.task-process__execution-main,
.task-process__execution-footer {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.task-process__execution-account {
  min-width: 0;
}

.task-process__execution-account strong {
  display: block;
  overflow: hidden;
  max-width: 320px;
  color: #10233f;
  font-size: 13px;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-process__execution-account span,
.task-process__execution-meta {
  color: #718096;
  font-size: 12px;
}

.task-process__execution-tags,
.task-process__execution-actions,
.task-process__execution-meta {
  display: flex;
  flex-shrink: 0;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.task-process__execution-tags {
  justify-content: flex-end;
  max-width: 260px;
}

.task-process__execution-actions {
  justify-content: flex-end;
}

.task-process__execution-message {
  display: -webkit-box;
  overflow: hidden;
  margin: 8px 0 10px;
  color: #334155;
  font-size: 12px;
  line-height: 18px;
  word-break: break-word;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.task-process__execution-meta {
  min-width: 0;
  overflow: hidden;
}

.task-process__execution-meta span {
  overflow: hidden;
  max-width: 160px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 960px) {
  .task-process__stats,
  .task-process__summary-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .task-process__execution-main,
  .task-process__execution-footer {
    flex-direction: column;
    gap: 8px;
  }

  .task-process__execution-account strong {
    max-width: 100%;
  }

  .task-process__execution-tags,
  .task-process__execution-actions {
    flex-shrink: 1;
  }
}
</style>
