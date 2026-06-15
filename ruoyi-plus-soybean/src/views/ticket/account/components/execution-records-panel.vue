<script setup lang="tsx">
import { h, onMounted, onUnmounted, ref, watch } from 'vue';
import { NButton, NCard, NInput, NTooltip } from 'naive-ui';
import {
  fetchGetTicketAccountLastNameRecordList,
  fetchGetTicketLoginBatchDetails,
  fetchGetTicketLoginBatchList,
  fetchGetTicketPlatformList,
  fetchGetTicketRegistrationBatchDetails,
  fetchGetTicketRegistrationBatchList,
  fetchSubmitTicketLoginEmailCode,
  fetchUpdateTicketLoginBatchStatus,
  fetchUpdateTicketRegistrationBatchStatus
} from '@/service/api/ticket';
import { useAuth } from '@/hooks/business/auth';
import { auditStatusOptions, batchStatusOptions, renderTicketEllipsis, renderTicketTag } from '../../common';

defineOptions({
  name: 'TicketExecutionRecordsPanel'
});

withDefaults(
  defineProps<{
    embedded?: boolean;
  }>(),
  {
    embedded: false
  }
);

const activeTab = ref<'register' | 'login' | 'lastName'>('register');
const { hasAuth } = useAuth();
const platformOptions = ref<{ label: string; value: CommonType.IdType }[]>([]);
const registrationLoading = ref(false);
const loginLoading = ref(false);
const lastNameLoading = ref(false);
const detailVisible = ref(false);
const detailTitle = ref('');
const detailRows = ref<Array<Api.Ticket.RegistrationBatchDetail | Api.Ticket.LoginBatchDetail>>([]);
const detailType = ref<'register' | 'login' | ''>('');
const detailBatchId = ref<CommonType.IdType | null>(null);
const detailLoading = ref(false);
const emailCodeInputs = ref<Record<string, string>>({});
const emailCodeSubmitting = ref<Record<string, boolean>>({});
const statusModalVisible = ref(false);
const statusSaving = ref(false);
const statusTarget = ref<{
  type: 'register' | 'login';
  batchId: CommonType.IdType;
  batchNo: string;
  oldStatus: string;
} | null>(null);
const statusForm = ref<Api.Ticket.BatchStatusUpdateParams>({
  batchStatus: 'completed',
  remark: ''
});

const registrationSearch = ref<Api.Ticket.RegistrationBatchSearchParams>({
  pageNum: 1,
  pageSize: 20,
  platformId: null,
  batchNo: null,
  batchStatus: null,
  params: {}
});
const loginSearch = ref<Api.Ticket.LoginBatchSearchParams>({
  pageNum: 1,
  pageSize: 20,
  platformId: null,
  batchNo: null,
  batchStatus: null,
  params: {}
});
const lastNameSearch = ref<Api.Ticket.AuditLogSearchParams>({
  pageNum: 1,
  pageSize: 20,
  moduleName: 'account',
  actionType: 'updateLastName',
  businessType: 'account',
  auditStatus: null,
  params: {}
});
const registrationRows = ref<Api.Ticket.RegistrationBatch[]>([]);
const loginRows = ref<Api.Ticket.LoginBatch[]>([]);
const lastNameRows = ref<Api.Ticket.AuditLog[]>([]);

const batchStatusEditOptions = batchStatusOptions.filter(item => item.value !== 'draft');

const registrationColumns = [
  { key: 'batchNo', title: '批次号', minWidth: 180, render: (row: Api.Ticket.RegistrationBatch) => renderTicketEllipsis(row.batchNo) },
  { key: 'platformName', title: '平台', width: 140 },
  { key: 'batchStatus', title: '状态', width: 110, render: (row: Api.Ticket.RegistrationBatch) => renderTicketTag(row.batchStatus) },
  { key: 'totalCount', title: '总数', width: 80 },
  { key: 'successCount', title: '成功', width: 80 },
  { key: 'failedCount', title: '失败', width: 80 },
  { key: 'skippedCount', title: '跳过', width: 80 },
  { key: 'executedAt', title: '完成时间', minWidth: 160 },
  {
    key: 'operate',
    title: '操作',
    width: 150,
    render: (row: Api.Ticket.RegistrationBatch) =>
      h('div', { class: 'flex items-center justify-center gap-8px' }, [
        h(NButton, { text: true, type: 'primary', onClick: () => openRegistrationDetails(row) }, { default: () => '明细' }),
        hasAuth('ticket:account:edit')
          ? h(NButton, { text: true, type: 'warning', onClick: () => openStatusModal('register', row) }, { default: () => '改状态' })
          : null
      ])
  }
];

