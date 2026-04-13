<script setup lang="tsx">
import { computed, h, onMounted, ref, watch } from 'vue';
import { NButton, NEllipsis, NPopconfirm, NPopover, NTag, type DataTableColumns } from 'naive-ui';
import { useAuth } from '@/hooks/business/auth';
import { defaultTransform, useNaivePaginatedTable } from '@/hooks/common/table';
import {
  fetchCreateTicketSaleTask,
  fetchDeleteTicketSaleTask,
  fetchGetTicketAccountList,
  fetchGetTicketPlatformList,
  fetchGetTicketSaleTaskList,
  fetchUpdateTicketSaleTask
} from '@/service/api/ticket';
import { useAppStore } from '@/store/modules/app';
import {
  renderTicketEllipsis,
  renderTicketEmail,
  renderTicketJsonSummary,
  renderTicketTag,
  orderFlowTypeOptions,
  fulfillmentTypeOptions,
  paymentModeOptions,
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
    productId: null,
    taskName: null,
    taskStatus: null,
    params: {}
  };
}

function createFormModel(): Api.Ticket.SaleTaskOperateParams {
  return {
    taskId: undefined,
    platformId: undefined,
    productId: '',
    taskName: '',
    taskStatus: 'draft',
    orderFlowType: 'direct_order',
    fulfillmentType: 'shipping',
    paymentMode: 'pending_manual',
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
const accountSearchKeyword = ref('');

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
        title: '任务 / 商品',
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
        key: 'taskFlow',
        title: '流程配置',
        align: 'left',
        minWidth: 250,
        render: row => renderSaleTaskFlow(row)
      },
      { key: 'purchaseQuantity', title: '单账号数量', align: 'center', width: 110 },
      {
        key: 'taskAccounts',
        title: '执行账号',
        align: 'left',
        minWidth: 260,
        render: row => renderSaleTaskAccounts(row)
      },
      {
        key: 'taskOptions',
        title: '履约参数',
        align: 'left',
        minWidth: 220,
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
        minWidth: 160,
        render: row => renderSaleTaskTime(row.scheduledTime, '立即执行')
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
        width: 180,
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

const modalTitle = computed(() => (operateType.value === 'add' ? '新增商品抢购任务' : '编辑商品抢购任务'));
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

  return accountTableData.value.filter(item => {
    return [item.email, item.phoneNumber, String(item.accountId)]
      .filter(Boolean)
      .some(value => String(value).toLowerCase().includes(keyword));
  });
});
const selectedAccountCount = computed(() => checkedAccountRowKeys.value.length);
const selectedAccountPreview = computed(() => {
  const selectedIds = new Set((checkedAccountRowKeys.value || []).map(id => String(id)));
  return accountTableData.value.filter(item => selectedIds.has(String(item.accountId))).slice(0, 4);
});
const accountTableColumns: DataTableColumns<Api.Ticket.Account> = [
  { type: 'selection', align: 'center', width: 48 },
  { key: 'phoneNumber', title: '来源号码', align: 'center', width: 140 },
  {
    key: 'email',
    title: '邮箱',
    align: 'center',
    minWidth: 220,
    render: (row: Api.Ticket.Account) => renderTicketEmail(row.email)
  },
  {
    key: 'accountInfo',
    title: '账号信息',
    align: 'center',
    minWidth: 240,
    render: (row: Api.Ticket.Account) => renderTicketJsonSummary(row.accountInfo, ['nickname', 'source'])
  },
  {
    key: 'loginStatus',
    title: '登录状态',
    align: 'center',
    width: 100,
    render: (row: Api.Ticket.Account) => renderTicketTag(row.loginStatus)
  },
  {
    key: 'lastLoginTime',
    title: '最近登录',
    align: 'center',
    width: 170,
    render: (row: Api.Ticket.Account) => row.lastLoginTime || '-'
  }
];

function parseTaskJson(value?: string | null) {
  if (!value) return null;

  try {
    const parsed = JSON.parse(value);
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return parsed as Record<string, unknown>;
    }
  } catch {
    return null;
  }

  return null;
}

function splitAccountEmails(value?: string | null) {
  return (value || '')
    .split(/[,\n;]/)
    .map(item => item.trim())
    .filter(Boolean);
}

