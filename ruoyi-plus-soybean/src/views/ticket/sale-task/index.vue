<script setup lang="tsx">
import { computed, h, onMounted, ref, watch } from 'vue';
import { NButton, NPopconfirm, NTag, NPopover, type DataTableColumns } from 'naive-ui';
import { useAuth } from '@/hooks/business/auth';
import { defaultTransform, useNaivePaginatedTable } from '@/hooks/common/table';
import {
  fetchCreateTicketSaleTask,
  fetchDeleteTicketSaleTask,
  fetchGetTicketAccountList,
  fetchGetTicketPlatformList,
  fetchGetTicketPurchaseTemplate,
  fetchGetTicketSaleTaskList,
  fetchUpdateTicketSaleTask
} from '@/service/api/ticket';
import { useAppStore } from '@/store/modules/app';
import {
  purchaseTypeOptions,
  renderTicketEllipsis,
  renderTicketEmail,
  renderTicketJsonSummary,
  renderTicketTag,
  taskStatusOptions
} from '../common';

defineOptions({
  name: 'TicketSaleTaskList'
});

const appStore = useAppStore();
const { hasAuth } = useAuth();

function createSearchParams(): Api.Ticket.SaleTaskSearchParams {
  return {
    pageNum: 1,
    pageSize: 10,
    platformId: null,
    purchaseType: null,
    taskName: null,
    taskStatus: null,
    params: {}
  };
}

function createFormModel(): Api.Ticket.SaleTaskOperateParams {
  return {
    taskId: undefined,
    platformId: undefined,
    taskName: '',
    taskStatus: 'draft',
    purchaseType: 'flash_sale',
    configSchemaKey: '',
    warmupTime: '',
    scheduledTime: '',
    purchaseQuantity: 1,
    taskOptions: '{\n  \n}',
    accountIds: [],
    remark: ''
  };
}

const searchParams = ref<Api.Ticket.SaleTaskSearchParams>(createSearchParams());
const checkedRowKeys = ref<CommonType.IdType[]>([]);
const modalVisible = ref(false);
const operateType = ref<NaiveUI.TableOperateType>('add');
const formModel = ref<Api.Ticket.SaleTaskOperateParams>(createFormModel());
const platformOptions = ref<{ label: string; value: CommonType.IdType }[]>([]);
const accountTableData = ref<Api.Ticket.Account[]>([]);
const accountOptionsLoading = ref(false);
const templateLoading = ref(false);
const accountSearchKeyword = ref('');
const lastLoadedTemplateText = ref('{\\n  \\n}');
const pauseSelectionWatcher = ref(false);

const modalTitle = computed(() => (operateType.value === 'add' ? '新增商品抢购任务' : '编辑商品抢购任务'));
const selectedAccountCount = computed(() => formModel.value.accountIds?.length || 0);
const selectedAccountPreview = computed(() =>
  accountTableData.value.filter(item => formModel.value.accountIds?.includes(item.accountId)).slice(0, 3)
);
const checkedAccountRowKeys = computed<CommonType.IdType[]>({
  get() {
    return formModel.value.accountIds || [];
  },
  set(value) {
    formModel.value.accountIds = value;
  }
});

const filteredAccountTableData = computed(() => {
  const keyword = accountSearchKeyword.value.trim().toLowerCase();
  if (!keyword) {
    return accountTableData.value;
  }
  return accountTableData.value.filter(item =>
    [String(item.accountId), item.email, item.phoneNumber, item.platformName]
      .filter(Boolean)
      .some(text => String(text).toLowerCase().includes(keyword))
  );
});

const accountTableColumns = computed<DataTableColumns<Api.Ticket.Account>>(() => [
  { type: 'selection', align: 'center', width: 48 },
  { key: 'phoneNumber', title: '来源号码', align: 'center', width: 130 },
  {
    key: 'email',
    title: '邮箱',
    align: 'left',
    minWidth: 220,
    render: row => renderTicketEmail(row.email)
  },
  {
    key: 'accountInfo',
    title: '账号信息',
    align: 'left',
    minWidth: 220,
    render: row => renderTicketJsonSummary(row.accountInfo, ['nickname', 'source'])
  },
  {
    key: 'reqData',
    title: '登录上下文',
    align: 'left',
    minWidth: 220,
    render: row => renderTicketJsonSummary(row.reqData, ['channel', 'platformCode', 'sessionToken'])
  },
  {
    key: 'lastLoginTime',
    title: '最近登录',
    align: 'center',
    width: 160,
    render: row => row.lastLoginTime || '-'
  }
]);

