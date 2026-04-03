<script setup lang="tsx">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { NButton } from 'naive-ui';
import { useAuth } from '@/hooks/business/auth';
import { defaultTransform, useNaivePaginatedTable } from '@/hooks/common/table';
import {
  fetchBulkImportTicketPhones,
  fetchChangeTicketPhoneStatus,
  fetchGetTicketPhoneList,
  fetchGetTicketPlatformList,
  fetchGetTicketRegisterablePhones,
  fetchGetTicketRegistrationBatch,
  fetchGetTicketRegistrationBatchDetails,
  fetchRegisterFromPhones
} from '@/service/api/ticket';
import { useAppStore } from '@/store/modules/app';
import { APP_SSE_MESSAGE_EVENT } from '@/utils/sse';
import {
  countryCodeOptions,
  phoneStatusOptions,
  renderCountryCode,
  renderPhoneStatusTag,
  renderTicketEllipsis,
  renderTicketTag
} from '../common';

defineOptions({
  name: 'TicketPhoneList'
});

type RegisterablePhoneRow = Api.Ticket.Phone & {
  executeStatus?: string;
  executeMessage?: string;
};

const appStore = useAppStore();
const { hasAuth } = useAuth();

function createSearchParams(): Api.Ticket.PhoneSearchParams {
  return {
    pageNum: 1,
    pageSize: 10,
    phoneNumber: null,
    countryCode: null,
    supplier: null,
    status: null,
    params: {}
  };
}

function createImportForm(): Api.Ticket.PhoneImportParams {
  return {
    supplier: 'default-pool',
    countryCode: '+81',
    status: 'available',
    note: '',
    numbers: ''
  };
}

function createModalSearchParams(): Api.Ticket.PhoneRegisterableSearchParams {
  return {
    pageNum: 1,
    pageSize: 10,
    phoneNumber: null,
    countryCode: '+81',
    params: {}
  };
}

const searchParams = ref<Api.Ticket.PhoneSearchParams>(createSearchParams());
const importForm = ref<Api.Ticket.PhoneImportParams>(createImportForm());
const checkedRowKeys = ref<CommonType.IdType[]>([]);
const platformOptions = ref<{ label: string; value: CommonType.IdType }[]>([]);

const registerModalVisible = ref(false);
const registerModalSearchParams = ref<Api.Ticket.PhoneRegisterableSearchParams>(createModalSearchParams());
const registerPlatformId = ref<CommonType.IdType | null>(null);
const registerCheckedRowKeys = ref<CommonType.IdType[]>([]);
const registerableRows = ref<RegisterablePhoneRow[]>([]);
const registerableLoading = ref(false);
const registerBatchId = ref<CommonType.IdType | null>(null);
const registerRunning = ref(false);
const registerBatchPollTimer = ref<number | null>(null);
const registerProgress = reactive({
  totalCount: 0,
  processedCount: 0,
  successCount: 0,
  failedCount: 0,
  skippedCount: 0,
  currentPhone: ''
});
let registerBatchSyncing = false;

const registerPagination = reactive({
  page: 1,
  pageSize: 10,
  itemCount: 0,
  showSizePicker: true,
  pageSizes: [10, 20, 30],
  onUpdatePage(page: number) {
    registerPagination.page = page;
    void loadRegisterablePhones();
  },
  onUpdatePageSize(pageSize: number) {
    registerPagination.pageSize = pageSize;
    registerPagination.page = 1;
    void loadRegisterablePhones();
  }
});

const importPlaceholder = computed(() => {
  if (importForm.value.countryCode === '+86') {
    return '一行一个号码，例如\n13800000001\n13900000002';
  }

  return '一行一个号码，例如\n09012345678\n08012345678';
});

const registerReady = computed(() => Boolean(registerPlatformId.value && registerModalSearchParams.value.countryCode));

