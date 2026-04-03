<script setup lang="tsx">
import { computed, onMounted, ref } from 'vue';
import { NButton, NPopconfirm } from 'naive-ui';
import { useAuth } from '@/hooks/business/auth';
import { defaultTransform, useNaivePaginatedTable } from '@/hooks/common/table';
import {
  fetchCreateTicketSaleTask,
  fetchDeleteTicketSaleTask,
  fetchExecuteTicketSaleTask,
  fetchGetTicketEventList,
  fetchGetTicketPlatformList,
  fetchGetTicketSaleTaskList,
  fetchUpdateTicketSaleTask
} from '@/service/api/ticket';
import { useAppStore } from '@/store/modules/app';
import { renderTicketTag, taskModeOptions, taskStatusOptions } from '../common';

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
    eventId: null,
    taskName: null,
    taskMode: null,
    taskStatus: null,
    params: {}
  };
}

function createFormModel(): Api.Ticket.SaleTaskOperateParams {
  return {
    taskId: undefined,
    platformId: undefined,
    eventId: undefined,
    taskName: '',
    taskMode: 'manual_confirm',
    taskStatus: 'draft',
    warmupTime: '',
    scheduledTime: '',
    ruleConfig: '',
    remark: ''
  };
}

const searchParams = ref<Api.Ticket.SaleTaskSearchParams>(createSearchParams());
const checkedRowKeys = ref<CommonType.IdType[]>([]);
const modalVisible = ref(false);
const operateType = ref<NaiveUI.TableOperateType>('add');
const formModel = ref<Api.Ticket.SaleTaskOperateParams>(createFormModel());
const platformOptions = ref<{ label: string; value: CommonType.IdType }[]>([]);
const eventOptions = ref<{ label: string; value: CommonType.IdType }[]>([]);

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
      { key: 'taskName', title: '任务名称', align: 'center', minWidth: 180 },
      { key: 'platformName', title: '平台', align: 'center', minWidth: 120 },
      { key: 'eventName', title: '活动', align: 'center', minWidth: 160 },
      {
        key: 'taskMode',
        title: '执行模式',
        align: 'center',
        width: 100,
        render: row => renderTicketTag(row.taskMode)
      },
      {
        key: 'taskStatus',
        title: '任务状态',
        align: 'center',
        width: 100,
        render: row => renderTicketTag(row.taskStatus)
      },
      { key: 'warmupTime', title: '预热时间', align: 'center', minWidth: 160 },
      { key: 'scheduledTime', title: '计划执行', align: 'center', minWidth: 160 },
      { key: 'lastExecutedTime', title: '最近执行', align: 'center', minWidth: 160 },
      {
        key: 'operate',
        title: '操作',
        align: 'center',
        width: 220,
        render: row => (
          <div class="flex-center gap-8px">
            {hasAuth('ticket:saleTask:execute') && (
              <NButton text type="primary" onClick={() => handleExecute(row.taskId)}>
                执行
              </NButton>
            )}
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
                  default: () => '确认删除该任务吗？'
                }}
              </NPopconfirm>
            )}
          </div>
        )
      }
    ]
  });

const modalTitle = computed(() => (operateType.value === 'add' ? '新增销售任务' : '编辑销售任务'));

async function loadOptions() {
  const [{ data: platformList, error: platformError }, { data: eventList, error: eventError }] = await Promise.all([
    fetchGetTicketPlatformList({ pageNum: 1, pageSize: 200, params: {} }),
    fetchGetTicketEventList({ pageNum: 1, pageSize: 200, params: {} })
  ]);
  if (!platformError) {
    platformOptions.value = (platformList.rows || []).map(item => ({ label: item.platformName, value: item.platformId }));
  }
  if (!eventError) {
    eventOptions.value = (eventList.rows || []).map(item => ({ label: item.eventName, value: item.eventId }));
  }
}

onMounted(() => {
  void getData();
  void loadOptions();
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

function handleEdit(row: Api.Ticket.SaleTask) {
  operateType.value = 'edit';
  formModel.value = {
    taskId: row.taskId,
    platformId: row.platformId,
    eventId: row.eventId,
    taskName: row.taskName,
    taskMode: row.taskMode,
    taskStatus: row.taskStatus,
    warmupTime: row.warmupTime,
    scheduledTime: row.scheduledTime,
    ruleConfig: row.ruleConfig,
    remark: row.remark || ''
  };
  modalVisible.value = true;
}

async function handleSubmit() {
  const requestFn = operateType.value === 'add' ? fetchCreateTicketSaleTask : fetchUpdateTicketSaleTask;
  const { error } = await requestFn(formModel.value);
  if (error) return;
  window.$message?.success(operateType.value === 'add' ? '销售任务已创建' : '销售任务已更新');
  modalVisible.value = false;
  await getData();
}

async function handleDelete(ids: CommonType.IdType[]) {
  const { error } = await fetchDeleteTicketSaleTask(ids);
  if (error) return;
  window.$message?.success('销售任务已删除');
  checkedRowKeys.value = checkedRowKeys.value.filter(key => !ids.includes(key));
  await getData();
}

async function handleExecute(taskId: CommonType.IdType) {
  const { data: executionId, error } = await fetchExecuteTicketSaleTask(taskId);
  if (error) return;
  window.$message?.success(`执行请求已发送：${executionId}`);
  await getData();
}
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <NCard title="销售任务筛选" :bordered="false" size="small" class="card-wrapper">
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
        <NFormItem label="活动">
          <NSelect
            v-model:value="searchParams.eventId"
            clearable
            filterable
            :options="eventOptions"
            placeholder="请选择活动"
            class="w-180px"
          />
        </NFormItem>
        <NFormItem label="任务名称">
          <NInput v-model:value="searchParams.taskName" clearable placeholder="请输入任务名称" />
        </NFormItem>
        <NFormItem label="执行模式">
          <NSelect
            v-model:value="searchParams.taskMode"
            clearable
            :options="taskModeOptions"
            placeholder="请选择模式"
            class="w-160px"
          />
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

    <NCard title="销售任务" :bordered="false" size="small" class="card-wrapper sm:flex-1-hidden">
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

    <NModal v-model:show="modalVisible" preset="card" :title="modalTitle" class="w-720px">
      <NForm label-placement="top" :model="formModel">
        <NGrid :cols="24" :x-gap="16">
          <NFormItemGi :span="12" label="所属平台">
            <NSelect v-model:value="formModel.platformId" filterable :options="platformOptions" />
          </NFormItemGi>
          <NFormItemGi :span="12" label="所属活动">
            <NSelect v-model:value="formModel.eventId" filterable :options="eventOptions" />
          </NFormItemGi>
          <NFormItemGi :span="12" label="任务名称">
            <NInput v-model:value="formModel.taskName" />
          </NFormItemGi>
          <NFormItemGi :span="12" label="执行模式">
            <NSelect v-model:value="formModel.taskMode" :options="taskModeOptions" />
          </NFormItemGi>
          <NFormItemGi :span="12" label="任务状态">
            <NSelect v-model:value="formModel.taskStatus" :options="taskStatusOptions" />
          </NFormItemGi>
          <NFormItemGi :span="12" label="预热时间">
            <NInput v-model:value="formModel.warmupTime" placeholder="2026-04-02 09:30:00" />
          </NFormItemGi>
          <NFormItemGi :span="24" label="计划执行时间">
            <NInput v-model:value="formModel.scheduledTime" placeholder="2026-04-02 10:00:00" />
          </NFormItemGi>
          <NFormItemGi :span="24" label="规则配置">
            <NInput v-model:value="formModel.ruleConfig" type="textarea" :rows="4" />
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
