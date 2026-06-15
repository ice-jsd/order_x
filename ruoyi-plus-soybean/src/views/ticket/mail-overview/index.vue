<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue';
import {
  NButton,
  NCollapseTransition,
  NDatePicker,
  NDrawer,
  NDrawerContent,
  NEmpty,
  NForm,
  NFormItem,
  NInput,
  NModal,
  NPopconfirm,
  NSelect,
  NSpin,
  NTabPane,
  NTag,
  NTabs,
  useMessage
} from 'naive-ui';
import type { FormInst, FormRules } from 'naive-ui';
import { useAuth } from '@/hooks/business/auth';
import { useDownload } from '@/hooks/business/download';
import {
  fetchDeleteTicketMailboxMailFeed,
  fetchGetTicketMailboxMailFeed,
  fetchGetTicketMailboxMailRecords,
  fetchGetTicketPlatformList,
  fetchSendTicketMailForward
} from '@/service/api/ticket';
import { handleCopy } from '@/utils/copy';

defineOptions({
  name: 'TicketMailOverview'
});

const { hasAuth } = useAuth();
const message = useMessage();
const { download } = useDownload();

const parseTypeOptions = [
  { label: '全部类型', value: '' },
  { label: '验证码', value: 'verify_code' },
  { label: '激活邮件', value: 'activation_url' },
  { label: '抽选申请邮件', value: 'lottery_applied' },
  { label: '当选邮件', value: 'lottery_selected' },
  { label: '落选邮件', value: 'lottery_rejected' },
  { label: '购入邮件', value: 'purchase_completed' },
  { label: '其他邮件', value: 'unknown' }
];

const modeLabels: Record<Api.Ticket.MailFeedMode, string> = {
  latest: '最新邮箱',
  timeline: '全部邮件'
};

const searchForm = reactive({
  mode: 'latest' as Api.Ticket.MailFeedMode,
  platformId: null as CommonType.IdType | null,
  keyword: '',
  email: '',
  parseType: '',
  beginReceivedAt: '',
  endReceivedAt: ''
});
const dateRangeReceivedAt = ref<[string, string] | null>(null);
const advancedFilterVisible = ref(false);
const platformOptions = ref<{ label: string; value: CommonType.IdType }[]>([]);

const quickFilterOptions = [
  { label: '全部类型', value: '' },
  { label: '验证码', value: 'verify_code' },
  { label: '激活', value: 'activation_url' },
  { label: '申请', value: 'lottery_applied' },
  { label: '当选', value: 'lottery_selected' },
  { label: '落选', value: 'lottery_rejected' },
  { label: '购入', value: 'purchase_completed' }
];

const feedRows = ref<Api.Ticket.MailRecord[]>([]);
const loading = ref(false);
const loadingMore = ref(false);
const total = ref(0);
const pager = reactive({
  pageNum: 1,
  pageSize: 20
});

const detailVisible = ref(false);
const detailRecord = ref<Api.Ticket.MailRecord | null>(null);
const forwardModalVisible = ref(false);
const forwardSubmitting = ref(false);
const forwardFormRef = ref<FormInst | null>(null);
const forwardSourceRecord = ref<Api.Ticket.MailRecord | null>(null);
const forwardForm = reactive<Api.Ticket.MailForwardSendParams>({
  recordId: '',
  targetEmail: ''
});
const forwardRules: FormRules = {
  targetEmail: [
    { required: true, message: '请输入目标邮箱', trigger: ['blur', 'input'] },
    { type: 'email', message: '请输入正确的邮箱地址', trigger: ['blur', 'input'] }
  ]
};

const mailboxRecordsVisible = ref(false);
const mailboxRecordsLoading = ref(false);
const mailboxRecords = ref<Api.Ticket.MailRecord[]>([]);
const mailboxRecordsPager = reactive({
  pageNum: 1,
  pageSize: 10,
  total: 0
});
const currentMailbox = ref<Pick<Api.Ticket.MailRecord, 'mailboxId' | 'email'> | null>(null);