const { columns, columnChecks, data, getData, getDataByPage, loading, mobilePagination, scrollX } =
  useNaivePaginatedTable({
    api: () => fetchGetTicketSaleTaskList(searchParams.value),
    transform: response => defaultTransform(response),
    onPaginationParamsChange: params => {
      searchParams.value.pageNum = params.page;
      searchParams.value.pageSize = params.pageSize;
    },
    columns: () => [
      { type: 'selection', align: 'center', width: 48 },
      {
        key: 'taskName',
        title: '任务信息',
        align: 'left',
        minWidth: 240,
        render: row => renderSaleTaskIdentity(row)
      },
      {
        key: 'platformName',
        title: '平台',
        align: 'left',
        minWidth: 160,
        render: row => renderPlatformName(row.platformName)
      },
      {
        key: 'purchaseType',
        title: '抢购类型',
        align: 'center',
        width: 110,
        render: row => renderTicketTag(row.purchaseType)
      },
      { key: 'purchaseQuantity', title: '单账号数量', align: 'center', width: 110 },
      {
        key: 'taskAccounts',
        title: '执行账号',
        align: 'left',
        minWidth: 280,
        render: row => renderSaleTaskAccounts(row)
      },
      {
        key: 'taskOptions',
        title: '平台预设配置',
        align: 'left',
        minWidth: 260,
        render: row => renderSaleTaskOptions(row.taskOptions)
      },
      {
        key: 'taskStatus',
        title: '任务状态',
        align: 'center',
        width: 110,
        render: row => renderTicketTag(row.taskStatus)
      },
      {
        key: 'warmupTime',
        title: '预热时间',
        align: 'center',
        minWidth: 150,
        render: row => renderSaleTaskTime(row.warmupTime, '未设置')
      },
      {
        key: 'scheduledTime',
        title: '计划抢购',
        align: 'center',
        minWidth: 170,
        render: row => renderSaleTaskTime(row.scheduledTime, '保存后立即排队')
      },
      {
        key: 'lastExecutedTime',
        title: '最近执行',
        align: 'center',
        minWidth: 150,
        render: row => renderSaleTaskTime(row.lastExecutedTime, '未执行')
      },
      {
        key: 'operate',
        title: '操作',
        align: 'center',
        fixed: 'right',
        width: 150,
        render: row => (
          <div class="flex-center gap-8px">
            {hasAuth('ticket:saleTask:edit') && (
              <NButton text type="primary" onClick={() => handleEdit(row)}>
                编辑
              </NButton>
            )}
            {hasAuth('ticket:saleTask:remove') && (
              <NPopconfirm onPositiveClick={() => handleDelete([row.taskId])}>
                {{
                  trigger: () => (
                    <NButton text type="error">
                      删除
                    </NButton>
                  ),
                  default: () => '确认删除该抢购任务吗？'
                }}
              </NPopconfirm>
            )}
          </div>
        )
      }
    ]
  });

watch(
  () => [formModel.value.platformId, formModel.value.purchaseType] as const,
  async ([platformId, purchaseType], [previousPlatformId, previousPurchaseType]) => {
    if (pauseSelectionWatcher.value || !modalVisible.value) return;
    if (platformId === previousPlatformId && purchaseType === previousPurchaseType) return;

    formModel.value.accountIds = [];
    accountSearchKeyword.value = '';
    await loadAccountOptions(platformId);

    if (!platformId || !purchaseType) return;

    const currentTaskOptions = formModel.value.taskOptions || '';
    const shouldPrompt =
      !!previousPlatformId &&
      !!previousPurchaseType &&
      currentTaskOptions.trim() &&
      currentTaskOptions.trim() !== lastLoadedTemplateText.value.trim();

    if (shouldPrompt) {
      const confirmed = window.confirm('切换平台或抢购类型会覆盖当前未保存的预设配置，是否继续？');
      if (!confirmed) {
        pauseSelectionWatcher.value = true;
        formModel.value.platformId = previousPlatformId;
        formModel.value.purchaseType = String(previousPurchaseType || 'flash_sale') as Api.Ticket.SaleTaskOperateParams['purchaseType'];
        pauseSelectionWatcher.value = false;
        await loadAccountOptions(previousPlatformId);
        return;
      }
    }

    await applyPurchaseTemplate(platformId, purchaseType, true);
  }
);

