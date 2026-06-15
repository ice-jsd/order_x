<script setup lang="tsx">
import { computed, h, onMounted, ref, watch } from 'vue';
import { NButton, NPopover } from 'naive-ui';
import { useAuth } from '@/hooks/business/auth';
import { defaultTransform, useNaivePaginatedTable } from '@/hooks/common/table';
import {
  fetchCreateTicketAccount,
  fetchDeleteTicketAccounts,
  fetchGetTicketAccountList,
  fetchGetTicketBindablePhoneList,
  fetchGetTicketPlatformList,
  fetchTicketAccountHandsFormQuickCreate,
  fetchTicketAccountBatchLogin,
  fetchTicketAccountBatchRegister,
  fetchUpdateTicketAccount,
  fetchUpdateTicketAccountLastName
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
import ExecutionRecordsPanel from './components/execution-records-panel.vue';

defineOptions({
  name: 'TicketAccountList'
});

const appStore = useAppStore();
const { hasAuth } = useAuth();

interface AccountFormModel {
  accountId?: CommonType.IdType;
  platformId: CommonType.IdType | null;
  platformCode: string;
  phoneId: CommonType.IdType | null;
  accountSource: 'external' | 'internal';
  email: string;
  platformPassword: string;
  fullName: string;
  furigana: string;
  accountInfo: string;
  reqData: string;
  loginReqData: string;
  accountStatus: string;
  loginStatus: string;
  lastError: string;
}

interface PhoneOption {
  label: string;
  value: CommonType.IdType;
}

interface PlatformOption {
  label: string;
  value: CommonType.IdType;
  platformCode: string;
  supportsPhoneIdentity: boolean;
}

type BatchLoginMode = 'auto_mailbox' | 'manual_email_code';

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
    platformCode: '',
    phoneId: null,
    accountSource: 'external',
    email: '',
    platformPassword: '',
    fullName: '',
    furigana: '',
    accountInfo: '',
    reqData: '',
    loginReqData: '',
    accountStatus: 'registered',
    loginStatus: 'offline',
    lastError: ''
  };
}

const searchParams = ref<Api.Ticket.AccountSearchParams>(createSearchParams());
const checkedRowKeys = ref<CommonType.IdType[]>([]);
const platformOptions = ref<PlatformOption[]>([]);
const modalVisible = ref(false);
const operateType = ref<NaiveUI.TableOperateType>('add');
const saving = ref(false);
const lastNameModalVisible = ref(false);
const lastNameSaving = ref(false);
const handsQuickCreateModalVisible = ref(false);
const handsQuickCreateSubmitting = ref(false);
const batchRegisterModalVisible = ref(false);
const batchLoginModalVisible = ref(false);
const executionRecordsModalVisible = ref(false);
const batchSubmitting = ref(false);
const phoneOptionsLoading = ref(false);
const bindablePhoneOptions = ref<PhoneOption[]>([]);
const formModel = ref<AccountFormModel>(createFormModel());
const lastNameForm = ref<{ accountId?: CommonType.IdType; email: string; lastName: string }>({
  accountId: undefined,
  email: '',
  lastName: ''
});
const batchRegisterForm = ref<{ platformId: CommonType.IdType | null; count: number | null }>({
  platformId: null,
  count: 10
});
const batchLoginForm = ref<{ platformId: CommonType.IdType | null; loginMode: BatchLoginMode }>({
  platformId: null,
  loginMode: 'auto_mailbox'
});
const batchLoginAccountSearch = ref<{ email: string; loginStatus: string | null }>({
  email: '',
  loginStatus: null
});
const batchLoginAccountLoading = ref(false);
const batchLoginAccountRows = ref<Api.Ticket.Account[]>([]);
const batchLoginSelectedRowKeys = ref<CommonType.IdType[]>([]);
const phoneSearchKeyword = ref('');
const handsQuickCreateForm = ref<{ count: number | null }>({
  count: 10
});

const isHandsFormPlatformCode = (platformCode?: string | null) => String(platformCode || '').trim().toLowerCase() === 'hands-form';
const isLivePocketPlatformCode = (platformCode?: string | null) => String(platformCode || '').trim().toLowerCase() === 'livepocket';
const getPlatformOption = (platformId?: CommonType.IdType | null) =>
  platformOptions.value.find(item => item.value === platformId) || null;
