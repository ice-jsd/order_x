<script setup lang="tsx">
import { computed, onMounted, ref, watch } from 'vue';
import { NButton } from 'naive-ui';
import { useAuth } from '@/hooks/business/auth';
import { defaultTransform, useNaivePaginatedTable } from '@/hooks/common/table';
import {
  fetchCreateTicketAccount,
  fetchGetTicketAccountList,
  fetchGetTicketBindablePhoneList,
  fetchGetTicketPlatformList,
  fetchUpdateTicketAccount
} from '@/service/api/ticket';
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
const { hasAuth } = useAuth();

interface AccountFormModel {
  accountId?: CommonType.IdType;
  platformId: CommonType.IdType | null;
  phoneId: CommonType.IdType | null;
  email: string;
  accountInfo: string;
  reqData: string;
  accountStatus: string;
  loginStatus: string;
  lastError: string;
}

interface PhoneOption {
  label: string;
  value: CommonType.IdType;
}

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

function createFormModel(): AccountFormModel {
  return {
    accountId: undefined,
    platformId: null,
    phoneId: null,
    email: '',
    accountInfo: '',
    reqData: '',
    accountStatus: 'registered',
    loginStatus: 'offline',
    lastError: ''
  };
}

const searchParams = ref<Api.Ticket.AccountSearchParams>(createSearchParams());
const checkedRowKeys = ref<CommonType.IdType[]>([]);
const platformOptions = ref<{ label: string; value: CommonType.IdType }[]>([]);
const modalVisible = ref(false);
const operateType = ref<NaiveUI.TableOperateType>('add');
const saving = ref(false);
const phoneOptionsLoading = ref(false);
const bindablePhoneOptions = ref<PhoneOption[]>([]);
const formModel = ref<AccountFormModel>(createFormModel());
const phoneSearchKeyword = ref('');

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
      },
      {
        key: 'operate',
        title: '操作',
        align: 'center',
        fixed: 'right',
        width: 90,
        render: row => (
          <div class="flex-center gap-8px">
            {hasAuth('ticket:account:edit') && (
              <NButton text type="primary" onClick={() => handleEdit(row)}>
                编辑
              </NButton>
            )}
          </div>
        )
      }
    ]
  });

const modalTitle = computed(() => (operateType.value === 'add' ? '新增账号' : '编辑账号'));

async function loadPlatformOptions() {
  const { data: list, error } = await fetchGetTicketPlatformList({ pageNum: 1, pageSize: 200, params: {} });
  if (error) return;
  platformOptions.value = (list.rows || []).map(item => ({ label: item.platformName, value: item.platformId }));
}

function buildPhoneOptionLabel(phone: Api.Ticket.Phone) {
  return `${phone.phoneNumber} · ${phone.countryCode || '-'} · ${phone.supplier || '-'}`;
}

async function loadBindablePhoneOptions(keyword = '') {
  if (!formModel.value.platformId) {
    bindablePhoneOptions.value = [];
    return;
  }

  phoneOptionsLoading.value = true;
  const { data: list, error } = await fetchGetTicketBindablePhoneList({
    platformId: formModel.value.platformId,
    phoneNumber: keyword || null,
    pageNum: 1,
    pageSize: 20,
    params: {}
  });
  phoneOptionsLoading.value = false;
  if (error) {
    return;
  }

  bindablePhoneOptions.value = (list.rows || []).map(item => ({
    label: buildPhoneOptionLabel(item),
    value: item.phoneId
  }));
}

function openAdd() {
  operateType.value = 'add';
  formModel.value = createFormModel();
  phoneSearchKeyword.value = '';
  bindablePhoneOptions.value = [];
  modalVisible.value = true;
}

function handleEdit(row: Api.Ticket.Account) {
  operateType.value = 'edit';
  formModel.value = {
    accountId: row.accountId,
    platformId: row.platformId,
    phoneId: row.phoneId,
    email: row.email || '',
    accountInfo: row.accountInfo || '',
    reqData: row.reqData || '',
    accountStatus: row.accountStatus || 'registered',
    loginStatus: row.loginStatus || 'offline',
    lastError: row.lastError || ''
  };
  phoneSearchKeyword.value = '';
  bindablePhoneOptions.value = row.phoneId
    ? [{ label: `${row.phoneNumber || row.phoneId}`, value: row.phoneId }]
    : [];
  modalVisible.value = true;
}

function handlePlatformChange() {
  formModel.value.phoneId = null;
  bindablePhoneOptions.value = [];
  phoneSearchKeyword.value = '';
  void loadBindablePhoneOptions();
}

function handlePhoneSearch(value: string) {
  phoneSearchKeyword.value = value;
  void loadBindablePhoneOptions(value);
}

function validateJsonText(label: string, value: string) {
  const trimmed = value.trim();
  if (!trimmed) {
    return true;
  }

  try {
    JSON.parse(trimmed);
    return true;
  } catch {
    window.$message?.warning(`${label} 不是合法的 JSON`);
    return false;
  }
}