const loginColumns = [
  { key: 'batchNo', title: '批次号', minWidth: 180, render: (row: Api.Ticket.LoginBatch) => renderTicketEllipsis(row.batchNo) },
  { key: 'platformName', title: '平台', width: 140 },
  { key: 'batchStatus', title: '状态', width: 110, render: (row: Api.Ticket.LoginBatch) => renderTicketTag(row.batchStatus) },
  { key: 'totalCount', title: '总数', width: 80 },
  { key: 'successCount', title: '成功', width: 80 },
  { key: 'failedCount', title: '失败', width: 80 },
  { key: 'executedAt', title: '完成时间', minWidth: 160 },
  {
    key: 'operate',
    title: '操作',
    width: 150,
    render: (row: Api.Ticket.LoginBatch) =>
      h('div', { class: 'flex items-center justify-center gap-8px' }, [
        h(NButton, { text: true, type: 'primary', onClick: () => openLoginDetails(row) }, { default: () => '明细' }),
        hasAuth('ticket:account:edit')
          ? h(NButton, { text: true, type: 'warning', onClick: () => openStatusModal('login', row) }, { default: () => '改状态' })
          : null
      ])
  }
];

type LastNamePayload = {
  accountId?: CommonType.IdType;
  email?: string;
  lastName?: string;
  message?: string;
};

function parseLastNamePayload(row: Api.Ticket.AuditLog): LastNamePayload {
  if (!row.payload) return {};
  try {
    return JSON.parse(row.payload) as LastNamePayload;
  } catch {
    return {};
  }
}

const lastNameStatusOptions = [{ label: '执行中', value: 'running' }, ...auditStatusOptions];
let detailPollTimer: number | null = null;

const lastNameColumns = [
  { key: 'eventTime', title: '执行时间', minWidth: 160 },
  { key: 'businessKey', title: '账号ID', minWidth: 150, render: (row: Api.Ticket.AuditLog) => row.businessKey || '-' },
  { key: 'email', title: '邮箱', minWidth: 220, render: (row: Api.Ticket.AuditLog) => renderTicketEllipsis(parseLastNamePayload(row).email) },
  { key: 'lastName', title: '姓', width: 120, render: (row: Api.Ticket.AuditLog) => parseLastNamePayload(row).lastName || '-' },
  { key: 'auditStatus', title: '状态', width: 110, render: (row: Api.Ticket.AuditLog) => renderTicketTag(row.auditStatus) },
  { key: 'message', title: '结果', minWidth: 260, render: (row: Api.Ticket.AuditLog) => renderTicketEllipsis(row.message) },
  {
    key: 'payloadMessage',
    title: '详情',
    minWidth: 260,
    render: (row: Api.Ticket.AuditLog) => renderTicketEllipsis(parseLastNamePayload(row).message || row.message)
  }
];

function renderDetailMessage(value?: string | null) {
  const text = value || '-';
  return h(
    NTooltip,
    { trigger: 'hover', placement: 'top', style: { maxWidth: '520px' } },
    {
      trigger: () =>
        h(
          'div',
          {
            class: 'registration-detail-message',
            style: {
              width: '100%',
              minWidth: 0,
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap'
            }
          },
          text
        ),
      default: () => text
    }
  );
}

function shouldPollLoginDetails() {
  return detailType.value === 'login' && detailRows.value.some(row => ['processing', 'waiting_code'].includes(row.executeStatus));
}

