<script setup lang="tsx">
import { onMounted, ref } from 'vue';
import { NButton, NInput, NModal, NPopconfirm, useMessage } from 'naive-ui';
import { defaultTransform, useNaivePaginatedTable } from '@/hooks/common/table';
import {
  fetchGetTicketOrderExecutionDetail,
  fetchDeleteTicketOrderExecutions,
  fetchMarkTicketOrderExecutionPaid,
  fetchGetTicketOrderExecutionList,
  fetchGetTicketPlatformList,
  fetchGetTicketSaleTaskList
} from '@/service/api/ticket';
import { useAuth } from '@/hooks/business/auth';
import { useAppStore } from '@/store/modules/app';
import {
  executionStatusOptions,
  getTicketLabel,
  paymentStatusOptions,
  purchaseTypeOptions,
  renderTicketEllipsis,
  renderTicketJsonSummary,
  renderTicketPaymentTag,
  renderTicketTag
} from '../common';

defineOptions({
  name: 'TicketOrderExecutionList'
});

const appStore = useAppStore();
const { hasAuth } = useAuth();
const message = useMessage();

function createSearchParams(): Api.Ticket.OrderExecutionSearchParams {
  return {
    pageNum: 1,
    pageSize: 10,
    taskId: null,
    platformId: null,
    accountId: null,
    purchaseType: null,
    orderNo: null,
    executionStatus: null,
    paymentStatus: null,
    excludeCancelled: true,
    params: {}
  };
}

const searchParams = ref<Api.Ticket.OrderExecutionSearchParams>(createSearchParams());
const platformOptions = ref<{ label: string; value: CommonType.IdType }[]>([]);
const taskOptions = ref<{ label: string; value: CommonType.IdType }[]>([]);
const orderExecutionStatusOptions = executionStatusOptions.filter(item => item.value !== 'cancelled');
const detailVisible = ref(false);
const detailTitle = ref('');
const detailContent = ref('');
const detailLoadingExecutionId = ref<CommonType.IdType | null>(null);
const detailLoadingPayload = ref<'stepTrace' | 'rawResult' | null>(null);
const markPaidLoadingId = ref<CommonType.IdType | null>(null);
const checkedRowKeys = ref<CommonType.IdType[]>([]);

function parseJsonPayload(value?: string | null) {
  if (!value) return null;
  try {
    return JSON.parse(value);
  } catch {
    return value;
  }
}

function formatJsonPayload(value: unknown) {
  if (value === null || value === undefined || value === '') {
    return '-';
  }
  if (typeof value === 'string') {
    const parsed = parseJsonPayload(value);
    return typeof parsed === 'string' ? parsed : JSON.stringify(parsed, null, 2);
  }
  return JSON.stringify(value, null, 2);
}

async function openPayloadDetail(
  title: string,
  row: Api.Ticket.OrderExecution,
  payloadKey: 'stepTrace' | 'rawResult'
) {
  if (!row.executionId) return;
  detailLoadingExecutionId.value = row.executionId;
  detailLoadingPayload.value = payloadKey;
  const { data: detail, error } = await fetchGetTicketOrderExecutionDetail(row.executionId);
  detailLoadingExecutionId.value = null;
  detailLoadingPayload.value = null;
  if (error) return;
  const value = payloadKey === 'stepTrace' ? detail?.stepTrace : detail?.rawResult;
  if (!value) {
    message.warning(`${title}暂无数据`);
    return;
  }
  detailTitle.value = title;
  detailContent.value = formatJsonPayload(value);
  detailVisible.value = true;
}

function renderPayloadButton(
  title: string,
  row: Api.Ticket.OrderExecution,
  payloadKey: 'stepTrace' | 'rawResult'
) {
  const enabled = payloadKey === 'stepTrace' ? row.hasStepTrace : row.hasRawResult;
  const loading = detailLoadingExecutionId.value === row.executionId && detailLoadingPayload.value === payloadKey;
  return (
    <NButton
      size="tiny"
      quaternary
      type="primary"
      disabled={!enabled}
      loading={loading}
      onClick={() => void openPayloadDetail(title, row, payloadKey)}
    >
      查看
    </NButton>
  );
}

function renderTaskInfo(row: Api.Ticket.OrderExecution) {
  return (
    <div class="min-w-0 text-left">
      <div class="text-13px font-medium leading-20px text-text-1">{renderTicketEllipsis(row.taskName)}</div>
      <div class="mt-4px flex-y-center flex-wrap gap-6px">
        <span class="text-12px text-text-3">{row.platformName || '-'}</span>
        {renderTicketTag(row.purchaseType)}
        <span class="text-12px text-text-3">单账号 {row.purchaseQuantity || '-'}</span>
      </div>
    </div>
  );
}

