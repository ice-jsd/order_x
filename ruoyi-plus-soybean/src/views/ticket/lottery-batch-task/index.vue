<script setup lang="tsx">
import { computed, h, onMounted, ref } from 'vue';
import { NButton, NTag } from 'naive-ui';
import { useAuth } from '@/hooks/business/auth';
import { defaultTransform, useNaivePaginatedTable } from '@/hooks/common/table';
import {
  fetchCreateTicketLotteryBatchTask,
  fetchExecuteNowTicketLotteryBatchTask,
  fetchGetTicketAccountList,
  fetchGetTicketLotteryBatchTask,
  fetchGetTicketLotteryBatchTaskList,
  fetchGetTicketLotteryBatchTaskProcess,
  fetchGetTicketLotteryEventParseRecord,
  fetchGetTicketPlatformList,
  fetchParseTicketLotteryEvent,
  fetchUpdateTicketLotteryBatchTask
} from '@/service/api/ticket';
import { useAppStore } from '@/store/modules/app';
import { handleCopy } from '@/utils/copy';
import { renderTicketEllipsis, renderTicketTag, taskStatusOptions } from '../common';

defineOptions({
  name: 'TicketLotteryBatchTaskList'
});

type ActivityCandidate = {
  key: string;
  eventUrl: string;
  eventTitle: string;
  notes?: string;
  salesType?: string;
};

type ReceptionGroup = {
  key: string;
  receptionId?: string;
  receptionTitle?: string;
  salesType?: string;
  sessionCount: number;
  sessions: Api.Ticket.LotteryEventSession[];
  label: string;
};

type LotteryBatchItemDraft = Api.Ticket.LotteryBatchTaskItem & {
  parseLoading?: boolean;
  eventInfo?: Api.Ticket.LotteryEventInfo | null;
  selectedReceptionKey?: string | null;
  selectedSessionIds?: string[];
};

const JAPAN_TIME_ZONE = 'Asia/Tokyo';
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

function createSearchParams(): Api.Ticket.LotteryBatchTaskSearchParams {
  return {
    pageNum: 1,
    pageSize: 10,
    batchTaskId: null,
    platformId: null,
    taskName: null,
    taskStatus: null,
    params: {}
  };
}

function createFormModel(): Api.Ticket.LotteryBatchTaskOperateParams {
  return {
    batchTaskId: undefined,
    platformId: undefined,
    taskName: '',
    taskStatus: 'draft',
    sourceUrl: '',
    taskOptions: '{}',
    remark: '',
    accountIds: [],
    items: []
  };
}

const searchParams = ref<Api.Ticket.LotteryBatchTaskSearchParams>(createSearchParams());
const platformOptions = ref<{ label: string; value: CommonType.IdType }[]>([]);
const checkedRowKeys = ref<CommonType.IdType[]>([]);
const modalVisible = ref(false);
const operateType = ref<NaiveUI.TableOperateType>('add');
const modalStep = ref(1);
const formModel = ref<Api.Ticket.LotteryBatchTaskOperateParams>(createFormModel());
const collectionParseLoading = ref(false);
const collectionInfo = ref<Api.Ticket.LotteryEventInfo | null>(null);
const collectionActivities = ref<ActivityCandidate[]>([]);
const selectedActivityKeys = ref<string[]>([]);
const accountLoading = ref(false);
const accountOptions = ref<Api.Ticket.Account[]>([]);
const accountSearchKeyword = ref('');
const itemDrafts = ref<LotteryBatchItemDraft[]>([]);
const activeItemKey = ref<string | undefined>(undefined);
const saving = ref(false);
const processVisible = ref(false);
const processLoading = ref(false);
const processData = ref<Api.Ticket.LotteryBatchTaskProcess | null>(null);
const executeNowLoading = ref<CommonType.IdType | null>(null);

const livePocketPlatformId = computed(() => {
  const platform = platformOptions.value.find(item => /live\s*pocket/i.test(item.label));
  return platform?.value || null;
});

const modalTitle = computed(() => (operateType.value === 'add' ? '新增批量抽票任务' : '编辑批量抽票任务'));
const modalStepItems = [
  { title: '输入链接' },
  { title: '选择活动' },
  { title: '选择账号' },
  { title: '配置活动' },
  { title: '提交保存' }
];
const selectedAccountCount = computed(() => formModel.value.accountIds?.length || 0);
const selectedActivitiesCount = computed(() => selectedActivityKeys.value.length);
const filteredAccounts = computed(() => {
  const keyword = accountSearchKeyword.value.trim().toLowerCase();
  const rows = accountOptions.value.filter(item => item.accountStatus === 'activated');
  if (!keyword) return rows;
  return rows.filter(item => String(item.email || '').toLowerCase().includes(keyword));
});
const activeItemDraft = computed(
  () => itemDrafts.value.find(item => item.eventUrl === activeItemKey.value) || itemDrafts.value[0] || null
);
const processSummaryItems = computed(() => {
  const summary = processData.value?.executionSummary || {};
  return [
    { key: 'total', label: '总数', value: summary.total || 0 },
    { key: 'queued', label: '排队', value: summary.queued || 0 },
    { key: 'running', label: '执行中', value: summary.running || 0 },
    { key: 'submitted', label: '已提交', value: summary.submitted || 0 },
    { key: 'pending_payment', label: '待支付', value: summary.pending_payment || 0 },
    { key: 'paid', label: '已支付', value: summary.paid || 0 },
    { key: 'failed', label: '失败', value: summary.failed || 0 },
    { key: 'blocked', label: '阻塞', value: summary.blocked || 0 }
  ];
});

