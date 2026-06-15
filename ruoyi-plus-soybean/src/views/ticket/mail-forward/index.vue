<script setup lang="tsx">
import { h, onMounted, ref } from 'vue';
import { NButton, NDataTable, NDrawer, NDrawerContent } from 'naive-ui';
import { defaultTransform, useNaivePaginatedTable } from '@/hooks/common/table';
import { fetchGetTicketMailForwardList } from '@/service/api/ticket';
import { useAppStore } from '@/store/modules/app';
import { handleCopy } from '@/utils/copy';
import { renderTicketEllipsis, renderTicketTag } from '../common';

defineOptions({
  name: 'TicketMailForwardList'
});

const appStore = useAppStore();

const sendStatusOptions = [
  { label: '成功', value: 'success' },
  { label: '失败', value: 'failed' }
];

function createSearchParams(): Api.Ticket.MailForwardSearchParams {
  return {
    pageNum: 1,
    pageSize: 10,
    sourceEmail: null,
    targetEmail: null,
    sendStatus: null,
    keyword: null,
    params: {}
  };
}

const searchParams = ref<Api.Ticket.MailForwardSearchParams>(createSearchParams());
const detailVisible = ref(false);
const detailRecord = ref<Api.Ticket.MailForwardRecord | null>(null);

const { columns, columnChecks, data, getData, getDataByPage, loading, mobilePagination, scrollX } =
  useNaivePaginatedTable({
    api: () => fetchGetTicketMailForwardList(searchParams.value),
    transform: response => defaultTransform(response),
    onPaginationParamsChange: params => {
      searchParams.value.pageNum = params.page;
      searchParams.value.pageSize = params.pageSize;
    },
    columns: () => [
      {
        key: 'sourceEmail',
        title: '源邮箱',
        align: 'left',
        minWidth: 220,
        render: row => renderTicketEllipsis(row.sourceEmail)
      },
      {
        key: 'targetEmail',
        title: '目标邮箱',
        align: 'left',
        minWidth: 220,
        render: row => renderTicketEllipsis(row.targetEmail)
      },
      {
        key: 'mailSubject',
        title: '原主题',
        align: 'left',
        minWidth: 260,
        render: row => renderTicketEllipsis(row.mailSubject)
      },
      {
        key: 'sendStatus',
        title: '状态',
        align: 'center',
        width: 100,
        render: row => renderTicketTag(row.sendStatus)
      },
      {
        key: 'sentAt',
        title: '发送时间',
        align: 'center',
        minWidth: 170
      },
      {
        key: 'operatorName',
        title: '操作人',
        align: 'center',
        minWidth: 120,
        render: row => renderTicketEllipsis(row.operatorName || row.createBy)
      },
      {
        key: 'errorMessage',
        title: '失败原因',
        align: 'left',
        minWidth: 220,
        render: row => renderTicketEllipsis(row.errorMessage)
      },
      {
        key: 'action',
        title: '操作',
        align: 'center',
        fixed: 'right',
        width: 88,
        render: row =>
          h(
            NButton,
            {
              text: true,
              type: 'primary',
              size: 'small',
              onClick: () => openDetail(row)
            },
            { default: () => '详情' }
          )
      }
    ]
  });

onMounted(() => {
  void getData();
});

function resetSearch() {
  searchParams.value = createSearchParams();
  void getDataByPage();
}

function openDetail(row: Api.Ticket.MailForwardRecord) {
  detailRecord.value = row;
  detailVisible.value = true;
}