const hasMore = computed(() => feedRows.value.length < total.value);
const canForwardMail = computed(() => hasAuth('ticket:mailForward:send'));
const canExportMail = computed(() => hasAuth('ticket:mailbox:export'));
const canDeleteMail = computed(() => hasAuth('ticket:mailRecord:remove'));
const isExportableParseType = computed(() =>
  ['lottery_applied', 'lottery_selected', 'purchase_completed'].includes(searchForm.parseType || '')
);
const exportFilename = computed(() => {
  if (searchForm.parseType === 'lottery_selected') return '邮件总览-当选.xlsx';
  if (searchForm.parseType === 'purchase_completed') return '邮件总览-购入.xlsx';
  return '邮件总览-申请.xlsx';
});
const activeParseLabel = computed(() => {
  if (!searchForm.parseType) return '全部类型';
  return parseTypeOptions.find(item => item.value === searchForm.parseType)?.label || searchForm.parseType;
});

onMounted(() => {
  void loadFeed(true);
  void loadPlatforms();
});

function buildMailFeedSearchParams(): Api.Ticket.MailFeedSearchParams {
  return {
    mode: searchForm.mode,
    platformId: searchForm.platformId,
    keyword: searchForm.keyword.trim() || null,
    email: searchForm.email.trim() || null,
    parseType: searchForm.parseType || null,
    beginReceivedAt: searchForm.beginReceivedAt || null,
    endReceivedAt: searchForm.endReceivedAt || null,
    params: {}
  };
}

async function loadFeed(reset = false) {
  if (!hasAuth('ticket:mailbox:list') || loading.value || loadingMore.value) return;
  if (reset) {
    pager.pageNum = 1;
    loading.value = true;
  } else {
    loadingMore.value = true;
  }
  const { data, error } = await fetchGetTicketMailboxMailFeed({
    ...buildMailFeedSearchParams(),
    pageNum: pager.pageNum,
    pageSize: pager.pageSize
  });
  loading.value = false;
  loadingMore.value = false;
  if (error) return;
  const rows = data?.rows || [];
  total.value = data?.total || 0;
  feedRows.value = reset ? rows : [...feedRows.value, ...rows];
}

async function loadPlatforms() {
  const { data, error } = await fetchGetTicketPlatformList({
    pageNum: 1,
    pageSize: 200,
    enabled: true
  });
  if (error) return;
  platformOptions.value = (data?.rows || []).map(item => ({
    label: item.platformName,
    value: item.platformId
  }));
}

function handleSearch() {
  void loadFeed(true);
}

function handlePlatformChange() {
  void loadFeed(true);
}

function handleReset() {
  searchForm.platformId = null;
  searchForm.keyword = '';
  searchForm.email = '';
  searchForm.parseType = '';
  searchForm.beginReceivedAt = '';
  searchForm.endReceivedAt = '';
  dateRangeReceivedAt.value = null;
  advancedFilterVisible.value = false;
  void loadFeed(true);
}

async function handleExport() {
  if (!isExportableParseType.value) {
    message.warning('请先筛选申请邮件、当选邮件或购入邮件再导出');
    return;
  }
  await download(
    '/ticket/mailbox-account/mail-feed/export',
    {
      ...buildMailFeedSearchParams()
    },
    exportFilename.value
  );
}

function handleTabChange(value: string) {
  searchForm.mode = value as Api.Ticket.MailFeedMode;
  void loadFeed(true);
}

function handleDateRangeReceivedAtUpdate(value: [string, string] | null) {
  if (value && value.length === 2) {
    [searchForm.beginReceivedAt, searchForm.endReceivedAt] = value;
    return;
  }
  searchForm.beginReceivedAt = '';
  searchForm.endReceivedAt = '';
}

function loadMore() {
  if (!hasMore.value) return;
  pager.pageNum += 1;
  void loadFeed(false);
}

const deleteMailFeedDisabled = computed(() => {
  return !searchForm.parseType || loading.value || loadingMore.value || total.value === 0;
});

async function handleDeleteMailFeed() {
  if (!searchForm.parseType) {
    message.warning('请先选择要删除的邮件类型');
    return;
  }
  const { data, error } = await fetchDeleteTicketMailboxMailFeed(buildMailFeedSearchParams());
  if (error) return;
  detailVisible.value = false;
  detailRecord.value = null;
  mailboxRecordsVisible.value = false;
  mailboxRecords.value = [];
  currentMailbox.value = null;
  message.success(`已删除 ${data || 0} 条邮件记录`);
  await loadFeed(true);
}