function isCollectionUrl(url?: string | null) {
  return /\/t\/[^/?#]+/.test(url || '');
}

function isCollectionEventInfo(info?: Api.Ticket.LotteryEventInfo | null) {
  if (!info) return false;
  return isCollectionUrl(info.eventUrl) || (info.sessions || []).some(item => Boolean(item.eventUrl));
}

function formatJapanDateTime(date: Date) {
  const parts = japanDateTimeFormatter.formatToParts(date);
  const partMap = Object.fromEntries(parts.map(item => [item.type, item.value]));
  return `${partMap.year}-${partMap.month}-${partMap.day} ${partMap.hour}:${partMap.minute}:${partMap.second}`;
}

function getDefaultScheduledTime() {
  return formatJapanDateTime(new Date(Date.now() + 30 * 60 * 1000));
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
    notes: item.notes || undefined
  };
}

function buildReceptionKey(item?: Api.Ticket.LotteryEventSession | null) {
  if (!item) return '';
  const receptionId = String(item.receptionId || '').trim();
  if (receptionId) return `reception:${receptionId}`;
  return `missing:${String(item.receptionTitle || item.salesType || item.sessionId || '').trim()}`;
}

function formatReceptionLabel(item?: Api.Ticket.LotteryEventSession | null) {
  if (!item) return '-';
  const parts = [item.salesType, item.receptionTitle].filter(Boolean);
  const prefix = parts.length ? parts.join(' / ') : '未识别受付';
  const suffix = item.receptionId ? ` / reception ${item.receptionId}` : ' / reception 未识别';
  return `${prefix}${suffix}`;
}

function getReceptionGroups(item: LotteryBatchItemDraft): ReceptionGroup[] {
  const groups = new Map<string, ReceptionGroup>();
  for (const session of item.eventInfo?.sessions || []) {
    if (!String(session.salesType || '').includes('抽選')) continue;
    const key = buildReceptionKey(session);
    const existing = groups.get(key);
    if (existing) {
      existing.sessionCount += 1;
      existing.sessions.push(session);
      continue;
    }
    groups.set(key, {
      key,
      receptionId: session.receptionId || undefined,
      receptionTitle: session.receptionTitle || undefined,
      salesType: session.salesType || undefined,
      sessionCount: 1,
      sessions: [session],
      label: formatReceptionLabel(session)
    });
  }
  return Array.from(groups.values());
}

function getCurrentReceptionGroup(item: LotteryBatchItemDraft) {
  return getReceptionGroups(item).find(group => group.key === item.selectedReceptionKey) || null;
}

function getCurrentReceptionSessions(item: LotteryBatchItemDraft) {
  return getCurrentReceptionGroup(item)?.sessions || [];
}

function formatSessionOptionLabel(item?: Api.Ticket.LotteryEventSession | null) {
  if (!item) return '';
  const parts = [item.salesType, item.receptionTitle].filter(Boolean);
  return parts.length ? `${item.sessionLabel}｜${parts.join(' / ')}` : item.sessionLabel;
}

function createItemDraft(activity: ActivityCandidate): LotteryBatchItemDraft {
  return {
    batchItemId: undefined,
    batchTaskId: undefined,
    eventUrl: activity.eventUrl,
    eventTitle: activity.eventTitle,
    receptionId: undefined,
    receptionTitle: undefined,
    salesType: undefined,
    itemStatus: 'draft',
    selectedSessions: [],
    schedules: [],
    parseLoading: false,
    eventInfo: null,
    selectedReceptionKey: null,
    selectedSessionIds: []
  };
}

function extractCollectionActivities(info: Api.Ticket.LotteryEventInfo | null): ActivityCandidate[] {
  return (info?.sessions || [])
    .filter(item => Boolean(item.eventUrl))
    .map(item => ({
      key: String(item.eventUrl),
      eventUrl: String(item.eventUrl),
      eventTitle: item.sessionLabel || String(item.eventUrl),
      notes: item.notes,
      salesType: item.salesType
    }));
}

function syncItemSelectedSessions(item: LotteryBatchItemDraft, sessionIds: string[]) {
  item.selectedSessionIds = [...sessionIds];
  const sessionMap = new Map(getCurrentReceptionSessions(item).map(session => [session.sessionId, session]));
  const previousScheduleMap = new Map((item.schedules || []).map(schedule => [schedule.sessionId, schedule]));
  item.selectedSessions = sessionIds
    .map(sessionId => sessionMap.get(sessionId))
    .filter(Boolean)
    .map(session => normalizeLotterySession(session!));
  item.schedules = item.selectedSessions.map(session => {
    const old = previousScheduleMap.get(session.sessionId);
    return {
      scheduleId: old?.scheduleId,
      batchItemId: old?.batchItemId,
      sessionId: session.sessionId,
      sessionLabel: session.sessionLabel,
      scheduledTime: old?.scheduledTime || getDefaultScheduledTime(),
      accountCount: old?.accountCount ?? 1,
      scheduleStatus: old?.scheduleStatus || 'pending',
      resultMessage: old?.resultMessage
    };
  });
}

function autoAssignItemSchedules(item: LotteryBatchItemDraft) {
  const total = selectedAccountCount.value;
  if (!item.schedules?.length) {
    window.$message?.warning('请先选择场次');
    return;
  }
  if (!total) {
    window.$message?.warning('请先选择账号');
    return;
  }
  const base = Math.floor(total / item.schedules.length);
  let remainder = total % item.schedules.length;
  item.schedules = item.schedules.map(schedule => ({
    ...schedule,
    accountCount: base + (remainder-- > 0 ? 1 : 0)
  }));
}

async function loadPlatformOptions() {
  const { data, error } = await fetchGetTicketPlatformList({ pageNum: 1, pageSize: 200, params: {} });
  if (error) return;
  platformOptions.value = (data.rows || []).map(item => ({ label: item.platformName, value: item.platformId }));
  if (!formModel.value.platformId && livePocketPlatformId.value) {
    formModel.value.platformId = livePocketPlatformId.value;
    searchParams.value.platformId = livePocketPlatformId.value;
  }
}