function renderSaleTaskIdentity(row: Api.Ticket.SaleTask) {
  return h('div', { class: 'sale-task-cell sale-task-cell--identity' }, [
    h('div', { class: 'sale-task-cell__title' }, row.taskName || '-'),
    h('div', { class: 'sale-task-cell__sub' }, `商品ID · ${row.productId || '-'}`)
  ]);
}

function renderPlatformName(platformName?: string | null) {
  return h('div', { class: 'sale-task-cell' }, [
    h('div', { class: 'sale-task-cell__title sale-task-cell__title--platform' }, platformName || '-')
  ]);
}

function renderSaleTaskFlow(row: Api.Ticket.SaleTask) {
  return h('div', { class: 'sale-task-flow' }, [
    renderTicketTag(row.orderFlowType),
    renderTicketTag(row.fulfillmentType),
    renderTicketTag(row.paymentMode)
  ]);
}

function renderSaleTaskAccounts(row: Api.Ticket.SaleTask) {
  const emails = splitAccountEmails(row.accountEmails);
  const previewEmails = emails.slice(0, 2);
  const count = row.boundAccountCount || emails.length || 0;

  return h('div', { class: 'sale-task-cell sale-task-cell--accounts' }, [
    h('div', { class: 'sale-task-account-count' }, [
      h('span', { class: 'sale-task-account-count__value' }, String(count)),
      h('span', { class: 'sale-task-account-count__label' }, '个账号')
    ]),
    previewEmails.length
      ? h(
          NPopover,
          { trigger: 'hover', placement: 'left', width: 320 },
          {
            trigger: () =>
              h('div', { class: 'sale-task-account-list' }, [
                ...previewEmails.map(email =>
                  h(
                    NTag,
                    {
                      key: email,
                      size: 'small',
                      bordered: false,
                      round: true,
                      type: 'success'
                    },
                    { default: () => email }
                  )
                ),
                emails.length > previewEmails.length
                  ? h('span', { class: 'sale-task-account-list__more' }, `+${emails.length - previewEmails.length}`)
                  : null
              ]),
            default: () =>
              h(
                'div',
                { class: 'sale-task-account-popover' },
                emails.map(email =>
                  h(
                    'div',
                    {
                      key: email,
                      class: 'sale-task-account-popover__item'
                    },
                    email
                  )
                )
              )
          }
        )
      : h('div', { class: 'sale-task-cell__sub' }, '未绑定账号')
  ]);
}

function renderSaleTaskOptions(value?: string | null) {
  const parsed = parseTaskJson(value);
  if (!parsed) {
    return renderTicketEllipsis(value || '-');
  }

  const summaryEntries = [
    ['pickupStoreCode', '门店'],
    ['deliveryOption', '配送'],
    ['mockBehavior', 'Mock']
  ]
    .filter(([key]) => parsed[key] !== undefined && parsed[key] !== null && String(parsed[key]).trim() !== '')
    .map(([key, label]) => ({ key, label, value: String(parsed[key]) }));

  if (!summaryEntries.length) {
    return h('div', { class: 'sale-task-cell__sub' }, '无额外参数');
  }

  return h(
    NPopover,
    { trigger: 'hover', placement: 'left', width: 360 },
    {
      trigger: () =>
        h('div', { class: 'sale-task-options' }, [
          ...summaryEntries.slice(0, 2).map(item =>
            h('div', { key: item.key, class: 'sale-task-options__row' }, [
              h('span', { class: 'sale-task-options__label' }, item.label),
              h(
                NEllipsis,
                { style: { maxWidth: '128px' }, tooltip: false },
                { default: () => item.value }
              )
            ])
          ),
          h('div', { class: 'sale-task-options__meta' }, `共 ${Object.keys(parsed).length} 个字段`)
        ]),
      default: () =>
        h(
          'pre',
          { class: 'sale-task-options__popover' },
          JSON.stringify(parsed, null, 2)
        )
    }
  );
}

function renderSaleTaskTime(value?: string | null, emptyText = '-') {
  return h('div', { class: 'sale-task-time' }, value || emptyText);
}

async function loadPlatformOptions() {
  const { data: platformList, error } = await fetchGetTicketPlatformList({ pageNum: 1, pageSize: 200, params: {} });
  if (error) return;
  platformOptions.value = (platformList.rows || []).map(item => ({ label: item.platformName, value: item.platformId }));
}