async function copyMailValue(value?: string | null) {
  if (!value) return;
  await handleCopy(value);
}

function shouldShowVerifyCode(record: Api.Ticket.MailRecord | null | undefined) {
  if (!record?.verifyCode) return false;
  if (record.parseType !== 'verify_code') return false;
  if (record.lotteryApplicationNo) return false;
  return true;
}

function getApplicationNoLabel(record: Api.Ticket.MailRecord | null | undefined) {
  return record?.parseType === 'purchase_completed' ? '申込番号' : '受付番号';
}

function openActivationUrl(url?: string | null) {
  if (!url) return;
  window.open(url, '_blank', 'noopener,noreferrer');
}

function openDetail(record: Api.Ticket.MailRecord) {
  detailRecord.value = record;
  detailVisible.value = true;
}

function openForwardModal(record: Api.Ticket.MailRecord) {
  forwardSourceRecord.value = record;
  forwardForm.recordId = record.recordId;
  forwardForm.targetEmail = '';
  forwardModalVisible.value = true;
}

function closeForwardModal() {
  if (forwardSubmitting.value) return;
  forwardModalVisible.value = false;
}

async function openMailboxRecordDetail(record: Api.Ticket.MailRecord) {
  mailboxRecordsVisible.value = false;
  await nextTick();
  openDetail(record);
}

async function openMailboxRecords(record: Api.Ticket.MailRecord) {
  currentMailbox.value = {
    mailboxId: record.mailboxId,
    email: record.email
  };
  mailboxRecordsPager.pageNum = 1;
  mailboxRecordsVisible.value = true;
  await loadMailboxRecords();
}

async function loadMailboxRecords() {
  if (!currentMailbox.value) return;
  mailboxRecordsLoading.value = true;
  const { data, error } = await fetchGetTicketMailboxMailRecords(currentMailbox.value.mailboxId, {
    pageNum: mailboxRecordsPager.pageNum,
    pageSize: mailboxRecordsPager.pageSize,
    params: {}
  });
  mailboxRecordsLoading.value = false;
  if (error) return;
  mailboxRecords.value = data?.rows || [];
  mailboxRecordsPager.total = data?.total || 0;
}

function getParseLabel(parseType?: string | null) {
  switch (parseType) {
    case 'verify_code':
      return '验证码';
    case 'activation_url':
      return '激活邮件';
    case 'lottery_applied':
      return '抽选申请邮件';
    case 'lottery_selected':
      return '当选邮件';
    case 'lottery_rejected':
      return '落选邮件';
    case 'purchase_completed':
      return '购入邮件';
    case 'unknown':
      return '其他邮件';
    default:
      return parseType || '未解析';
  }
}

function shouldShowParseTag(parseType?: string | null) {
  return parseType && parseType !== 'unknown';
}

function getParseTagType(parseType?: string | null) {
  switch (parseType) {
    case 'verify_code':
      return 'success';
    case 'activation_url':
      return 'info';
    case 'lottery_applied':
      return 'default';
    case 'lottery_selected':
      return 'warning';
    case 'lottery_rejected':
      return 'error';
    case 'purchase_completed':
      return 'success';
    default:
      return 'default';
  }
}

function getExcerpt(record: Api.Ticket.MailRecord) {
  return record.bodyExcerpt || record.bodyContent || record.activationUrl || '暂无内容';
}

function toggleQuickFilter(value: string) {
  searchForm.parseType = value;
  void loadFeed(true);
}

async function submitForward() {
  try {
    await forwardFormRef.value?.validate();
  } catch {
    return;
  }

  forwardSubmitting.value = true;
  const { error } = await fetchSendTicketMailForward({
    recordId: forwardForm.recordId,
    targetEmail: forwardForm.targetEmail.trim()
  });
  forwardSubmitting.value = false;

  if (error) return;

  message.success('邮件转发成功');
  forwardModalVisible.value = false;
}
</script>