onMounted(() => {
  void loadPlatformOptions();
  void getData();
});

function parseTaskJson(value?: string | null) {
  if (!value) return null;
  try {
    return JSON.parse(value) as Record<string, unknown>;
  } catch {
    return null;
  }
}

function formatTaskOptions(value: Record<string, unknown>) {
  return JSON.stringify(value, null, 2);
}

function renderSaleTaskIdentity(row: Api.Ticket.SaleTask) {
  return h('div', { class: 'sale-task-cell sale-task-cell--identity' }, [
    h('div', { class: 'sale-task-cell__title' }, row.taskName || '-'),
    h('div', { class: 'sale-task-cell__sub' }, `配置方案 · ${row.configSchemaKey || row.purchaseType || '-'}`)
  ]);
}

function renderPlatformName(platformName?: string | null) {
  return h('div', { class: 'sale-task-cell' }, [
    h('div', { class: 'sale-task-cell__title sale-task-cell__title--platform' }, platformName || '-')
  ]);
}

function splitAccountEmails(value?: string | null) {
  return (value || '')
    .split(',')
    .map(item => item.trim())
    .filter(Boolean);
}

function renderSaleTaskAccounts(row: Api.Ticket.SaleTask) {
  const emails = splitAccountEmails(row.accountEmails);
  const previewEmails = emails.slice(0, 2);
  return h('div', { class: 'sale-task-cell sale-task-cell--accounts' }, [
    h('div', { class: 'sale-task-account-count' }, [
      h('span', { class: 'sale-task-account-count__value' }, String(row.boundAccountCount || 0)),
      h('span', { class: 'sale-task-account-count__label' }, '个账号')
    ]),
    emails.length
      ? h(
          NPopover,
          { trigger: 'hover', placement: 'left', width: 280 },
          {
            trigger: () =>
              h(
                'div',
                { class: 'sale-task-account-list' },
                previewEmails.map(email =>
                  h(
                    NTag,
                    {
                      size: 'small',
                      round: true,
                      type: 'success',
                      bordered: false
                    },
                    { default: () => email }
                  )
                ).concat(
                  emails.length > previewEmails.length
                    ? [h('span', { class: 'sale-task-account-list__more' }, `+${emails.length - previewEmails.length}`)]
                    : []
                )
              ),
            default: () =>
              h(
                'div',
                { class: 'sale-task-account-popover' },
                emails.map(email => h('div', { class: 'sale-task-account-popover__item' }, email))
              )
          }
        )
      : h('div', { class: 'sale-task-cell__sub' }, '未绑定账号')
  ]);
}

function renderSaleTaskOptions(value?: string | null) {
  const preferredKeys = ['ticketsPageUrl', 'ticketQuantity', 'paymentMethod', 'purchaseMode', 'pickupStoreCode', 'deliveryOption', 'lotteryEntryUrl'];
  return renderTicketJsonSummary(value, preferredKeys);
}

function renderSaleTaskTime(value?: string | null, fallback = '-') {
  return renderTicketEllipsis(value || fallback);
}

async function loadPlatformOptions() {
  const { data: response, error } = await fetchGetTicketPlatformList({ pageNum: 1, pageSize: 200, enabled: true });
  if (error) {
    return;
  }
  const rows = response?.rows || [];
  platformOptions.value = rows.map((item: Api.Ticket.Platform) => ({ label: item.platformName, value: item.platformId }));
}

async function loadAccountOptions(platformId?: CommonType.IdType | null) {
  if (!platformId) {
    accountTableData.value = [];
    return;
  }
  accountOptionsLoading.value = true;
  try {
    const { data: response, error } = await fetchGetTicketAccountList({
      pageNum: 1,
      pageSize: 200,
      platformId,
      accountStatus: 'registered',
      loginStatus: 'logged_in'
    });
    accountTableData.value = error ? [] : response?.rows || [];
  } finally {
    accountOptionsLoading.value = false;
  }
}

