<script setup lang="tsx">
import { h, onMounted, ref } from 'vue';
import { NButton } from 'naive-ui';
import { defaultTransform, useNaivePaginatedTable } from '@/hooks/common/table';
import {
  fetchGetTicketPlatformList,
  fetchGetTicketRegistrationBatch,
  fetchGetTicketRegistrationBatchDetails,
  fetchGetTicketRegistrationBatchList
} from '@/service/api/ticket';
import { useAppStore } from '@/store/modules/app';
import { batchStatusOptions, renderTicketEllipsis, renderTicketTag } from '../common';

defineOptions({
  name: 'TicketRegistrationBatchList'
});

const appStore = useAppStore();

function createSearchParams(): Api.Ticket.RegistrationBatchSearchParams {
  return {
    pageNum: 1,
    pageSize: 10,
    platformId: null,
    batchNo: null,
    batchStatus: null,
    params: {}
  };
}

const searchParams = ref<Api.Ticket.RegistrationBatchSearchParams>(createSearchParams());
const platformOptions = ref<{ label: string; value: CommonType.IdType }[]>([]);
const detailVisible = ref(false);
const detailLoading = ref(false);
const currentBatch = ref<Api.Ticket.RegistrationBatch | null>(null);
const currentDetails = ref<Api.Ticket.RegistrationBatchDetail[]>([]);

async function openDetail(batchId: CommonType.IdType) {
  detailVisible.value = true;
  detailLoading.value = true;
  const [{ data: batch, error: batchError }, { data: details, error: detailError }] = await Promise.all([
    fetchGetTicketRegistrationBatch(batchId),
    fetchGetTicketRegistrationBatchDetails(batchId)
  ]);
  detailLoading.value = false;
  if (batchError || detailError) return;
  currentBatch.value = batch;
  currentDetails.value = details || [];
}

const { columns, columnChecks, data, getData, getDataByPage, loading, mobilePagination, scrollX } =
  useNaivePaginatedTable({
    api: () => fetchGetTicketRegistrationBatchList(searchParams.value),
    transform: response => defaultTransform(response),
    onPaginationParamsChange: params => {
      searchParams.value.pageNum = params.page;
      searchParams.value.pageSize = params.pageSize;
    },
    columns: () => [
      { key: 'batchNo', title: '批次号', align: 'center', minWidth: 220 },
      { key: 'platformName', title: '平台', align: 'center', minWidth: 140 },
      {
        key: 'batchStatus',
        title: '批次状态',
        align: 'center',
        width: 100,
        render: row => renderTicketTag(row.batchStatus)
      },
      { key: 'totalCount', title: '总数', align: 'center', width: 80 },
      { key: 'successCount', title: '成功', align: 'center', width: 80 },
      { key: 'skippedCount', title: '跳过', align: 'center', width: 80 },
      { key: 'failedCount', title: '失败', align: 'center', width: 80 },
      {
        key: 'resultSummary',
        title: '结果摘要',
        align: 'center',
        minWidth: 240,
        render: row => renderTicketEllipsis(row.resultSummary)
      },
      { key: 'executedAt', title: '完成时间', align: 'center', minWidth: 160 },
      {
        key: 'actions',
        title: '操作',
        align: 'center',
        width: 120,
        render: row =>
          h(
            NButton,
            {
              type: 'primary',
              ghost: true,
              size: 'small',
              onClick: () => openDetail(row.batchId)
            },
            { default: () => '查看明细' }
          )
      }
    ]
  });

const detailColumns: NaiveUI.TableColumn<Api.Ticket.RegistrationBatchDetail>[] = [
  { key: 'phoneNumber', title: '号码', align: 'center', minWidth: 160 },
  { key: 'platformName', title: '平台', align: 'center', minWidth: 140 },
  {
    key: 'executeStatus',
    title: '执行结果',
    align: 'center',
    width: 100,
    render: row => renderTicketTag(row.executeStatus)
  },
  {
    key: 'accountNo',
    title: '账号',
    align: 'center',
    minWidth: 180,
    render: row => renderTicketEllipsis(row.accountNo)
  },
  {
    key: 'resultMessage',
    title: '返回信息',
    align: 'center',
    minWidth: 260,
    render: row => renderTicketEllipsis(row.resultMessage)
  },
  { key: 'executedAt', title: '执行时间', align: 'center', minWidth: 160 }
];

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
  void getDataByPage();
}

function closeDetail() {
  detailVisible.value = false;
  currentBatch.value = null;
  currentDetails.value = [];
}
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <NCard title="注册批次筛选" :bordered="false" size="small" class="card-wrapper">
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
        <NFormItem label="批次号">
          <NInput v-model:value="searchParams.batchNo" clearable placeholder="请输入批次号" />
        </NFormItem>
        <NFormItem label="批次状态">
          <NSelect
            v-model:value="searchParams.batchStatus"
            clearable
            :options="batchStatusOptions"
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

    <NCard title="注册批次列表" :bordered="false" size="small" class="card-wrapper sm:flex-1-hidden">
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
        :columns="columns"
        :data="data"
        size="small"
        remote
        :loading="loading"
        :flex-height="!appStore.isMobile"
        :scroll-x="scrollX"
        :row-key="row => row.batchId"
        :pagination="mobilePagination"
        class="sm:h-full"
      />
    </NCard>

    <NModal v-model:show="detailVisible" preset="card" title="注册批次明细" class="w-1000px">
      <NSpin :show="detailLoading">
        <div class="flex-col-stretch gap-16px">
          <NCard size="small" :bordered="false">
            <NGrid :cols="24" :x-gap="16">
              <NFormItemGi :span="8" label="批次号">{{ currentBatch?.batchNo || '-' }}</NFormItemGi>
              <NFormItemGi :span="8" label="平台">{{ currentBatch?.platformName || '-' }}</NFormItemGi>
              <NFormItemGi :span="8" label="状态">
                <component :is="renderTicketTag(currentBatch?.batchStatus)" />
              </NFormItemGi>
              <NFormItemGi :span="6" label="总数">{{ currentBatch?.totalCount ?? 0 }}</NFormItemGi>
              <NFormItemGi :span="6" label="成功">{{ currentBatch?.successCount ?? 0 }}</NFormItemGi>
              <NFormItemGi :span="6" label="跳过">{{ currentBatch?.skippedCount ?? 0 }}</NFormItemGi>
              <NFormItemGi :span="6" label="失败">{{ currentBatch?.failedCount ?? 0 }}</NFormItemGi>
            </NGrid>
          </NCard>

          <NDataTable
            :columns="detailColumns"
            :data="currentDetails"
            size="small"
            :pagination="false"
            :row-key="row => row.detailId"
          />

          <div class="flex justify-end">
            <NButton @click="closeDetail">关闭</NButton>
          </div>
        </div>
      </NSpin>
    </NModal>
  </div>
</template>