async function loadAccountOptions() {
  if (!formModel.value.platformId) return;
  accountLoading.value = true;
  const { data, error } = await fetchGetTicketAccountList({
    pageNum: 1,
    pageSize: 500,
    platformId: formModel.value.platformId,
    accountStatus: 'activated',
    params: {}
  });
  accountLoading.value = false;
  if (error) return;
  accountOptions.value = data.rows || [];
}

async function pollParseRecord(
  recordId: CommonType.IdType,
  onCompleted: (info: Api.Ticket.LotteryEventInfo) => Promise<void> | void,
  failedMessage: string
) {
  for (let attempt = 0; attempt < 60; attempt += 1) {
    await new Promise(resolve => window.setTimeout(resolve, 2000));
    const { data: eventInfo, error } = await fetchGetTicketLotteryEventParseRecord(recordId);
    if (error || !eventInfo) continue;
    if (eventInfo.parseStatus === 'completed') {
      await onCompleted(eventInfo);
      return true;
    }
    if (eventInfo.parseStatus === 'failed') {
      window.$message?.error(eventInfo.parseMessage || failedMessage);
      return false;
    }
  }
  window.$message?.warning('解析仍在进行，请稍后重试');
  return false;
}

async function requestLotteryParse(
  eventUrl: string,
  onCompleted: (info: Api.Ticket.LotteryEventInfo) => Promise<void> | void,
  options: { queuedMessage: string; failedMessage: string }
) {
  const platformId = formModel.value.platformId;
  if (!platformId) {
    window.$message?.error('请先选择目标平台');
    return false;
  }
  const { data: eventInfo, error } = await fetchParseTicketLotteryEvent({ platformId, eventUrl });
  if (error || !eventInfo) return false;
  if (eventInfo.parseStatus === 'completed') {
    await onCompleted(eventInfo);
    return true;
  }
  if (!eventInfo.recordId) {
    window.$message?.error('解析记录缺失');
    return false;
  }
  window.$message?.info(eventInfo.parseMessage || options.queuedMessage);
  return pollParseRecord(eventInfo.recordId, onCompleted, options.failedMessage);
}

async function parseCollectionSource() {
  if (!isCollectionUrl(formModel.value.sourceUrl)) {
    window.$message?.warning('批量抽票只支持 /t/... 集合链接');
    return;
  }
  collectionParseLoading.value = true;
  collectionInfo.value = null;
  collectionActivities.value = [];
  selectedActivityKeys.value = [];
  itemDrafts.value = [];
  activeItemKey.value = undefined;
  try {
    await requestLotteryParse(
      formModel.value.sourceUrl || '',
      async eventInfo => {
        if (!isCollectionEventInfo(eventInfo)) {
          window.$message?.error('当前链接没有解析出活动集合');
          return;
        }
        collectionInfo.value = eventInfo;
        collectionActivities.value = extractCollectionActivities(eventInfo);
        if (!formModel.value.taskName) {
          formModel.value.taskName = eventInfo.eventTitle || '批量抽票任务';
        }
        modalStep.value = 2;
        window.$message?.success('活动集合解析成功');
      },
      { queuedMessage: '活动解析已排队，请稍候', failedMessage: '活动解析失败' }
    );
  } finally {
    collectionParseLoading.value = false;
  }
}

function buildActivityDraftsFromSelection() {
  const activityMap = new Map(collectionActivities.value.map(item => [item.eventUrl, item]));
  itemDrafts.value = selectedActivityKeys.value
    .map(key => activityMap.get(key))
    .filter(Boolean)
    .map(activity => createItemDraft(activity!));
  activeItemKey.value = itemDrafts.value[0]?.eventUrl || undefined;
}

async function parseItemEvent(item: LotteryBatchItemDraft) {
  item.parseLoading = true;
  try {
    await requestLotteryParse(
      item.eventUrl,
      async eventInfo => {
        item.eventInfo = eventInfo;
        const groups = getReceptionGroups(item);
        item.selectedReceptionKey = groups.length === 1 ? groups[0].key : null;
        item.selectedSessions = [];
        item.selectedSessionIds = [];
        item.schedules = [];
        if (groups.length === 1) {
          item.receptionId = groups[0].receptionId;
          item.receptionTitle = groups[0].receptionTitle;
          item.salesType = groups[0].salesType;
        } else {
          item.receptionId = undefined;
          item.receptionTitle = undefined;
          item.salesType = undefined;
        }
        window.$message?.success('活动场次解析成功');
      },
      { queuedMessage: '所选活动场次解析已排队，请稍候', failedMessage: '场次解析失败' }
    );
  } finally {
    item.parseLoading = false;
  }
}

function handleReceptionChange(item: LotteryBatchItemDraft, nextKey: string | null) {
  item.selectedReceptionKey = nextKey;
  const group = getCurrentReceptionGroup(item);
  item.receptionId = group?.receptionId;
  item.receptionTitle = group?.receptionTitle;
  item.salesType = group?.salesType;
  item.selectedSessions = [];
  item.selectedSessionIds = [];
  item.schedules = [];
}