async function applyPurchaseTemplate(platformId: CommonType.IdType, purchaseType: string, force = false) {
  if (!platformId || !purchaseType) return;
  const currentTaskOptions = formModel.value.taskOptions || '';
  if (!force && currentTaskOptions.trim() && currentTaskOptions.trim() !== lastLoadedTemplateText.value.trim()) {
    return;
  }
  templateLoading.value = true;
  try {
    const { data: template, error } = await fetchGetTicketPurchaseTemplate(platformId, purchaseType);
    if (error || !template) {
      return;
    }
    formModel.value.configSchemaKey = template.configSchemaKey;
    formModel.value.taskOptions = formatTaskOptions(template.configTemplate || {});
    lastLoadedTemplateText.value = formModel.value.taskOptions || '{\n  \n}';
  } finally {
    templateLoading.value = false;
  }
}

function openAdd() {
  operateType.value = 'add';
  pauseSelectionWatcher.value = true;
  formModel.value = createFormModel();
  pauseSelectionWatcher.value = false;
  lastLoadedTemplateText.value = formModel.value.taskOptions || '{\n  \n}';
  accountSearchKeyword.value = '';
  accountTableData.value = [];
  modalVisible.value = true;
}

async function handleEdit(row: Api.Ticket.SaleTask) {
  operateType.value = 'edit';
  pauseSelectionWatcher.value = true;
  formModel.value = {
    taskId: row.taskId,
    platformId: row.platformId,
    taskName: row.taskName,
    taskStatus: row.taskStatus,
    purchaseType: row.purchaseType,
    configSchemaKey: row.configSchemaKey,
    warmupTime: row.warmupTime,
    scheduledTime: row.scheduledTime,
    purchaseQuantity: row.purchaseQuantity,
    taskOptions: row.taskOptions || '{\n  \n}',
    accountIds: row.accountIds || [],
    remark: row.remark || ''
  };
  pauseSelectionWatcher.value = false;
  lastLoadedTemplateText.value = formModel.value.taskOptions || '{\n  \n}';
  accountSearchKeyword.value = '';
  await loadAccountOptions(row.platformId);
  modalVisible.value = true;
}

async function handleSubmit() {
  try {
    JSON.parse(formModel.value.taskOptions || '{}');
  } catch {
    window.$message?.error('平台预设配置必须是合法 JSON');
    return;
  }

  if (!formModel.value.platformId) {
    window.$message?.error('请选择目标平台');
    return;
  }
  if (!formModel.value.purchaseType) {
    window.$message?.error('请选择抢购类型');
    return;
  }
  if (!formModel.value.accountIds?.length) {
    window.$message?.error('请至少选择一个执行账号');
    return;
  }

  const requestFn = operateType.value === 'add' ? fetchCreateTicketSaleTask : fetchUpdateTicketSaleTask;
  await requestFn(formModel.value);
  window.$message?.success(formModel.value.scheduledTime ? '任务已排队，系统将按计划时间自动抢购' : '任务已排队，系统将立即执行');
  modalVisible.value = false;
  await getData();
}

async function handleDelete(taskIds: CommonType.IdType[]) {
  if (!taskIds.length) return;
  await fetchDeleteTicketSaleTask(taskIds);
  checkedRowKeys.value = checkedRowKeys.value.filter(item => !taskIds.includes(item));
  window.$message?.success('删除成功');
  await getData();
}

function resetSearch() {
  searchParams.value = createSearchParams();
  checkedRowKeys.value = [];
  void getDataByPage();
}
</script>