<template>
  <div class="mail-overview-page">
    <div class="mail-overview-page__header">
      <div>
        <h1 class="mail-overview-page__title">邮件总览</h1>
        <p class="mail-overview-page__desc">快速查看各个邮箱的最新邮件和历史邮件流</p>
      </div>
    </div>

    <NTabs type="segment" animated class="mail-overview-tabs" :value="searchForm.mode" @update:value="handleTabChange">
      <NTabPane name="latest" tab="最新邮箱" />
      <NTabPane name="timeline" tab="全部邮件" />
    </NTabs>

    <div class="mail-overview-toolbar">
      <div class="mail-overview-toolbar__search">
        <NInput v-model:value="searchForm.keyword" clearable placeholder="搜标题、正文或验证码" />
        <NSelect
          v-model:value="searchForm.platformId"
          :options="platformOptions"
          clearable
          filterable
          placeholder="平台筛选"
          @update:value="handlePlatformChange"
        />
        <NDatePicker
          v-model:formatted-value="dateRangeReceivedAt"
          type="datetimerange"
          value-format="yyyy-MM-dd HH:mm:ss"
          clearable
          start-placeholder="收件开始"
          end-placeholder="收件结束"
          class="mail-overview-toolbar__date-range"
          @update:formatted-value="handleDateRangeReceivedAtUpdate"
        />
        <div class="mail-overview-toolbar__search-actions">
          <NButton type="primary" @click="handleSearch">查</NButton>
          <NButton
            v-if="canExportMail"
            type="primary"
            ghost
            :disabled="!isExportableParseType"
            @click="handleExport"
          >
            导出
          </NButton>
          <NPopconfirm
            v-if="canDeleteMail && searchForm.mode === 'timeline'"
            @positive-click="handleDeleteMailFeed"
          >
            <template #trigger>
              <NButton type="error" ghost :disabled="deleteMailFeedDisabled">全部删除</NButton>
            </template>
            确认删除当前筛选范围内该类型的全部邮件记录？该操作仅做逻辑删除。
          </NPopconfirm>
        </div>
      </div>
      <div class="mail-overview-toolbar__quick-filters">
        <button
          v-for="item in quickFilterOptions"
          :key="item.value || 'all'"
          type="button"
          class="mail-overview-chip"
          :class="{ 'is-active': searchForm.parseType === item.value }"
          @click="toggleQuickFilter(item.value)"
        >
          {{ item.label }}
        </button>
        <button
          type="button"
          class="mail-overview-chip mail-overview-chip--ghost"
          :class="{ 'is-active': advancedFilterVisible }"
          @click="advancedFilterVisible = !advancedFilterVisible"
        >
          {{ advancedFilterVisible ? '收起筛选' : '更多筛选' }}
        </button>
      </div>
      <NCollapseTransition :show="advancedFilterVisible">
        <div class="mail-overview-toolbar__advanced">
          <NInput v-model:value="searchForm.email" clearable placeholder="搜邮箱地址" />
          <NSelect v-model:value="searchForm.parseType" :options="parseTypeOptions" clearable placeholder="邮件类型" />
          <div class="mail-overview-toolbar__actions">
            <NButton @click="handleReset">重置</NButton>
          </div>
        </div>
      </NCollapseTransition>
    </div>

    <div class="mail-overview-toolbar__summary">
      <div class="mail-overview-toolbar__summary-main">
        <span>共 {{ total }} 条</span>
        <span>{{ modeLabels[searchForm.mode] }}</span>
      </div>
      <div class="mail-overview-toolbar__summary-sub">
        {{ activeParseLabel }}
      </div>
    </div>

    <NSpin :show="loading">
      <div v-if="feedRows.length" class="mail-overview-list">
        <article v-for="record in feedRows" :key="record.recordId" class="mail-card" @click="openDetail(record)">
          <div class="mail-card__top">
            <div class="mail-card__top-main">
              <button type="button" class="mail-card__email" @click.stop="openMailboxRecords(record)">
                {{ record.email || '-' }}
              </button>
            </div>
            <NTag
              v-if="shouldShowParseTag(record.parseType)"
              size="small"
              :type="getParseTagType(record.parseType)"
              :bordered="false"
            >
              {{ getParseLabel(record.parseType) }}
            </NTag>
          </div>

          <div class="mail-card__subject">{{ record.subject || '无标题邮件' }}</div>
          <div class="mail-card__excerpt">{{ getExcerpt(record) }}</div>

          <div class="mail-card__meta">
            <span>{{ record.receivedAt || '-' }}</span>
            <span class="mail-card__from">{{ record.fromAddress || '未知发件人' }}</span>
          </div>

          <div class="mail-card__tags">
            <NTag
              v-if="shouldShowVerifyCode(record)"
              size="small"
              type="success"
              :bordered="false"
              class="mail-card__clickable-tag"
              @click.stop="copyMailValue(record.verifyCode)"
            >
              验证码 {{ record.verifyCode }}
            </NTag>
            <NTag
              v-if="record.activationUrl"
              size="small"
              type="primary"
              :bordered="false"
              class="mail-card__clickable-tag"
              @click.stop="openActivationUrl(record.activationUrl)"
            >
              激活链接
            </NTag>
            <NTag v-if="record.lotteryResultStatus === 'selected'" size="small" type="warning" :bordered="false">
              已当选
            </NTag>
            <NTag
              v-if="record.lotteryApplicationNo"
              size="small"
              type="info"
              :bordered="false"
              class="mail-card__clickable-tag"
              @click.stop="copyMailValue(record.lotteryApplicationNo)"
            >
              {{ getApplicationNoLabel(record) }} {{ record.lotteryApplicationNo }}
            </NTag>
          </div>

          <div class="mail-card__actions">
            <span class="mail-card__detail-hint">点击查看详情</span>
          </div>
        </article>
      </div>
      <NEmpty v-else-if="!loading" description="暂无邮件记录" class="mail-overview-empty" />
    </NSpin>

    <div v-if="feedRows.length" class="mail-overview-load-more">
      <NButton secondary block :loading="loadingMore" :disabled="!hasMore" @click="loadMore">
        {{ hasMore ? '加载更多' : '已经到底了' }}
      </NButton>
    </div>

    <NDrawer v-model:show="detailVisible" placement="bottom" :height="'88vh'">
      <NDrawerContent :title="detailRecord?.subject || '邮件详情'" closable>
        <div v-if="detailRecord" class="mail-detail">
          <div class="mail-detail__meta">
            <div><span>邮箱</span><strong>{{ detailRecord.email || '-' }}</strong></div>
            <div><span>发件人</span><strong>{{ detailRecord.fromAddress || '-' }}</strong></div>
            <div><span>收件时间</span><strong>{{ detailRecord.receivedAt || '-' }}</strong></div>
            <div><span>类型</span><strong>{{ getParseLabel(detailRecord.parseType) }}</strong></div>
          </div>

          <div class="mail-detail__quick-actions">
            <NButton v-if="canForwardMail" secondary type="warning" @click="openForwardModal(detailRecord)">转发邮件</NButton>
            <NButton
              v-if="shouldShowVerifyCode(detailRecord)"
              secondary
              type="success"
              @click="copyMailValue(detailRecord.verifyCode)"
            >
              复制验证码 {{ detailRecord.verifyCode }}
            </NButton>
            <NButton
              v-if="detailRecord.lotteryApplicationNo"
              secondary
              type="info"
              @click="copyMailValue(detailRecord.lotteryApplicationNo)"
            >
              复制{{ getApplicationNoLabel(detailRecord) }} {{ detailRecord.lotteryApplicationNo }}
            </NButton>
            <NButton
              v-if="detailRecord.activationUrl"
              secondary
              type="primary"
              @click="openActivationUrl(detailRecord.activationUrl)"
            >
              打开激活链接
            </NButton>
            <NButton secondary @click="openMailboxRecords(detailRecord)">查看该邮箱记录</NButton>
          </div>

          <pre class="mail-detail__content">{{ detailRecord.bodyContent || detailRecord.bodyExcerpt || '-' }}</pre>
        </div>
      </NDrawerContent>
    </NDrawer>

    <NModal v-model:show="forwardModalVisible" preset="card" title="转发邮件" class="w-420px" @close="closeForwardModal">
      <div class="mail-forward-modal">
        <div v-if="forwardSourceRecord" class="mail-forward-modal__summary">
          <div><span>源邮箱</span><strong>{{ forwardSourceRecord.email || '-' }}</strong></div>
          <div><span>原主题</span><strong>{{ forwardSourceRecord.subject || '无标题邮件' }}</strong></div>
        </div>

        <NForm ref="forwardFormRef" label-placement="top" :model="forwardForm" :rules="forwardRules">
          <NFormItem label="目标邮箱" path="targetEmail">
            <NInput v-model:value="forwardForm.targetEmail" clearable placeholder="请输入接收转发的邮箱" />
          </NFormItem>
        </NForm>

        <div class="mail-forward-modal__actions">
          <NButton @click="closeForwardModal">取消</NButton>
          <NButton type="primary" :loading="forwardSubmitting" @click="submitForward">确认转发</NButton>
        </div>
      </div>
    </NModal>

    <NDrawer v-model:show="mailboxRecordsVisible" placement="bottom" :height="'90vh'">
      <NDrawerContent :title="currentMailbox?.email ? `${currentMailbox.email} 的邮件记录` : '邮箱邮件记录'" closable>
        <NSpin :show="mailboxRecordsLoading">
          <div v-if="mailboxRecords.length" class="mailbox-record-list">
            <article
              v-for="record in mailboxRecords"
              :key="record.recordId"
              class="mailbox-record-card"
              @click="openMailboxRecordDetail(record)"
            >
              <div class="mailbox-record-card__header">
                <div class="mailbox-record-card__subject">{{ record.subject || '无标题邮件' }}</div>
                <NTag size="small" :type="getParseTagType(record.parseType)" :bordered="false">
                  {{ getParseLabel(record.parseType) }}
                </NTag>
              </div>
              <div class="mailbox-record-card__excerpt">{{ getExcerpt(record) }}</div>
              <div class="mailbox-record-card__tags">
                <NTag
                  v-if="shouldShowVerifyCode(record)"
                  size="small"
                  type="success"
                  :bordered="false"
                  class="mail-card__clickable-tag"
                  @click.stop="copyMailValue(record.verifyCode)"
                >
                  验证码 {{ record.verifyCode }}
                </NTag>
                <NTag
                  v-if="record.activationUrl"
                  size="small"
                  type="primary"
                  :bordered="false"
                  class="mail-card__clickable-tag"
                  @click.stop="openActivationUrl(record.activationUrl)"
                >
                  激活链接
                </NTag>
                <NTag v-if="record.lotteryResultStatus === 'selected'" size="small" type="warning" :bordered="false">
                  已当选
                </NTag>
                <NTag
                  v-if="record.lotteryApplicationNo"
                  size="small"
                  type="info"
                  :bordered="false"
                  class="mail-card__clickable-tag"
                  @click.stop="copyMailValue(record.lotteryApplicationNo)"
                >
                  {{ getApplicationNoLabel(record) }} {{ record.lotteryApplicationNo }}
                </NTag>
              </div>
              <div class="mailbox-record-card__footer">
                <span>{{ record.receivedAt || '-' }}</span>
                <span class="mailbox-record-card__hint">点击查看详情</span>
              </div>
            </article>
          </div>
          <NEmpty v-else-if="!mailboxRecordsLoading" description="该邮箱暂无邮件记录" />
        </NSpin>
      </NDrawerContent>
    </NDrawer>
  </div>