function validateConfiguredItems() {
  const totalAccounts = selectedAccountCount.value;
  for (const item of itemDrafts.value) {
    if (!item.receptionId) {
      window.$message?.warning(`活动「${item.eventTitle}」还没有选择受付`);
      activeItemKey.value = item.eventUrl;
      return false;
    }
    if (!item.selectedSessions?.length) {
      window.$message?.warning(`活动「${item.eventTitle}」还没有选择场次`);
      activeItemKey.value = item.eventUrl;
      return false;
    }
    const receptionIds = new Set(item.selectedSessions.map(session => session.receptionId).filter(Boolean));
    if (receptionIds.size > 1 || (receptionIds.size === 1 && !receptionIds.has(item.receptionId))) {
      window.$message?.warning(`活动「${item.eventTitle}」的场次混入了其他受付`);
      activeItemKey.value = item.eventUrl;
      return false;
    }
    if (!item.schedules?.length) {
      window.$message?.warning(`活动「${item.eventTitle}」还没有配置分时段`);
      activeItemKey.value = item.eventUrl;
      return false;
    }
    const scheduleSessionIds = new Set(item.schedules.map(schedule => schedule.sessionId).filter(Boolean));
    const selectedSessionIds = new Set(item.selectedSessions.map(session => session.sessionId));
    if ([...scheduleSessionIds].some(sessionId => typeof sessionId === 'string' && !selectedSessionIds.has(sessionId))) {
      window.$message?.warning(`活动「${item.eventTitle}」的分时段和已选场次不一致`);
      activeItemKey.value = item.eventUrl;
      return false;
    }
    const plannedCount = item.schedules.reduce((sum, schedule) => sum + Number(schedule.accountCount || 0), 0);
    if (plannedCount <= 0) {
      window.$message?.warning(`活动「${item.eventTitle}」的账号数量必须大于 0`);
      activeItemKey.value = item.eventUrl;
      return false;
    }
    if (plannedCount > totalAccounts) {
      window.$message?.warning(`活动「${item.eventTitle}」计划 ${plannedCount} 个账号，但当前只选了 ${totalAccounts} 个账号`);
      activeItemKey.value = item.eventUrl;
      return false;
    }
    if (item.schedules.some(schedule => !schedule.scheduledTime)) {
      window.$message?.warning(`活动「${item.eventTitle}」仍有场次未设置执行时间`);
      activeItemKey.value = item.eventUrl;
      return false;
    }
  }
  return true;
}

function buildSubmitPayload(): Api.Ticket.LotteryBatchTaskOperateParams {
  return {
    batchTaskId: formModel.value.batchTaskId,
    platformId: formModel.value.platformId,
    taskName: formModel.value.taskName,
    taskStatus: formModel.value.taskStatus || 'draft',
    sourceUrl: formModel.value.sourceUrl,
    taskOptions: formModel.value.taskOptions || '{}',
    remark: formModel.value.remark || '',
    accountIds: [...(formModel.value.accountIds || [])],
    items: itemDrafts.value.map(item => ({
      batchItemId: item.batchItemId,
      batchTaskId: item.batchTaskId,
      eventUrl: item.eventUrl,
      eventTitle: item.eventTitle,
      receptionId: item.receptionId,
      receptionTitle: item.receptionTitle,
      salesType: item.salesType,
      itemStatus: item.itemStatus || 'draft',
      selectedSessions: (item.selectedSessions || []).map(session => normalizeLotterySession(session)),
      schedules: (item.schedules || []).map(schedule => ({
        scheduleId: schedule.scheduleId,
        batchItemId: schedule.batchItemId,
        sessionId: schedule.sessionId,
        sessionLabel: schedule.sessionLabel,
        scheduledTime: schedule.scheduledTime,
        accountCount: Number(schedule.accountCount || 0),
        scheduleStatus: schedule.scheduleStatus || 'pending',
        resultMessage: schedule.resultMessage
      }))
    }))
  };
}

async function handleSubmit() {
  if (!formModel.value.platformId) {
    window.$message?.warning('请选择目标平台');
    return;
  }
  if (!formModel.value.taskName?.trim()) {
    window.$message?.warning('请输入任务名称');
    return;
  }
  if (!isCollectionUrl(formModel.value.sourceUrl)) {
    window.$message?.warning('批量抽票只支持 /t/... 集合链接');
    return;
  }
  if (!selectedAccountCount.value) {
    window.$message?.warning('请先选择账号');
    return;
  }
  if (!itemDrafts.value.length) {
    window.$message?.warning('请先选择至少一个活动');
    return;
  }
  if (!validateConfiguredItems()) {
    return;
  }
  saving.value = true;
  const payload = buildSubmitPayload();
  const requestFn = operateType.value === 'add' ? fetchCreateTicketLotteryBatchTask : fetchUpdateTicketLotteryBatchTask;
  const { error } = await requestFn(payload);
  saving.value = false;
  if (error) return;
  window.$message?.success(operateType.value === 'add' ? '批量抽票任务已创建' : '批量抽票任务已更新');
  modalVisible.value = false;
  await getData();
}

function openAdd() {
  operateType.value = 'add';
  modalStep.value = 1;
  formModel.value = createFormModel();
  if (livePocketPlatformId.value) {
    formModel.value.platformId = livePocketPlatformId.value;
  }
  collectionInfo.value = null;
  collectionActivities.value = [];
  selectedActivityKeys.value = [];
  itemDrafts.value = [];
  activeItemKey.value = undefined;
  accountSearchKeyword.value = '';
  modalVisible.value = true;
  void loadAccountOptions();
}

async function openEdit(row: Api.Ticket.LotteryBatchTask) {
  const { data: detail, error } = await fetchGetTicketLotteryBatchTask(row.batchTaskId);
  if (error || !detail) return;
  operateType.value = 'edit';
  formModel.value = {
    batchTaskId: detail.batchTaskId,
    platformId: detail.platformId,
    taskName: detail.taskName,
    taskStatus: detail.taskStatus,
    sourceUrl: detail.sourceUrl,
    taskOptions: detail.taskOptions || '{}',
    remark: detail.remark || '',
    accountIds: detail.accountIds || [],
    items: detail.items || []
  };
  itemDrafts.value = (detail.items || []).map(item => {
    const draft: LotteryBatchItemDraft = {
      ...item,
      selectedSessions: (item.selectedSessions || []).map(session => normalizeLotterySession(session)),
      schedules: (item.schedules || []).map(schedule => ({
        ...schedule,
        scheduledTime: schedule.scheduledTime || getDefaultScheduledTime(),
        accountCount: Number(schedule.accountCount || 0)
      })),
      parseLoading: false,
      eventInfo: item.selectedSessions?.length
        ? {
            eventUrl: item.eventUrl,
            eventTitle: item.eventTitle,
            entryStartTime: '',
            entryEndTime: '',
            sessions: (item.selectedSessions || []).map(session => normalizeLotterySession(session)),
            parseStatus: 'completed'
          }
        : null,
      selectedReceptionKey: item.receptionId ? `reception:${item.receptionId}` : null,
      selectedSessionIds: (item.selectedSessions || []).map(session => session.sessionId)
    };
    return draft;
  });
  activeItemKey.value = itemDrafts.value[0]?.eventUrl || undefined;
  collectionActivities.value = itemDrafts.value.map(item => ({
    key: item.eventUrl,
    eventUrl: item.eventUrl,
    eventTitle: item.eventTitle,
    notes: item.selectedSessions?.[0]?.notes,
    salesType: item.salesType
  }));
  selectedActivityKeys.value = collectionActivities.value.map(item => item.eventUrl);
  modalStep.value = 4;
  accountSearchKeyword.value = '';
  modalVisible.value = true;
  await loadAccountOptions();
}

