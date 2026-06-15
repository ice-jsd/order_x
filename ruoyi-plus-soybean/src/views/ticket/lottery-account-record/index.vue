<script setup lang="tsx">
import { h, onMounted, ref } from 'vue';
import { NButton } from 'naive-ui';
import IconEpCopyDocument from '~icons/ep/copy-document';
import { defaultTransform, useNaivePaginatedTable } from '@/hooks/common/table';
import { fetchGetTicketOrderExecutionList, fetchGetTicketSaleTaskList } from '@/service/api/ticket';
import { useAppStore } from '@/store/modules/app';
import { handleCopy } from '@/utils/copy';
import {
  executionStatusOptions,
  lotteryResultStatusOptions,
  paymentStatusOptions,
  renderTicketEllipsis,
  renderTicketPaymentTag,
  renderTicketTag
} from '../common';

defineOptions({
  name: 'TicketLotteryAccountRecord'
});

const appStore = useAppStore();

function createSearchParams(): Api.Ticket.OrderExecutionSearchParams {
  return {
    pageNum: 1,
    pageSize: 10,
    taskId: null,
    platformId: null,
    accountId: null,
    email: null,
    purchaseType: 'lottery',
    orderNo: null,
    executionStatus: null,
    paymentStatus: null,
    lotteryResultStatus: null,
    excludeCancelled: false,
    params: {}
  };
}

const searchParams = ref<Api.Ticket.OrderExecutionSearchParams>(createSearchParams());
const taskOptions = ref<{ label: string; value: CommonType.IdType }[]>([]);

function renderAccountInfo(row: Api.Ticket.OrderExecution) {
  return (
    <div class="min-w-0 text-left">
      <div class="text-13px leading-20px text-text-1">{renderTicketEllipsis(row.email)}</div>
      <div class="mt-4px text-12px text-text-3">ID {row.accountId || '-'}</div>
    </div>
  );
}

async function copyLink(event: MouseEvent, value?: string | null) {
  event.preventDefault();
  event.stopPropagation();
  if (!value) return;
  await handleCopy(value);
}

function renderLink(value?: string | null) {
  if (!value) {
    return <span class="text-12px text-text-3">-</span>;
  }
  return (
    <div class="lottery-record-link">
      <a class="lottery-record-link__text" href={value} target="_blank" rel="noopener noreferrer" title={value}>
        {value}
      </a>
      <NButton text size="tiny" focusable={false} onClick={(event: MouseEvent) => copyLink(event, value)}>
        {{
          icon: () => h(IconEpCopyDocument, { class: 'text-14px' })
        }}
      </NButton>
    </div>
  );
}

function renderEventInfo(row: Api.Ticket.OrderExecution) {
  return (
    <div class="min-w-0 text-left">
      <div class="text-13px font-medium leading-20px text-text-1">{renderTicketEllipsis(row.eventTitle || row.taskName)}</div>
      <div class="mt-6px">{renderLink(row.eventUrl)}</div>
    </div>
  );
}

function renderSessionInfo(row: Api.Ticket.OrderExecution) {
  return (
    <div class="min-w-0 text-left">
      <div class="text-13px leading-20px text-text-1">{renderTicketEllipsis(row.lotterySessionLabel)}</div>
      <div class="mt-4px text-12px text-text-3">{row.lotteryScheduledTime || '-'}</div>
    </div>
  );
}

function renderStatusInfo(row: Api.Ticket.OrderExecution) {
  return (
    <div class="flex-y-center flex-wrap gap-6px">
      {renderTicketTag(row.executionStatus)}
      {renderTicketTag(row.stepStatus)}
      {renderTicketPaymentTag(row)}
    </div>
  );
}