function stopDetailPolling() {
  if (detailPollTimer) {
    window.clearInterval(detailPollTimer);
    detailPollTimer = null;
  }
}

function startDetailPolling() {
  stopDetailPolling();
  if (detailType.value !== 'login') {
    return;
  }
  detailPollTimer = window.setInterval(() => {
    if (!detailVisible.value || !detailBatchId.value || !shouldPollLoginDetails()) {
      stopDetailPolling();
      return;
    }
    void reloadLoginDetails();
  }, 2000);
}

async function reloadLoginDetails(showLoading = false) {
  if (!detailBatchId.value) {
    return;
  }
  if (showLoading) {
    detailLoading.value = true;
  }
  const { data, error } = await fetchGetTicketLoginBatchDetails(detailBatchId.value);
  if (showLoading) {
    detailLoading.value = false;
  }
  if (!error) {
    detailRows.value = data || [];
    if (!shouldPollLoginDetails()) {
      stopDetailPolling();
    }
  }
}

async function submitEmailCode(row: Api.Ticket.LoginBatchDetail) {
  const detailId = row.detailId;
  const verifyCode = String(emailCodeInputs.value[detailId] || '').trim();
  if (!verifyCode) {
    window.$message?.warning('请输入验证码');
    return;
  }
  emailCodeSubmitting.value = { ...emailCodeSubmitting.value, [detailId]: true };
  const { error } = await fetchSubmitTicketLoginEmailCode(row.batchId, detailId, { verifyCode });
  emailCodeSubmitting.value = { ...emailCodeSubmitting.value, [detailId]: false };
  if (error) {
    return;
  }
  emailCodeInputs.value = { ...emailCodeInputs.value, [detailId]: '' };
  window.$message?.success('验证码已提交');
  await reloadLoginDetails();
  startDetailPolling();
}

async function reloadCurrentDetails() {
  if (!detailBatchId.value || !detailType.value) {
    return;
  }
  detailLoading.value = true;
  if (detailType.value === 'login') {
    const { data, error } = await fetchGetTicketLoginBatchDetails(detailBatchId.value);
    detailLoading.value = false;
    if (!error) {
      detailRows.value = data || [];
      if (shouldPollLoginDetails()) {
        startDetailPolling();
      } else {
        stopDetailPolling();
      }
    }
    return;
  }
  const { data, error } = await fetchGetTicketRegistrationBatchDetails(detailBatchId.value);
  detailLoading.value = false;
  if (!error) {
    detailRows.value = data || [];
  }
}

function renderEmailCodeControl(row: any) {
  if (row.executeStatus !== 'waiting_code') {
    return '-';
  }
  const detailId = row.detailId;
  return h('div', { class: 'flex items-center justify-center gap-8px' }, [
    h(NInput, {
      value: emailCodeInputs.value[detailId] || '',
      size: 'small',
      placeholder: '验证码',
      maxlength: 12,
      style: { width: '160px' },
      onUpdateValue: (value: string) => {
        emailCodeInputs.value = { ...emailCodeInputs.value, [detailId]: value };
      }
    }),
    h(
      NButton,
      {
        size: 'small',
        type: 'primary',
        loading: Boolean(emailCodeSubmitting.value[detailId]),
        onClick: () => submitEmailCode(row as Api.Ticket.LoginBatchDetail)
      },
      { default: () => '提交' }
    )
  ]);
}

const detailColumns = [
  { key: 'accountId', title: '账号ID', width: 150 },
  { key: 'email', title: '邮箱', width: 220, render: (row: any) => renderTicketEllipsis(row.email) },
  { key: 'executeStatus', title: '状态', width: 100, render: (row: any) => renderTicketTag(row.executeStatus) },
  { key: 'resultMessage', title: '结果', width: 300, render: (row: any) => renderDetailMessage(row.resultMessage) },
  { key: 'emailCode', title: '邮箱验证码', width: 300, render: (row: any) => renderEmailCodeControl(row) },
  { key: 'executedAt', title: '执行时间', width: 170 }
];