async function openProcess(row: Api.Ticket.LotteryBatchTask) {
  processLoading.value = true;
  processVisible.value = true;
  const { data: detail, error } = await fetchGetTicketLotteryBatchTaskProcess(row.batchTaskId);
  processLoading.value = false;
  if (error || !detail) return;
  processData.value = detail;
}

async function executeNow(row: Api.Ticket.LotteryBatchTask) {
  executeNowLoading.value = row.batchTaskId;
  const { error } = await fetchExecuteNowTicketLotteryBatchTask(row.batchTaskId);
  executeNowLoading.value = null;
  if (error) return;
  window.$message?.success('批量抽票任务已立即排队');
  await getData();
}

function resetSearch() {
  searchParams.value = createSearchParams();
  if (livePocketPlatformId.value) {
    searchParams.value.platformId = livePocketPlatformId.value;
  }
  checkedRowKeys.value = [];
  void getDataByPage();
}

function handleNextStep() {
  if (modalStep.value === 1) {
    void parseCollectionSource();
    return;
  }
  if (modalStep.value === 2) {
    if (!selectedActivityKeys.value.length) {
      window.$message?.warning('请先选择至少一个活动');
      return;
    }
    buildActivityDraftsFromSelection();
    modalStep.value = 3;
    return;
  }
  if (modalStep.value === 3) {
    if (!selectedAccountCount.value) {
      window.$message?.warning('请先选择账号');
      return;
    }
    modalStep.value = 4;
    return;
  }
  if (modalStep.value === 4) {
    if (!validateConfiguredItems()) return;
    modalStep.value = 5;
  }
}

function handlePrevStep() {
  if (modalStep.value > 1) {
    modalStep.value -= 1;
  }
}

function openSourceUrl(url?: string) {
  if (!url) return;
  window.open(url, '_blank');
}

const { columns, columnChecks, data, getData, getDataByPage, loading, mobilePagination, scrollX } =
  useNaivePaginatedTable({
    api: () => fetchGetTicketLotteryBatchTaskList(searchParams.value),
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
        minWidth: 260,
        render: row =>
          h('div', { class: 'batch-task-title' }, [
            h('div', { class: 'batch-task-title__main' }, row.taskName || '-'),
            h('div', { class: 'batch-task-title__sub' }, `批量任务ID ${row.batchTaskId}`)
          ])
      },
      { key: 'platformName', title: '平台', align: 'center', width: 140 },
      {
        key: 'sourceUrl',
        title: '集合链接',
        minWidth: 220,
        render: row =>
          h('div', { class: 'batch-task-link' }, [
            h(
              'button',
              {
                type: 'button',
                class: 'batch-task-link__button',
                onClick: () => openSourceUrl(row.sourceUrl)
              },
              row.sourceUrl || '-'
            ),
            row.sourceUrl
              ? h(
                  NButton,
                  {
                    quaternary: true,
                    size: 'tiny',
                    onClick: () => handleCopy(row.sourceUrl)
                  },
                  { default: () => '复制' }
                )
              : null
          ])
      },
      {
        key: 'boundAccountCount',
        title: '账号',
        align: 'center',
        width: 120,
        render: row => h(NTag, { size: 'small', type: 'info', bordered: false }, { default: () => `${row.boundAccountCount || 0} 个账号` })
      },
      {
        key: 'items',
        title: '活动数',
        align: 'center',
        width: 110,
        render: row => h(NTag, { size: 'small', type: 'warning', bordered: false }, { default: () => `${row.items?.length || 0} 个活动` })
      },
      {
        key: 'taskStatus',
        title: '状态',
        align: 'center',
        width: 100,
        render: row => renderTicketTag(row.taskStatus)
      },
      {
        key: 'executionSummary',
        title: '执行摘要',
        minWidth: 220,
        render: row => {
          const summary = row.executionSummary || {};
          return h('div', { class: 'batch-task-summary' }, [
            h('span', { class: 'batch-task-summary__item' }, `总 ${summary.total || 0}`),
            h('span', { class: 'batch-task-summary__item is-success' }, `提交 ${summary.submitted || 0}`),
            h('span', { class: 'batch-task-summary__item is-warn' }, `待支付 ${summary.pending_payment || 0}`),
            h('span', { class: 'batch-task-summary__item is-error' }, `失败 ${summary.failed || 0}`)
          ]);
        }
      },
      { key: 'updateTime', title: '更新时间', align: 'center', minWidth: 170 },
      {
        key: 'operate',
        title: '操作',
        align: 'center',
        width: 220,
        render: row =>
          h('div', { class: 'batch-task-operate' }, [
            hasAuth('ticket:lotteryBatchTask:query')
              ? h(NButton, { text: true, type: 'primary', onClick: () => openProcess(row) }, { default: () => '结果' })
              : null,
            hasAuth('ticket:lotteryBatchTask:execute')
              ? h(NButton, {
                  text: true,
                  type: 'warning',
                  loading: executeNowLoading.value === row.batchTaskId,
                  onClick: () => executeNow(row)
                }, { default: () => '立即执行' })
              : null,
            hasAuth('ticket:lotteryBatchTask:edit')
              ? h(NButton, { text: true, type: 'info', onClick: () => openEdit(row) }, { default: () => '编辑' })
              : null
          ])
      }
    ]
  });