const handsFormPlatformOption = computed(() => platformOptions.value.find(item => isHandsFormPlatformCode(item.platformCode)) || null);
const isHandsFormForm = computed(() => isHandsFormPlatformCode(formModel.value.platformCode));
const selectedFormPlatformOption = computed(() => getPlatformOption(formModel.value.platformId));
const formRequiresPhoneIdentity = computed(() => {
  const platform = selectedFormPlatformOption.value;
  return platform ? platform.supportsPhoneIdentity : isLivePocketPlatformCode(formModel.value.platformCode);
});
const isExternalAccountForm = computed(() => !isHandsFormForm.value && formModel.value.accountSource === 'external');
const formNeedsPhoneIdentity = computed(() => formRequiresPhoneIdentity.value && !isExternalAccountForm.value);
const batchRegisterPlatformOptions = computed(() => platformOptions.value.filter(item => !isHandsFormPlatformCode(item.platformCode)));
const batchLoginPlatformOptions = computed(() => platformOptions.value.filter(item => !isHandsFormPlatformCode(item.platformCode)));
const batchLoginSelectedPlatform = computed(() => getPlatformOption(batchLoginForm.value.platformId));
const batchLoginSupportsManualCode = computed(() => isLivePocketPlatformCode(batchLoginSelectedPlatform.value?.platformCode));
const accountAddHint = computed(() => {
  if (isHandsFormForm.value) {
    return 'Hands 表单平台账号会作为公开表单身份保存，新增后默认状态为“已激活、离线”，无需绑定来源号码。';
  }
  if (isExternalAccountForm.value) {
    return '外部邮箱账号只需要邮箱和平台密码，新增后默认“已激活、离线”，可用于人工验证码批量登录。';
  }
  if (formNeedsPhoneIdentity.value) {
    return '内部账号需要绑定来源号码，新增后默认状态为“已注册、离线”。';
  }
  return '当前平台账号无需绑定来源号码，新增后默认状态为“已激活、离线”。';
});
const accountEditHint = computed(() => {
  if (isHandsFormForm.value) {
    return '编辑 Hands 表单身份时不会变更目标平台；如果手动改为“已登录”，系统只会更新账号本身状态。';
  }
  if (isExternalAccountForm.value) {
    return '编辑外部邮箱账号时可直接维护平台密码，不需要绑定来源号码。';
  }
  if (formNeedsPhoneIdentity.value) {
    return '编辑账号不会变更目标平台；来源号码可换绑，保存后会同步号码平台关系状态。';
  }
  return '编辑账号不会变更目标平台；当前平台无需来源号码，保存时不会绑定号码。';
});

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
        render: row => renderTicketJsonSummary(row.accountInfo, ['fullName', 'furigana', 'familyName', 'nickname', 'source', 'email'])
      },
      {
        key: 'reqData',
        title: '注册上下文',
        align: 'center',
        minWidth: 200,
        render: row => renderTicketJsonSummary(row.reqData, ['phoneNumber', 'smsEquipno', 'phoneLeaseExpireTime'])
      },
      {
        key: 'loginReqData',
        title: '登录上下文',
        align: 'left',
        width: 180,
        render: row => renderLoginReqDataSummary(row.loginReqData)
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
        width: 140,
        render: row => (
          <div class="flex-center gap-8px">
            {hasAuth('ticket:account:edit') && isLivePocketPlatformCode(row.platformCode) && (
              <NButton text type="primary" onClick={() => openLastNameModal(row)}>
                改姓
              </NButton>
            )}
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

function maskLongText(value: string, head = 10, tail = 4) {
  if (!value) return '-';
  if (value.length <= head + tail + 3) return value;
  return `${value.slice(0, head)}...${value.slice(-tail)}`;
}

function summarizeCookieHeader(header: string) {
  const pairs = header
    .split(';')
    .map(item => item.trim())
    .filter(Boolean)
    .map(item => {
      const [name, ...rest] = item.split('=');
      return { name: name.trim(), value: rest.join('=').trim() };
    })
    .filter(item => item.name && item.value);

  if (!pairs.length) return '';

  const preferred = ['_session', 'reauth', 'lptlvt', 'aws-waf-token'];
  const picked = preferred.map(name => pairs.find(item => item.name === name)).find(Boolean) || pairs[0];

  return `${pairs.length} cookies · ${picked?.name}: ${maskLongText(picked?.value || '')}`;
}

function renderLoginReqDataSummary(value?: string | null) {
  if (!value) return '-';

  let detail = value;
  let summary = '';

  try {
    const parsed = JSON.parse(value) as Record<string, any>;
    detail = JSON.stringify(parsed, null, 2);

    if (typeof parsed.cookieHeader === 'string' && parsed.cookieHeader) {
      summary = summarizeCookieHeader(parsed.cookieHeader);
    } else if (Array.isArray(parsed.cookies)) {
      const sessionCookie = parsed.cookies.find((item: any) => item?.name === '_session') || parsed.cookies[0];
      summary = `${parsed.cookies.length} cookies`;
      if (sessionCookie?.name && sessionCookie?.value) {
        summary += ` · ${sessionCookie.name}: ${maskLongText(String(sessionCookie.value))}`;
      }
    } else if (typeof parsed.userAgent === 'string' && parsed.userAgent) {
      summary = `UA · ${maskLongText(parsed.userAgent, 18, 0)}`;
    }
  } catch {
    summary = summarizeCookieHeader(value) || maskLongText(value, 16, 6);
  }

  summary = summary || '查看登录上下文';
  const popoverMaxWidth = 'min(460px, calc(100vw - 48px))';

  return h(
    NPopover,
    { trigger: 'hover', placement: 'top', width: 460, style: { maxWidth: popoverMaxWidth } },
    {
      trigger: () =>
        h(
          'div',
          {
            class: 'cursor-help text-left',
            style: { width: '160px', maxWidth: '100%', minWidth: 0, overflow: 'hidden' }
          },
          [
            h(
              'div',
              {
                class: 'truncate text-12px leading-18px text-text-2'
              },
              summary
            ),
            h(
              'div',
              {
                class: 'text-12px leading-18px text-text-3'
              },
              '悬浮查看完整内容'
            )
          ]
        ),
      default: () =>
        h(
          'pre',
          {
            class:
              'max-h-420px overflow-auto whitespace-pre-wrap break-all rounded-8px bg-#f6f8fb p-12px text-12px leading-18px text-#334155',
            style: { maxWidth: popoverMaxWidth, boxSizing: 'border-box' }
          },
          detail
        )
    }
  );
}

const modalTitle = computed(() => (operateType.value === 'add' ? '新增账号' : '编辑账号'));

function hasPlatformPassword(row: Api.Ticket.Account) {
  const accountInfo = parseAccountInfo(row.accountInfo);
  return Boolean(String(accountInfo.platformPassword || '').trim());
}

function isExternalMailboxAccount(row: Api.Ticket.Account) {
  return row.mailboxBindingStatus === 'external';
}

function isBatchLoginAccountSelectable(row: Api.Ticket.Account) {
  if (row.accountStatus !== 'activated') {
    return false;
  }
  if (batchLoginForm.value.loginMode === 'manual_email_code') {
    return isExternalMailboxAccount(row) && hasPlatformPassword(row);
  }
  return !isExternalMailboxAccount(row);
}

function renderMailboxSource(row: Api.Ticket.Account) {
  const status = row.mailboxBindingStatus || 'external';
  const statusMap: Record<string, string> = {
    bound: '内部',
    shared: '内部占用',
    available: '内部可用',
    internal_domain: '内部域名',
    external: '外部'
  };
  return renderTicketTag(statusMap[status] || statusMap.external);
}

const batchLoginAccountColumns = computed(() => [
  {
    type: 'selection' as const,
    align: 'center' as const,
    width: 48,
    disabled: (row: Api.Ticket.Account) => !isBatchLoginAccountSelectable(row)
  },
  {
    key: 'email',
    title: '邮箱',
    align: 'center' as const,
    minWidth: 220,
    render: (row: Api.Ticket.Account) => renderTicketEmail(row.email)
  },
  {
    key: 'accountStatus',
    title: '账号状态',
    align: 'center' as const,
    width: 100,
    render: (row: Api.Ticket.Account) => renderTicketTag(row.accountStatus)
  },
  {
    key: 'loginStatus',
    title: '登录状态',
    align: 'center' as const,
    width: 100,
    render: (row: Api.Ticket.Account) => renderTicketTag(row.loginStatus)
  },
  {
    key: 'mailboxBindingStatus',
    title: '邮箱来源',
    align: 'center' as const,
    width: 110,
    render: (row: Api.Ticket.Account) => renderMailboxSource(row)
  },
  {
    key: 'platformPassword',
    title: '平台密码',
    align: 'center' as const,
    width: 100,
    render: (row: Api.Ticket.Account) => renderTicketTag(hasPlatformPassword(row) ? '有' : '无')
  },
  { key: 'lastLoginTime', title: '最近登录', align: 'center' as const, minWidth: 160 },
  {
    key: 'lastError',
    title: '最近错误',
    align: 'center' as const,
    minWidth: 180,
    render: (row: Api.Ticket.Account) => renderTicketEllipsis(row.lastError)
  }
]);

async function loadPlatformOptions() {
  const { data: list, error } = await fetchGetTicketPlatformList({ pageNum: 1, pageSize: 200, params: {} });
  if (error) return;
  platformOptions.value = (list.rows || []).map(item => ({
    label: item.platformName,
    value: item.platformId,
    platformCode: item.platformCode,
    supportsPhoneIdentity: Boolean(item.supportsPhoneIdentity)
  }));
}

function buildPhoneOptionLabel(phone: Api.Ticket.Phone) {
  return `${phone.phoneNumber} · ${phone.countryCode || '-'} · ${phone.supplier || '-'}`;
}

async function loadBindablePhoneOptions(keyword = '') {
  if (!formModel.value.platformId || !formNeedsPhoneIdentity.value) {
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
  const accountInfo = parseAccountInfo(row.accountInfo);
  const isExternalAccount = !row.phoneId && Boolean(String(accountInfo.platformPassword || '').trim());
  operateType.value = 'edit';
  formModel.value = {
    accountId: row.accountId,
    platformId: row.platformId,
    platformCode: row.platformCode || '',
    phoneId: row.phoneId,
    accountSource: isExternalAccount ? 'external' : 'internal',
    email: row.email || '',
    platformPassword: String(accountInfo.platformPassword || ''),
    fullName: String(accountInfo.fullName || ''),
    furigana: String(accountInfo.furigana || ''),
    accountInfo: row.accountInfo || '',
    reqData: row.reqData || '',
    loginReqData: row.loginReqData || '',
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

function parseAccountInfo(value?: string | null) {
  if (!value) return {};
  try {
    return JSON.parse(value) as Record<string, unknown>;
  } catch {
    return {};
  }
}

function openLastNameModal(row: Api.Ticket.Account) {
  if (!String(row.loginReqData || '').trim()) {
    window.$message?.warning('该账号没有登录上下文，请先批量登录成功后再改姓');
    return;
  }
  const accountInfo = parseAccountInfo(row.accountInfo);
  lastNameForm.value = {
    accountId: row.accountId,
    email: row.email || '',
    lastName: String(accountInfo.familyName || '')
  };
  lastNameModalVisible.value = true;
}

function normalizeLastNameInput(value: string) {
  const decoded = value.replace(/(?:U\+|\\u|0x)([0-9a-fA-F]{4,6})/g, (token, hex: string) => {
    const codePoint = Number.parseInt(hex, 16);
    if (!Number.isSafeInteger(codePoint) || codePoint < 0 || codePoint > 0x10ffff) {
      return token;
    }
    return String.fromCodePoint(codePoint);
  });

  let start = 0;
  let end = decoded.length;
  while (start < end && decoded.charCodeAt(start) <= 0x20) start += 1;
  while (end > start && decoded.charCodeAt(end - 1) <= 0x20) end -= 1;
  return decoded.slice(start, end);
}

async function handleSubmitLastName() {
  if (!lastNameForm.value.accountId) {
    window.$message?.warning('缺少账号ID');
    return;
  }
  const lastName = normalizeLastNameInput(lastNameForm.value.lastName);
  if (!lastName) {
    window.$message?.warning('请输入姓');
    return;
  }
  lastNameSaving.value = true;
  const { error } = await fetchUpdateTicketAccountLastName(lastNameForm.value.accountId, { lastName });
  lastNameSaving.value = false;
  if (error) {
    return;
  }
  window.$message?.success('LivePocket 姓氏修改已提交，执行结果稍后刷新到账号列表');
  lastNameModalVisible.value = false;
  await getData();
  window.setTimeout(() => {
    getData();
  }, 3000);
}

function openBatchRegisterModal() {
  if (!batchRegisterPlatformOptions.value.length) {
    window.$message?.warning('当前没有支持批量注册的平台');
    return;
  }
  const platformId =
    searchParams.value.platformId && !isHandsFormPlatformCode(getPlatformOption(searchParams.value.platformId)?.platformCode)
      ? searchParams.value.platformId
      : null;
  batchRegisterForm.value = {
    platformId,
    count: 10
  };
  batchRegisterModalVisible.value = true;
}

function openHandsQuickCreateModal() {
  if (!handsFormPlatformOption.value) {
    window.$message?.warning('当前没有可用的 Hands 平台配置');
    return;
  }
  handsQuickCreateForm.value = {
    count: 10
  };
  handsQuickCreateModalVisible.value = true;
}

function openBatchLoginModal() {
  if (!batchLoginPlatformOptions.value.length) {
    window.$message?.warning('当前没有支持批量登录的平台');
    return;
  }
  const platformId =
    searchParams.value.platformId && !isHandsFormPlatformCode(getPlatformOption(searchParams.value.platformId)?.platformCode)
      ? searchParams.value.platformId
      : null;
  batchLoginForm.value = {
    platformId,
    loginMode: 'auto_mailbox'
  };
  batchLoginAccountSearch.value = {
    email: '',
    loginStatus: null
  };
  batchLoginSelectedRowKeys.value = [];
  batchLoginAccountRows.value = [];
  batchLoginModalVisible.value = true;
  if (batchLoginForm.value.platformId) {
    void loadBatchLoginAccounts();
  }
}

async function handleBatchRegister() {
  const { platformId, count } = batchRegisterForm.value;
  if (!platformId) {
    window.$message?.warning('请选择目标平台');
    return;
  }
  if (!count || count <= 0) {
    window.$message?.warning('注册数量必须大于 0');
    return;
  }
  if (isHandsFormPlatformCode(getPlatformOption(platformId)?.platformCode)) {
    window.$message?.warning('Hands 表单平台不支持批量注册');
    return;
  }
  batchSubmitting.value = true;
  const { data: batchId, error } = await fetchTicketAccountBatchRegister({ platformId, count });
  batchSubmitting.value = false;
  if (error) return;
  window.$message?.success(`批量注册已提交，批次ID：${batchId}`);
  batchRegisterModalVisible.value = false;
}

async function handleHandsQuickCreate() {
  const count = handsQuickCreateForm.value.count;
  if (!count || count <= 0) {
    window.$message?.warning('创建数量必须大于 0');
    return;
  }
  handsQuickCreateSubmitting.value = true;
  const { data: result, error } = await fetchTicketAccountHandsFormQuickCreate({ count });
  handsQuickCreateSubmitting.value = false;
  if (error || !result) return;
  const summary = `Hands 快速建号完成，成功 ${result.successCount} 个，失败 ${result.failedCount} 个`;
  if (result.failedCount > 0) {
    const firstFailedMessage = result.failedMessages[0] ? `；${result.failedMessages[0]}` : '';
    window.$message?.warning(summary + firstFailedMessage);
  } else {
    window.$message?.success(summary);
  }
  handsQuickCreateModalVisible.value = false;
  if (handsFormPlatformOption.value) {
    searchParams.value.platformId = handsFormPlatformOption.value.value;
  }
  searchParams.value.pageNum = 1;
  await getData();
}

async function handleBatchLogin() {
  const { platformId, loginMode } = batchLoginForm.value;
  if (!platformId) {
    window.$message?.warning('请选择目标平台');
    return;
  }
  if (isHandsFormPlatformCode(getPlatformOption(platformId)?.platformCode)) {
    window.$message?.warning('Hands 表单平台不支持批量登录');
    return;
  }
  if (loginMode === 'manual_email_code' && !batchLoginSupportsManualCode.value) {
    window.$message?.warning('当前平台不支持人工验证码登录');
    return;
  }
  if (loginMode === 'manual_email_code' && !batchLoginSelectedRowKeys.value.length) {
    window.$message?.warning('请先选择外部邮箱账号');
    return;
  }
  if (batchLoginSelectedRowKeys.value.length) {
    const selectedRows = batchLoginAccountRows.value.filter(row => batchLoginSelectedRowKeys.value.includes(row.accountId));
    const hasInvalidAccount = selectedRows.some(row => !isBatchLoginAccountSelectable(row));
    if (hasInvalidAccount || selectedRows.length !== batchLoginSelectedRowKeys.value.length) {
      window.$message?.warning(loginMode === 'manual_email_code' ? '人工验证码登录只能选择有平台密码的外部邮箱账号' : '自动登录只能选择内部邮箱账号');
      return;
    }
  }
  batchSubmitting.value = true;
  const payload: Api.Ticket.AccountBatchLoginParams = {
    platformId,
    accountIds: batchLoginSelectedRowKeys.value.length ? batchLoginSelectedRowKeys.value : undefined,
    loginMode
  };
  const { data: batchId, error } = await fetchTicketAccountBatchLogin(payload);
  batchSubmitting.value = false;
  if (error) return;
  window.$message?.success(`批量登录已提交，批次ID：${batchId}`);
  batchLoginModalVisible.value = false;
  batchLoginSelectedRowKeys.value = [];
  await getData();
}

function openExecutionRecords() {
  executionRecordsModalVisible.value = true;
}

async function loadBatchLoginAccounts() {
  const platformId = batchLoginForm.value.platformId;
  if (!platformId) {
    batchLoginAccountRows.value = [];
    batchLoginSelectedRowKeys.value = [];
    return;
  }
  batchLoginAccountLoading.value = true;
  const { data: list, error } = await fetchGetTicketAccountList({
    pageNum: 1,
    pageSize: 200,
    platformId,
    accountId: null,
    phoneId: null,
    email: batchLoginAccountSearch.value.email.trim() || null,
    accountStatus: 'activated',
    loginStatus: batchLoginAccountSearch.value.loginStatus,
    params: {}
  });
  batchLoginAccountLoading.value = false;
  if (error) return;
  batchLoginAccountRows.value = list.rows || [];
  const availableIds = new Set(batchLoginAccountRows.value.filter(isBatchLoginAccountSelectable).map(item => item.accountId));
  batchLoginSelectedRowKeys.value = batchLoginSelectedRowKeys.value.filter(item => availableIds.has(item));
}

function handleBatchLoginPlatformChange() {
  batchLoginSelectedRowKeys.value = [];
  batchLoginAccountRows.value = [];
  if (!batchLoginSupportsManualCode.value) {
    batchLoginForm.value.loginMode = 'auto_mailbox';
  }
  void loadBatchLoginAccounts();
}

function handleBatchLoginModeChange() {
  batchLoginSelectedRowKeys.value = [];
}

function handlePlatformChange() {
  const platform = getPlatformOption(formModel.value.platformId);
  formModel.value.platformCode = platform?.platformCode || '';
  formModel.value.phoneId = null;
  bindablePhoneOptions.value = [];
  phoneSearchKeyword.value = '';
  if (operateType.value === 'add') {
    formModel.value.accountStatus = formNeedsPhoneIdentity.value ? 'registered' : 'activated';
    formModel.value.loginStatus = 'offline';
  }
  if (formNeedsPhoneIdentity.value) {
    void loadBindablePhoneOptions();
  }
}

function handleAccountSourceChange() {
  formModel.value.phoneId = null;
  bindablePhoneOptions.value = [];
  phoneSearchKeyword.value = '';
  if (operateType.value === 'add') {
    formModel.value.accountStatus = formNeedsPhoneIdentity.value ? 'registered' : 'activated';
  }
  if (formNeedsPhoneIdentity.value) {
    void loadBindablePhoneOptions();
  }
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
  if (formNeedsPhoneIdentity.value && !formModel.value.phoneId) {
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
  if (isExternalAccountForm.value && !formModel.value.platformPassword.trim()) {
    window.$message?.warning('请输入平台密码');
    return;
  }
  if (isHandsFormForm.value && !formModel.value.fullName.trim()) {
    window.$message?.warning('请输入姓名');
    return;
  }
  if (isHandsFormForm.value && !formModel.value.furigana.trim()) {
    window.$message?.warning('请输入フリガナ');
    return;
  }
  if (
    !isHandsFormForm.value &&
    !isExternalAccountForm.value &&
    (!validateJsonText('账号信息', formModel.value.accountInfo) ||
      !validateJsonText('注册上下文', formModel.value.reqData) ||
      !validateJsonText('登录上下文', formModel.value.loginReqData))
  ) {
    return;
  }

  let accountInfo = '';
  if (isHandsFormForm.value) {
    accountInfo = JSON.stringify(
      {
        identityType: 'form_profile',
        fullName: formModel.value.fullName.trim(),
        furigana: formModel.value.furigana.trim()
      },
      null,
      2
    );
  } else if (isExternalAccountForm.value) {
    const externalAccountInfo = parseAccountInfo(formModel.value.accountInfo);
    accountInfo = JSON.stringify(
      {
        ...externalAccountInfo,
        identityType: 'external_email',
        platformPassword: formModel.value.platformPassword.trim()
      },
      null,
      2
    );
  } else {
    accountInfo = formModel.value.accountInfo.trim();
  }
  const reqData = isHandsFormForm.value || isExternalAccountForm.value ? '' : formModel.value.reqData.trim();
  const loginReqData = isHandsFormForm.value || isExternalAccountForm.value ? '' : formModel.value.loginReqData.trim();

  saving.value = true;
  const requestFn = operateType.value === 'add' ? fetchCreateTicketAccount : fetchUpdateTicketAccount;
  const { error } = await requestFn({
    accountId: formModel.value.accountId || null,
    platformId: formModel.value.platformId,
    phoneId: formNeedsPhoneIdentity.value ? formModel.value.phoneId : null,
    email: formModel.value.email.trim(),
    accountInfo,
    reqData,
    loginReqData,
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

async function handleBatchDelete() {
  if (checkedRowKeys.value.length === 0) {
    window.$message?.warning('请选择需要删除的账号');
    return;
  }
  const { error } = await fetchDeleteTicketAccounts(checkedRowKeys.value);
  if (error) return;
  window.$message?.success('账号已删除');
  checkedRowKeys.value = [];
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
          :show-delete="hasAuth('ticket:account:remove')"
          :disabled-delete="checkedRowKeys.length === 0"
          @add="openAdd"
          @delete="handleBatchDelete"
          @refresh="getData"
        >
          <template #prefix>
            <NButton
              v-if="hasAuth('ticket:account:add')"
              size="small"
              type="primary"
              ghost
              @click="openHandsQuickCreateModal"
            >
              Hands 快速建号
            </NButton>
            <NButton v-if="hasAuth('ticket:account:add')" size="small" type="primary" ghost @click="openBatchRegisterModal">
              批量注册
            </NButton>
            <NButton v-if="hasAuth('ticket:account:edit')" size="small" type="primary" ghost @click="openBatchLoginModal">
              批量登录
            </NButton>
            <NButton size="small" @click="openExecutionRecords">执行记录</NButton>
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
        :row-key="row => row.accountId"
        :pagination="mobilePagination"
        class="sm:h-full"
      />
    </NCard>

    <NModal v-model:show="lastNameModalVisible" preset="card" title="修改 LivePocket 姓氏" class="w-420px">
      <NForm label-placement="top" :model="lastNameForm">
        <NFormItem label="账号邮箱">
          <NInput :value="lastNameForm.email" disabled />
        </NFormItem>
        <NFormItem label="姓 / last_name">
          <NInput v-model:value="lastNameForm.lastName" clearable placeholder="请输入要写入 LivePocket 的姓" />
        </NFormItem>
      </NForm>
      <template #footer>
        <div class="flex justify-end gap-12px">
          <NButton @click="lastNameModalVisible = false">取消</NButton>
          <NButton type="primary" :loading="lastNameSaving" @click="handleSubmitLastName">保存</NButton>
        </div>
      </template>
    </NModal>

    <NModal v-model:show="handsQuickCreateModalVisible" preset="card" title="Hands 快速建号" class="w-420px">
      <NAlert type="info" :show-icon="false" class="mb-16px">
        只需输入数量，系统会自动从邮箱池占用邮箱；若可用邮箱不足，会尝试自动创建新邮箱，并生成姓名与フリガナ。
      </NAlert>
      <NForm label-placement="top" :model="handsQuickCreateForm">
        <NFormItem label="创建数量">
          <NInputNumber v-model:value="handsQuickCreateForm.count" :min="1" :max="500" :precision="0" class="w-full" />
        </NFormItem>
      </NForm>
      <template #footer>
        <div class="flex justify-end gap-12px">
          <NButton @click="handsQuickCreateModalVisible = false">取消</NButton>
          <NButton type="primary" :loading="handsQuickCreateSubmitting" @click="handleHandsQuickCreate">提交</NButton>
        </div>
      </template>
    </NModal>

    <NModal v-model:show="batchRegisterModalVisible" preset="card" title="批量注册" class="w-420px">
      <NForm label-placement="top" :model="batchRegisterForm">
        <NFormItem label="目标平台">
          <NSelect
            v-model:value="batchRegisterForm.platformId"
            filterable
            :options="batchRegisterPlatformOptions"
            placeholder="请选择平台"
          />
        </NFormItem>
        <NFormItem label="注册数量">
          <NInputNumber v-model:value="batchRegisterForm.count" :min="1" :precision="0" class="w-full" />
        </NFormItem>
      </NForm>
      <template #footer>
        <div class="flex justify-end gap-12px">
          <NButton @click="batchRegisterModalVisible = false">取消</NButton>
          <NButton type="primary" :loading="batchSubmitting" @click="handleBatchRegister">提交</NButton>
        </div>
      </template>
    </NModal>

    <NModal v-model:show="batchLoginModalVisible" preset="card" title="批量登录" class="w-820px">
      <NAlert v-if="batchLoginSelectedRowKeys.length" type="info" :show-icon="false" class="mb-16px">
        将登录已选择的 {{ batchLoginSelectedRowKeys.length }} 个已激活账号，已登录账号也会重新登录。
      </NAlert>
      <NAlert v-else-if="batchLoginForm.loginMode === 'auto_mailbox'" type="info" :show-icon="false" class="mb-16px">
        未选择账号时，将登录目标平台下全部内部邮箱已激活账号。
      </NAlert>
      <NAlert v-else type="warning" :show-icon="false" class="mb-16px">请选择外部邮箱账号后提交人工验证码登录。</NAlert>
      <NForm label-placement="top" :model="batchLoginForm">
        <NFormItem label="目标平台">
          <NSelect
            v-model:value="batchLoginForm.platformId"
            filterable
            :options="batchLoginPlatformOptions"
            placeholder="请选择平台"
            @update:value="handleBatchLoginPlatformChange"
          />
        </NFormItem>
        <NFormItem label="登录方式">
          <NRadioGroup v-model:value="batchLoginForm.loginMode" @update:value="handleBatchLoginModeChange">
            <NRadioButton value="auto_mailbox">自动登录</NRadioButton>
            <NRadioButton value="manual_email_code" :disabled="!batchLoginSupportsManualCode">人工验证码登录</NRadioButton>
          </NRadioGroup>
        </NFormItem>
      </NForm>
      <div class="rounded-6px border border-#e5e7eb p-12px">
        <div class="mb-12px text-14px font-medium">选择已激活账号</div>
        <NForm inline label-placement="left" :label-width="72" class="mb-12px">
          <NFormItem label="邮箱">
            <NInput v-model:value="batchLoginAccountSearch.email" clearable placeholder="请输入邮箱" />
          </NFormItem>
          <NFormItem label="登录状态">
            <NSelect
              v-model:value="batchLoginAccountSearch.loginStatus"
              clearable
              :options="loginStatusOptions"
              placeholder="全部"
              class="w-150px"
            />
          </NFormItem>
          <NFormItem>
            <NButton :disabled="!batchLoginForm.platformId" @click="loadBatchLoginAccounts">查询</NButton>
          </NFormItem>
        </NForm>
        <NDataTable
          v-model:checked-row-keys="batchLoginSelectedRowKeys"
          :columns="batchLoginAccountColumns"
          :data="batchLoginAccountRows"
          :loading="batchLoginAccountLoading"
          :row-key="row => row.accountId"
          size="small"
          max-height="320"
          :scroll-x="1080"
        />
      </div>
      <template #footer>
        <div class="flex justify-end gap-12px">
          <NButton @click="batchLoginModalVisible = false">取消</NButton>
          <NButton type="primary" :loading="batchSubmitting" @click="handleBatchLogin">提交</NButton>
        </div>
      </template>
    </NModal>

    <NModal
      v-model:show="executionRecordsModalVisible"
      preset="card"
      title="执行记录"
      class="execution-records-modal"
      :style="{ width: 'calc(100vw - 24px)', maxWidth: 'none', height: 'calc(100vh - 24px)' }"
      :segmented="{ content: true }"
    >
      <div class="execution-records-modal__body">
        <ExecutionRecordsPanel embedded />
      </div>
    </NModal>

    <NModal v-model:show="modalVisible" preset="card" :title="modalTitle" class="w-760px">
      <NAlert v-if="operateType === 'add'" type="info" :show-icon="false" class="mb-16px">
        {{ accountAddHint }}
      </NAlert>
      <NAlert v-else type="warning" :show-icon="false" class="mb-16px">
        {{ accountEditHint }}
      </NAlert>
      <NForm label-placement="top" :model="formModel">
        <NGrid :cols="24" :x-gap="16">
          <NFormItemGi :span="formNeedsPhoneIdentity || isHandsFormForm || (operateType === 'add' && !isHandsFormForm) ? 12 : 24" label="目标平台">
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
          <NFormItemGi v-if="operateType === 'add' && !isHandsFormForm" :span="12" label="账号来源">
            <NRadioGroup v-model:value="formModel.accountSource" @update:value="handleAccountSourceChange">
              <NRadioButton value="external">外部邮箱</NRadioButton>
              <NRadioButton value="internal">内部账号</NRadioButton>
            </NRadioGroup>
          </NFormItemGi>
          <NFormItemGi v-if="formNeedsPhoneIdentity" :span="12" label="来源号码">
            <NSelect
              v-model:value="formModel.phoneId"
              filterable
              remote
              clearable
              :loading="phoneOptionsLoading"
              :disabled="!formModel.platformId"
              :options="bindablePhoneOptions"
              placeholder="请先选择平台，再搜索号码"
              @focus="loadBindablePhoneOptions(phoneSearchKeyword)"
              @search="handlePhoneSearch"
            />
          </NFormItemGi>
          <NFormItemGi v-else-if="isHandsFormForm" :span="12" label="身份类型">
            <NInput value="公开表单身份" disabled />
          </NFormItemGi>
          <NFormItemGi :span="24" label="邮箱">
            <NInput v-model:value="formModel.email" placeholder="请输入邮箱" />
          </NFormItemGi>
          <NFormItemGi v-if="isExternalAccountForm" :span="24" label="平台密码">
            <NInput
              v-model:value="formModel.platformPassword"
              type="password"
              show-password-on="click"
              placeholder="请输入该平台账号密码"
            />
          </NFormItemGi>
          <NFormItemGi v-if="isHandsFormForm" :span="12" label="姓名">
            <NInput v-model:value="formModel.fullName" placeholder="请输入姓名" />
          </NFormItemGi>
          <NFormItemGi v-if="isHandsFormForm" :span="12" label="フリガナ">
            <NInput v-model:value="formModel.furigana" placeholder="请输入全角片假名" />
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
          <NFormItemGi v-if="!isHandsFormForm && !isExternalAccountForm" :span="24" label="账号信息">
            <NInput
              v-model:value="formModel.accountInfo"
              type="textarea"
              :rows="4"
              placeholder='可选，填写 JSON，例如 {"nickname":"demo-account"}'
            />
          </NFormItemGi>
          <NFormItemGi v-if="!isHandsFormForm && !isExternalAccountForm" :span="24" label="注册上下文">
            <NInput
              v-model:value="formModel.reqData"
              type="textarea"
              :rows="4"
              placeholder='可选，填写 JSON，例如 {"phoneNumber":"080..."}'
            />
          </NFormItemGi>
          <NFormItemGi v-if="!isHandsFormForm && !isExternalAccountForm" :span="24" label="登录上下文">
            <NInput
              v-model:value="formModel.loginReqData"
              type="textarea"
              :rows="4"
              placeholder='可选，填写 JSON，例如 {"sessionToken":"xxx","userAgent":"Mozilla/5.0"}'
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

<style scoped>
.execution-records-modal__body {
  height: calc(100vh - 132px);
  min-height: 0;
  overflow: auto;
}
</style>
