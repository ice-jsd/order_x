<script setup lang="tsx">
import { h, reactive, ref } from 'vue';
import { NButton, NEllipsis, NPopover, NSwitch, NTag } from 'naive-ui';
import { useAuth } from '@/hooks/business/auth';
import { defaultTransform, useNaivePaginatedTable } from '@/hooks/common/table';
import {
  fetchBatchCreateTicketMailboxAccounts,
  fetchChangeTicketMailboxStatus,
  fetchGetTicketMailboxAccountList,
  fetchSyncTicketMailboxMail,
  fetchSyncTicketMailboxMails
} from '@/service/api/ticket';
import { useAppStore } from '@/store/modules/app';
import { mailboxStatusOptions, renderTicketEllipsis, renderTicketTag } from '../common';

defineOptions({
  name: 'TicketMailboxAccountList'
});

const appStore = useAppStore();
const { hasAuth } = useAuth();

function createSearchParams(): Api.Ticket.MailboxAccountSearchParams {
  return {
    pageNum: 1,
    pageSize: 10,
    email: null,
    status: null,
    params: {}
  };
}

const searchParams = ref<Api.Ticket.MailboxAccountSearchParams>(createSearchParams());
const checkedRowKeys = ref<CommonType.IdType[]>([]);
const createModalVisible = ref(false);
const createCount = ref(10);
const creating = ref(false);
const statusLoadingMap = reactive<Record<string, boolean>>({});
const syncLoadingMap = reactive<Record<string, boolean>>({});

const { columns, columnChecks, data, getData, getDataByPage, loading, mobilePagination, scrollX } =
  useNaivePaginatedTable({
    api: () => fetchGetTicketMailboxAccountList(searchParams.value),
    transform: response => defaultTransform(response),
    onPaginationParamsChange: params => {
      searchParams.value.pageNum = params.page;
      searchParams.value.pageSize = params.pageSize;
    },
    columns: () => [
      { type: 'selection', align: 'center', width: 48 },
      {
        key: 'email',
        title: '邮箱',
        align: 'left',
        fixed: 'left',
        minWidth: 260,
        render: row => renderMailboxIdentity(row)
      },
      {
        key: 'password',
        title: '密码',
        align: 'center',
        minWidth: 180,
        render: row => renderTicketEllipsis(row.password)
      },
      { key: 'provider', title: '服务商', align: 'center', width: 90 },
      {
        key: 'stalwartPrincipalId',
        title: 'Stalwart ID',
        align: 'center',
        width: 110,
        render: row => renderTicketEllipsis(row.stalwartPrincipalId)
      },
      {
        key: 'status',
        title: '状态',
        align: 'center',
        width: 100,
        render: row => renderTicketTag(row.status)
      },
      {
        key: 'usedAccountEmail',
        title: '使用账号',
        align: 'center',
        minWidth: 190,
        render: row => renderTicketEllipsis(row.usedAccountEmail || (row.usedAccountId ? String(row.usedAccountId) : '-'))
      },
      { key: 'usedTime', title: '使用时间', align: 'center', minWidth: 150 },
      {
        key: 'latestMail',
        title: '最新邮件内容',
        align: 'left',
        minWidth: 320,
        render: row => renderLatestMail(row)
      },
      { key: 'lastMailSyncTime', title: '同步时间', align: 'center', minWidth: 150 },
      {
        key: 'lastError',
        title: '异常',
        align: 'center',
        minWidth: 150,
        render: row => renderMailboxError(row)
      },
      {
        key: 'syncAction',
        title: '操作',
        align: 'center',
        fixed: 'right',
        width: 110,
        render: row =>
          h(
            NButton,
            {
              text: true,
              type: 'primary',
              size: 'small',
              loading: syncLoadingMap[String(row.mailboxId)],
              disabled: !hasAuth('ticket:mailbox:sync'),
              onClick: () => handleSyncMail(row)
            },
            { default: () => '同步邮件' }
          )
      },
      {
        key: 'enabledSwitch',
        title: '启用',
        align: 'center',
        fixed: 'right',
        width: 96,
        render: row => {
          const canToggle = ['available', 'disabled'].includes(row.status);
          return (
            <NSwitch
              value={row.status === 'available'}
              size="small"
              loading={statusLoadingMap[String(row.mailboxId)]}
              disabled={!hasAuth('ticket:mailbox:edit') || !canToggle}
              onUpdateValue={value => handleToggleStatus(row, value)}
            />
          );
        }
      }
    ]
  });