function renderAccountInfo(row: Api.Ticket.OrderExecution) {
  return (
    <div class="min-w-0 text-left">
      <div class="text-13px leading-20px text-text-1">{renderTicketEllipsis(row.email)}</div>
      <div class="mt-4px text-12px text-text-3">ID {row.accountId || '-'}</div>
    </div>
  );
}

function renderOrderInfo(row: Api.Ticket.OrderExecution) {
  return (
    <div class="text-left">
      <div class="text-13px leading-20px text-text-1">{row.orderNo || '-'}</div>
      <div class="mt-4px text-12px text-text-3">{row.executedAt || row.startedAt || row.heartbeatAt || '-'}</div>
    </div>
  );
}

const orderStepAliasMap: Record<string, string> = {
  LOTTERY_ENTRY: '抽票提交',
  PURCHASE_SUBMIT: '下单提交'
};

function getOrderStepLabel(value?: string | null) {
  const raw = value ? String(value) : '';
  if (!raw) return '';
  return orderStepAliasMap[raw] || getTicketLabel(raw);
}

function getStatusHint(row: Api.Ticket.OrderExecution) {
  const executionStatus = row.executionStatus || '';
  if (['cancelled', 'submitted', 'paid', 'completed'].includes(executionStatus)) {
    return '';
  }

  const stepLabel = getOrderStepLabel(row.currentStep);
  const stepStatusLabel = getTicketLabel(row.stepStatus);
  const hasMeaningfulStep = !!stepLabel && stepLabel !== '-' && stepLabel !== '已完成';

  if (executionStatus === 'queued') {
    return hasMeaningfulStep ? `等待阶段：${stepLabel}` : '';
  }

  if (executionStatus === 'running') {
    if (hasMeaningfulStep) {
      return `当前阶段：${stepLabel}`;
    }
    return stepStatusLabel && stepStatusLabel !== '-' ? `当前阶段：${stepStatusLabel}` : '';
  }

  if (['failed', 'timeout', 'blocked'].includes(executionStatus) && hasMeaningfulStep) {
    return `停在${stepLabel}`;
  }

  return '';
}

function renderStatusInfo(row: Api.Ticket.OrderExecution) {
  const statusHint = getStatusHint(row);
  return (
    <div class="min-w-0 text-center">
      <div class="flex-y-center flex-wrap justify-center gap-6px">
        {renderTicketTag(row.executionStatus)}
        {renderTicketPaymentTag(row)}
      </div>
      {statusHint ? <div class="mt-4px text-12px leading-18px text-text-3">{statusHint}</div> : null}
    </div>
  );
}

function renderResultInfo(row: Api.Ticket.OrderExecution) {
  return (
    <div class="min-w-0 text-left">
      {row.lotteryResultStatus ? (
        <div class="mb-4px flex-y-center gap-6px text-12px">
          <span class="text-text-3">抽选结果</span>
          {renderTicketTag(row.lotteryResultStatus)}
          <span class="text-text-3">{row.lotteryResultAt || ''}</span>
        </div>
      ) : null}
      <div class="text-13px leading-20px text-text-1">{renderTicketEllipsis(row.resultMessage)}</div>
      <div class="mt-4px flex-y-center gap-8px text-12px">
        <span class="text-text-3">轨迹</span>
        {renderPayloadButton('步骤轨迹', row, 'stepTrace')}
        <span class="text-text-3">原始</span>
        {renderPayloadButton('原始结果', row, 'rawResult')}
      </div>
    </div>
  );
}

function canMarkPaid(row: Api.Ticket.OrderExecution) {
  return (
    row.purchaseType === 'lottery' &&
    row.lotteryResultStatus === 'selected' &&
    ['offline_pending', 'pending_online', 'manual_pending'].includes(row.paymentStatus)
  );
}

async function handleMarkPaid(row: Api.Ticket.OrderExecution) {
  if (!row.executionId) return;
  markPaidLoadingId.value = row.executionId;
  const { error } = await fetchMarkTicketOrderExecutionPaid(row.executionId, {
    resultMessage: '人工确认付款完成'
  });
  markPaidLoadingId.value = null;
  if (error) return;
  message.success('已标记为已支付');
  await getData();
}

