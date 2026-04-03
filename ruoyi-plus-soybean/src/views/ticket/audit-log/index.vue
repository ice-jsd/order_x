<script setup lang="tsx">
import { onMounted, ref } from 'vue';
import { defaultTransform, useNaivePaginatedTable } from '@/hooks/common/table';
import { fetchGetTicketAuditLogList } from '@/service/api/ticket';
import { useAppStore } from '@/store/modules/app';
import { auditStatusOptions, renderTicketEllipsis, renderTicketTag } from '../common';

defineOptions({
  name: 'TicketAuditLogList'
});

const appStore = useAppStore();

function createSearchParams(): Api.Ticket.AuditLogSearchParams {
  return {
    pageNum: 1,
    pageSize: 10,
    moduleName: null,
    actionType: null,
    businessType: null,
    auditStatus: null,
    params: {}
  };
}

const searchParams = ref<Api.Ticket.AuditLogSearchParams>(createSearchParams());

const { columns, columnChecks, data, getData, getDataByPage, loading, mobilePagination, scrollX } =
  useNaivePaginatedTable({
    api: () => fetchGetTicketAuditLogList(searchParams.value),
    transform: response => defaultTransform(response),
    onPaginationParamsChange: params => {
      searchParams.value.pageNum = params.page;
      searchParams.value.pageSize = params.pageSize;
    },
    columns: () => [
      { key: 'moduleName', title: '模块', align: 'center', minWidth: 120 },
      { key: 'actionType', title: '动作', align: 'center', minWidth: 120 },
      { key: 'businessType', title: '业务类型', align: 'center', minWidth: 120 },
      { key: 'businessKey', title: '业务主键', align: 'center', minWidth: 160 },
      {
        key: 'auditStatus',
        title: '审计状态',
        align: 'center',
        width: 100,
        render: row => renderTicketTag(row.auditStatus)
      },
      {
        key: 'message',
        title: '消息',
        align: 'center',
        minWidth: 220,
        render: row => renderTicketEllipsis(row.message)
      },
      {
        key: 'payload',
        title: '载荷',
        align: 'center',
        minWidth: 240,
        render: row => renderTicketEllipsis(row.payload)
      },
      { key: 'eventTime', title: '事件时间', align: 'center', minWidth: 160 }
    ]
  });

onMounted(() => {
  void getData();
});

function resetSearch() {
  searchParams.value = createSearchParams();
  void getDataByPage();
}
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <NCard title="审计筛选" :bordered="false" size="small" class="card-wrapper">
      <NForm inline label-placement="left" :label-width="72">
        <NFormItem label="模块">
          <NInput v-model:value="searchParams.moduleName" clearable placeholder="请输入模块名" />
        </NFormItem>
        <NFormItem label="动作">
          <NInput v-model:value="searchParams.actionType" clearable placeholder="请输入动作" />
        </NFormItem>
        <NFormItem label="业务类型">
          <NInput v-model:value="searchParams.businessType" clearable placeholder="请输入业务类型" />
        </NFormItem>
        <NFormItem label="审计状态">
          <NSelect
            v-model:value="searchParams.auditStatus"
            clearable
            :options="auditStatusOptions"
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

    <NCard title="审计日志" :bordered="false" size="small" class="card-wrapper sm:flex-1-hidden">
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
        :row-key="row => row.auditId"
        :pagination="mobilePagination"
        class="sm:h-full"
      />
    </NCard>
  </div>
</template>