function renderMailboxIdentity(row: Api.Ticket.MailboxAccount) {
  const isLegacyDomain = row.email?.endsWith('@orderx.top') || row.domain === 'orderx.top';

  return h('div', { class: 'mailbox-identity' }, [
    h('div', { class: 'mailbox-identity__main' }, [
      h(
        NEllipsis,
        { tooltip: true, style: { maxWidth: '210px' } },
        { default: () => row.email || '-' }
      ),
      isLegacyDomain
        ? h(NTag, { size: 'tiny', type: 'warning', bordered: false }, { default: () => '旧域名' })
        : h(NTag, { size: 'tiny', type: 'success', bordered: false }, { default: () => row.domain || 'gjcytech.com' })
    ]),
    h('div', { class: 'mailbox-identity__sub' }, [
      h('span', '登录名：'),
      h(
        NEllipsis,
        { tooltip: true, style: { maxWidth: '150px' } },
        { default: () => row.username || '-' }
      )
    ])
  ]);
}

function renderLatestMail(row: Api.Ticket.MailboxAccount) {
  const hasMail = !!(
    row.latestMailSubject ||
    row.latestMailExcerpt ||
    row.latestVerifyCode ||
    row.latestActivationUrl
  );
  if (!hasMail) {
    return h('div', { class: 'mailbox-mail mailbox-mail--empty' }, [
      h('div', { class: 'mailbox-mail__title' }, '暂无邮件'),
      h('div', { class: 'mailbox-mail__meta' }, row.lastMailSyncTime ? `最近同步：${row.lastMailSyncTime}` : '尚未同步')
    ]);
  }

  const previewTitle = row.latestMailSubject || '无标题邮件';
  const previewExcerpt = row.latestMailExcerpt || row.latestActivationUrl || row.latestVerifyCode || '-';
  return h(
    NPopover,
    { trigger: 'hover', placement: 'left', width: 460 },
    {
      trigger: () =>
        h('div', { class: 'mailbox-mail cursor-help' }, [
          h('div', { class: 'mailbox-mail__tags' }, [
            row.latestVerifyCode
              ? h(NTag, { size: 'tiny', type: 'success', bordered: false }, { default: () => row.latestVerifyCode })
              : null,
            row.latestActivationUrl
              ? h(NTag, { size: 'tiny', type: 'info', bordered: false }, { default: () => '激活链接' })
              : null
          ]),
          h(
            NEllipsis,
            { tooltip: false, style: { maxWidth: '280px' } },
            { default: () => previewTitle }
          ),
          h(
            NEllipsis,
            { tooltip: false, lineClamp: 1, style: { maxWidth: '280px' } },
            { default: () => previewExcerpt }
          ),
          h('div', { class: 'mailbox-mail__meta' }, row.latestMailReceivedAt || row.latestMailFrom || '查看邮件详情')
        ]),
      default: () =>
        h('div', { class: 'max-w-430px text-left text-12px leading-20px' }, [
          h('div', { class: 'font-600 text-text-1' }, row.latestMailSubject || '无标题邮件'),
          h('div', { class: 'mt-6px text-text-3' }, `发件人：${row.latestMailFrom || '-'}`),
          h('div', { class: 'text-text-3' }, `收件时间：${row.latestMailReceivedAt || '-'}`),
          h('div', { class: 'text-text-3' }, `Message-ID：${row.latestMailMessageId || '-'}`),
          h('div', { class: 'mt-8px' }, `验证码：${row.latestVerifyCode || '-'}`),
          h('div', { class: 'break-all' }, `激活链接：${row.latestActivationUrl || '-'}`),
          h(
            'pre',
            { class: 'mt-8px max-h-220px overflow-auto whitespace-pre-wrap break-all rounded-8px bg-#f6f8fb p-10px' },
            row.latestMailExcerpt || '-'
          )
        ])
    }
  );
}

