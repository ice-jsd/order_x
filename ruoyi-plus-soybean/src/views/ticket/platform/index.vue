<script setup lang="tsx">
import { computed, onMounted, reactive, ref } from 'vue';
import { NButton, NSwitch } from 'naive-ui';
import { useAuth } from '@/hooks/business/auth';
import { defaultTransform, useNaivePaginatedTable } from '@/hooks/common/table';
import {
  fetchCreateTicketPlatform,
  fetchGetTicketPlatformList,
  fetchUpdateTicketPlatform
} from '@/service/api/ticket';
import { useAppStore } from '@/store/modules/app';
import { renderTicketEllipsis } from '../common';

defineOptions({
  name: 'TicketPlatformList'
});

const appStore = useAppStore();
const { hasAuth } = useAuth();

interface PlatformFormModel {
  platformId?: CommonType.IdType;
  platformCode: string;
  platformName: string;
  enabled: boolean;
  orderSubmitUrl: string;
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
    enabled: true,
    orderSubmitUrl: '',
  };
}

const searchParams = ref<Api.Ticket.PlatformSearchParams>(createSearchParams());
const checkedRowKeys = ref<CommonType.IdType[]>([]);
const modalVisible = ref(false);
const operateType = ref<NaiveUI.TableOperateType>('add');
const formModel = ref<PlatformFormModel>(createFormModel());
const statusLoadingMap = reactive<Record<string, boolean>>({});
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
      { key: 'orderSubmitUrl', title: '下单接口地址', align: 'center', minWidth: 260, render: row => renderTicketEllipsis(row.orderSubmitUrl) },
      {
        key: 'operate',
        title: '操作',
        align: 'center',
        width: 100,
        render: row => (
          <div class="flex-center gap-8px">
            {hasAuth('ticket:platform:edit') && (
              <NButton text type="primary" onClick={() => handleEdit(row)}>
                编辑
              </NButton>
            )}
          </div>
        )
      },
      {
        key: 'enabled',
        title: '启用',
        align: 'center',
        width: 110,
        render: row => {
          const rowKey = String(row.platformId);
          return (
            <NSwitch
              value={row.enabled}
              size="small"
              loading={statusLoadingMap[rowKey]}
              disabled={!hasAuth('ticket:platform:edit')}
              onUpdateValue={value => handleTogglePlatformEnabled(row, value)}
            />
          );
        }
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
    enabled: row.enabled,
    orderSubmitUrl: row.orderSubmitUrl,
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

async function handleTogglePlatformEnabled(row: Api.Ticket.Platform, enabled: boolean) {
  if (row.enabled === enabled) {
    return;
  }

  const actionText = enabled ? '启用' : '停用';
  window.$dialog?.warning({
    title: '状态确认',
    content: `确定要${actionText}平台 ${row.platformName} 吗？`,
    positiveText: '确定',
    negativeText: '取消',
    onPositiveClick: async () => {
      const rowKey = String(row.platformId);
      statusLoadingMap[rowKey] = true;
      const payload: Api.Ticket.PlatformOperateParams = {
        platformId: row.platformId,
        platformCode: row.platformCode,
        platformName: row.platformName,
        enabled,
        orderSubmitUrl: row.orderSubmitUrl
      };
      const { error } = await fetchUpdateTicketPlatform(payload);
      statusLoadingMap[rowKey] = false;
      if (error) {
        return;
      }
      row.enabled = enabled;
      window.$message?.success(`平台已${actionText}`);
    }
  });
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
          :loading="loading"
          :show-add="hasAuth('ticket:platform:add')"
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
          <NFormItemGi :span="24" label="启用状态">
            <NSpace>
              <NCheckbox v-model:checked="formModel.enabled">启用平台</NCheckbox>
            </NSpace>
          </NFormItemGi>
          <NFormItemGi :span="24" label="下单接口地址">
            <NInput v-model:value="formModel.orderSubmitUrl" placeholder="https://example.com/api/order/submit" />
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