const { columns, columnChecks, data, getData, getDataByPage, loading, mobilePagination, scrollX } =
  useNaivePaginatedTable({
    api: () => fetchGetTicketPhoneList(searchParams.value),
    transform: response => defaultTransform(response),
    onPaginationParamsChange: params => {
      searchParams.value.pageNum = params.page;
      searchParams.value.pageSize = params.pageSize;
    },
    columns: () => [
      { type: 'selection', align: 'center', width: 48 },
      { key: 'phoneNumber', title: '号码', align: 'center', minWidth: 160 },
      {
        key: 'countryCode',
        title: '国家区号',
        align: 'center',
        width: 120,
        render: row => renderCountryCode(row.countryCode)
      },
      { key: 'supplier', title: '供应商', align: 'center', minWidth: 120 },
      {
        key: 'status',
        title: '号码状态',
        align: 'center',
        width: 100,
        render: row => renderPhoneStatusTag(row.status)
      },
      { key: 'registeredPlatformCount', title: '已注册平台数', align: 'center', width: 120 },
      { key: 'loggedInPlatformCount', title: '已登录平台数', align: 'center', width: 120 },
      {
        key: 'note',
        title: '备注',
        align: 'center',
        minWidth: 220,
        render: row => renderTicketEllipsis(row.note)
      },
      { key: 'createTime', title: '创建时间', align: 'center', minWidth: 160 }
    ]
  });