function renderMailboxError(row: Api.Ticket.MailboxAccount) {
  const rawError = row.lastError || row.lastMailSyncError;
  if (!rawError) {
    return h('span', { class: 'text-12px text-text-3' }, '-');
  }

  const isDomainSwitch = rawError.includes('域名') || rawError.includes('gjcytech.com');
  const label = isDomainSwitch ? '旧域名停用' : row.lastMailSyncError ? '同步异常' : '创建异常';
  const type = isDomainSwitch ? 'warning' : 'error';

  return h(
    NPopover,
    { trigger: 'hover', placement: 'left', width: 360 },
    {
      trigger: () =>
        h('div', { class: 'inline-flex cursor-help flex-col items-center gap-4px' }, [
          h(NTag, { size: 'small', type, bordered: false }, { default: () => label }),
          h(
            NEllipsis,
            { tooltip: false, style: { maxWidth: '120px' } },
            { default: () => rawError }
          )
        ]),
      default: () => h('div', { class: 'max-w-330px break-all text-12px leading-20px' }, rawError)
    }
  );
}

function getMailboxRowClass(row: Api.Ticket.MailboxAccount) {
  if (row.status === 'disabled') {
    return 'mailbox-row-disabled';
  }
  if (row.email?.endsWith('@orderx.top') || row.domain === 'orderx.top') {
    return 'mailbox-row-legacy';
  }
  return '';
}

function resetSearch() {
  searchParams.value = createSearchParams();
  checkedRowKeys.value = [];
  void getDataByPage();
}

function openCreateModal() {
  createCount.value = 10;
  createModalVisible.value = true;
}

async function handleBatchCreate() {
  if (!createCount.value || createCount.value < 1 || createCount.value > 500) {
    window.$message?.warning('创建数量必须在 1-500 之间');
    return;
  }

  creating.value = true;
  const { data: result, error } = await fetchBatchCreateTicketMailboxAccounts({ count: createCount.value });
  creating.value = false;
  if (error) return;

  const message = `创建完成，成功 ${result.successCount}/${result.requestedCount} 个，尝试 ${result.attemptCount} 次`;
  if (result.successCount === result.requestedCount) {
    window.$message?.success(message);
  } else {
    window.$message?.warning(`${message}，失败 ${result.failedMessages.length} 条`);
  }
  createModalVisible.value = false;
  await getData();
}

async function handleToggleStatus(row: Api.Ticket.MailboxAccount, enabled: boolean) {
  const nextStatus = enabled ? 'available' : 'disabled';
  if (row.status === nextStatus) {
    return;
  }

  const actionText = enabled ? '启用' : '停用';
  window.$dialog?.warning({
    title: '状态确认',
    content: `确定要${actionText}邮箱 ${row.email} 吗？`,
    positiveText: '确定',
    negativeText: '取消',
    onPositiveClick: async () => {
      const rowKey = String(row.mailboxId);
      statusLoadingMap[rowKey] = true;
      const { error } = await fetchChangeTicketMailboxStatus({
        mailboxIds: [row.mailboxId],
        status: nextStatus
      });
      statusLoadingMap[rowKey] = false;
      if (error) {
        return;
      }
      row.status = nextStatus;
      window.$message?.success(`邮箱已${actionText}`);
    }
  });
}

async function handleSyncMail(row: Api.Ticket.MailboxAccount) {
  const rowKey = String(row.mailboxId);
  syncLoadingMap[rowKey] = true;
  const { error } = await fetchSyncTicketMailboxMail(row.mailboxId);
  syncLoadingMap[rowKey] = false;
  if (error) {
    return;
  }
  window.$message?.success('邮件同步完成');
  await getData();
}