async function loadAccountOptions(platformId?: CommonType.IdType | null) {
  accountTableData.value = [];
  if (!platformId) {
    return;
  }
  accountOptionsLoading.value = true;
  const { data: accountList, error } = await fetchGetTicketAccountList({
    pageNum: 1,
    pageSize: 1000,
    platformId,
    loginStatus: 'logged_in',
    params: {}
  });
  accountOptionsLoading.value = false;
  if (error) return;
  accountTableData.value = accountList.rows || [];
}

watch(
  () => formModel.value.platformId,
  async (platformId, previousPlatformId) => {
    if (platformId === previousPlatformId) return;
    formModel.value.accountIds = [];
    accountSearchKeyword.value = '';
    await loadAccountOptions(platformId);
  }
);

onMounted(() => {
  void getData();
  void loadPlatformOptions();
});

function resetSearch() {
  searchParams.value = createSearchParams();
  checkedRowKeys.value = [];
  void getDataByPage();
}

function openAdd() {
  operateType.value = 'add';
  formModel.value = createFormModel();
  accountSearchKeyword.value = '';
  accountTableData.value = [];
  modalVisible.value = true;
}

async function handleEdit(row: Api.Ticket.SaleTask) {
  operateType.value = 'edit';
  formModel.value = {
    taskId: row.taskId,
    platformId: row.platformId,
    productId: row.productId,
    taskName: row.taskName,
    taskStatus: row.taskStatus,
    orderFlowType: row.orderFlowType,
    fulfillmentType: row.fulfillmentType,
    paymentMode: row.paymentMode,
    warmupTime: row.warmupTime,
    scheduledTime: row.scheduledTime,
    purchaseQuantity: row.purchaseQuantity,
    taskOptions: row.taskOptions || '{\n  \n}',
    accountIds: row.accountIds || [],
    remark: row.remark || ''
  };
  accountSearchKeyword.value = '';
  await loadAccountOptions(row.platformId);
  modalVisible.value = true;
}

async function handleSubmit() {
  if (formModel.value.taskOptions) {
    try {
      JSON.parse(formModel.value.taskOptions);
    } catch {
      window.$message?.error('平台扩展参数必须是合法 JSON');
      return;
    }
  }
  const requestFn = operateType.value === 'add' ? fetchCreateTicketSaleTask : fetchUpdateTicketSaleTask;
  const { error } = await requestFn(formModel.value);
  if (error) return;
  const hasFutureSchedule = !!String(formModel.value.scheduledTime || '').trim();
  window.$message?.success(
    hasFutureSchedule ? '任务已排队，系统将按计划时间自动抢购' : '任务已排队，系统将立即执行'
  );
  modalVisible.value = false;
  await getData();
}

async function handleDelete(ids: CommonType.IdType[]) {
  const { error } = await fetchDeleteTicketSaleTask(ids);
  if (error) return;
  window.$message?.success('商品抢购任务已删除');
  checkedRowKeys.value = checkedRowKeys.value.filter(key => !ids.includes(key));
  await getData();
}

</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <NCard title="商品抢购任务筛选" :bordered="false" size="small" class="card-wrapper">
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
        <NFormItem label="商品ID">
          <NInput v-model:value="searchParams.productId" clearable placeholder="请输入商品ID" />
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
          <NFormItemGi :span="12" label="任务名称">
            <NInput v-model:value="formModel.taskName" />
          </NFormItemGi>
          <NFormItemGi :span="12" label="商品ID">
            <NInput v-model:value="formModel.productId" placeholder="请输入平台商品ID" />
          </NFormItemGi>
          <NFormItemGi :span="8" label="下单方式">
            <NSelect v-model:value="formModel.orderFlowType" :options="orderFlowTypeOptions" />
          </NFormItemGi>
          <NFormItemGi :span="8" label="履约方式">
            <NSelect v-model:value="formModel.fulfillmentType" :options="fulfillmentTypeOptions" />
          </NFormItemGi>
          <NFormItemGi :span="8" label="支付方式">
            <NSelect v-model:value="formModel.paymentMode" :options="paymentModeOptions" />
          </NFormItemGi>
          <NFormItemGi :span="12" label="单账号购买数量">
            <NInputNumber v-model:value="formModel.purchaseQuantity" class="w-full" :min="1" />
          </NFormItemGi>
          <NFormItemGi :span="12" label="预热时间">
            <NInput v-model:value="formModel.warmupTime" placeholder="2026-04-10 19:55:00" />
          </NFormItemGi>
          <NFormItemGi :span="24" label="计划抢购时间">
            <NInput v-model:value="formModel.scheduledTime" placeholder="不填则保存后自动立即抢购，例如：2026-04-10 20:00:00" />
          </NFormItemGi>
          <NFormItemGi :span="24" label="指定账号">
            <div class="account-picker w-full">
              <div class="account-picker__header">
                <div>
                  <div class="account-picker__title">已登录账号池</div>
                  <div class="account-picker__hint">
                    <span v-if="formModel.platformId">先筛选，再勾选需要执行抢购的账号</span>
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
                <div class="account-picker__empty-text">先选择目标平台，再从已登录账号里勾选参与抢购的账号。</div>
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
          <NFormItemGi :span="24" label="平台扩展参数">
            <NInput
              v-model:value="formModel.taskOptions"
              type="textarea"
              :rows="6"
              placeholder='例如：{"pickupStoreCode":"shibuya","deliveryWindow":"pm"}'
            />
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

