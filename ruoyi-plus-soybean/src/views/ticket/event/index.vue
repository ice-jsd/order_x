<script setup lang="tsx">
import { computed, onMounted, ref } from 'vue';
import { NButton, NPopconfirm } from 'naive-ui';
import { useAuth } from '@/hooks/business/auth';
import { defaultTransform, useNaivePaginatedTable } from '@/hooks/common/table';
import {
  fetchCreateTicketEvent,
  fetchDeleteTicketEvent,
  fetchGetTicketEventList,
  fetchGetTicketPlatformList,
  fetchUpdateTicketEvent
} from '@/service/api/ticket';
import { useAppStore } from '@/store/modules/app';
import { eventStatusOptions, renderTicketEllipsis, renderTicketTag } from '../common';

defineOptions({
  name: 'TicketEventList'
});

const appStore = useAppStore();
const { hasAuth } = useAuth();

function createSearchParams(): Api.Ticket.EventSearchParams {
  return {
    pageNum: 1,
    pageSize: 10,
    platformId: null,
    eventCode: null,
    eventName: null,
    eventStatus: null,
    params: {}
  };
}

function createFormModel(): Api.Ticket.EventOperateParams {
  return {
    eventId: undefined,
    platformId: undefined,
    eventCode: '',
    eventName: '',
    saleTime: '',
    eventStatus: 'draft',
    inventoryPolicy: '',
    remark: ''
  };
}

const searchParams = ref<Api.Ticket.EventSearchParams>(createSearchParams());
const checkedRowKeys = ref<CommonType.IdType[]>([]);
const modalVisible = ref(false);
const operateType = ref<NaiveUI.TableOperateType>('add');
const formModel = ref<Api.Ticket.EventOperateParams>(createFormModel());
const platformOptions = ref<{ label: string; value: CommonType.IdType }[]>([]);

const { columns, columnChecks, data, getData, getDataByPage, loading, mobilePagination, scrollX } =
  useNaivePaginatedTable({
    api: () => fetchGetTicketEventList(searchParams.value),
    transform: response => defaultTransform(response),
    onPaginationParamsChange: params => {
      searchParams.value.pageNum = params.page;
      searchParams.value.pageSize = params.pageSize;
    },
    columns: () => [
      { type: 'selection', align: 'center', width: 48 },
      { key: 'platformName', title: '平台', align: 'center', minWidth: 140 },
      { key: 'eventCode', title: '活动编码', align: 'center', minWidth: 140 },
      { key: 'eventName', title: '活动名称', align: 'center', minWidth: 180 },
      { key: 'saleTime', title: '开售时间', align: 'center', minWidth: 160 },
      {
        key: 'eventStatus',
        title: '活动状态',
        align: 'center',
        width: 100,
        render: row => renderTicketTag(row.eventStatus)
      },
      {
        key: 'inventoryPolicy',
        title: '库存策略',
        align: 'center',
        minWidth: 180,
        render: row => renderTicketEllipsis(row.inventoryPolicy)
      },
      {
        key: 'remark',
        title: '备注',
        align: 'center',
        minWidth: 160,
        render: row => renderTicketEllipsis(row.remark)
      },
      {
        key: 'operate',
        title: '操作',
        align: 'center',
        width: 160,
        render: row => (
          <div class="flex-center gap-8px">
            {hasAuth('ticket:event:edit') && (
              <NButton text type="primary" onClick={() => handleEdit(row)}>
                编辑
              </NButton>
            )}
            {hasAuth('ticket:event:remove') && (
              <NPopconfirm onPositiveClick={() => handleDelete([row.eventId])}>
                {{
                  trigger: () => (
                    <NButton text type="error">
                      删除
                    </NButton>
                  ),
                  default: () => '确认删除该活动吗？'
                }}
              </NPopconfirm>
            )}
          </div>
        )
      }
    ]
  });

const modalTitle = computed(() => (operateType.value === 'add' ? '新增活动' : '编辑活动'));

async function loadPlatformOptions() {
  const { data: list, error } = await fetchGetTicketPlatformList({ pageNum: 1, pageSize: 200, params: {} });
  if (error) return;
  platformOptions.value = (list.rows || []).map(item => ({ label: item.platformName, value: item.platformId }));
}

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
  modalVisible.value = true;
}

function handleEdit(row: Api.Ticket.Event) {
  operateType.value = 'edit';
  formModel.value = {
    eventId: row.eventId,
    platformId: row.platformId,
    eventCode: row.eventCode,
    eventName: row.eventName,
    saleTime: row.saleTime,
    eventStatus: row.eventStatus,
    inventoryPolicy: row.inventoryPolicy,
    remark: row.remark || ''
  };
  modalVisible.value = true;
}

async function handleSubmit() {
  const requestFn = operateType.value === 'add' ? fetchCreateTicketEvent : fetchUpdateTicketEvent;
  const { error } = await requestFn(formModel.value);
  if (error) return;
  window.$message?.success(operateType.value === 'add' ? '活动已创建' : '活动已更新');
  modalVisible.value = false;
  await getData();
}

async function handleDelete(ids: CommonType.IdType[]) {
  const { error } = await fetchDeleteTicketEvent(ids);
  if (error) return;
  window.$message?.success('活动已删除');
  checkedRowKeys.value = checkedRowKeys.value.filter(key => !ids.includes(key));
  await getData();
}
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <NCard title="活动筛选" :bordered="false" size="small" class="card-wrapper">
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
        <NFormItem label="活动编码">
          <NInput v-model:value="searchParams.eventCode" clearable placeholder="请输入活动编码" />
        </NFormItem>
        <NFormItem label="活动名称">
          <NInput v-model:value="searchParams.eventName" clearable placeholder="请输入活动名称" />
        </NFormItem>
        <NFormItem label="状态">
          <NSelect
            v-model:value="searchParams.eventStatus"
            clearable
            :options="eventStatusOptions"
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

    <NCard title="活动配置" :bordered="false" size="small" class="card-wrapper sm:flex-1-hidden">
      <template #header-extra>
        <TableHeaderOperation
          v-model:columns="columnChecks"
          :disabled-delete="checkedRowKeys.length === 0"
          :loading="loading"
          :show-add="hasAuth('ticket:event:add')"
          :show-delete="hasAuth('ticket:event:remove')"
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
        :row-key="row => row.eventId"
        :pagination="mobilePagination"
        class="sm:h-full"
      />
    </NCard>

    <NModal v-model:show="modalVisible" preset="card" :title="modalTitle" class="w-640px">
      <NForm label-placement="top" :model="formModel">
        <NGrid :cols="24" :x-gap="16">
          <NFormItemGi :span="12" label="所属平台">
            <NSelect v-model:value="formModel.platformId" filterable :options="platformOptions" />
          </NFormItemGi>
          <NFormItemGi :span="12" label="活动状态">
            <NSelect v-model:value="formModel.eventStatus" :options="eventStatusOptions" />
          </NFormItemGi>
          <NFormItemGi :span="12" label="活动编码">
            <NInput v-model:value="formModel.eventCode" />
          </NFormItemGi>
          <NFormItemGi :span="12" label="活动名称">
            <NInput v-model:value="formModel.eventName" />
          </NFormItemGi>
          <NFormItemGi :span="24" label="开售时间">
            <NInput v-model:value="formModel.saleTime" placeholder="2026-04-02 10:00:00" />
          </NFormItemGi>
          <NFormItemGi :span="24" label="库存策略">
            <NInput v-model:value="formModel.inventoryPolicy" type="textarea" :rows="3" />
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