async function handleBatchSyncMail() {
  if (!checkedRowKeys.value.length) {
    window.$message?.warning('请选择要同步的邮箱');
    return;
  }
  checkedRowKeys.value.forEach(key => {
    syncLoadingMap[String(key)] = true;
  });
  const { error } = await fetchSyncTicketMailboxMails({ mailboxIds: checkedRowKeys.value });
  checkedRowKeys.value.forEach(key => {
    syncLoadingMap[String(key)] = false;
  });
  if (error) {
    return;
  }
  window.$message?.success('已提交同步');
  checkedRowKeys.value = [];
  await getData();
}

void getData();
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <NCard title="邮箱账号池筛选" :bordered="false" size="small" class="card-wrapper">
      <NForm inline label-placement="left" :label-width="72">
        <NFormItem label="邮箱">
          <NInput v-model:value="searchParams.email" clearable placeholder="请输入邮箱" />
        </NFormItem>
        <NFormItem label="状态">
          <NSelect
            v-model:value="searchParams.status"
            clearable
            :options="mailboxStatusOptions"
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

    <NCard title="邮箱账号池管理" :bordered="false" size="small" class="card-wrapper sm:flex-1-hidden">
      <template #header-extra>
        <TableHeaderOperation
          v-model:columns="columnChecks"
          :loading="loading"
          :show-add="false"
          :show-delete="false"
          @refresh="getData"
        >
          <template #prefix>
            <NButton v-if="hasAuth('ticket:mailbox:create')" size="small" ghost type="primary" @click="openCreateModal">
              <template #icon>
                <icon-material-symbols-add class="text-icon" />
              </template>
              批量创建
            </NButton>
            <NButton
              v-if="hasAuth('ticket:mailbox:sync')"
              size="small"
              ghost
              type="primary"
              :disabled="!checkedRowKeys.length"
              @click="handleBatchSyncMail"
            >
              同步邮件
            </NButton>
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
        :row-key="row => row.mailboxId"
        :row-class-name="getMailboxRowClass"
        :pagination="mobilePagination"
        class="sm:h-full"
      />
    </NCard>

    <NModal v-model:show="createModalVisible" preset="card" title="批量创建邮箱账号" class="w-520px">
      <NAlert type="info" :show-icon="false" class="mb-16px">
        系统会随机生成 6-10 位数字和小写字母组成的邮箱前缀，用户名为前缀，密码为完整邮箱地址。
      </NAlert>
      <NForm label-placement="top">
        <NFormItem label="创建数量">
          <NInputNumber v-model:value="createCount" :min="1" :max="500" class="w-full" placeholder="请输入创建数量" />
        </NFormItem>
      </NForm>
      <template #footer>
        <div class="flex justify-end gap-12px">
          <NButton @click="createModalVisible = false">取消</NButton>
          <NButton type="primary" :loading="creating" @click="handleBatchCreate">开始创建</NButton>
        </div>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.mailbox-identity {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  padding-left: 2px;
}

.mailbox-identity__main {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  font-weight: 600;
  color: var(--text-color-1);
}

.mailbox-identity__sub {
  display: flex;
  align-items: center;
  min-width: 0;
  font-size: 12px;
  color: var(--text-color-3);
}

.mailbox-mail {
  display: flex;
  flex-direction: column;
  gap: 3px;
  max-width: 300px;
  min-height: 44px;
  justify-content: center;
  text-align: left;
}

.mailbox-mail--empty {
  color: var(--text-color-3);
}

.mailbox-mail__tags {
  display: flex;
  align-items: center;
  gap: 6px;
  min-height: 18px;
}

.mailbox-mail__title {
  font-size: 13px;
  color: var(--text-color-2);
}

.mailbox-mail__meta {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--text-color-3);
}

:deep(.mailbox-row-disabled td),
:deep(.mailbox-row-legacy td) {
  background: #fbfcfe;
  color: var(--text-color-3);
}

:deep(.mailbox-row-disabled:hover td),
:deep(.mailbox-row-legacy:hover td) {
  background: #f6f8fb;
}
</style>
