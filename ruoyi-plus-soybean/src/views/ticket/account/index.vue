<script setup lang="tsx">
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { NButton } from 'naive-ui';
import { useAuth } from '@/hooks/business/auth';
import { defaultTransform, useNaivePaginatedTable } from '@/hooks/common/table';
import {
  fetchGetTicketAccountList,
  fetchGetTicketLoginBatch,
  fetchGetTicketLoginBatchDetails,
  fetchGetTicketLoginableAccounts,
  fetchGetTicketPlatformList,
  fetchLoginAccounts
} from '@/service/api/ticket';
import { useAppStore } from '@/store/modules/app';
import { APP_SSE_MESSAGE_EVENT } from '@/utils/sse';
import { accountStatusOptions, loginStatusOptions, renderTicketEllipsis, renderTicketTag } from '../common';

defineOptions({
  name: 'TicketAccountList'
});

type LoginableAccountRow = Api.Ticket.Account & {
  executeStatus?: string;
  executeMessage?: string;
};

const appStore = useAppStore();
const { hasAuth } = useAuth();

function createSearchParams(): Api.Ticket.AccountSearchParams {
  return {
    pageNum: 1,
    pageSize: 10,
    platformId: null,
    phoneId: null,
    accountNo: null,
    displayName: null,
    accountStatus: null,
    loginStatus: null,
    params: {}
  };
}

function createModalSearchParams(): Api.Ticket.AccountSearchParams {
  return {
    pageNum: 1,
    pageSize: 10,
    platformId: null,
    phoneId: null,
    accountNo: null,
    displayName: null,
    accountStatus: null,
    loginStatus: null,
    params: {}
  };
}

const searchParams = ref<Api.Ticket.AccountSearchParams>(createSearchParams());
const checkedRowKeys = ref<CommonType.IdType[]>([]);
const platformOptions = ref<{ label: string; value: CommonType.IdType }[]>([]);

const loginModalVisible = ref(false);
const loginModalSearchParams = ref<Api.Ticket.AccountSearchParams>(createModalSearchParams());
const loginPlatformId = ref<CommonType.IdType | null>(null);
const loginCheckedRowKeys = ref<CommonType.IdType[]>([]);
const loginableRows = ref<LoginableAccountRow[]>([]);
const loginableLoading = ref(false);
const loginBatchId = ref<CommonType.IdType | null>(null);
const loginRunning = ref(false);
const loginBatchPollTimer = ref<number | null>(null);
const loginProgress = reactive({
  totalCount: 0,
  processedCount: 0,
  successCount: 0,
  failedCount: 0,
  currentAccount: ''
});
let loginBatchSyncing = false;

const loginPagination = reactive({
  page: 1,
  pageSize: 10,
  itemCount: 0,
  showSizePicker: true,
  pageSizes: [10, 20, 30],
  onUpdatePage(page: number) {
    loginPagination.page = page;
    void loadLoginableAccounts();
  },
  onUpdatePageSize(pageSize: number) {
    loginPagination.pageSize = pageSize;
    loginPagination.page = 1;
    void loadLoginableAccounts();
  }
});

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
      { key: 'accountNo', title: '账号编号', align: 'center', minWidth: 180 },
      { key: 'displayName', title: '显示名称', align: 'center', minWidth: 150 },
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
      { key: 'sessionExpireTime', title: '会话到期', align: 'center', minWidth: 160 },
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

const loginColumns: NaiveUI.TableColumn<LoginableAccountRow>[] = [
  { type: 'selection', align: 'center', width: 48, disabled: () => loginRunning.value },
  { key: 'accountNo', title: '账号编号', align: 'center', minWidth: 180 },
  { key: 'phoneNumber', title: '来源号码', align: 'center', minWidth: 140 },
  { key: 'displayName', title: '显示名称', align: 'center', minWidth: 150 },
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
  {
    key: 'executeStatus',
    title: '执行结果',
    align: 'center',
    width: 100,
    render: row => renderTicketTag(row.executeStatus)
  },
  {
    key: 'lastError',
    title: '最近错误',
    align: 'center',
    minWidth: 180,
    render: row => renderTicketEllipsis(row.lastError)
  },
  {
    key: 'executeMessage',
    title: '返回信息',
    align: 'center',
    minWidth: 220,
    render: row => renderTicketEllipsis(row.executeMessage)
  }
];

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

