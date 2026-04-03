<script setup lang="tsx">
import { computed, onMounted, ref } from 'vue';
import { NButton, NPopconfirm } from 'naive-ui';
import { useAuth } from '@/hooks/business/auth';
import { defaultTransform, useNaivePaginatedTable } from '@/hooks/common/table';
import {
  fetchCreateTicketPlatform,
  fetchDeleteTicketPlatform,
  fetchGetTicketPlatformList,
  fetchUpdateTicketPlatform
} from '@/service/api/ticket';
import { useAppStore } from '@/store/modules/app';
import { renderTicketEllipsis, renderTicketTag } from '../common';

defineOptions({
  name: 'TicketPlatformList'
});

const appStore = useAppStore();
const { hasAuth } = useAuth();

interface PlatformFormModel {
  platformId?: CommonType.IdType;
  platformCode: string;
  platformName: string;
  adapterType: string;
  environment: string;
  enabled: boolean;
  supportsBatchRegister: boolean;
  supportsBatchLogin: boolean;
  supportsSms: boolean;
  supportsEmail: boolean;
  supportsPhoneIdentity: boolean;
  callbackUrl: string;
  callbackSecretMask: string;
  registrationTemplate: string;
  loginStrategy: string;
  remark: string;
}

function createSearchParams(): Api.Ticket.PlatformSearchParams {
  return {
    pageNum: 1,
    pageSize: 10,
    platformCode: null,
    platformName: null,
    enabled: null,
    params: {}
  };
}

function createFormModel(): PlatformFormModel {
  return {
    platformId: undefined,
    platformCode: '',
    platformName: '',
    adapterType: 'mock',
    environment: 'sandbox',
    enabled: true,
    supportsBatchRegister: true,
    supportsBatchLogin: true,
    supportsSms: false,
    supportsEmail: true,
    supportsPhoneIdentity: true,
    callbackUrl: '',
    callbackSecretMask: '',
    registrationTemplate: '',
    loginStrategy: '',
    remark: ''
  };
}

const searchParams = ref<Api.Ticket.PlatformSearchParams>(createSearchParams());
const checkedRowKeys = ref<CommonType.IdType[]>([]);
const modalVisible = ref(false);
const operateType = ref<NaiveUI.TableOperateType>('add');
const formModel = ref<PlatformFormModel>(createFormModel());
const enabledSelectOptions = [
  { label: '启用', value: 'true' },
  { label: '停用', value: 'false' }
];

const enabledValue = computed<string | null>({
  get() {
    if (searchParams.value.enabled === null || searchParams.value.enabled === undefined) {
      return null;
    }
    return String(searchParams.value.enabled);
  },
  set(value) {
    searchParams.value.enabled = value === null ? null : value === 'true';
  }
});

const { columns, columnChecks, data, getData, getDataByPage, loading, mobilePagination, scrollX } =
  useNaivePaginatedTable({
    api: () => fetchGetTicketPlatformList(searchParams.value),
    transform: response => defaultTransform(response),
    onPaginationParamsChange: params => {
      searchParams.value.pageNum = params.page;
      searchParams.value.pageSize = params.pageSize;
    },
    columns: () => [
      { type: 'selection', align: 'center', width: 48 },
      { key: 'platformCode', title: '平台编码', align: 'center', width: 140 },
      { key: 'platformName', title: '平台名称', align: 'center', minWidth: 180 },
      { key: 'adapterType', title: '适配器', align: 'center', width: 100 },
      { key: 'environment', title: '环境', align: 'center', width: 100 },
      {
        key: 'enabled',
        title: '状态',
        align: 'center',
        width: 90,
        render: row => renderTicketTag(row.enabled)
      },
      {
        key: 'capabilities',
        title: '能力开关',
        align: 'center',
        minWidth: 260,
        render: row =>
          renderTicketEllipsis(
            [
              row.supportsBatchRegister ? '批量注册' : null,
              row.supportsBatchLogin ? '批量登录' : null,
              row.supportsSms ? '短信' : null,
              row.supportsEmail ? '邮箱' : null,
              row.supportsPhoneIdentity ? '号码身份' : null
            ]
              .filter(Boolean)
              .join(' / ')
          )
      },
      {
        key: 'callbackUrl',
        title: '回调地址',
        align: 'center',
        minWidth: 220,
        render: row => renderTicketEllipsis(row.callbackUrl)
      },
      {
        key: 'remark',
        title: '备注',
        align: 'center',
        minWidth: 180,
        render: row => renderTicketEllipsis(row.remark)
      },
      {
        key: 'operate',
        title: '操作',
        align: 'center',
        width: 160,
        render: row => (
          <div class="flex-center gap-8px">
            {hasAuth('ticket:platform:edit') && (
              <NButton text type="primary" onClick={() => handleEdit(row)}>
                编辑
              </NButton>
            )}
            {hasAuth('ticket:platform:remove') && (
              <NPopconfirm onPositiveClick={() => handleDelete([row.platformId])}>
                {{
                  trigger: () => (
                    <NButton text type="error">
                      删除
                    </NButton>
                  ),
                  default: () => '确认删除该平台吗？'
                }}
              </NPopconfirm>
            )}
          </div>
        )
      }
    ]
  });

const modalTitle = computed(() => (operateType.value === 'add' ? '新增平台' : '编辑平台'));

onMounted(() => {
  void getData();
});

function resetSearch() {
  searchParams.value = createSearchParams();
  checkedRowKeys.value = [];
  void getDataByPage();
}