:deep(.sale-task-flow) {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  padding-right: 8px;
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
  word-break: break-all;
}

:deep(.sale-task-options) {
  display: flex;
  flex-direction: column;
  gap: 4px;
  cursor: help;
}

:deep(.sale-task-options__row) {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  line-height: 18px;
  color: #334155;
}

:deep(.sale-task-options__label) {
  min-width: 32px;
  color: #6b7a90;
}

:deep(.sale-task-options__meta) {
  font-size: 12px;
  line-height: 18px;
  color: #94a3b8;
}

:deep(.sale-task-options__popover) {
  max-width: 320px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
  border-radius: 8px;
  background: #f6f8fb;
  padding: 12px;
  font-size: 12px;
  line-height: 18px;
  color: #334155;
}

:deep(.sale-task-time) {
  font-size: 12px;
  line-height: 18px;
  color: #475569;
  white-space: nowrap;
}

.account-picker {
  border: 1px solid #e4e9f2;
  border-radius: 16px;
  background:
    linear-gradient(180deg, #fbfcfe 0%, #f6f8fc 100%);
  padding: 16px;
}

.account-picker__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.account-picker__title {
  font-size: 14px;
  font-weight: 600;
  color: #1e293b;
  line-height: 22px;
}

.account-picker__hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 18px;
  color: #64748b;
}

.account-picker__meta {
  display: flex;
  gap: 10px;
}

.account-picker__meta-item {
  min-width: 74px;
  padding: 8px 12px;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.86);
  text-align: center;
  color: #334155;
}

.account-picker__meta-item strong {
  display: block;
  margin-top: 2px;
  font-size: 18px;
  line-height: 22px;
  font-weight: 600;
}

.account-picker__meta-item--active {
  border-color: #bfd3ff;
  background: linear-gradient(180deg, #eef4ff 0%, #e8f0ff 100%);
  color: #1d4ed8;
}

.account-picker__meta-label {
  display: block;
  font-size: 11px;
  line-height: 16px;
  color: inherit;
  opacity: 0.78;
}

.account-picker__toolbar {
  margin-bottom: 12px;
}

.account-picker__summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.88);
}

.account-picker__summary-label {
  font-size: 12px;
  font-weight: 600;
  color: #475569;
}

.account-picker__summary-more {
  font-size: 12px;
  color: #64748b;
}

.account-picker__empty {
  padding: 28px 20px;
  border: 1px dashed #d7dfec;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.72);
  text-align: center;
}

.account-picker__empty-title {
  font-size: 14px;
  font-weight: 600;
  color: #334155;
}

.account-picker__empty-text {
  margin-top: 6px;
  font-size: 12px;
  line-height: 18px;
  color: #64748b;
}

.account-picker__table {
  overflow: hidden;
  border: 1px solid #e7ecf4;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.92);
}

.account-picker__table :deep(.n-data-table-th) {
  background: #f8fafc;
  color: #475569;
  font-weight: 600;
}

.account-picker__table :deep(.n-data-table-td) {
  padding-top: 10px;
  padding-bottom: 10px;
}

.account-picker__table :deep(.n-data-table-tr:hover td) {
  background: #f8fbff;
}
</style>