const registerColumns: NaiveUI.TableColumn<RegisterablePhoneRow>[] = [
  { type: 'selection', align: 'center', width: 48, disabled: () => registerRunning.value },
  { key: 'phoneNumber', title: '号码', align: 'center', minWidth: 160 },
  {
    key: 'countryCode',
    title: '国家区号',
    align: 'center',
    width: 120,
    render: row => renderCountryCode(row.countryCode)
  },
  { key: 'supplier', title: '供应商', align: 'center', minWidth: 120 },
  {
    key: 'status',
    title: '当前状态',
    align: 'center',
    width: 100,
    render: row => renderPhoneStatusTag(row.status)
  },
  {
    key: 'executeStatus',
    title: '执行结果',
    align: 'center',
    width: 100,
    render: row => renderTicketTag(row.executeStatus)
  },
  {
    key: 'note',
    title: '备注',
    align: 'center',
    minWidth: 220,
    render: row => renderTicketEllipsis(row.note)
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

function resetRegisterProgress() {
  registerBatchId.value = null;
  registerRunning.value = false;
  registerProgress.totalCount = 0;
  registerProgress.processedCount = 0;
  registerProgress.successCount = 0;
  registerProgress.failedCount = 0;
  registerProgress.skippedCount = 0;
  registerProgress.currentPhone = '';
}

function clearRegisterBatchPolling() {
  if (registerBatchPollTimer.value !== null) {
    window.clearInterval(registerBatchPollTimer.value);
    registerBatchPollTimer.value = null;
  }
}

function resetRegisterModal() {
  clearRegisterBatchPolling();
  registerModalSearchParams.value = createModalSearchParams();
  registerPlatformId.value = null;
  registerCheckedRowKeys.value = [];
  registerableRows.value = [];
  registerPagination.page = 1;
  registerPagination.pageSize = 10;
  registerPagination.itemCount = 0;
  resetRegisterProgress();
}

function resetSearch() {
  searchParams.value = createSearchParams();
  checkedRowKeys.value = [];
  void getDataByPage();
}

function normalizeImportedNumbers(input: string) {
  return input
    .split(/\r?\n/)
    .map((item, index) => ({
      line: index + 1,
      raw: item,
      value: item.trim().replace(/[\s-]/g, '')
    }))
    .filter(item => item.value);
}

function validateImportedNumbers(countryCode: string, numbers: string) {
  const rows = normalizeImportedNumbers(numbers);

  if (!rows.length) {
    return {
      valid: false,
      normalizedNumbers: [] as string[],
      message: '请先输入要导入的号码'
    };
  }

  const ruleMap: Record<string, RegExp> = {
    '+86': /^1[3-9]\d{9}$/,
    '+81': /^(070|080|090)\d{8}$/
  };

  const rule = ruleMap[countryCode];
  if (!rule) {
    return {
      valid: false,
      normalizedNumbers: [] as string[],
      message: '暂不支持当前国家区号'
    };
  }

  const invalidRows = rows.filter(item => !rule.test(item.value));
  if (invalidRows.length) {
    const preview = invalidRows
      .slice(0, 3)
      .map(item => `第 ${item.line} 行：${item.raw || item.value}`)
      .join('；');
    const countryLabel = countryCode === '+86' ? '中国大陆手机号' : '日本手机号';
    return {
      valid: false,
      normalizedNumbers: [] as string[],
      message: `${countryLabel}格式不正确，${preview}${invalidRows.length > 3 ? ' 等' : ''}`
    };
  }

  return {
    valid: true,
    normalizedNumbers: rows.map(item => item.value),
    message: ''
  };
}

async function handleImport() {
  const validation = validateImportedNumbers(importForm.value.countryCode || '', importForm.value.numbers || '');
  if (!validation.valid) {
    window.$message?.warning(validation.message);
    return;
  }

  const payload: Api.Ticket.PhoneImportParams = {
    ...importForm.value,
    numbers: validation.normalizedNumbers.join('\n')
  };

  const { data: result, error } = await fetchBulkImportTicketPhones(payload);
  if (error) return;
  window.$message?.success(`导入完成，新增 ${result.importedCount} 条，跳过 ${result.skippedCount} 条`);
  importForm.value.numbers = '';
  await getData();
}

async function handleStatusChange(status: string) {
  if (!checkedRowKeys.value.length) {
    window.$message?.warning('请先选择号码');
    return;
  }
  const { error } = await fetchChangeTicketPhoneStatus({ phoneIds: checkedRowKeys.value, status });
  if (error) return;
  window.$message?.success('号码状态已更新');
  await getData();
}

function openRegisterModal() {
  resetRegisterModal();
  registerModalVisible.value = true;
}

watch(registerModalVisible, visible => {
  if (!visible) {
    resetRegisterModal();
  }
});

async function loadRegisterablePhones() {
  if (!registerReady.value || !registerPlatformId.value) {
    registerableRows.value = [];
    registerPagination.itemCount = 0;
    return;
  }

  registerableLoading.value = true;
  const { data: list, error } = await fetchGetTicketRegisterablePhones(registerPlatformId.value, {
    ...registerModalSearchParams.value,
    pageNum: registerPagination.page,
    pageSize: registerPagination.pageSize
  });
  registerableLoading.value = false;
  if (error) return;

  const previousMap = new Map(registerableRows.value.map(row => [row.phoneId, row]));
  registerableRows.value = (list.rows || []).map(row => {
    const previous = previousMap.get(row.phoneId);
    return {
      ...row,
      executeStatus: previous?.executeStatus,
      executeMessage: previous?.executeMessage
    };
  });
  registerPagination.itemCount = list.total;
}

async function handleRegisterSearch() {
  registerPagination.page = 1;
  registerCheckedRowKeys.value = [];
  await loadRegisterablePhones();
}

async function syncRegisterBatchSnapshot(batchId = registerBatchId.value) {
  if (!batchId || registerBatchSyncing) {
    return;
  }

  registerBatchSyncing = true;
  const [{ data: batch, error: batchError }, { data: details, error: detailsError }] = await Promise.all([
    fetchGetTicketRegistrationBatch(batchId),
    fetchGetTicketRegistrationBatchDetails(batchId)
  ]);
  registerBatchSyncing = false;

  if (batchError || detailsError || !batch) {
    return;
  }

  registerProgress.totalCount = batch.totalCount || 0;
  registerProgress.processedCount = (batch.successCount || 0) + (batch.failedCount || 0) + (batch.skippedCount || 0);
  registerProgress.successCount = batch.successCount || 0;
  registerProgress.failedCount = batch.failedCount || 0;
  registerProgress.skippedCount = batch.skippedCount || 0;

  if (details?.length) {
    const latestDetail = details[details.length - 1];
    registerProgress.currentPhone = latestDetail?.phoneNumber || registerProgress.currentPhone;

    const detailMap = new Map(details.map(item => [item.phoneId, item]));
    registerableRows.value = registerableRows.value.map(row => {
      const detail = detailMap.get(row.phoneId);
      if (!detail) {
        return row;
      }

      return {
        ...row,
        executeStatus: detail.executeStatus,
        executeMessage: detail.resultMessage || row.executeMessage
      };
    });
  }

  if (['completed', 'partial', 'blocked'].includes(batch.batchStatus)) {
    registerRunning.value = false;
    clearRegisterBatchPolling();
    await Promise.all([getData(), loadRegisterablePhones()]);
  }
}

function startRegisterBatchPolling(batchId: CommonType.IdType) {
  clearRegisterBatchPolling();
  void syncRegisterBatchSnapshot(batchId);
  registerBatchPollTimer.value = window.setInterval(() => {
    void syncRegisterBatchSnapshot(batchId);
  }, 2000);
}

async function handleStartRegister() {
  if (!registerPlatformId.value) {
    window.$message?.warning('请先选择目标平台');
    return;
  }
  if (!registerCheckedRowKeys.value.length) {
    window.$message?.warning('请先勾选需要注册的号码');
    return;
  }

  const { data: batchId, error } = await fetchRegisterFromPhones(registerPlatformId.value, {
    phoneIds: registerCheckedRowKeys.value
  });
  if (error) return;

  registerBatchId.value = batchId;
  registerRunning.value = true;
  registerProgress.totalCount = registerCheckedRowKeys.value.length;
  registerProgress.processedCount = 0;
  registerProgress.successCount = 0;
  registerProgress.failedCount = 0;
  registerProgress.skippedCount = 0;
  registerProgress.currentPhone = '';

  const selectedSet = new Set(registerCheckedRowKeys.value);
  registerableRows.value = registerableRows.value.map(row => ({
    ...row,
    executeStatus: selectedSet.has(row.phoneId) ? 'queued' : row.executeStatus,
    executeMessage: selectedSet.has(row.phoneId) ? '等待处理' : row.executeMessage
  }));

  startRegisterBatchPolling(batchId);

  window.$message?.success(`注册批次已启动：${batchId}`);
}

function patchMainTableRow(payload: Api.Ticket.RegisterProgressMessage) {
  if (!payload.phoneId) return;
  const row = data.value.find(item => item.phoneId === payload.phoneId);
  if (!row) return;
  if (payload.phoneStatus) {
    row.status = payload.phoneStatus;
  }
  if (payload.note !== undefined) {
    row.note = payload.note;
  }
  if (payload.registeredPlatformCount !== undefined) {
    row.registeredPlatformCount = payload.registeredPlatformCount;
  }
  if (payload.loggedInPlatformCount !== undefined) {
    row.loggedInPlatformCount = payload.loggedInPlatformCount;
  }
}

function patchModalRow(payload: Api.Ticket.RegisterProgressMessage) {
  if (!registerBatchId.value || payload.batchId !== registerBatchId.value) {
    return;
  }

  registerProgress.totalCount = payload.totalCount;
  registerProgress.processedCount = payload.processedCount;
  registerProgress.successCount = payload.successCount;
  registerProgress.failedCount = payload.failedCount;
  registerProgress.skippedCount = payload.skippedCount;
  registerProgress.currentPhone = payload.phoneNumber || '';

  if (payload.stepStatus === 'completed') {
    registerRunning.value = false;
    clearRegisterBatchPolling();
    void Promise.all([getData(), loadRegisterablePhones()]);
    return;
  }

  if (!payload.phoneId) {
    return;
  }

  const index = registerableRows.value.findIndex(item => item.phoneId === payload.phoneId);
  if (index < 0) return;

  registerableRows.value[index] = {
    ...registerableRows.value[index],
    status: payload.phoneStatus || registerableRows.value[index].status,
    note: payload.note ?? registerableRows.value[index].note,
    registeredPlatformCount: payload.registeredPlatformCount ?? registerableRows.value[index].registeredPlatformCount,
    loggedInPlatformCount: payload.loggedInPlatformCount ?? registerableRows.value[index].loggedInPlatformCount,
    executeStatus: payload.stepStatus,
    executeMessage: payload.message
  };
}

function handleRegisterSseMessage(event: Event) {
  const payload = (event as CustomEvent<Api.Ticket.RegisterProgressMessage>).detail;
  if (!payload || payload.module !== 'ticket_register') {
    return;
  }
  patchMainTableRow(payload);
  patchModalRow(payload);
}

watch(
  [registerModalVisible, registerPlatformId, () => registerModalSearchParams.value.countryCode],
  async ([visible, platformId, countryCode], [previousVisible, previousPlatformId, previousCountryCode]) => {
    if (!visible || registerRunning.value) {
      return;
    }

    if (platformId === previousPlatformId && countryCode === previousCountryCode && visible === previousVisible) {
      return;
    }

    registerPagination.page = 1;
    registerCheckedRowKeys.value = [];

    if (!platformId || !countryCode) {
      registerableRows.value = [];
      registerPagination.itemCount = 0;
      return;
    }

    await loadRegisterablePhones();
  }
);

onMounted(() => {
  void getData();
  void loadPlatformOptions();
  window.addEventListener(APP_SSE_MESSAGE_EVENT, handleRegisterSseMessage as EventListener);
});

onBeforeUnmount(() => {
  clearRegisterBatchPolling();
  window.removeEventListener(APP_SSE_MESSAGE_EVENT, handleRegisterSseMessage as EventListener);
});
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <NCard title="批量导入号码" :bordered="false" size="small" class="card-wrapper">
      <NGrid :cols="24" :x-gap="16">
        <NFormItemGi :span="6" label="供应商">
          <NInput v-model:value="importForm.supplier" placeholder="请输入供应商" />
        </NFormItemGi>
        <NFormItemGi :span="4" label="国家区号">
          <NSelect v-model:value="importForm.countryCode" :options="countryCodeOptions" placeholder="请选择国家区号" />
        </NFormItemGi>
        <NFormItemGi :span="4" label="初始状态">
          <NSelect v-model:value="importForm.status" :options="phoneStatusOptions" />
        </NFormItemGi>
        <NFormItemGi :span="10" label="备注">
          <NInput v-model:value="importForm.note" placeholder="本批次备注" />
        </NFormItemGi>
        <NFormItemGi :span="24" label="号码列表">
          <NInput v-model:value="importForm.numbers" type="textarea" :rows="5" :placeholder="importPlaceholder" />
        </NFormItemGi>
      </NGrid>
      <div class="mt-16px flex justify-end">
        <NButton type="primary" @click="handleImport">一键导入</NButton>
      </div>
    </NCard>

    <NCard title="号码池筛选" :bordered="false" size="small" class="card-wrapper">
      <NForm inline label-placement="left" :label-width="80">
        <NFormItem label="号码">
          <NInput v-model:value="searchParams.phoneNumber" clearable placeholder="请输入号码" />
        </NFormItem>
        <NFormItem label="国家区号">
          <NSelect
            v-model:value="searchParams.countryCode"
            clearable
            :options="countryCodeOptions"
            placeholder="请选择国家区号"
            class="w-160px"
          />
        </NFormItem>
        <NFormItem label="供应商">
          <NInput v-model:value="searchParams.supplier" clearable placeholder="请输入供应商" />
        </NFormItem>
        <NFormItem label="号码状态">
          <NSelect
            v-model:value="searchParams.status"
            clearable
            :options="phoneStatusOptions"
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

    <NCard title="号码池管理" :bordered="false" size="small" class="card-wrapper sm:flex-1-hidden">
      <template #header-extra>
        <TableHeaderOperation
          v-model:columns="columnChecks"
          :disabled-delete="checkedRowKeys.length === 0"
          :show-add="false"
          :show-delete="false"
          :loading="loading"
          @refresh="getData"
        >
          <template #prefix>
            <NSpace>
              <NButton
                v-if="hasAuth('ticket:platform:register')"
                size="small"
                type="primary"
                ghost
                @click="openRegisterModal"
              >
                批量注册
              </NButton>
              <NButton
                v-if="hasAuth('ticket:phone:edit')"
                ghost
                size="small"
                type="success"
                @click="handleStatusChange('available')"
              >
                启用
              </NButton>
              <NButton
                v-if="hasAuth('ticket:phone:edit')"
                ghost
                size="small"
                type="warning"
                @click="handleStatusChange('disabled')"
              >
                停用
              </NButton>
            </NSpace>
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
        :row-key="row => row.phoneId"
        :pagination="mobilePagination"
        class="sm:h-full"
      />
    </NCard>

    <NModal
      v-model:show="registerModalVisible"
      preset="card"
      title="批量注册平台账号"
      class="w-1100px"
      :mask-closable="!registerRunning"
    >
      <div class="flex-col-stretch gap-16px">
        <NCard size="small" :bordered="false">
          <NForm inline label-placement="left" :label-width="80">
            <NFormItem label="国家区号">
              <NSelect
                v-model:value="registerModalSearchParams.countryCode"
                :disabled="registerRunning"
                :options="countryCodeOptions"
                placeholder="请选择国家区号"
                class="w-160px"
              />
            </NFormItem>
            <NFormItem label="目标平台">
              <NSelect
                v-model:value="registerPlatformId"
                :disabled="registerRunning"
                filterable
                :options="platformOptions"
                placeholder="请选择平台"
                class="w-220px"
              />
            </NFormItem>
            <NFormItem label="号码">
              <NInput
                v-model:value="registerModalSearchParams.phoneNumber"
                :disabled="registerRunning"
                clearable
                placeholder="支持按号码搜索"
              />
            </NFormItem>
            <NFormItem>
              <NSpace>
                <NButton type="primary" :disabled="!registerReady" @click="handleRegisterSearch">筛选号码</NButton>
              </NSpace>
            </NFormItem>
          </NForm>
        </NCard>

        <NCard size="small" :bordered="false" title="执行面板">
          <NSpace justify="space-between" class="w-full">
            <NSpace>
              <NTag :bordered="false" type="info">总数 {{ registerProgress.totalCount }}</NTag>
              <NTag :bordered="false" type="default">已处理 {{ registerProgress.processedCount }}</NTag>
              <NTag :bordered="false" type="success">成功 {{ registerProgress.successCount }}</NTag>
              <NTag :bordered="false" type="error">失败 {{ registerProgress.failedCount }}</NTag>
              <NTag :bordered="false" type="warning">跳过 {{ registerProgress.skippedCount }}</NTag>
            </NSpace>
            <div class="text-14px text-text-secondary">当前处理：{{ registerProgress.currentPhone || '-' }}</div>
          </NSpace>
        </NCard>

        <NCard size="small" :bordered="false" title="候选号码">
          <template #header-extra>
            <NSpace>
              <NButton
                type="primary"
                :disabled="registerRunning || !registerCheckedRowKeys.length"
                @click="handleStartRegister"
              >
                开始注册
              </NButton>
            </NSpace>
          </template>
          <NDataTable
            v-model:checked-row-keys="registerCheckedRowKeys"
            :columns="registerColumns"
            :data="registerableRows"
            :loading="registerableLoading"
            remote
            size="small"
            :row-key="row => row.phoneId"
            :pagination="registerPagination"
          />
        </NCard>
      </div>
    </NModal>
  </div>
</template>