function openAdd() {
  operateType.value = 'add';
  formModel.value = createFormModel();
  modalVisible.value = true;
}

function handleEdit(row: Api.Ticket.Platform) {
  operateType.value = 'edit';
  formModel.value = {
    platformId: row.platformId,
    platformCode: row.platformCode,
    platformName: row.platformName,
    adapterType: row.adapterType,
    environment: row.environment,
    enabled: row.enabled,
    supportsBatchRegister: row.supportsBatchRegister,
    supportsBatchLogin: row.supportsBatchLogin,
    supportsSms: row.supportsSms,
    supportsEmail: row.supportsEmail,
    supportsPhoneIdentity: row.supportsPhoneIdentity,
    callbackUrl: row.callbackUrl,
    callbackSecretMask: row.callbackSecretMask,
    registrationTemplate: row.registrationTemplate,
    loginStrategy: row.loginStrategy,
    remark: row.remark || ''
  };
  modalVisible.value = true;
}

async function handleSubmit() {
  const requestFn = operateType.value === 'add' ? fetchCreateTicketPlatform : fetchUpdateTicketPlatform;
  const { error } = await requestFn(formModel.value as Api.Ticket.PlatformOperateParams);
  if (error) return;
  window.$message?.success(operateType.value === 'add' ? '平台已创建' : '平台已更新');
  modalVisible.value = false;
  await getData();
}

async function handleDelete(ids: CommonType.IdType[]) {
  const { error } = await fetchDeleteTicketPlatform(ids);
  if (error) return;
  window.$message?.success('平台已删除');
  checkedRowKeys.value = checkedRowKeys.value.filter(key => !ids.includes(key));
  await getData();
}

function handleBatchDelete() {
  if (!checkedRowKeys.value.length) {
    window.$message?.warning('请先选择要删除的平台');
    return;
  }
  void handleDelete(checkedRowKeys.value);
}
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <NCard title="平台筛选" :bordered="false" size="small" class="card-wrapper">
      <NForm inline label-placement="left" :label-width="72">
        <NFormItem label="平台编码">
          <NInput v-model:value="searchParams.platformCode" clearable placeholder="请输入平台编码" />
        </NFormItem>
        <NFormItem label="平台名称">
          <NInput v-model:value="searchParams.platformName" clearable placeholder="请输入平台名称" />
        </NFormItem>
        <NFormItem label="状态">
          <NSelect
            v-model:value="enabledValue"
            clearable
            :options="enabledSelectOptions"
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

    <NCard title="平台配置中心" :bordered="false" size="small" class="card-wrapper sm:flex-1-hidden">
      <template #header-extra>
        <TableHeaderOperation
          v-model:columns="columnChecks"
          :disabled-delete="checkedRowKeys.length === 0"
          :loading="loading"
          :show-add="hasAuth('ticket:platform:add')"
          :show-delete="hasAuth('ticket:platform:remove')"
          @add="openAdd"
          @delete="handleBatchDelete"
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
        :row-key="row => row.platformId"
        :pagination="mobilePagination"
        class="sm:h-full"
      />
    </NCard>

    <NModal v-model:show="modalVisible" preset="card" :title="modalTitle" class="w-760px">
      <NForm label-placement="top" :model="formModel">
        <NGrid :cols="24" :x-gap="16">
          <NFormItemGi :span="12" label="平台编码">
            <NInput v-model:value="formModel.platformCode" placeholder="如 jp-ticket-mesh" />
          </NFormItemGi>
          <NFormItemGi :span="12" label="平台名称">
            <NInput v-model:value="formModel.platformName" placeholder="请输入平台名称" />
          </NFormItemGi>
          <NFormItemGi :span="12" label="适配器类型">
            <NInput v-model:value="formModel.adapterType" placeholder="mock" />
          </NFormItemGi>
          <NFormItemGi :span="12" label="运行环境">
            <NInput v-model:value="formModel.environment" placeholder="sandbox / prod" />
          </NFormItemGi>
          <NFormItemGi :span="24" label="能力开关">
            <NSpace>
              <NCheckbox v-model:checked="formModel.enabled">启用平台</NCheckbox>
              <NCheckbox v-model:checked="formModel.supportsBatchRegister">批量注册</NCheckbox>
              <NCheckbox v-model:checked="formModel.supportsBatchLogin">批量登录</NCheckbox>
              <NCheckbox v-model:checked="formModel.supportsSms">短信验证</NCheckbox>
              <NCheckbox v-model:checked="formModel.supportsEmail">邮箱验证</NCheckbox>
              <NCheckbox v-model:checked="formModel.supportsPhoneIdentity">号码身份</NCheckbox>
            </NSpace>
          </NFormItemGi>
          <NFormItemGi :span="24" label="回调地址">
            <NInput v-model:value="formModel.callbackUrl" placeholder="https://example.com/ticket/callback" />
          </NFormItemGi>
          <NFormItemGi :span="24" label="回调密钥摘要">
            <NInput v-model:value="formModel.callbackSecretMask" placeholder="用于页面展示的摘要值" />
          </NFormItemGi>
          <NFormItemGi :span="12" label="注册模板">
            <NInput v-model:value="formModel.registrationTemplate" type="textarea" :rows="4" />
          </NFormItemGi>
          <NFormItemGi :span="12" label="登录策略">
            <NInput v-model:value="formModel.loginStrategy" type="textarea" :rows="4" />
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
