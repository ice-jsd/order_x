<script setup lang="tsx">
import { onMounted, ref } from 'vue';
import { NButton, NPopconfirm } from 'naive-ui';
import { useAuth } from '@/hooks/business/auth';
import { defaultTransform, useNaivePaginatedTable } from '@/hooks/common/table';
import {
  fetchGetTicketOrderExecutionList,
  fetchGetTicketPlatformList,
  fetchGetTicketSaleTaskList,
  fetchMarkTicketOrderExecutionPaid
} from '@/service/api/ticket';
import { useAppStore } from '@/store/modules/app';
import {
  executionStatusOptions,
  paymentStatusOptions,
  renderTicketEllipsis,
  renderTicketJsonSummary,
  renderTicketTag
} from '../common';

defineOptions({
  name: 'TicketOrderExecutionList'
});

const appStore = useAppStore();
const { hasAuth } = useAuth();

function createSearchParams(): Api.Ticket.OrderExecutionSearchParams {
  return {
    pageNum: 1,
    pageSize: 10,
    taskId: null,
    platformId: null,
    accountId: null,
    productId: null,
    orderNo: null,
    executionStatus: null,
    paymentStatus: null,
    params: {}
  };
}

const searchParams = ref<Api.Ticket.OrderExecutionSearchParams>(createSearchParams());
const platformOptions = ref<{ label: string; value: CommonType.IdType }[]>([]);
const taskOptions = ref<{ label: string; value: CommonType.IdType }[]>([]);

const { columns, columnChecks, data, getData, getDataByPage, loading, mobilePagination, scrollX } =
  useNaivePaginatedTable({
    api: () => fetchGetTicketOrderExecutionList(searchParams.value),
    transform: response => defaultTransform(response),
    onPaginationParamsChange: params => {
      searchParams.value.pageNum = params.page;
      searchParams.value.pageSize = params.pageSize;
    },
    columns: () => [
      { key: 'taskName', title: '抢购任务', align: 'center', minWidth: 180 },
      { key: 'platformName', title: '平台', align: 'center', minWidth: 140 },
      { key: 'productId', title: '商品ID', align: 'center', minWidth: 180 },
      {
        key: 'flowType',
        title: '下单方式',
        align: 'center',
        width: 110,
        render: row => renderTicketTag(row.flowType)
      },
      {
        key: 'fulfillmentType',
        title: '履约方式',
        align: 'center',
        width: 110,
        render: row => renderTicketTag(row.fulfillmentType)
      },
      {
        key: 'paymentMode',
        title: '支付方式',
        align: 'center',
        width: 110,
        render: row => renderTicketTag(row.paymentMode)
      },
      { key: 'purchaseQuantity', title: '订单数量', align: 'center', width: 100 },
      { key: 'accountId', title: '账号ID', align: 'center', minWidth: 120 },
      {
        key: 'email',
        title: '邮箱',
        align: 'center',
        minWidth: 220,
        render: row => renderTicketEllipsis(row.email)
      },
      {
        key: 'accountInfo',
        title: '账号信息',
        align: 'center',
        minWidth: 220,
        render: row => renderTicketJsonSummary(row.accountInfo, ['email', 'nickname', 'source'])
      },
      {
        key: 'reqData',
        title: '请求上下文',
        align: 'center',
        minWidth: 220,
        render: row => renderTicketJsonSummary(row.reqData, ['sessionId', 'platformCode', 'loginAt'])
      },
      { key: 'orderNo', title: '订单号', align: 'center', minWidth: 180 },
      {
        key: 'executionStatus',
        title: '执行状态',
        align: 'center',
        width: 110,
        render: row => renderTicketTag(row.executionStatus)
      },
      {
        key: 'currentStep',
        title: '当前步骤',
        align: 'center',
        width: 120,
        render: row => renderTicketTag(row.currentStep)
      },
      {
        key: 'stepStatus',
        title: '步骤状态',
        align: 'center',
        width: 100,
        render: row => renderTicketTag(row.stepStatus)
      },
      {
        key: 'paymentStatus',
        title: '支付状态',
        align: 'center',
        width: 120,
        render: row => renderTicketTag(row.paymentStatus)
      },
      {
        key: 'resultMessage',
        title: '结果信息',
        align: 'center',
        minWidth: 220,
        render: row => renderTicketEllipsis(row.resultMessage)
      },
      {
        key: 'stepTrace',
        title: '步骤轨迹',
        align: 'center',
        minWidth: 220,
        render: row => renderTicketJsonSummary(row.stepTrace)
      },
      {
        key: 'rawResult',
        title: '原始结果',
        align: 'center',
        minWidth: 220,
        render: row => renderTicketJsonSummary(row.rawResult)
      },
      { key: 'executedAt', title: '执行时间', align: 'center', minWidth: 160 },
      {
        key: 'operate',
        title: '操作',
        align: 'center',
        width: 120,
        render: row => (
          <div class="flex-center gap-8px">
            {hasAuth('ticket:orderExecution:edit') && ['submitted', 'pending_payment'].includes(row.executionStatus) && (
              <NPopconfirm onPositiveClick={() => handleMarkPaid(row.executionId)}>
                {{
                  trigger: () => (
                    <NButton text type="primary">
                      标记已支付
                    </NButton>
                  ),
                  default: () => '确认将该订单标记为已支付吗？'
                }}
              </NPopconfirm>
            )}
          </div>
        )
      }
    ]
  });

async function loadOptions() {
  const [{ data: platformList, error: platformError }, { data: taskList, error: taskError }] = await Promise.all([
    fetchGetTicketPlatformList({ pageNum: 1, pageSize: 200, params: {} }),
    fetchGetTicketSaleTaskList({ pageNum: 1, pageSize: 200, params: {} })
  ]);
  if (!platformError) {
    platformOptions.value = (platformList.rows || []).map(item => ({
      label: item.platformName,
      value: item.platformId
    }));
  }
  if (!taskError) {
    taskOptions.value = (taskList.rows || []).map(item => ({ label: item.taskName, value: item.taskId }));
  }
}

onMounted(() => {
  void getData();
  void loadOptions();
});

function resetSearch() {
  searchParams.value = createSearchParams();
  void getDataByPage();
}

async function handleMarkPaid(executionId: CommonType.IdType) {
  const { error } = await fetchMarkTicketOrderExecutionPaid(executionId, {
    resultMessage: '已支付'
  });
  if (error) return;
  window.$message?.success('订单已标记为已支付');
  await getData();
}
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <NCard title="下单执行筛选" :bordered="false" size="small" class="card-wrapper">
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
        <NFormItem label="商品ID">
          <NInput v-model:value="searchParams.productId" clearable placeholder="请输入商品ID" />
        </NFormItem>
        <NFormItem label="订单号">
          <NInput v-model:value="searchParams.orderNo" clearable placeholder="请输入订单号" />
        </NFormItem>
        <NFormItem label="执行状态">
          <NSelect
            v-model:value="searchParams.executionStatus"
            clearable
            :options="executionStatusOptions"
            placeholder="请选择状态"
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

    <NCard title="下单执行列表" :bordered="false" size="small" class="card-wrapper sm:flex-1-hidden">
      <template #header-extra>
        <TableHeaderOperation
          v-model:columns="columnChecks"
          :loading="loading"
          :show-add="false"
          :show-delete="false"
          @refresh="getData"
        />
      </template>
      <NDataTable
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
  </div>
</template>