async function copyForwardContent() {
  const content = detailRecord.value?.forwardContent;
  if (!content) return;
  await handleCopy(content);
}
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <NCard title="转发筛选" :bordered="false" size="small" class="card-wrapper">
      <NForm inline label-placement="left" :label-width="72">
        <NFormItem label="源邮箱">
          <NInput v-model:value="searchParams.sourceEmail" clearable placeholder="请输入源邮箱" />
        </NFormItem>
        <NFormItem label="目标邮箱">
          <NInput v-model:value="searchParams.targetEmail" clearable placeholder="请输入目标邮箱" />
        </NFormItem>
        <NFormItem label="发送状态">
          <NSelect
            v-model:value="searchParams.sendStatus"
            clearable
            :options="sendStatusOptions"
            placeholder="请选择状态"
            class="w-160px"
          />
        </NFormItem>
        <NFormItem label="关键词">
          <NInput v-model:value="searchParams.keyword" clearable placeholder="搜原主题或转发内容" />
        </NFormItem>
        <NFormItem>
          <NSpace>
            <NButton type="primary" @click="getDataByPage()">查询</NButton>
            <NButton @click="resetSearch">重置</NButton>
          </NSpace>
        </NFormItem>
      </NForm>
    </NCard>

    <NCard title="邮件转发记录" :bordered="false" size="small" class="card-wrapper sm:flex-1-hidden">
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
        :row-key="row => row.forwardId"
        :pagination="mobilePagination"
        class="sm:h-full"
      />
    </NCard>

    <NDrawer v-model:show="detailVisible" placement="bottom" :height="appStore.isMobile ? '92vh' : 560">
      <NDrawerContent :title="detailRecord?.forwardSubject || '转发详情'" closable>
        <div v-if="detailRecord" class="mail-forward-detail">
          <div class="mail-forward-detail__meta">
            <div><span>源邮箱</span><strong>{{ detailRecord.sourceEmail || '-' }}</strong></div>
            <div><span>目标邮箱</span><strong>{{ detailRecord.targetEmail || '-' }}</strong></div>
            <div><span>发送状态</span><strong>{{ detailRecord.sendStatus || '-' }}</strong></div>
            <div><span>发送时间</span><strong>{{ detailRecord.sentAt || '-' }}</strong></div>
            <div><span>操作人</span><strong>{{ detailRecord.operatorName || detailRecord.createBy || '-' }}</strong></div>
            <div><span>原主题</span><strong>{{ detailRecord.mailSubject || '-' }}</strong></div>
          </div>

          <div class="mail-forward-detail__actions">
            <NButton secondary type="primary" @click="copyForwardContent">复制转发正文</NButton>
          </div>

          <div v-if="detailRecord.errorMessage" class="mail-forward-detail__error">
            <span>失败原因</span>
            <strong>{{ detailRecord.errorMessage }}</strong>
          </div>

          <div class="mail-forward-detail__subject">
            <span>转发主题</span>
            <strong>{{ detailRecord.forwardSubject || '-' }}</strong>
          </div>

          <pre class="mail-forward-detail__content">{{ detailRecord.forwardContent || '-' }}</pre>
        </div>
      </NDrawerContent>
    </NDrawer>
  </div>
</template>

<style scoped>
.mail-forward-detail {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.mail-forward-detail__meta {
  display: grid;
  gap: 10px;
  border-radius: 16px;
  background: #f8fafc;
  padding: 14px;
}

.mail-forward-detail__meta span,
.mail-forward-detail__subject span,
.mail-forward-detail__error span {
  display: block;
  margin-bottom: 4px;
  color: #667085;
  font-size: 12px;
}

.mail-forward-detail__meta strong,
.mail-forward-detail__subject strong,
.mail-forward-detail__error strong {
  display: block;
  color: #101828;
  font-size: 14px;
  line-height: 1.5;
  word-break: break-word;
}

.mail-forward-detail__actions {
  display: flex;
  justify-content: flex-start;
}

.mail-forward-detail__subject,
.mail-forward-detail__error {
  border-radius: 16px;
  background: #f8fafc;
  padding: 14px;
}

.mail-forward-detail__error {
  background: #fff5f5;
}

.mail-forward-detail__content {
  margin: 0;
  border-radius: 16px;
  background: #111827;
  padding: 14px;
  color: #f9fafb;
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
