<script setup lang="tsx">
import { onMounted, ref } from 'vue';
import { defaultTransform, useNaivePaginatedTable } from '@/hooks/common/table';
import {
  fetchGetTicketOrderExecutionList,
  fetchGetTicketPlatformList,
  fetchGetTicketSaleTaskList
} from '@/service/api/ticket';
import { useAppStore } from '@/store/modules/app';
import { executionStatusOptions, renderTicketEllipsis, renderTicketTag } from '../common';

defineOptions({
  name: 'TicketOrderExecutionList'
});

const appStore = useAppStore();

function createSearchParams(): Api.Ticket.OrderExecutionSearchParams {
  return {
    pageNum: 1,
    pageSize: 10,
    taskId: null,
    platformId: null,
    accountId: null,
    orderNo: null,
    executionStatus: null,
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
      { key: 'taskName', title: '任务名称', align: 'center', minWidth: 180 },
      { key: 'platformName', title: '平台', align: 'center', minWidth: 140 },
      { key: 'accountNo', title: '账号', align: 'center', minWidth: 180 },
      { key: 'orderNo', title: '订单号', align: 'center', minWidth: 180 },
      {
        key: 'executionStatus',
        title: '执行状态',
        align: 'center',
        width: 100,
        render: row => renderTicketTag(row.executionStatus)
      },
      {
        key: 'resultMessage',
        title: '结果信息',
        align: 'center',
        minWidth: 240,
        render: row => renderTicketEllipsis(row.resultMessage)
      },
      { key: 'executedAt', title: '执行时间', align: 'center', minWidth: 160 }
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
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <NCard title="订单执行筛选" :bordered="false" size="small" class="card-wrapper">
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
        <NFormItem>
          <NSpace>
            <NButton type="primary" @click="getDataByPage()">查询</NButton>
            <NButton @click="resetSearch">重置</NButton>
          </NSpace>
        </NFormItem>
      </NForm>
    </NCard>

    <NCard title="订单执行列表" :bordered="false" size="small" class="card-wrapper sm:flex-1-hidden">
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