function clearLoginBatchPolling() {
  if (loginBatchPollTimer.value !== null) {
    window.clearInterval(loginBatchPollTimer.value);
    loginBatchPollTimer.value = null;
  }
}

function resetLoginProgress() {
  loginBatchId.value = null;
  loginRunning.value = false;
  loginProgress.totalCount = 0;
  loginProgress.processedCount = 0;
  loginProgress.successCount = 0;
  loginProgress.failedCount = 0;
  loginProgress.currentAccount = '';
}

function resetLoginModal() {
  clearLoginBatchPolling();
  loginModalSearchParams.value = createModalSearchParams();
  loginPlatformId.value = null;
  loginCheckedRowKeys.value = [];
  loginableRows.value = [];
  loginPagination.page = 1;
  loginPagination.pageSize = 10;
  loginPagination.itemCount = 0;
  resetLoginProgress();
}

function openLoginModal() {
  resetLoginModal();
  loginPlatformId.value = searchParams.value.platformId || null;
  loginModalSearchParams.value.loginStatus = searchParams.value.loginStatus || null;
  loginModalVisible.value = true;
}

watch(loginModalVisible, visible => {
  if (!visible) {
    resetLoginModal();
  }
});

async function loadLoginableAccounts() {
  if (!loginModalVisible.value || !loginPlatformId.value) {
    loginableRows.value = [];
    loginPagination.itemCount = 0;
    return;
  }

  loginableLoading.value = true;
  const { data: list, error } = await fetchGetTicketLoginableAccounts(loginPlatformId.value, {
    ...loginModalSearchParams.value,
    pageNum: loginPagination.page,
    pageSize: loginPagination.pageSize
  });
  loginableLoading.value = false;
  if (error) return;

  const previousMap = new Map(loginableRows.value.map(row => [row.accountId, row]));
  loginableRows.value = (list.rows || []).map(row => {
    const previous = previousMap.get(row.accountId);
    return {
      ...row,
      executeStatus: previous?.executeStatus,
      executeMessage: previous?.executeMessage
    };
  });
  loginPagination.itemCount = list.total;
}

async function handleLoginSearch() {
  loginPagination.page = 1;
  loginCheckedRowKeys.value = [];
  await loadLoginableAccounts();
}

async function syncLoginBatchSnapshot(batchId = loginBatchId.value) {
  if (!batchId || loginBatchSyncing) {
    return;
  }

  loginBatchSyncing = true;
  const [{ data: batch, error: batchError }, { data: details, error: detailError }] = await Promise.all([
    fetchGetTicketLoginBatch(batchId),
    fetchGetTicketLoginBatchDetails(batchId)
  ]);
  loginBatchSyncing = false;

  if (batchError || detailError || !batch) {
    return;
  }

  loginProgress.totalCount = batch.totalCount || 0;
  loginProgress.processedCount = (batch.successCount || 0) + (batch.failedCount || 0);
  loginProgress.successCount = batch.successCount || 0;
  loginProgress.failedCount = batch.failedCount || 0;

  if (details?.length) {
    const latestDetail = details[details.length - 1];
    loginProgress.currentAccount = latestDetail?.accountNo || loginProgress.currentAccount;

    const detailMap = new Map(details.map(item => [item.accountId, item]));
    loginableRows.value = loginableRows.value.map(row => {
      const detail = detailMap.get(row.accountId);
      if (!detail) return row;

      return {
        ...row,
        executeStatus: detail.executeStatus,
        executeMessage: detail.resultMessage || row.executeMessage,
        sessionExpireTime: detail.sessionExpireTime || row.sessionExpireTime
      };
    });
  }

  if (['completed', 'partial', 'blocked'].includes(batch.batchStatus)) {
    loginRunning.value = false;
    clearLoginBatchPolling();
    await Promise.all([getData(), loadLoginableAccounts()]);
  }
}