async function loadPlatformOptions() {
  const { data, error } = await fetchGetTicketPlatformList({ pageNum: 1, pageSize: 200, params: {} });
  if (!error) {
    platformOptions.value = (data.rows || []).map(item => ({ label: item.platformName, value: item.platformId }));
  }
}

async function loadRegistrationRows() {
  registrationLoading.value = true;
  const { data, error } = await fetchGetTicketRegistrationBatchList(registrationSearch.value);
  registrationLoading.value = false;
  if (!error) registrationRows.value = data.rows || [];
}

async function loadLoginRows() {
  loginLoading.value = true;
  const { data, error } = await fetchGetTicketLoginBatchList(loginSearch.value);
  loginLoading.value = false;
  if (!error) loginRows.value = data.rows || [];
}

async function loadLastNameRows() {
  lastNameLoading.value = true;
  const { data, error } = await fetchGetTicketAccountLastNameRecordList(lastNameSearch.value);
  lastNameLoading.value = false;
  if (!error) lastNameRows.value = data.rows || [];
}

async function openRegistrationDetails(row: Api.Ticket.RegistrationBatch) {
  stopDetailPolling();
  detailType.value = 'register';
  detailBatchId.value = row.batchId;
  detailTitle.value = `注册明细：${row.batchNo}`;
  detailLoading.value = true;
  const { data, error } = await fetchGetTicketRegistrationBatchDetails(row.batchId);
  detailLoading.value = false;
  if (!error) {
    detailRows.value = data || [];
    detailVisible.value = true;
  }
}

async function openLoginDetails(row: Api.Ticket.LoginBatch) {
  stopDetailPolling();
  detailType.value = 'login';
  detailBatchId.value = row.batchId;
  detailTitle.value = `登录明细：${row.batchNo}`;
  detailLoading.value = true;
  const { data, error } = await fetchGetTicketLoginBatchDetails(row.batchId);
  detailLoading.value = false;
  if (!error) {
    detailRows.value = data || [];
    detailVisible.value = true;
    startDetailPolling();
  }
}

function openStatusModal(type: 'register' | 'login', row: Api.Ticket.RegistrationBatch | Api.Ticket.LoginBatch) {
  statusTarget.value = {
    type,
    batchId: row.batchId,
    batchNo: row.batchNo,
    oldStatus: row.batchStatus || '-'
  };
  statusForm.value = {
    batchStatus: row.batchStatus || 'completed',
    remark: ''
  };
  statusModalVisible.value = true;
}

async function submitStatusUpdate() {
  if (!statusTarget.value) {
    return;
  }
  if (!statusForm.value.batchStatus) {
    window.$message?.warning('请选择状态');
    return;
  }
  statusSaving.value = true;
  const request =
    statusTarget.value.type === 'register'
      ? fetchUpdateTicketRegistrationBatchStatus(statusTarget.value.batchId, statusForm.value)
      : fetchUpdateTicketLoginBatchStatus(statusTarget.value.batchId, statusForm.value);
  const { error } = await request;
  statusSaving.value = false;
  if (error) return;
  window.$message?.success('批次状态已更新');
  statusModalVisible.value = false;
  if (statusTarget.value.type === 'register') {
    await loadRegistrationRows();
  } else {
    await loadLoginRows();
  }
}

onMounted(() => {
  void loadPlatformOptions();
  void loadRegistrationRows();
  void loadLoginRows();
  void loadLastNameRows();
});

watch(detailVisible, visible => {
  if (!visible) {
    stopDetailPolling();
  } else if (detailType.value === 'login') {
    startDetailPolling();
  }
});

onUnmounted(() => {
  stopDetailPolling();
});
</script>

