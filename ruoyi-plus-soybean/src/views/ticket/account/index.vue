<script setup lang="tsx">
import { onMounted, ref } from 'vue';
import { defaultTransform, useNaivePaginatedTable } from '@/hooks/common/table';
import { fetchGetTicketAccountList, fetchGetTicketPlatformList } from '@/service/api/ticket';
import { useAppStore } from '@/store/modules/app';
import {
  accountStatusOptions,
  loginStatusOptions,
  renderTicketEllipsis,
  renderTicketEmail,
  renderTicketJsonSummary,
  renderTicketTag
} from '../common';

defineOptions({
  name: 'TicketAccountList'
});

const appStore = useAppStore();

function createSearchParams(): Api.Ticket.AccountSearchParams {
  return {
    pageNum: 1,
    pageSize: 10,
    accountId: null,
    platformId: null,
    phoneId: null,
    email: null,
    accountStatus: null,
    loginStatus: null,
    params: {}
  };
}

const searchParams = ref<Api.Ticket.AccountSearchParams>(createSearchParams());
const checkedRowKeys = ref<CommonType.IdType[]>([]);
const platformOptions = ref<{ label: string; value: CommonType.IdType }[]>([]);

const { columns, columnChecks, data, getData, getDataByPage, loading, mobilePagination, scrollX } =
  useNaivePaginatedTable({
    api: () => fetchGetTicketAccountList(searchParams.value),
    transform: response => defaultTransform(response),
    onPaginationParamsChange: params => {
      searchParams.value.pageNum = params.page;
      searchParams.value.pageSize = params.pageSize;
    },
    columns: () => [
      { type: 'selection', align: 'center', width: 48 },
      { key: 'platformName', title: '目标平台', align: 'center', minWidth: 140 },
      { key: 'phoneNumber', title: '来源号码', align: 'center', minWidth: 140 },
      {
        key: 'accountId',
        title: '账号ID',
        align: 'center',
        minWidth: 160,
        render: row => renderTicketEllipsis(String(row.accountId || '-'))
      },
      {
        key: 'email',
        title: '邮箱',
        align: 'center',
        minWidth: 220,
        render: row => renderTicketEmail(row.email)
      },
      {
        key: 'accountInfo',
        title: '账号信息',
        align: 'center',
        minWidth: 200,
        render: row => renderTicketJsonSummary(row.accountInfo, ['nickname', 'source', 'email'])
      },
      {
        key: 'reqData',
        title: '请求上下文',
        align: 'center',
        minWidth: 200,
        render: row => renderTicketJsonSummary(row.reqData, ['platformCode', 'channel', 'phoneNumber', 'sessionId'])
      },
      {
        key: 'accountStatus',
        title: '账号状态',
        align: 'center',
        width: 100,
        render: row => renderTicketTag(row.accountStatus)
      },
      {
        key: 'loginStatus',
        title: '登录状态',
        align: 'center',
        width: 100,
        render: row => renderTicketTag(row.loginStatus)
      },
      { key: 'lastLoginTime', title: '最近登录', align: 'center', minWidth: 160 },
      {
        key: 'lastError',
        title: '最近错误',
        align: 'center',
        minWidth: 220,
        render: row => renderTicketEllipsis(row.lastError)
      }
    ]
  });

async function loadPlatformOptions() {
  const { data: list, error } = await fetchGetTicketPlatformList({ pageNum: 1, pageSize: 200, params: {} });
  if (error) return;
  platformOptions.value = (list.rows || []).map(item => ({ label: item.platformName, value: item.platformId }));
}

function resetSearch() {
  searchParams.value = createSearchParams();
  checkedRowKeys.value = [];
  void getDataByPage();
}

onMounted(() => {
  void getData();
  void loadPlatformOptions();
});
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <NCard title="账号池筛选" :bordered="false" size="small" class="card-wrapper">
      <NForm inline label-placement="left" :label-width="72">
        <NFormItem label="账号ID">
          <NInputNumber
            v-model:value="searchParams.accountId"
            clearable
            placeholder="请输入账号ID"
            class="w-180px"
          />
        </NFormItem>
        <NFormItem label="目标平台">
          <NSelect
            v-model:value="searchParams.platformId"
            clearable
            filterable
            :options="platformOptions"
            placeholder="请选择平台"
            class="w-180px"
          />
        </NFormItem>
        <NFormItem label="邮箱">
          <NInput v-model:value="searchParams.email" clearable placeholder="请输入邮箱" />
        </NFormItem>
        <NFormItem label="账号状态">
          <NSelect
            v-model:value="searchParams.accountStatus"
            clearable
            :options="accountStatusOptions"
            placeholder="请选择账号状态"
            class="w-160px"
          />
        </NFormItem>
        <NFormItem label="登录状态">
          <NSelect
            v-model:value="searchParams.loginStatus"
            clearable
            :options="loginStatusOptions"
            placeholder="请选择登录状态"
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

    <NCard title="账号池管理" :bordered="false" size="small" class="card-wrapper sm:flex-1-hidden">
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
        v-model:checked-row-keys="checkedRowKeys"
        :columns="columns"
        :data="data"
        size="small"
        remote
        :loading="loading"
        :flex-height="!appStore.isMobile"
        :scroll-x="scrollX"
        :row-key="row => row.accountId"
        :pagination="mobilePagination"
        class="sm:h-full"
      />
    </NCard>
  </div>
</template>