const { columns, columnChecks, data, getData, getDataByPage, loading, mobilePagination, scrollX } =
  useNaivePaginatedTable({
    api: () => fetchGetTicketOrderExecutionList(searchParams.value),
    transform: response => defaultTransform(response),
    onPaginationParamsChange: params => {
      searchParams.value.pageNum = params.page;
      searchParams.value.pageSize = params.pageSize;
    },
    columns: () => [
      ...(hasAuth('ticket:orderExecution:remove')
        ? [
            {
              type: 'selection' as const,
              align: 'center' as const,
              width: 48
            }
          ]
        : []),
      {
        key: 'taskInfo',
        title: '任务',
        align: 'left',
        minWidth: 260,
        render: row => renderTaskInfo(row)
      },
      {
        key: 'accountInfo',
        title: '账号',
        align: 'left',
        minWidth: 230,
        render: row => renderAccountInfo(row)
      },
      {
        key: 'configSnapshot',
        title: '配置',
        align: 'left',
        minWidth: 220,
        render: row => renderTicketJsonSummary(row.configSnapshot, ['ticketsPageUrl', 'ticketQuantity', 'paymentMethod', 'lotteryEntryUrl'])
      },
      {
        key: 'orderInfo',
        title: '订单 / 时间',
        align: 'left',
        minWidth: 180,
        render: row => renderOrderInfo(row)
      },
      {
        key: 'statusInfo',
        title: '状态',
        align: 'center',
        minWidth: 190,
        render: row => renderStatusInfo(row)
      },
      {
        key: 'resultInfo',
        title: '结果',
        align: 'left',
        minWidth: 280,
        render: row => renderResultInfo(row)
      },
      {
        key: 'operation',
        title: '操作',
        align: 'center',
        fixed: 'right',
        minWidth: 120,
        render: row =>
          canMarkPaid(row) ? (
            <NPopconfirm onPositiveClick={() => handleMarkPaid(row)}>
              {{
                trigger: () => (
                  <NButton size="tiny" type="primary" ghost loading={markPaidLoadingId.value === row.executionId}>
                    标记已支付
                  </NButton>
                ),
                default: () => '确认已完成便利店付款？'
              }}
            </NPopconfirm>
          ) : (
            <span class="text-12px text-text-3">-</span>
          )
      }
    ]
  });

async function loadOptions() {
  const [{ data: platformList, error: platformError }, { data: saleTaskList, error: taskError }] = await Promise.all([
    fetchGetTicketPlatformList({ pageNum: 1, pageSize: 200, enabled: true }),
    fetchGetTicketSaleTaskList({ pageNum: 1, pageSize: 200 })
  ]);

  if (platformError || taskError) {
    return;
  }

  platformOptions.value = (platformList?.rows || []).map((item: Api.Ticket.Platform) => ({
    label: item.platformName,
    value: item.platformId
  }));
  taskOptions.value = (saleTaskList?.rows || []).map((item: Api.Ticket.SaleTask) => ({
    label: item.taskName,
    value: item.taskId
  }));
}

onMounted(() => {
  void getData();
  void loadOptions();
});

async function handleBatchDelete() {
  if (!checkedRowKeys.value.length) {
    message.warning('请选择需要删除的订单记录');
    return;
  }
  const { error } = await fetchDeleteTicketOrderExecutions(checkedRowKeys.value);
  if (error) return;
  message.success('订单记录已删除');
  checkedRowKeys.value = [];
  await getData();
}

function resetSearch() {
  searchParams.value = createSearchParams();
  checkedRowKeys.value = [];
  void getDataByPage();
}

</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <NCard title="订单列表筛选" :bordered="false" size="small" class="card-wrapper">
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
        <NFormItem label="任务">
          <NSelect
            v-model:value="searchParams.taskId"
            clearable
            filterable
            :options="taskOptions"
            placeholder="请选择任务"
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
        <NFormItem label="订单号">
          <NInput v-model:value="searchParams.orderNo" clearable placeholder="请输入订单号" />
        </NFormItem>
        <NFormItem label="执行状态">
          <NSelect
            v-model:value="searchParams.executionStatus"
            clearable
            :options="orderExecutionStatusOptions"
            placeholder="请选择执行状态"
            class="w-160px"
          />
        </NFormItem>
        <NFormItem label="支付状态">
          <NSelect
            v-model:value="searchParams.paymentStatus"
            clearable
            :options="paymentStatusOptions"
            placeholder="请选择支付状态"
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

    <NCard title="订单列表" :bordered="false" size="small" class="card-wrapper sm:flex-1-hidden">
      <template #header-extra>
        <TableHeaderOperation v-model:columns="columnChecks" :loading="loading" :show-add="false" :show-delete="false" @refresh="getData">
          <template #prefix>
            <NPopconfirm v-if="hasAuth('ticket:orderExecution:remove')" @positive-click="handleBatchDelete">
              <template #trigger>
                <NButton size="small" ghost type="error" :disabled="checkedRowKeys.length === 0">
                  <template #icon>
                    <icon-material-symbols-delete-outline class="text-icon" />
                  </template>
                  批量删除
                </NButton>
              </template>
              仅已结束订单可删除，非终态记录会被后端拒绝。
            </NPopconfirm>
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
        :row-key="row => row.executionId"
        :pagination="mobilePagination"
        class="sm:h-full"
      />
    </NCard>

    <NModal v-model:show="detailVisible" preset="card" :title="detailTitle" class="w-720px">
      <NInput :value="detailContent" type="textarea" :rows="18" readonly />
    </NModal>
  </div>
</template>