function renderLotteryResult(row: Api.Ticket.OrderExecution) {
  return (
    <div class="min-w-0 text-left">
      <div class="text-13px leading-20px text-text-1">{row.orderNo || '-'}</div>
      <div class="mt-4px flex-y-center flex-wrap gap-6px">
        {row.lotteryResultStatus ? renderTicketTag(row.lotteryResultStatus) : <span class="text-12px text-text-3">未标记</span>}
        {row.lotteryResultAt ? <span class="text-12px text-text-3">{row.lotteryResultAt}</span> : null}
      </div>
    </div>
  );
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
      {
        key: 'account',
        title: '账号',
        align: 'left',
        minWidth: 230,
        render: row => renderAccountInfo(row)
      },
      {
        key: 'event',
        title: '活动链接',
        align: 'left',
        minWidth: 340,
        render: row => renderEventInfo(row)
      },
      {
        key: 'ticketEntryUrl',
        title: '抽票入口',
        align: 'left',
        minWidth: 320,
        render: row => renderLink(row.ticketEntryUrl)
      },
      {
        key: 'session',
        title: '场次',
        align: 'left',
        minWidth: 180,
        render: row => renderSessionInfo(row)
      },
      {
        key: 'status',
        title: '状态 / 支付',
        align: 'left',
        minWidth: 190,
        render: row => renderStatusInfo(row)
      },
      {
        key: 'lotteryResult',
        title: '申込番号 / 当选',
        align: 'left',
        minWidth: 190,
        render: row => renderLotteryResult(row)
      },
      {
        key: 'executedAt',
        title: '执行时间',
        align: 'left',
        minWidth: 170,
        render: row => row.executedAt || row.startedAt || row.heartbeatAt || '-'
      }
    ]
  });

async function loadTaskOptions() {
  const { data: taskList, error } = await fetchGetTicketSaleTaskList({ pageNum: 1, pageSize: 200, purchaseType: 'lottery' });
  if (error) return;
  taskOptions.value = (taskList?.rows || []).map((item: Api.Ticket.SaleTask) => ({
    label: item.taskName,
    value: item.taskId
  }));
}

function resetSearch() {
  searchParams.value = createSearchParams();
  void getDataByPage();
}

onMounted(() => {
  void getData();
  void loadTaskOptions();
});
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <NCard title="账号抽票记录筛选" :bordered="false" size="small" class="card-wrapper">
      <NForm inline label-placement="left" :label-width="84">
        <NFormItem label="邮箱">
          <NInput v-model:value="searchParams.email" clearable placeholder="请输入邮箱" class="w-220px" />
        </NFormItem>
        <NFormItem label="任务">
          <NSelect
            v-model:value="searchParams.taskId"
            clearable
            filterable
            :options="taskOptions"
            placeholder="请选择任务"
            class="w-220px"
          />
        </NFormItem>
        <NFormItem label="执行状态">
          <NSelect
            v-model:value="searchParams.executionStatus"
            clearable
            :options="executionStatusOptions"
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
        <NFormItem label="当选状态">
          <NSelect
            v-model:value="searchParams.lotteryResultStatus"
            clearable
            :options="lotteryResultStatusOptions"
            placeholder="请选择当选状态"
            class="w-160px"
          />
        </NFormItem>
        <NFormItem label="申込番号">
          <NInput v-model:value="searchParams.orderNo" clearable placeholder="请输入申込番号" class="w-180px" />
        </NFormItem>
        <NFormItem>
          <NSpace>
            <NButton type="primary" @click="getDataByPage()">查询</NButton>
            <NButton @click="resetSearch">重置</NButton>
          </NSpace>
        </NFormItem>
      </NForm>
    </NCard>

    <NCard title="账号抽票记录" :bordered="false" size="small" class="card-wrapper sm:flex-1-hidden">
      <template #header-extra>
        <TableHeaderOperation v-model:columns="columnChecks" :loading="loading" :show-add="false" :show-delete="false" @refresh="getData" />
      </template>
      <NDataTable
        :columns="columns"
        :data="data"
        size="small"
        remote
        :loading="loading"
        :flex-height="!appStore.isMobile"
        :scroll-x="scrollX"
        :pagination="mobilePagination"
        class="sm:h-full"
      />
    </NCard>
  </div>
</template>

<style scoped>
.lottery-record-link {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 6px;
}

.lottery-record-link__text {
  min-width: 0;
  max-width: 280px;
  overflow: hidden;
  color: #2563eb;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