</template>

<style scoped>
.mail-overview-page {
  display: flex;
  min-height: 100%;
  flex-direction: column;
  gap: 10px;
  padding: 10px;
  background: #f6f8fc;
}

.mail-overview-page__header {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding-top: 2px;
}

.mail-overview-page__title {
  margin: 0;
  color: #101828;
  font-size: 20px;
  font-weight: 700;
}

.mail-overview-page__desc {
  margin: 2px 0 0;
  color: #667085;
  font-size: 12px;
  line-height: 1.5;
}

.mail-overview-tabs {
  background: #f6f8fc;
}

.mail-overview-toolbar {
  display: flex;
  flex-direction: column;
  gap: 10px;
  border-radius: 16px;
  background: #fff;
  padding: 10px;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.06);
}

.mail-overview-toolbar__search {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 8px;
}

.mail-overview-toolbar__search-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.mail-overview-toolbar__search-actions :deep(.n-button) {
  min-width: 56px;
}

.mail-overview-toolbar__date-range {
  min-width: 0;
}

.mail-overview-toolbar__quick-filters {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding-bottom: 2px;
  scrollbar-width: none;
}

.mail-overview-toolbar__quick-filters::-webkit-scrollbar {
  display: none;
}

.mail-overview-chip {
  flex: 0 0 auto;
  border: 0;
  border-radius: 999px;
  background: #eef2ff;
  padding: 8px 12px;
  color: #3656d4;
  font-size: 12px;
  font-weight: 600;
}