onMounted(() => {
  void loadPlatformOptions();
  void getData();
});
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <NCard title="批量抽票筛选" :bordered="false" size="small" class="card-wrapper">
      <NForm inline label-placement="left" :label-width="72">
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
        <NFormItem label="任务名称">
          <NInput v-model:value="searchParams.taskName" clearable placeholder="请输入任务名称" />
        </NFormItem>
        <NFormItem label="状态">
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
            <NButton type="primary" @click="getDataByPage()">查询</NButton>
            <NButton @click="resetSearch">重置</NButton>
          </NSpace>
        </NFormItem>
      </NForm>
    </NCard>

    <NCard title="批量抽票任务" :bordered="false" size="small" class="card-wrapper sm:flex-1-hidden">
      <template #header-extra>
        <TableHeaderOperation
          v-model:columns="columnChecks"
          :loading="loading"
          :show-add="hasAuth('ticket:lotteryBatchTask:add')"
          :show-delete="false"
          @add="openAdd"
          @refresh="getData"
        />
      </template>
      <NDataTable
        v-model:checked-row-keys="checkedRowKeys"
        :columns="columns as any"
        :data="data"
        size="small"
        remote
        :loading="loading"
        :flex-height="!appStore.isMobile"
        :scroll-x="scrollX"
        :row-key="row => row.batchTaskId"
        :pagination="mobilePagination"
        class="sm:h-full"
      />
    </NCard>

    <NModal
      v-model:show="modalVisible"
      preset="card"
      :title="modalTitle"
      :style="{ width: 'min(1100px, calc(100vw - 40px))' }"
      :segmented="{ content: true }"
    >
      <div class="batch-modal">
        <NSteps :current="modalStep" size="small" class="mb-16px">
          <NStep v-for="item in modalStepItems" :key="item.title" :title="item.title" />
        </NSteps>

        <div v-if="modalStep === 1" class="batch-step-panel">
          <NAlert type="info" :bordered="false" class="mb-16px">
            批量抽票 v1 只支持 LivePocket 的 <code>/t/...</code> 集合链接。
          </NAlert>
          <NForm label-placement="top" :model="formModel">
            <NGrid :cols="24" :x-gap="16">
              <NFormItemGi :span="12" label="平台">
                <NSelect v-model:value="formModel.platformId" filterable :options="platformOptions" />
              </NFormItemGi>
              <NFormItemGi :span="12" label="任务名称">
                <NInput v-model:value="formModel.taskName" placeholder="例如：鬼灭咖啡 5/24 批量抽票" />
              </NFormItemGi>
              <NFormItemGi :span="24" label="集合链接">
                <NInput v-model:value="formModel.sourceUrl" placeholder="https://livepocket.jp/t/..." />
              </NFormItemGi>
              <NFormItemGi :span="24" label="备注">
                <NInput v-model:value="formModel.remark" type="textarea" :rows="3" placeholder="可选备注" />
              </NFormItemGi>
            </NGrid>
          </NForm>
        </div>

        <div v-else-if="modalStep === 2" class="batch-step-panel">
          <div class="batch-toolbar">
            <div class="batch-toolbar__meta">
              已解析 {{ collectionActivities.length }} 个活动，当前选择 {{ selectedActivitiesCount }} 个
            </div>
          </div>
          <NDataTable
            v-model:checked-row-keys="selectedActivityKeys"
            size="small"
            :row-key="row => row.key"
            :pagination="false"
            :scroll-x="960"
            :data="collectionActivities"
            :columns="[
              { type: 'selection', width: 48 },
              { key: 'salesType', title: '状态', width: 100, render: row => row.salesType ? h(NTag, { size: 'small', type: 'warning', bordered: false }, { default: () => row.salesType }) : '-' },
              { key: 'eventTitle', title: '活动', minWidth: 320 },
              { key: 'eventUrl', title: '链接', minWidth: 240, render: row => renderTicketEllipsis(row.eventUrl) },
              { key: 'notes', title: '说明', minWidth: 320, render: row => renderTicketEllipsis(row.notes) }
            ]"
          />
        </div>

        <div v-else-if="modalStep === 3" class="batch-step-panel">
          <div class="batch-toolbar">
            <div class="batch-toolbar__meta">已选 {{ selectedAccountCount }} 个账号，所有活动都会使用这批账号</div>
            <NInput v-model:value="accountSearchKeyword" clearable placeholder="搜索邮箱" class="w-240px" />
          </div>
          <NCheckboxGroup v-model:value="formModel.accountIds">
            <NSpace vertical size="small" class="w-full">
              <div v-for="account in filteredAccounts" :key="account.accountId" class="batch-account-card">
                <NCheckbox :value="account.accountId">
                  <div class="batch-account-card__main">
                    <div class="batch-account-card__email">{{ account.email }}</div>
                    <div class="batch-account-card__meta">
                      <span>{{ account.platformName || 'LivePocket' }}</span>
                      <span>{{ account.accountStatus }}</span>
                      <span>{{ account.loginStatus }}</span>
                    </div>
                  </div>
                </NCheckbox>
              </div>
            </NSpace>
          </NCheckboxGroup>
          <NEmpty v-if="!filteredAccounts.length && !accountLoading" description="没有可选的已激活账号" class="mt-24px" />
        </div>

        <div v-else-if="modalStep === 4" class="batch-step-panel">
          <div class="batch-toolbar">
            <div class="batch-toolbar__meta">逐个活动完成：解析场次 -> 选择受付 -> 选择场次 -> 分时段配置</div>
          </div>
          <NTabs v-model:value="activeItemKey" type="line" animated>
            <NTabPane v-for="item in itemDrafts" :key="item.eventUrl" :name="item.eventUrl" :tab="item.eventTitle">
              <div class="batch-item-card">
                <div class="batch-item-card__header">
                  <div>
                    <div class="batch-item-card__title">{{ item.eventTitle }}</div>
                    <div class="batch-item-card__sub">{{ item.eventUrl }}</div>
                  </div>
                  <NSpace>
                    <NButton size="small" :loading="item.parseLoading" @click="parseItemEvent(item)">解析场次</NButton>
                    <NButton
                      v-if="item.eventUrl"
                      size="small"
                      quaternary
                      @click="handleCopy(item.eventUrl)"
                    >
                      复制链接
                    </NButton>
                  </NSpace>
                </div>

                <template v-if="item.eventInfo">
                  <div class="batch-section">
                    <div class="batch-section__title">选择受付</div>
                    <NSpace vertical size="small" class="w-full">
                      <label
                        v-for="group in getReceptionGroups(item)"
                        :key="group.key"
                        class="batch-reception-card"
                        :class="{ 'is-active': item.selectedReceptionKey === group.key }"
                      >
                        <input
                          class="batch-reception-card__radio"
                          type="radio"
                          :checked="item.selectedReceptionKey === group.key"
                          @change="handleReceptionChange(item, group.key)"
                        />
                        <div class="batch-reception-card__body">
                          <div class="batch-reception-card__title">{{ group.label }}</div>
                          <div class="batch-reception-card__sub">共 {{ group.sessionCount }} 个抽选场次</div>
                        </div>
                      </label>
                    </NSpace>
                  </div>

                  <div v-if="item.selectedReceptionKey" class="batch-section">
                    <div class="batch-section__title">选择场次</div>
                    <NCheckboxGroup
                      :value="item.selectedSessionIds || []"
                      @update:value="value => syncItemSelectedSessions(item, value as string[])"
                    >
                      <NSpace vertical size="small" class="w-full">
                        <div v-for="session in getCurrentReceptionSessions(item)" :key="session.sessionId" class="batch-session-card">
                          <NCheckbox :value="session.sessionId">
                            <div class="batch-session-card__main">
                              <div class="batch-session-card__title">{{ session.sessionLabel }}</div>
                              <div class="batch-session-card__meta">
                                <span>{{ session.salesType || '-' }}</span>
                                <span>{{ session.receptionTitle || '-' }}</span>
                                <span>{{ session.ticketId || '-' }}</span>
                              </div>
                              <div v-if="session.notes" class="batch-session-card__notes">{{ session.notes }}</div>
                            </div>
                          </NCheckbox>
                        </div>
                      </NSpace>
                    </NCheckboxGroup>
                  </div>

                  <div v-if="item.schedules?.length" class="batch-section">
                    <div class="batch-section__header">
                      <div class="batch-section__title">分时段配置</div>
                      <NButton size="small" quaternary type="primary" @click="autoAssignItemSchedules(item)">
                        自动分配账号
                      </NButton>
                    </div>
                    <div class="batch-schedule-table">
                      <div class="batch-schedule-table__head">
                        <span>场次</span>
                        <span>执行时间</span>
                        <span>账号数量</span>
                      </div>
                      <div v-for="schedule in item.schedules" :key="schedule.sessionId" class="batch-schedule-table__row">
                        <div class="batch-schedule-table__label">{{ schedule.sessionLabel || schedule.sessionId }}</div>
                        <NDatePicker
                          v-model:formatted-value="schedule.scheduledTime"
                          type="datetime"
                          clearable
                          value-format="yyyy-MM-dd HH:mm:ss"
                          class="w-full"
                        />
                        <NInputNumber v-model:value="schedule.accountCount" :min="0" :max="selectedAccountCount || 999" />
                      </div>
                    </div>
                  </div>
                </template>

                <NEmpty v-else description="先解析该活动的场次和受付信息" class="mt-16px" />
              </div>
            </NTabPane>
          </NTabs>
        </div>

        <div v-else class="batch-step-panel">
          <NDescriptions bordered :column="1" label-placement="left">
            <NDescriptionsItem label="任务名称">{{ formModel.taskName || '-' }}</NDescriptionsItem>
            <NDescriptionsItem label="集合链接">{{ formModel.sourceUrl || '-' }}</NDescriptionsItem>
            <NDescriptionsItem label="账号数量">{{ selectedAccountCount }}</NDescriptionsItem>
            <NDescriptionsItem label="活动数量">{{ itemDrafts.length }}</NDescriptionsItem>
          </NDescriptions>
          <div class="batch-review-list">
            <div v-for="item in itemDrafts" :key="item.eventUrl" class="batch-review-card">
              <div class="batch-review-card__title">{{ item.eventTitle }}</div>
              <div class="batch-review-card__sub">{{ item.receptionTitle || item.receptionId || '未选择受付' }}</div>
              <div class="batch-review-card__meta">
                已选 {{ item.selectedSessions?.length || 0 }} 个场次 / 已配 {{ item.schedules?.length || 0 }} 个分时段
              </div>
            </div>
          </div>
        </div>
      </div>

      <template #footer>
        <div class="flex justify-end gap-12px">
          <NButton @click="modalVisible = false">取消</NButton>
          <NButton v-if="modalStep > 1" @click="handlePrevStep">上一步</NButton>
          <NButton v-if="modalStep < 5" type="primary" :loading="collectionParseLoading || accountLoading" @click="handleNextStep">
            {{ modalStep === 1 ? '解析活动' : '下一步' }}
          </NButton>
          <NButton v-else type="primary" :loading="saving" @click="handleSubmit">保存批量抽票任务</NButton>
        </div>
      </template>
    </NModal>

    <NDrawer v-model:show="processVisible" :width="900" placement="right">
      <NDrawerContent title="批量抽票任务结果" closable>
        <template v-if="processData">
          <div class="batch-process">
            <div class="batch-process__title">{{ processData.task.taskName }}</div>
            <div class="batch-process__summary">
              <div v-for="item in processSummaryItems" :key="item.key" class="batch-process-card">
                <div class="batch-process-card__label">{{ item.label }}</div>
                <div class="batch-process-card__value">{{ item.value }}</div>
              </div>
            </div>
            <div class="batch-process__steps">
              <div v-for="step in processData.createSteps || []" :key="step.stepKey" class="batch-process-step">
                <div class="batch-process-step__tag">
                  <NTag size="small" :type="step.status === 'finish' ? 'success' : step.status === 'error' ? 'error' : 'default'" :bordered="false">
                    {{ step.title }}
                  </NTag>
                </div>
                <div class="batch-process-step__desc">{{ step.description || '-' }}</div>
              </div>
            </div>
            <div class="batch-process__items">
              <div v-for="item in processData.items || []" :key="item.batchItemId || item.eventUrl" class="batch-process-item">
                <div class="batch-process-item__title">
                  {{ item.eventTitle }}
                  <span class="batch-process-item__status">{{ item.itemStatus || '-' }}</span>
                </div>
                <div class="batch-process-item__sub">{{ item.receptionTitle || item.receptionId || '-' }}</div>
                <div class="batch-process-item__sub">场次 {{ item.selectedSessions?.length || 0 }} / 分时段 {{ item.schedules?.length || 0 }}</div>
              </div>
            </div>
          </div>
        </template>
        <NSpin v-else-if="processLoading" size="small" />
        <NEmpty v-else description="暂无任务结果" />
      </NDrawerContent>
    </NDrawer>
  </div>