<template>
  <div class="h-full flex-col-stretch gap-16px overflow-hidden">
    <NCard title="商品抢购任务筛选" :bordered="false" size="small" class="card-wrapper">
      <NForm inline :label-width="80">
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
        <NFormItem label="抢购类型">
          <NSelect
            v-model:value="searchParams.purchaseType"
            clearable
            :options="purchaseTypeOptions"
            placeholder="请选择抢购类型"
            class="w-160px"
          />
        </NFormItem>
        <NFormItem label="任务名称">
          <NInput v-model:value="searchParams.taskName" clearable placeholder="请输入任务名称" />
        </NFormItem>
        <NFormItem label="任务状态">
          <NSelect
            v-model:value="searchParams.taskStatus"
            clearable
            :options="taskStatusOptions"
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

    <NCard title="商品抢购任务" :bordered="false" size="small" class="card-wrapper sm:flex-1-hidden">
      <template #header-extra>
        <TableHeaderOperation
          v-model:columns="columnChecks"
          :disabled-delete="checkedRowKeys.length === 0"
          :loading="loading"
          :show-add="hasAuth('ticket:saleTask:add')"
          :show-delete="hasAuth('ticket:saleTask:remove')"
          @add="openAdd"
          @delete="handleDelete(checkedRowKeys)"
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
        :row-key="row => row.taskId"
        :pagination="mobilePagination"
        class="sm:h-full"
      />
    </NCard>

    <NModal v-model:show="modalVisible" preset="card" :title="modalTitle" class="w-900px purchase-task-modal">
      <NForm label-placement="top" :model="formModel">
        <NGrid :cols="24" :x-gap="16">
          <NFormItemGi :span="12" label="目标平台">
            <NSelect v-model:value="formModel.platformId" filterable :options="platformOptions" />
          </NFormItemGi>
          <NFormItemGi :span="12" label="抢购类型">
            <NSelect v-model:value="formModel.purchaseType" :options="purchaseTypeOptions" />
          </NFormItemGi>
          <NFormItemGi :span="12" label="任务名称">
            <NInput v-model:value="formModel.taskName" />
          </NFormItemGi>
          <NFormItemGi :span="12" label="单账号数量">
            <NInputNumber v-model:value="formModel.purchaseQuantity" class="w-full" :min="1" />
          </NFormItemGi>
          <NFormItemGi :span="12" label="预热时间">
            <NInput v-model:value="formModel.warmupTime" placeholder="2026-04-10 19:55:00" />
          </NFormItemGi>
          <NFormItemGi :span="12" label="计划抢购时间">
            <NInput v-model:value="formModel.scheduledTime" placeholder="不填则保存后自动立即抢购，例如：2026-04-10 20:00:00" />
          </NFormItemGi>
          <NFormItemGi :span="24" label="指定账号">
            <div class="account-picker w-full">
              <div class="account-picker__header">
                <div>
                  <div class="account-picker__title">已登录账号池</div>
                  <div class="account-picker__hint">
                    <span v-if="formModel.platformId">先筛选，再勾选需要执行任务的账号</span>
                    <span v-else>选择目标平台后，这里会自动加载可用账号</span>
                  </div>
                </div>
                <div class="account-picker__meta">
                  <div class="account-picker__meta-item">
                    <span class="account-picker__meta-label">已加载</span>
                    <strong>{{ accountTableData.length }}</strong>
                  </div>
                  <div class="account-picker__meta-item account-picker__meta-item--active">
                    <span class="account-picker__meta-label">已选择</span>
                    <strong>{{ selectedAccountCount }}</strong>
                  </div>
                </div>
              </div>

              <div class="account-picker__toolbar">
                <NInput
                  v-model:value="accountSearchKeyword"
                  clearable
                  placeholder="搜索邮箱 / 来源号码 / 账号ID"
                  class="w-full"
                  :disabled="!formModel.platformId"
                />
              </div>

              <div v-if="selectedAccountPreview.length" class="account-picker__summary">
                <span class="account-picker__summary-label">当前已选</span>
                <NTag
                  v-for="item in selectedAccountPreview"
                  :key="item.accountId"
                  size="small"
                  round
                  type="success"
                  :bordered="false"
                >
                  {{ item.email }}
                </NTag>
                <span v-if="selectedAccountCount > selectedAccountPreview.length" class="account-picker__summary-more">
                  +{{ selectedAccountCount - selectedAccountPreview.length }}
                </span>
              </div>

              <div v-if="!formModel.platformId" class="account-picker__empty">
                <div class="account-picker__empty-title">还没有加载账号</div>
                <div class="account-picker__empty-text">先选择目标平台，再从已登录账号里勾选参与任务的账号。</div>
              </div>
              <div v-else class="account-picker__table">
                <NDataTable
                  v-model:checked-row-keys="checkedAccountRowKeys"
                  :columns="accountTableColumns"
                  :data="filteredAccountTableData"
                  size="small"
                  max-height="300"
                  :bordered="false"
                  :loading="accountOptionsLoading"
                  :row-key="row => row.accountId"
                  :single-line="false"
                />
              </div>
            </div>
          </NFormItemGi>
          <NFormItemGi :span="24" label="平台预设配置">
            <div class="flex-col gap-8px">
              <div class="text-12px text-text-3">
                {{ templateLoading ? '正在加载平台预设配置...' : `当前配置方案：${formModel.configSchemaKey || '未生成'}` }}
              </div>
              <NInput
                v-model:value="formModel.taskOptions"
                type="textarea"
                :rows="8"
                placeholder='例如：{"ticketsPageUrl":"https://livepocket.jp/e/.../tickets","ticketQuantity":1}'
              />
            </div>
          </NFormItemGi>
          <NFormItemGi :span="24" label="备注">
            <NInput v-model:value="formModel.remark" type="textarea" :rows="3" />
          </NFormItemGi>
        </NGrid>
      </NForm>
      <template #footer>
        <div class="flex justify-end gap-12px">
          <NButton @click="modalVisible = false">取消</NButton>
          <NButton type="primary" @click="handleSubmit">保存</NButton>
        </div>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.purchase-task-modal :deep(.n-card) {
  border-radius: 20px;
}

