<script setup lang="tsx">
import { h, reactive, ref } from 'vue';
import { NButton, NDataTable, NEllipsis, NModal, NPagination, NPopover, NSwitch, NTag } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import { useAuth } from '@/hooks/business/auth';
import { defaultTransform, useNaivePaginatedTable } from '@/hooks/common/table';
import {
  fetchBatchCreateTicketMailboxAccounts,
  fetchChangeTicketMailboxStatus,
  fetchGetTicketMailboxAccountList,
  fetchGetTicketMailboxMailRecords,
  fetchReparseTicketMailboxMailRecords,
  fetchSyncTicketMailboxMail,
  fetchSyncTicketMailboxMails
} from '@/service/api/ticket';
import { useAppStore } from '@/store/modules/app';
import { handleCopy } from '@/utils/copy';
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
    mailKeyword: null,
    params: {}
  };
}

const searchParams = ref<Api.Ticket.MailboxAccountSearchParams>(createSearchParams());
const checkedRowKeys = ref<CommonType.IdType[]>([]);
const createModalVisible = ref(false);
const createCount = ref(10);
const creating = ref(false);
const reparseLoading = ref(false);
const statusLoadingMap = reactive<Record<string, boolean>>({});
const syncLoadingMap = reactive<Record<string, boolean>>({});
const mailRecordVisible = ref(false);
const mailRecordLoading = ref(false);
const mailRecordDetailVisible = ref(false);
const mailRecordDetailTitle = ref('');
const mailRecordDetailContent = ref('');
const mailRecordMailbox = ref<Api.Ticket.MailboxAccount | null>(null);
const mailRecordRows = ref<Api.Ticket.MailRecord[]>([]);
const mailRecordPagination = reactive({
  page: 1,
  pageSize: 10,
  itemCount: 0
});
const mailRecordColumns: DataTableColumns<Api.Ticket.MailRecord> = [
  { key: 'receivedAt', title: '收件时间', align: 'center', width: 150 },
  {
    key: 'subject',
    title: '邮件内容',
    align: 'left',
    minWidth: 360,
    render: (row: Api.Ticket.MailRecord) => renderMailRecordContent(row)
  },
  {
    key: 'parseType',
    title: '解析',
    align: 'center',
    width: 150,
    render: (row: Api.Ticket.MailRecord) => renderMailRecordParse(row)
  },
  { key: 'syncTime', title: '同步时间', align: 'center', width: 150 }
];

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
      {
        key: 'matchedMail',
        title: '命中邮件',
        align: 'left',
        width: 340,
        minWidth: 320,
        render: row => renderMatchedMail(row)
      },
      { key: 'lastMailSyncTime', title: '同步时间', align: 'center', width: 170, minWidth: 170 },
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
        width: 160,
        render: row =>
          h('div', { class: 'flex-center gap-8px' }, [
            h(
              NButton,
              {
                text: true,
                type: 'primary',
                size: 'small',
                onClick: () => openMailRecords(row)
              },
              { default: () => '记录' }
            ),
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
              { default: () => '同步' }
            )
          ])
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
              value={row.status !== 'disabled'}
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
  const domainLabel = isLegacyDomain ? '旧域名' : row.domain || 'gjcytech.com';
  const domainType = isLegacyDomain ? 'warning' : 'success';

  return h('div', { class: 'mailbox-identity' }, [
    h(
      NEllipsis,
      { class: 'mailbox-identity__email', tooltip: true },
      { default: () => row.email || '-' }
    ),
    h('div', { class: 'mailbox-identity__sub' }, [
      h('div', { class: 'mailbox-identity__login' }, [
        h('span', { class: 'mailbox-identity__label' }, '登录名：'),
        h(
          NEllipsis,
          { tooltip: true, style: { maxWidth: '126px' } },
          { default: () => row.username || '-' }
        )
      ]),
      h(NTag, { class: 'mailbox-identity__domain', size: 'tiny', type: domainType, bordered: false }, { default: () => domainLabel })
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
  const popoverMaxWidth = 'min(460px, calc(100vw - 48px))';
  const latestMailPreviewText = [row.latestMailSubject, row.latestMailExcerpt].filter(Boolean).join(' ');
  const latestMailLooksLikeLotteryApplication =
    latestMailPreviewText.includes('受付番号') ||
    latestMailPreviewText.includes('申込番号') ||
    latestMailPreviewText.includes('抽選申し込みを受け付けました') ||
    latestMailPreviewText.includes('お申し込みを受け付けました');
  const shouldShowLatestVerifyCode = !!row.latestVerifyCode && !latestMailLooksLikeLotteryApplication;
  return h(
    NPopover,
    { trigger: 'hover', placement: 'top', width: 460, style: { maxWidth: popoverMaxWidth } },
    {
      trigger: () =>
        h('div', { class: 'mailbox-mail cursor-help' }, [
          h('div', { class: 'mailbox-mail__tags' }, [
            shouldShowLatestVerifyCode
              ? h(
                  NTag,
                  {
                    class: 'mailbox-mail__verify-code',
                    size: 'tiny',
                    type: 'success',
                    bordered: false,
                    title: '点击复制验证码',
                    onClick: (event: MouseEvent) => handleCopyVerifyCode(event, row.latestVerifyCode)
                  },
                  { default: () => row.latestVerifyCode }
                )
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
        h('div', { class: 'text-left text-12px leading-20px', style: { maxWidth: popoverMaxWidth } }, [
          h('div', { class: 'font-600 text-text-1' }, row.latestMailSubject || '无标题邮件'),
          h('div', { class: 'mt-6px text-text-3' }, `发件人：${row.latestMailFrom || '-'}`),
          h('div', { class: 'text-text-3' }, `收件时间：${row.latestMailReceivedAt || '-'}`),
          h('div', { class: 'text-text-3' }, `Message-ID：${row.latestMailMessageId || '-'}`),
            shouldShowLatestVerifyCode
              ? h(
                'button',
                {
                  class: 'mailbox-mail__popover-code',
                  type: 'button',
                  title: '点击复制验证码',
                  onClick: (event: MouseEvent) => handleCopyVerifyCode(event, row.latestVerifyCode)
                },
                `验证码：${row.latestVerifyCode}`
              )
            : h('div', { class: 'mt-8px' }, latestMailLooksLikeLotteryApplication ? '受付番号：请查看邮件记录' : '验证码：-'),
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

function shouldShowMailRecordVerifyCode(row: Api.Ticket.MailRecord) {
  if (!row.verifyCode) return false;
  if (row.parseType !== 'verify_code') return false;
  if (row.lotteryApplicationNo) return false;
  return true;
}

function getMailRecordApplicationNoLabel(row: Api.Ticket.MailRecord) {
  return row.parseType === 'purchase_completed' ? '申込番号' : '受付番号';
}

function renderMatchedMail(row: Api.Ticket.MailboxAccount) {
  const hasKeyword = !!searchParams.value.mailKeyword?.trim();
  if (!hasKeyword) {
    return h('span', { class: 'text-12px text-text-3' }, '-');
  }
  if (!row.matchedMailRecordId) {
    return h('div', { class: 'mailbox-mail mailbox-mail--empty' }, [
      h('div', { class: 'mailbox-mail__title' }, '无命中记录'),
      h('div', { class: 'mailbox-mail__meta' }, '当前页未返回匹配邮件')
    ]);
  }
  return h(
    'button',
    {
      class: 'matched-mail',
      type: 'button',
      title: '查看该邮箱邮件记录',
      onClick: () => openMailRecords(row)
    },
    [
      h(
        NEllipsis,
        { tooltip: true, style: { width: '100%', maxWidth: '100%' } },
        { default: () => row.matchedMailSubject || '无标题邮件' }
      ),
      h(
        NEllipsis,
        { tooltip: false, lineClamp: 2, style: { width: '100%', maxWidth: '100%' } },
        { default: () => row.matchedMailExcerpt || '-' }
      ),
      h('span', { class: 'matched-mail__meta' }, row.matchedMailReceivedAt || row.matchedMailFrom || '-')
    ]
  );
}

async function handleCopyVerifyCode(event: MouseEvent, verifyCode?: string) {
  event.preventDefault();
  event.stopPropagation();
  if (!verifyCode) return;
  await handleCopy(verifyCode);
}

function renderMailboxError(row: Api.Ticket.MailboxAccount) {
  const rawError = row.lastError || row.lastMailSyncError;
  if (!rawError) {
    return h('span', { class: 'text-12px text-text-3' }, '-');
  }

  const isDomainSwitch = rawError.includes('域名') || rawError.includes('gjcytech.com');
  const label = isDomainSwitch ? '旧域名停用' : row.lastMailSyncError ? '同步异常' : '创建异常';
  const type = isDomainSwitch ? 'warning' : 'error';
  const popoverMaxWidth = 'min(360px, calc(100vw - 48px))';

  return h(
    NPopover,
    { trigger: 'hover', placement: 'top', width: 360, style: { maxWidth: popoverMaxWidth } },
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
      default: () =>
        h('div', { class: 'break-all text-12px leading-20px', style: { maxWidth: popoverMaxWidth } }, rawError)
    }
  );
}

function renderMailRecordContent(row: Api.Ticket.MailRecord) {
  return h('div', { class: 'mail-record-content' }, [
    h('div', { class: 'mail-record-content__title' }, [
      h(NEllipsis, { tooltip: true }, { default: () => row.subject || '无标题邮件' })
    ]),
    h('div', { class: 'mail-record-content__meta' }, `发件人：${row.fromAddress || '-'} · 目录：${row.folderName || '-'}`),
    h('div', { class: 'mail-record-content__excerpt' }, [
      h(NEllipsis, { lineClamp: 2, tooltip: false }, { default: () => row.bodyContent || row.bodyExcerpt || row.activationUrl || '-' })
    ]),
    h('div', { class: 'mail-record-content__actions' }, [
      row.messageId ? h('span', { class: 'mail-record-content__message-id' }, `Message-ID：${row.messageId}`) : null,
      h(
        NButton,
        {
          text: true,
          size: 'tiny',
          type: 'primary',
          disabled: !(row.bodyContent || row.bodyExcerpt),
          onClick: () => openMailRecordDetail(row)
        },
        { default: () => '详情' }
      )
    ])
  ]);
}

function renderMailRecordParse(row: Api.Ticket.MailRecord) {
  const parseLabel =
    {
      verify_code: '验证码',
      activation_url: '激活邮件',
      lottery_applied: '抽选申请邮件',
      lottery_selected: '当选邮件',
      lottery_rejected: '落选邮件',
      purchase_completed: '购入邮件',
      unknown: '其他邮件'
    }[row.parseType || 'unknown'] || row.parseType || 'unknown';
  return h('div', { class: 'mail-record-parse' }, [
    h(NTag, { size: 'small', type: row.parsed ? 'success' : 'default', bordered: false }, { default: () => parseLabel }),
    shouldShowMailRecordVerifyCode(row)
      ? h(
          NTag,
          {
            class: 'mailbox-mail__verify-code',
            size: 'small',
            type: 'success',
            bordered: false,
            title: '点击复制验证码',
            onClick: (event: MouseEvent) => handleCopyVerifyCode(event, row.verifyCode)
          },
          { default: () => row.verifyCode }
        )
      : null,
    row.lotteryApplicationNo
      ? h(
          NTag,
          { size: 'small', type: 'success', bordered: false },
          { default: () => `${getMailRecordApplicationNoLabel(row)} ${row.lotteryApplicationNo}` }
        )
      : null,
    row.activationUrl ? h(NTag, { size: 'small', type: 'info', bordered: false }, { default: () => '激活链接' }) : null
  ]);
}

function openMailRecordDetail(row: Api.Ticket.MailRecord) {
  mailRecordDetailTitle.value = row.subject || '邮件详情';
  mailRecordDetailContent.value = [
    `发件人：${row.fromAddress || '-'}`,
    `收件时间：${row.receivedAt || '-'}`,
    `Message-ID：${row.messageId || '-'}`,
    `类型：${
      {
        verify_code: '验证码',
        activation_url: '激活邮件',
        lottery_applied: '抽选申请邮件',
        lottery_selected: '当选邮件',
        lottery_rejected: '落选邮件',
        purchase_completed: '购入邮件',
        unknown: '其他邮件'
      }[row.parseType || 'unknown'] || row.parseType || 'unknown'
    }`,
    `验证码：${shouldShowMailRecordVerifyCode(row) ? row.verifyCode : '-'}`,
    `激活链接：${row.activationUrl || '-'}`,
    `抽选结果：${row.lotteryResultStatus === 'selected' ? '已当选' : row.lotteryResultStatus || '-'}`,
    `${getMailRecordApplicationNoLabel(row)}：${row.lotteryApplicationNo || '-'}`,
    '',
    row.bodyContent || row.bodyExcerpt || '-'
  ].join('\n');
  mailRecordDetailVisible.value = true;
}

async function openMailRecords(row: Api.Ticket.MailboxAccount) {
  mailRecordMailbox.value = row;
  mailRecordPagination.page = 1;
  mailRecordVisible.value = true;
  await loadMailRecords();
}

async function loadMailRecords() {
  if (!mailRecordMailbox.value) return;
  mailRecordLoading.value = true;
  const { data: response, error } = await fetchGetTicketMailboxMailRecords(mailRecordMailbox.value.mailboxId, {
    pageNum: mailRecordPagination.page,
    pageSize: mailRecordPagination.pageSize,
    params: {}
  });
  mailRecordLoading.value = false;
  if (error) return;
  mailRecordRows.value = response?.rows || [];
  mailRecordPagination.itemCount = response?.total || 0;
}

function handleMailRecordPageChange(page: number) {
  mailRecordPagination.page = page;
  void loadMailRecords();
}

function handleMailRecordPageSizeChange(pageSize: number) {
  mailRecordPagination.pageSize = pageSize;
  mailRecordPagination.page = 1;
  void loadMailRecords();
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
  window.$message?.success('邮件同步完成');
  checkedRowKeys.value = [];
  await getData();
}

function handleReparseMailRecords() {
  window.$dialog?.warning({
    title: '重解析历史邮件',
    content: '将对系统中已保存的所有邮件记录重新分类，不会重新连接邮箱服务器。确定开始吗？',
    positiveText: '开始重解析',
    negativeText: '取消',
    onPositiveClick: async () => {
      reparseLoading.value = true;
      const { data, error } = await fetchReparseTicketMailboxMailRecords();
      reparseLoading.value = false;
      if (error) {
        return;
      }
      window.$message?.success(data?.message || '历史邮件重解析已开始，请稍后刷新查看');
    }
  });
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
        <NFormItem label="邮件内容">
          <NInput
            v-model:value="searchParams.mailKeyword"
            clearable
            placeholder="匹配历史邮件标题/正文"
            class="w-260px"
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
            <NButton
              v-if="hasAuth('ticket:mailbox:sync')"
              size="small"
              ghost
              type="warning"
              :loading="reparseLoading"
              @click="handleReparseMailRecords"
            >
              重解析历史邮件
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

    <NModal
      v-model:show="mailRecordVisible"
      preset="card"
      :title="`邮件记录：${mailRecordMailbox?.email || ''}`"
      class="w-980px"
    >
      <NDataTable
        :columns="mailRecordColumns"
        :data="mailRecordRows"
        size="small"
        :bordered="false"
        :loading="mailRecordLoading"
        :pagination="false"
        max-height="520"
      />
      <div class="mt-14px flex justify-end">
        <NPagination
          v-model:page="mailRecordPagination.page"
          v-model:page-size="mailRecordPagination.pageSize"
          show-size-picker
          :item-count="mailRecordPagination.itemCount"
          :page-sizes="[10, 20, 50]"
          @update:page="handleMailRecordPageChange"
          @update:page-size="handleMailRecordPageSizeChange"
        />
      </div>
    </NModal>

    <NModal v-model:show="mailRecordDetailVisible" preset="card" :title="mailRecordDetailTitle" class="w-760px">
      <NInput :value="mailRecordDetailContent" type="textarea" :rows="20" readonly />
    </NModal>
  </div>
</template>

<style scoped>
.mailbox-identity {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 0;
  padding-left: 2px;
  text-align: left;
}

.mailbox-identity__email {
  max-width: 220px;
  font-weight: 600;
  color: var(--text-color-1);
  line-height: 18px;
}

.mailbox-identity__sub {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 220px;
  min-width: 0;
  font-size: 12px;
  color: var(--text-color-3);
  line-height: 18px;
}

.mailbox-identity__login {
  display: flex;
  min-width: 0;
  align-items: center;
}

.mailbox-identity__label {
  flex: none;
}

.mailbox-identity__domain {
  flex: none;
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

:deep(.mailbox-mail__verify-code) {
  cursor: pointer;
  user-select: none;
  transition:
    filter 0.18s ease,
    transform 0.18s ease;
}

:deep(.mailbox-mail__verify-code:hover) {
  filter: brightness(0.96);
  transform: translateY(-1px);
}

.mailbox-mail__popover-code {
  padding: 0;
  margin-top: 8px;
  font-size: 12px;
  line-height: 20px;
  color: #16a34a;
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: 0;
}

.mailbox-mail__popover-code:hover {
  color: #15803d;
  text-decoration: underline;
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

.matched-mail {
  display: flex;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  min-height: 54px;
  flex-direction: column;
  justify-content: center;
  gap: 4px;
  padding: 0;
  color: var(--text-color-2);
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: 0;
  box-sizing: border-box;
  overflow: hidden;
}

.matched-mail:hover {
  color: #2563eb;
}

.matched-mail :deep(.n-ellipsis) {
  min-width: 0;
  max-width: 100%;
}

.matched-mail__meta {
  display: block;
  width: 100%;
  max-width: 100%;
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

.mail-record-content {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
  text-align: left;
}

.mail-record-content__title {
  font-weight: 600;
  color: var(--text-color-1);
  line-height: 18px;
}

.mail-record-content__meta,
.mail-record-content__message-id {
  font-size: 12px;
  color: var(--text-color-3);
  line-height: 18px;
}

.mail-record-content__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.mail-record-content__excerpt {
  font-size: 12px;
  color: var(--text-color-2);
  line-height: 18px;
}

.mail-record-parse {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}
</style>