</template>

<style scoped>
.batch-task-title {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.batch-task-title__main {
  font-weight: 600;
  line-height: 1.4;
}

.batch-task-title__sub {
  color: var(--n-text-color-disabled);
  font-size: 12px;
}

.batch-task-link {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.batch-task-link__button {
  min-width: 0;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  background: transparent;
  border: none;
  color: var(--n-primary-color);
  cursor: pointer;
}

.batch-task-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.batch-task-summary__item {
  padding: 2px 8px;
  border-radius: 999px;
  background: rgb(148 163 184 / 12%);
  font-size: 12px;
}

.batch-task-summary__item.is-success {
  background: rgb(16 185 129 / 12%);
  color: rgb(4 120 87);
}

.batch-task-summary__item.is-warn {
  background: rgb(245 158 11 / 12%);
  color: rgb(180 83 9);
}

.batch-task-summary__item.is-error {
  background: rgb(239 68 68 / 12%);
  color: rgb(185 28 28);
}

.batch-task-operate {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
}

.batch-modal {
  max-height: min(78vh, 860px);
  overflow: auto;
}

.batch-step-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.batch-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.batch-toolbar__meta {
  color: var(--n-text-color-2);
  font-size: 13px;
}

.batch-account-card,
.batch-session-card,
.batch-reception-card,
.batch-review-card,
.batch-process-item {
  border: 1px solid var(--n-border-color);
  border-radius: 8px;
  background: var(--n-color);
}

.batch-account-card,
.batch-session-card,
.batch-review-card,
.batch-process-item {
  padding: 12px;
}

.batch-account-card__email,
.batch-item-card__title,
.batch-review-card__title,
.batch-process-item__title {
  font-weight: 600;
}

.batch-account-card__meta,
.batch-item-card__sub,
.batch-review-card__sub,
.batch-review-card__meta,
.batch-process-item__sub,
.batch-session-card__meta,
.batch-session-card__notes {
  color: var(--n-text-color-2);
  font-size: 12px;
}

.batch-account-card__meta,
.batch-session-card__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.batch-item-card {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.batch-item-card__header,
.batch-section__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.batch-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.batch-section__title {
  font-weight: 600;
}

.batch-reception-card {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px;
  cursor: pointer;
}

.batch-reception-card.is-active {
  border-color: var(--n-primary-color);
  background: rgb(59 130 246 / 6%);
}

.batch-reception-card__radio {
  margin-top: 2px;
}

.batch-reception-card__body {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.batch-reception-card__title {
  font-weight: 600;
}

.batch-reception-card__sub {
  color: var(--n-text-color-2);
  font-size: 12px;
}

.batch-schedule-table {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.batch-schedule-table__head,
.batch-schedule-table__row {
  display: grid;
  grid-template-columns: minmax(220px, 1.4fr) minmax(220px, 1fr) 140px;
  gap: 12px;
  align-items: center;
}

.batch-schedule-table__head {
  color: var(--n-text-color-2);
  font-size: 12px;
}

.batch-schedule-table__label {
  font-size: 13px;
  line-height: 1.5;
}

.batch-review-list,
.batch-process__items {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 12px;
  margin-top: 16px;
}

.batch-process {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.batch-process__title {
  font-size: 18px;
  font-weight: 700;
}

.batch-process__summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: 12px;
}

.batch-process-card {
  border: 1px solid var(--n-border-color);
  border-radius: 10px;
  padding: 12px;
}

.batch-process-card__label {
  color: var(--n-text-color-2);
  font-size: 12px;
}

.batch-process-card__value {
  margin-top: 8px;
  font-size: 24px;
  font-weight: 700;
}

.batch-process__steps {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.batch-process-step {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--n-border-color);
  border-radius: 8px;
}

.batch-process-step__desc {
  color: var(--n-text-color-2);
  font-size: 13px;
}

.batch-process-item__status {
  margin-left: 8px;
  color: var(--n-text-color-2);
  font-size: 12px;
  font-weight: 400;
}
</style>