function startLoginBatchPolling(batchId: CommonType.IdType) {
  clearLoginBatchPolling();
  void syncLoginBatchSnapshot(batchId);
  loginBatchPollTimer.value = window.setInterval(() => {
    void syncLoginBatchSnapshot(batchId);
  }, 2000);
}

async function handleStartLogin() {
  if (!loginPlatformId.value) {
    window.$message?.warning('请先选择目标平台');
    return;
  }

  if (!loginCheckedRowKeys.value.length) {
    window.$message?.warning('请先勾选需要登录的账号');
    return;
  }

  const { data: batchId, error } = await fetchLoginAccounts(loginPlatformId.value, {
    accountIds: loginCheckedRowKeys.value
  });
  if (error) return;

  loginBatchId.value = batchId;
  loginRunning.value = true;
  loginProgress.totalCount = loginCheckedRowKeys.value.length;
  loginProgress.processedCount = 0;
  loginProgress.successCount = 0;
  loginProgress.failedCount = 0;
  loginProgress.currentAccount = '';

  const selectedSet = new Set(loginCheckedRowKeys.value);
  loginableRows.value = loginableRows.value.map(row => ({
    ...row,
    executeStatus: selectedSet.has(row.accountId) ? 'queued' : row.executeStatus,
    executeMessage: selectedSet.has(row.accountId) ? '等待处理' : row.executeMessage
  }));

  startLoginBatchPolling(batchId);
  window.$message?.success(`登录批次已启动：${batchId}`);
}

function patchMainTableRow(payload: Api.Ticket.LoginProgressMessage) {
  if (!payload.accountId) return;
  const row = data.value.find(item => item.accountId === payload.accountId);
  if (!row) return;

  if (payload.loginStatus) {
    row.loginStatus = payload.loginStatus;
  }
  if (payload.lastError !== undefined) {
    row.lastError = payload.lastError;
  }
  if (payload.sessionExpireTime !== undefined) {
    row.sessionExpireTime = payload.sessionExpireTime;
  }
  if (payload.lastLoginTime !== undefined) {
    row.lastLoginTime = payload.lastLoginTime;
  }
}

function patchModalRow(payload: Api.Ticket.LoginProgressMessage) {
  if (!loginBatchId.value || payload.batchId !== loginBatchId.value) {
    return;
  }

  loginProgress.totalCount = payload.totalCount;
  loginProgress.processedCount = payload.processedCount;
  loginProgress.successCount = payload.successCount;
  loginProgress.failedCount = payload.failedCount;
  loginProgress.currentAccount = payload.accountNo || '';

  if (payload.stepStatus === 'completed') {
    loginRunning.value = false;
    clearLoginBatchPolling();
    void Promise.all([getData(), loadLoginableAccounts()]);
    return;
  }

  if (!payload.accountId) {
    return;
  }

  const index = loginableRows.value.findIndex(item => item.accountId === payload.accountId);
  if (index < 0) return;

  loginableRows.value[index] = {
    ...loginableRows.value[index],
    loginStatus: payload.loginStatus || loginableRows.value[index].loginStatus,
    lastError: payload.lastError ?? loginableRows.value[index].lastError,
    sessionExpireTime: payload.sessionExpireTime ?? loginableRows.value[index].sessionExpireTime,
    lastLoginTime: payload.lastLoginTime ?? loginableRows.value[index].lastLoginTime,
    executeStatus: payload.stepStatus,
    executeMessage: payload.message
  };
}

function handleLoginSseMessage(event: Event) {
  const payload = (event as CustomEvent<Api.Ticket.LoginProgressMessage>).detail;
  if (!payload || payload.module !== 'ticket_login') {
    return;
  }
  patchMainTableRow(payload);
  patchModalRow(payload);
}

watch([loginModalVisible, loginPlatformId], async ([visible, platformId], [previousVisible, previousPlatformId]) => {
  if (!visible || loginRunning.value) {
    return;
  }

  if (visible === previousVisible && platformId === previousPlatformId) {
    return;
  }

  loginPagination.page = 1;
  loginCheckedRowKeys.value = [];

  if (!platformId) {
    loginableRows.value = [];
    loginPagination.itemCount = 0;
    return;
  }

  await loadLoginableAccounts();
});