async function handleSubmit() {
  if (!formModel.value.platformId) {
    window.$message?.warning('请选择目标平台');
    return;
  }
  if (!formModel.value.phoneId) {
    window.$message?.warning('请选择来源号码');
    return;
  }
  if (operateType.value === 'edit' && !formModel.value.accountId) {
    window.$message?.warning('缺少账号ID');
    return;
  }
  if (!formModel.value.email.trim()) {
    window.$message?.warning('请输入邮箱');
    return;
  }
  if (!validateJsonText('账号信息', formModel.value.accountInfo) || !validateJsonText('请求上下文', formModel.value.reqData)) {
    return;
  }

  saving.value = true;
  const requestFn = operateType.value === 'add' ? fetchCreateTicketAccount : fetchUpdateTicketAccount;
  const { error } = await requestFn({
    accountId: formModel.value.accountId || null,
    platformId: formModel.value.platformId,
    phoneId: formModel.value.phoneId,
    email: formModel.value.email.trim(),
    accountInfo: formModel.value.accountInfo.trim(),
    reqData: formModel.value.reqData.trim(),
    accountStatus: formModel.value.accountStatus,
    loginStatus: formModel.value.loginStatus,
    lastError: formModel.value.lastError.trim()
  });
  saving.value = false;
  if (error) {
    return;
  }

  window.$message?.success(operateType.value === 'add' ? '账号已创建' : '账号已更新');
  modalVisible.value = false;
  await getData();
}
function resetSearch() {
  searchParams.value = createSearchParams();
  checkedRowKeys.value = [];
  void getDataByPage();
}

watch(
  () => modalVisible.value,
  visible => {
    if (!visible) {
      formModel.value = createFormModel();
      operateType.value = 'add';
      bindablePhoneOptions.value = [];
      phoneSearchKeyword.value = '';
    }
  }
);

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
          :show-add="hasAuth('ticket:account:add')"
          :show-delete="false"
          @add="openAdd"
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

    <NModal v-model:show="modalVisible" preset="card" :title="modalTitle" class="w-760px">
      <NAlert v-if="operateType === 'add'" type="info" :show-icon="false" class="mb-16px">
        新增后默认状态为“已注册、离线”，后续可通过外部登录上报接口更新为已登录。
      </NAlert>
      <NAlert v-else type="warning" :show-icon="false" class="mb-16px">
        编辑账号不会变更目标平台和来源号码；如果手动改为“已登录”，系统会同步更新最近登录时间和号码平台关系状态。
      </NAlert>
      <NForm label-placement="top" :model="formModel">
        <NGrid :cols="24" :x-gap="16">
          <NFormItemGi :span="12" label="目标平台">
            <NSelect
              v-model:value="formModel.platformId"
              filterable
              clearable
              :disabled="operateType === 'edit'"
              :options="platformOptions"
              placeholder="请选择平台"
              @update:value="handlePlatformChange"
            />
          </NFormItemGi>
          <NFormItemGi :span="12" label="来源号码">
            <NSelect
              v-model:value="formModel.phoneId"
              filterable
              remote
              clearable
              :loading="phoneOptionsLoading"
              :disabled="!formModel.platformId || operateType === 'edit'"
              :options="bindablePhoneOptions"
              placeholder="请先选择平台，再搜索号码"
              @focus="loadBindablePhoneOptions(phoneSearchKeyword)"
              @search="handlePhoneSearch"
            />
          </NFormItemGi>
          <NFormItemGi :span="24" label="邮箱">
            <NInput v-model:value="formModel.email" placeholder="请输入邮箱" />
          </NFormItemGi>
          <NFormItemGi v-if="operateType === 'edit'" :span="12" label="账号状态">
            <NSelect
              v-model:value="formModel.accountStatus"
              :options="accountStatusOptions"
              placeholder="请选择账号状态"
            />
          </NFormItemGi>
          <NFormItemGi v-if="operateType === 'edit'" :span="12" label="登录状态">
            <NSelect
              v-model:value="formModel.loginStatus"
              :options="loginStatusOptions"
              placeholder="请选择登录状态"
            />
          </NFormItemGi>
          <NFormItemGi :span="24" label="账号信息">
            <NInput
              v-model:value="formModel.accountInfo"
              type="textarea"
              :rows="4"
              placeholder='可选，填写 JSON，例如 {"nickname":"demo-account"}'
            />
          </NFormItemGi>
          <NFormItemGi :span="24" label="请求上下文">
            <NInput
              v-model:value="formModel.reqData"
              type="textarea"
              :rows="4"
              placeholder='可选，填写 JSON，例如 {"channel":"email"}'
            />
          </NFormItemGi>
          <NFormItemGi v-if="operateType === 'edit'" :span="24" label="最近错误">
            <NInput
              v-model:value="formModel.lastError"
              type="textarea"
              :rows="3"
              placeholder="可选，留空则清除最近错误"
            />
          </NFormItemGi>
        </NGrid>
      </NForm>
      <template #footer>
        <div class="flex justify-end gap-12px">
          <NButton @click="modalVisible = false">取消</NButton>
          <NButton type="primary" :loading="saving" @click="handleSubmit">保存</NButton>
        </div>
      </template>
    </NModal>
  </div>
</template>