:deep(.sale-task-cell) {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding-right: 8px;
}

:deep(.sale-task-cell__title) {
  font-size: 13px;
  line-height: 20px;
  font-weight: 600;
  color: #243247;
}

:deep(.sale-task-cell__title--platform) {
  font-weight: 500;
}

:deep(.sale-task-cell__sub) {
  font-size: 12px;
  line-height: 18px;
  color: #6b7a90;
}

:deep(.sale-task-cell--accounts) {
  gap: 8px;
}

:deep(.sale-task-account-count) {
  display: inline-flex;
  align-items: baseline;
  gap: 4px;
}

:deep(.sale-task-account-count__value) {
  font-size: 18px;
  line-height: 22px;
  font-weight: 700;
  color: #1d4ed8;
}

:deep(.sale-task-account-count__label) {
  font-size: 12px;
  color: #6b7a90;
}

:deep(.sale-task-account-list) {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  cursor: help;
}

:deep(.sale-task-account-list__more) {
  font-size: 12px;
  color: #6b7a90;
}

:deep(.sale-task-account-popover) {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-width: 280px;
}

:deep(.sale-task-account-popover__item) {
  font-size: 12px;
  line-height: 18px;
  color: #334155;
}

.account-picker {
  display: flex;
  flex-direction: column;
  gap: 12px;
  border: 1px solid #e2e8f0;
  border-radius: 16px;
  background: linear-gradient(180deg, #fcfdff 0%, #f8fbff 100%);
  padding: 16px;
}

.account-picker__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.account-picker__title {
  font-size: 14px;
  font-weight: 600;
  color: #243247;
}

.account-picker__hint {
  margin-top: 4px;
  font-size: 12px;
  color: #6b7a90;
}

.account-picker__meta {
  display: flex;
  gap: 12px;
}

.account-picker__meta-item {
  min-width: 72px;
  border-radius: 12px;
  background: #fff;
  padding: 10px 12px;
  box-shadow: inset 0 0 0 1px rgba(148, 163, 184, 0.16);
  text-align: center;
}

.account-picker__meta-item--active {
  background: #eef4ff;
  box-shadow: inset 0 0 0 1px rgba(59, 130, 246, 0.2);
}

.account-picker__meta-label {
  display: block;
  margin-bottom: 4px;
  font-size: 11px;
  color: #6b7a90;
}

.account-picker__toolbar,
.account-picker__summary {
  display: flex;
  align-items: center;
  gap: 8px;
}

.account-picker__summary {
  flex-wrap: wrap;
}

.account-picker__summary-label,
.account-picker__summary-more {
  font-size: 12px;
  color: #6b7a90;
}

.account-picker__table {
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.92);
  padding: 8px;
}

.account-picker__empty {
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.86);
  padding: 24px;
  text-align: center;
}

.account-picker__empty-title {
  font-size: 14px;
  font-weight: 600;
  color: #243247;
}

.account-picker__empty-text {
  margin-top: 6px;
  font-size: 12px;
  color: #6b7a90;
}
</style>