onMounted(() => {
  void getData();
  void loadPlatformOptions();
  window.addEventListener(APP_SSE_MESSAGE_EVENT, handleLoginSseMessage as EventListener);
});

onBeforeUnmount(() => {
  clearLoginBatchPolling();
  window.removeEventListener(APP_SSE_MESSAGE_EVENT, handleLoginSseMessage as EventListener);
});
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <NCard title="账号池筛选" :bordered="false" size="small" class="card-wrapper">
      <NForm inline label-placement="left" :label-width="72">
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
        <NFormItem label="账号编号">
          <NInput v-model:value="searchParams.accountNo" clearable placeholder="请输入账号编号" />
        </NFormItem>
        <NFormItem label="显示名称">
          <NInput v-model:value="searchParams.displayName" clearable placeholder="请输入显示名称" />
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
        >
          <template #prefix>
            <NButton v-if="hasAuth('ticket:account:login')" size="small" type="primary" ghost @click="openLoginModal">
              批量登录
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
        :row-key="row => row.accountId"
        :pagination="mobilePagination"
        class="sm:h-full"
      />
    </NCard>

    <NModal
      v-model:show="loginModalVisible"
      preset="card"
      title="批量登录平台账号"
      class="w-1100px"
      :mask-closable="!loginRunning"
    >
      <div class="flex-col-stretch gap-16px">
        <NCard size="small" :bordered="false">
          <NForm inline label-placement="left" :label-width="80">
            <NFormItem label="目标平台">
              <NSelect
                v-model:value="loginPlatformId"
                :disabled="loginRunning"
                clearable
                filterable
                :options="platformOptions"
                placeholder="请选择目标平台"
                class="w-220px"
              />
            </NFormItem>
            <NFormItem label="账号编号">
              <NInput
                v-model:value="loginModalSearchParams.accountNo"
                :disabled="loginRunning"
                clearable
                placeholder="支持按账号编号搜索"
              />
            </NFormItem>
            <NFormItem label="登录状态">
              <NSelect
                v-model:value="loginModalSearchParams.loginStatus"
                :disabled="loginRunning"
                clearable
                :options="loginStatusOptions"
                placeholder="请选择登录状态"
                class="w-180px"
              />
            </NFormItem>
            <NFormItem>
              <NSpace>
                <NButton type="primary" :disabled="!loginPlatformId" @click="handleLoginSearch">筛选账号</NButton>
              </NSpace>
            </NFormItem>
          </NForm>
        </NCard>

        <NCard size="small" :bordered="false" title="执行面板">
          <NSpace justify="space-between" class="w-full">
            <NSpace>
              <NTag :bordered="false" type="info">总数 {{ loginProgress.totalCount }}</NTag>
              <NTag :bordered="false" type="default">已处理 {{ loginProgress.processedCount }}</NTag>
              <NTag :bordered="false" type="success">成功 {{ loginProgress.successCount }}</NTag>
              <NTag :bordered="false" type="error">失败 {{ loginProgress.failedCount }}</NTag>
            </NSpace>
            <div class="text-14px text-text-secondary">当前处理：{{ loginProgress.currentAccount || '-' }}</div>
          </NSpace>
        </NCard>

        <NCard size="small" :bordered="false" title="候选账号">
          <template #header-extra>
            <NSpace>
              <NButton type="primary" :disabled="loginRunning || !loginCheckedRowKeys.length" @click="handleStartLogin">
                开始登录
              </NButton>
            </NSpace>
          </template>
          <NDataTable
            v-model:checked-row-keys="loginCheckedRowKeys"
            :columns="loginColumns"
            :data="loginableRows"
            :loading="loginableLoading"
            remote
            size="small"
            :row-key="row => row.accountId"
            :pagination="loginPagination"
          />
        </NCard>
      </div>
    </NModal>
  </div>
</template>