<template>
  <div class="execution-records-panel">
    <component
      :is="embedded ? 'div' : NCard"
      v-bind="embedded ? { class: 'execution-records-panel__embedded' } : { title: '执行记录', bordered: false, size: 'small', class: 'card-wrapper sm:flex-1-hidden' }"
    >
      <div class="execution-records-panel__content">
        <NTabs v-model:value="activeTab" type="line" animated class="min-h-0 flex-col-stretch">
          <NTabPane name="register" tab="批量注册">
            <NForm inline label-placement="left" :label-width="72" class="mb-12px">
              <NFormItem label="平台">
                <NSelect v-model:value="registrationSearch.platformId" clearable filterable :options="platformOptions" class="w-180px" />
              </NFormItem>
              <NFormItem label="状态">
                <NSelect v-model:value="registrationSearch.batchStatus" clearable :options="batchStatusOptions" class="w-160px" />
              </NFormItem>
              <NFormItem>
                <NButton type="primary" @click="loadRegistrationRows">查询</NButton>
              </NFormItem>
            </NForm>
            <NDataTable :columns="registrationColumns" :data="registrationRows" :loading="registrationLoading" size="small" />
          </NTabPane>
          <NTabPane name="login" tab="批量登录">
            <NForm inline label-placement="left" :label-width="72" class="mb-12px">
              <NFormItem label="平台">
                <NSelect v-model:value="loginSearch.platformId" clearable filterable :options="platformOptions" class="w-180px" />
              </NFormItem>
              <NFormItem label="状态">
                <NSelect v-model:value="loginSearch.batchStatus" clearable :options="batchStatusOptions" class="w-160px" />
              </NFormItem>
              <NFormItem>
                <NButton type="primary" @click="loadLoginRows">查询</NButton>
              </NFormItem>
            </NForm>
            <NDataTable :columns="loginColumns" :data="loginRows" :loading="loginLoading" size="small" />
          </NTabPane>
          <NTabPane name="lastName" tab="改姓记录">
            <NForm inline label-placement="left" :label-width="72" class="mb-12px">
              <NFormItem label="状态">
                <NSelect v-model:value="lastNameSearch.auditStatus" clearable :options="lastNameStatusOptions" class="w-160px" />
              </NFormItem>
              <NFormItem>
                <NButton type="primary" @click="loadLastNameRows">查询</NButton>
              </NFormItem>
            </NForm>
            <NDataTable :columns="lastNameColumns" :data="lastNameRows" :loading="lastNameLoading" size="small" />
          </NTabPane>
        </NTabs>
      </div>
    </component>

    <NModal v-model:show="detailVisible" preset="card" :title="detailTitle" class="w-1320px max-w-96vw">
      <div class="mb-12px flex justify-end">
        <NButton size="small" :loading="detailLoading" @click="reloadCurrentDetails">刷新状态</NButton>
      </div>
      <NDataTable
        :columns="detailColumns"
        :data="detailRows"
        :loading="detailLoading"
        size="small"
        virtual-scroll
        :max-height="560"
        :scroll-x="1240"
      />
    </NModal>

    <NModal v-model:show="statusModalVisible" preset="card" title="修改执行状态" class="w-420px">
      <NForm label-placement="top" :model="statusForm">
        <NFormItem label="批次号">
          <NInput :value="statusTarget?.batchNo" disabled />
        </NFormItem>
        <NFormItem label="当前状态">
          <NInput :value="statusTarget?.oldStatus" disabled />
        </NFormItem>
        <NFormItem label="目标状态">
          <NSelect v-model:value="statusForm.batchStatus" :options="batchStatusEditOptions" />
        </NFormItem>
        <NFormItem label="备注">
          <NInput v-model:value="statusForm.remark" type="textarea" placeholder="例如：线上任务已确认结束，手动收口" />
        </NFormItem>
      </NForm>
      <template #footer>
        <div class="flex justify-end gap-12px">
          <NButton @click="statusModalVisible = false">取消</NButton>
          <NButton type="primary" :loading="statusSaving" @click="submitStatusUpdate">保存</NButton>
        </div>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.execution-records-panel {
  min-height: 0;
}

.execution-records-panel__embedded {
  height: 100%;
  min-height: 0;
}

.execution-records-panel__content {
  min-height: 0;
}

.registration-detail-message {
  width: 100%;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: help;
}
</style>