.mail-overview-chip--ghost {
  background: #f4f6fb;
  color: #667085;
}

.mail-overview-chip.is-active {
  background: #2448d8;
  color: #fff;
}

.mail-overview-toolbar__advanced {
  display: grid;
  gap: 8px;
  padding-top: 2px;
}

.mail-overview-toolbar__actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.mail-overview-toolbar__summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 2px;
  color: #667085;
  font-size: 12px;
}

.mail-overview-toolbar__summary-main {
  display: flex;
  gap: 10px;
}

.mail-overview-toolbar__summary-sub {
  color: #98a2b3;
}

.mail-overview-list,
.mailbox-record-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.mail-card,
.mailbox-record-card {
  border-radius: 16px;
  background: #fff;
  padding: 12px;
  box-shadow: 0 10px 28px rgba(15, 23, 42, 0.07);
}

.mail-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.mail-card__top,
.mailbox-record-card__header,
.mailbox-record-card__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.mail-card__top-main {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
}

.mail-card__email,
.mailbox-record-card__code {
  min-width: 0;
  padding: 0;
  border: 0;
  background: transparent;
  color: #295cff;
  font-size: 12px;
  font-weight: 600;
  text-align: left;
}

.mailbox-record-card__hint {
  margin-left: auto;
  color: #295cff;
  font-size: 12px;
}

.mail-card__subject,
.mailbox-record-card__subject {
  color: #101828;
  font-size: 14px;
  font-weight: 700;
  line-height: 1.45;
  word-break: break-word;
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.mail-card__excerpt,
.mailbox-record-card__excerpt {
  display: -webkit-box;
  overflow: hidden;
  color: #475467;
  font-size: 12px;
  line-height: 1.55;
  word-break: break-word;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.mail-card__meta,
.mail-card__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  color: #667085;
  font-size: 12px;
}

.mail-card__from {
  opacity: 0.8;
}

.mail-card__tags,
.mailbox-record-card__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.mail-card__clickable-tag {
  cursor: pointer;
}

.mail-card__actions {
  align-items: center;
  justify-content: flex-start;
}

.mail-card__detail-hint {
  color: #295cff;
  font-size: 12px;
  font-weight: 600;
}

.mail-overview-load-more {
  padding-bottom: 10px;
}

.mail-overview-empty {
  border-radius: 16px;
  background: #fff;
  padding: 40px 12px;
}

.mail-detail {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.mail-detail__meta {
  display: grid;
  gap: 10px;
  border-radius: 16px;
  background: #f8fafc;
  padding: 14px;
}

.mail-detail__meta span {
  display: block;
  margin-bottom: 4px;
  color: #667085;
  font-size: 12px;
}

.mail-detail__meta strong {
  display: block;
  color: #101828;
  font-size: 14px;
  line-height: 1.5;
  word-break: break-word;
}

.mail-detail__quick-actions {
  display: grid;
  gap: 10px;
}

.mail-detail__content {
  margin: 0;
  border-radius: 16px;
  background: #111827;
  padding: 14px;
  color: #f9fafb;
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.mail-forward-modal {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.mail-forward-modal__summary {
  display: grid;
  gap: 10px;
  border-radius: 16px;
  background: #f8fafc;
  padding: 14px;
}

.mail-forward-modal__summary span {
  display: block;
  margin-bottom: 4px;
  color: #667085;
  font-size: 12px;
}

.mail-forward-modal__summary strong {
  display: block;
  color: #101828;
  font-size: 14px;
  line-height: 1.5;
  word-break: break-word;
}

.mail-forward-modal__actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

@media (min-width: 768px) {
  .mail-overview-page {
    max-width: 960px;
    margin: 0 auto;
    padding: 18px;
  }

  .mail-overview-toolbar__search {
    grid-template-columns: minmax(0, 1fr) 180px;
    align-items: center;
  }

  .mail-overview-toolbar__advanced {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .mail-overview-toolbar__actions {
    justify-self: end;
    align-self: end;
  }
}

@media (min-width: 1100px) {
  .mail-overview-toolbar__search {
    grid-template-columns: minmax(0, 1fr) 180px minmax(280px, 320px) auto;
  }
}
</style>
