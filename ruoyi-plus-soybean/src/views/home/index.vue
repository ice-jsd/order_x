<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { fetchGetTicketDashboardOverview } from '@/service/api/ticket';
import { useAppStore } from '@/store/modules/app';

defineOptions({
  name: 'TicketOpsHome'
});

type TagType = 'default' | 'primary' | 'success' | 'info' | 'warning' | 'error';

type StatusMeta = {
  label: string;
  type: TagType;
};

type MetricCard = {
  title: string;
  value: number;
  unit: string;
  tone: string;
  description: string;
  items: Array<{ label: string; value: number; status?: 'normal' | 'warn' | 'danger' | 'good' }>;
  path: string;
};

type BatchItem = {
  batchId: CommonType.IdType;
  batchNo: string;
  type: 'register' | 'login';
  platformName?: string;
  batchStatus: string;
  totalCount: number;
  successCount: number;
  failedCount: number;
  skippedCount?: number;
  executedAt?: string;
};

const appStore = useAppStore();
const router = useRouter();

const gap = computed(() => (appStore.isMobile ? 12 : 16));
const loading = ref(false);
const lastUpdatedAt = ref('');

const totals = ref({
  platforms: 0,
  tasks: 0,
  executions: 0,
  accounts: 0,
  mailboxes: 0
});

const saleTasks = ref<Api.Ticket.SaleTask[]>([]);
const executions = ref<Api.Ticket.OrderExecution[]>([]);
const registrationBatches = ref<Api.Ticket.RegistrationBatch[]>([]);
const loginBatches = ref<Api.Ticket.LoginBatch[]>([]);
const dashboardCounts = ref({
  enabledPlatformCount: 0,
  runningTaskCount: 0,
  abnormalTaskCount: 0,
  loggedInAccountCount: 0,
  activatedAccountCount: 0,
  accountErrorCount: 0,
  runningExecutionCount: 0,
  successExecutionCount: 0,
  abnormalExecutionCount: 0,
  mailboxErrorCount: 0,
  unusedMailboxCount: 0
});

const taskStatusMap: Record<string, StatusMeta> = {
  draft: { label: '草稿', type: 'default' },
  executing: { label: '执行中', type: 'primary' },
  pending_payment: { label: '待支付', type: 'warning' },
  partial: { label: '部分完成', type: 'warning' },
  completed: { label: '已完成', type: 'success' },
  failed: { label: '失败', type: 'error' },
  blocked: { label: '阻塞', type: 'error' },
  cancelled: { label: '已取消', type: 'default' }
};

const executionStatusMap: Record<string, StatusMeta> = {
  queued: { label: '排队中', type: 'info' },
  running: { label: '执行中', type: 'primary' },
  submitted: { label: '已提交', type: 'success' },
  pending_payment: { label: '待支付', type: 'warning' },
  paid: { label: '已支付', type: 'success' },
  completed: { label: '已完成', type: 'success' },
  failed: { label: '失败', type: 'error' },
  timeout: { label: '超时', type: 'error' },
  blocked: { label: '阻塞', type: 'error' }
};

const batchStatusMap: Record<string, StatusMeta> = {
  draft: { label: '草稿', type: 'default' },
  executing: { label: '执行中', type: 'primary' },
  completed: { label: '已完成', type: 'success' },
  partial: { label: '部分完成', type: 'warning' },
  blocked: { label: '阻塞', type: 'error' }
};

const purchaseTypeMap: Record<string, string> = {
  flash_sale: '抢票',
  lottery: '抽票'
};

const runningTaskStatuses = ['executing', 'pending_payment', 'partial'];
const abnormalTaskStatuses = ['failed', 'blocked'];
const abnormalExecutionStatuses = ['failed', 'timeout', 'blocked'];

const enabledPlatformCount = computed(() => dashboardCounts.value.enabledPlatformCount);
const runningTaskCount = computed(() => dashboardCounts.value.runningTaskCount);
const abnormalTaskCount = computed(() => dashboardCounts.value.abnormalTaskCount);
const loggedInAccountCount = computed(() => dashboardCounts.value.loggedInAccountCount);
const activatedAccountCount = computed(() => dashboardCounts.value.activatedAccountCount);
const accountErrorCount = computed(() => dashboardCounts.value.accountErrorCount);
const runningExecutionCount = computed(() => dashboardCounts.value.runningExecutionCount);
const successExecutionCount = computed(() => dashboardCounts.value.successExecutionCount);
const abnormalExecutionCount = computed(() => dashboardCounts.value.abnormalExecutionCount);
const mailboxErrorCount = computed(() => dashboardCounts.value.mailboxErrorCount);
const unusedMailboxCount = computed(() => dashboardCounts.value.unusedMailboxCount);

const accountLoginRate = computed(() => percentage(loggedInAccountCount.value, totals.value.accounts));
const executionSuccessRate = computed(() => percentage(successExecutionCount.value, totals.value.executions));

const metricCards = computed<MetricCard[]>(() => [
  {
    title: '售票任务',
    value: totals.value.tasks,
    unit: '个',
    tone: 'blue',
    description: `${runningTaskCount.value} 个正在排队或执行`,
    path: '/ticket/sale-task',
    items: [
      { label: '执行中', value: runningTaskCount.value, status: 'normal' },
      { label: '异常', value: abnormalTaskCount.value, status: abnormalTaskCount.value > 0 ? 'danger' : 'good' }
    ]
  },
  {
    title: '账号池',
    value: totals.value.accounts,
    unit: '个',
    tone: 'green',
    description: `已登录率 ${accountLoginRate.value}%`,
    path: '/ticket/account',
    items: [
      { label: '已激活', value: activatedAccountCount.value, status: 'good' },
      { label: '异常', value: accountErrorCount.value, status: accountErrorCount.value > 0 ? 'danger' : 'good' }
    ]
  },
  {
    title: '订单列表',
    value: totals.value.executions,
    unit: '条',
    tone: 'amber',
    description: `提交成功率 ${executionSuccessRate.value}%`,
    path: '/ticket/order-execution',
    items: [
      { label: '运行中', value: runningExecutionCount.value, status: 'normal' },
      { label: '失败/超时', value: abnormalExecutionCount.value, status: abnormalExecutionCount.value > 0 ? 'danger' : 'good' }
    ]
  },
  {
    title: '邮箱池',
    value: totals.value.mailboxes,
    unit: '个',
    tone: 'slate',
    description: `${unusedMailboxCount.value} 个邮箱未绑定账号`,
    path: '/ticket/mailbox-account',
    items: [
      { label: '平台', value: enabledPlatformCount.value, status: 'normal' },
      { label: '邮箱异常', value: mailboxErrorCount.value, status: mailboxErrorCount.value > 0 ? 'danger' : 'good' }
    ]
  }
]);

const todoItems = computed(() => {
  const rows = [
    {
      title: '执行失败或心跳超时',
      count: abnormalExecutionCount.value,
      tone: 'danger',
      path: '/ticket/order-execution',
      detail: '需要核对执行结果、支付状态和 Python 回传'
    },
    {
      title: '账号登录或资料异常',
      count: accountErrorCount.value,
      tone: 'warn',
      path: '/ticket/account',
      detail: '优先处理登录失败、最近错误和可用账号不足'
    },
    {
      title: '邮箱同步异常',
      count: mailboxErrorCount.value,
      tone: 'warn',
      path: '/ticket/mailbox-account',
      detail: '检查邮箱可读性、验证码邮件和账号绑定状态'
    },
    {
      title: '售票任务阻塞',
      count: abnormalTaskCount.value,
      tone: 'danger',
      path: '/ticket/sale-task',
      detail: '刷新任务进度，确认时段、账号数量和执行记录'
    }
  ];

  return rows.filter(item => item.count > 0);
});

const recentBatches = computed<BatchItem[]>(() => {
  const rows: BatchItem[] = [
    ...registrationBatches.value.map(row => ({
      batchId: row.batchId,
      batchNo: row.batchNo,
      type: 'register' as const,
      platformName: row.platformName,
      batchStatus: row.batchStatus,
      totalCount: row.totalCount,
      successCount: row.successCount,
      failedCount: row.failedCount,
      skippedCount: row.skippedCount,
      executedAt: row.executedAt || row.updateTime || row.createTime
    })),
    ...loginBatches.value.map(row => ({
      batchId: row.batchId,
      batchNo: row.batchNo,
      type: 'login' as const,
      platformName: row.platformName,
      batchStatus: row.batchStatus,
      totalCount: row.totalCount,
      successCount: row.successCount,
      failedCount: row.failedCount,
      executedAt: row.executedAt || row.updateTime || row.createTime
    }))
  ];

  return rows.sort((a, b) => timeValue(b.executedAt) - timeValue(a.executedAt)).slice(0, 6);
});

function percentage(value: number, total: number) {
  if (!total) return 0;
  return Math.min(100, Math.round((value / total) * 100));
}

function timeValue(value?: string) {
  if (!value) return 0;
  const time = new Date(value).getTime();
  return Number.isNaN(time) ? 0 : time;
}

function statusMeta(map: Record<string, StatusMeta>, status?: string) {
  if (!status) return { label: '未知', type: 'default' as TagType };
  return map[status] || { label: status, type: 'default' as TagType };
}

function formatTime(value?: string | null) {
  if (!value) return '-';
  const time = new Date(value);
  if (Number.isNaN(time.getTime())) return value;
  return time.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  });
}

function routeTo(path: string) {
  void router.push(path);
}

async function refreshDashboard() {
  loading.value = true;

  const { data, error } = await fetchGetTicketDashboardOverview();
  if (!error) {
    totals.value = {
      platforms: data.platformTotal || 0,
      tasks: data.taskTotal || 0,
      executions: data.executionTotal || 0,
      accounts: data.accountTotal || 0,
      mailboxes: data.mailboxTotal || 0
    };
    dashboardCounts.value = {
      enabledPlatformCount: data.enabledPlatformCount || 0,
      runningTaskCount: data.runningTaskCount || 0,
      abnormalTaskCount: data.abnormalTaskCount || 0,
      loggedInAccountCount: data.loggedInAccountCount || 0,
      activatedAccountCount: data.activatedAccountCount || 0,
      accountErrorCount: data.accountErrorCount || 0,
      runningExecutionCount: data.runningExecutionCount || 0,
      successExecutionCount: data.successExecutionCount || 0,
      abnormalExecutionCount: data.abnormalExecutionCount || 0,
      mailboxErrorCount: data.mailboxErrorCount || 0,
      unusedMailboxCount: data.unusedMailboxCount || 0
    };
    saleTasks.value = data.recentTasks || [];
    executions.value = data.recentExecutions || [];
    registrationBatches.value = data.recentRegistrationBatches || [];
    loginBatches.value = data.recentLoginBatches || [];
    lastUpdatedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false });
  }
  loading.value = false;
}

onMounted(() => {
  void refreshDashboard();
});
</script>

<template>
  <div class="ticket-ops-home">
    <section class="ops-header">
      <div class="ops-header__main">
        <p class="ops-kicker">Purchase Operations</p>
        <h1>抢购运营工作台</h1>
        <p class="ops-subtitle">围绕任务排队、账号可用性、执行结果和邮箱验证码的实时运营视图。</p>
      </div>
      <div class="ops-header__actions">
        <div class="ops-refresh-time">最近刷新 {{ lastUpdatedAt || '-' }}</div>
        <NButton type="primary" :loading="loading" @click="refreshDashboard">刷新</NButton>
      </div>
    </section>

    <NGrid :x-gap="gap" :y-gap="16" responsive="screen" item-responsive>
      <NGi v-for="card in metricCards" :key="card.title" span="24 s:12 l:6">
        <button class="metric-card" :class="`metric-card--${card.tone}`" type="button" @click="routeTo(card.path)">
          <span class="metric-card__label">{{ card.title }}</span>
          <span class="metric-card__value">
            {{ card.value }}
            <small>{{ card.unit }}</small>
          </span>
          <span class="metric-card__desc">{{ card.description }}</span>
          <span class="metric-card__items">
            <span v-for="item in card.items" :key="item.label" class="metric-card__item" :class="`is-${item.status || 'normal'}`">
              {{ item.label }} {{ item.value }}
            </span>
          </span>
        </button>
      </NGi>
    </NGrid>

    <NGrid :x-gap="gap" :y-gap="16" responsive="screen" item-responsive>
      <NGi span="24 l:15">
        <NCard :bordered="false" class="ops-card" size="small">
          <template #header>
            <div class="card-heading">
              <span>近期售票任务</span>
              <NButton quaternary size="small" @click="routeTo('/ticket/sale-task')">全部任务</NButton>
            </div>
          </template>
          <NSpin :show="loading">
            <div v-if="saleTasks.length" class="ops-table">
              <div class="ops-table__head ops-table__row--tasks">
                <span>任务</span>
                <span>类型</span>
                <span>账号</span>
                <span>状态</span>
                <span>时间</span>
              </div>
              <button
                v-for="task in saleTasks.slice(0, 8)"
                :key="task.taskId"
                class="ops-table__row ops-table__row--tasks"
                type="button"
                @click="routeTo('/ticket/sale-task')"
              >
                <span class="cell-main">
                  <strong>{{ task.taskName }}</strong>
                  <small>{{ task.platformName || '-' }}</small>
                </span>
                <span>{{ purchaseTypeMap[task.purchaseType] || task.purchaseType }}</span>
                <span>{{ task.boundAccountCount || 0 }} 个</span>
                <span>
                  <NTag round size="small" :type="statusMeta(taskStatusMap, task.taskStatus).type">
                    {{ statusMeta(taskStatusMap, task.taskStatus).label }}
                  </NTag>
                </span>
                <span class="cell-time">{{ formatTime(task.scheduledTime || task.lastExecutedTime || task.updateTime) }}</span>
              </button>
            </div>
            <NEmpty v-else description="暂无售票任务" />
          </NSpin>
        </NCard>
      </NGi>

      <NGi span="24 l:9">
        <NCard :bordered="false" class="ops-card" size="small">
          <template #header>
            <div class="card-heading">
              <span>待处理</span>
              <NTag size="small" :type="todoItems.length ? 'warning' : 'success'">
                {{ todoItems.length ? `${todoItems.length} 类` : '正常' }}
              </NTag>
            </div>
          </template>
          <div v-if="todoItems.length" class="todo-list">
            <button
              v-for="item in todoItems"
              :key="item.title"
              class="todo-item"
              :class="`todo-item--${item.tone}`"
              type="button"
              @click="routeTo(item.path)"
            >
              <span class="todo-item__count">{{ item.count }}</span>
              <span>
                <strong>{{ item.title }}</strong>
                <small>{{ item.detail }}</small>
              </span>
            </button>
          </div>
          <div v-else class="todo-empty">
            <strong>当前没有明显异常</strong>
            <span>账号、邮箱和执行结果都处在可处理范围内。</span>
          </div>
        </NCard>
      </NGi>
    </NGrid>

    <NGrid :x-gap="gap" :y-gap="16" responsive="screen" item-responsive>
      <NGi span="24 l:12">
        <NCard :bordered="false" class="ops-card" size="small">
          <template #header>
            <div class="card-heading">
              <span>最近订单</span>
              <NButton quaternary size="small" @click="routeTo('/ticket/order-execution')">订单列表</NButton>
            </div>
          </template>
          <div v-if="executions.length" class="execution-list">
            <button
              v-for="item in executions.slice(0, 6)"
              :key="item.executionId"
              class="execution-item"
              type="button"
              @click="routeTo('/ticket/order-execution')"
            >
              <span class="execution-item__left">
                <strong>{{ item.taskName || item.orderNo || item.executionId }}</strong>
                <small>{{ item.email || '-' }}</small>
              </span>
              <span class="execution-item__right">
                <NTag round size="small" :type="statusMeta(executionStatusMap, item.executionStatus).type">
                  {{ statusMeta(executionStatusMap, item.executionStatus).label }}
                </NTag>
                <small>{{ formatTime(item.executedAt || item.startedAt) }}</small>
              </span>
            </button>
          </div>
          <NEmpty v-else description="暂无订单记录" />
        </NCard>
      </NGi>

      <NGi span="24 l:12">
        <NCard :bordered="false" class="ops-card" size="small">
          <template #header>
            <div class="card-heading">
              <span>批量任务记录</span>
              <NButton quaternary size="small" @click="routeTo('/ticket/registration-batch')">执行记录</NButton>
            </div>
          </template>
          <div v-if="recentBatches.length" class="batch-list">
            <button
              v-for="batch in recentBatches"
              :key="`${batch.type}-${batch.batchId}`"
              class="batch-item"
              type="button"
              @click="routeTo('/ticket/registration-batch')"
            >
              <span class="batch-item__type" :class="`is-${batch.type}`">{{ batch.type === 'register' ? '注册' : '登录' }}</span>
              <span class="batch-item__main">
                <strong>{{ batch.batchNo }}</strong>
                <small>{{ batch.platformName || '-' }} · {{ formatTime(batch.executedAt) }}</small>
              </span>
              <span class="batch-item__result">
                <NTag round size="small" :type="statusMeta(batchStatusMap, batch.batchStatus).type">
                  {{ statusMeta(batchStatusMap, batch.batchStatus).label }}
                </NTag>
                <small>
                  成功 {{ batch.successCount }} / 失败 {{ batch.failedCount }}
                  <template v-if="batch.skippedCount">/ 跳过 {{ batch.skippedCount }}</template>
                </small>
              </span>
            </button>
          </div>
          <NEmpty v-else description="暂无批量任务记录" />
        </NCard>
      </NGi>
    </NGrid>

    <section class="quick-actions">
      <button type="button" @click="routeTo('/ticket/sale-task')">
        <strong>新增抢票/抽票任务</strong>
        <span>配置时间、账号和执行策略</span>
      </button>
      <button type="button" @click="routeTo('/ticket/account')">
        <strong>维护账号池</strong>
        <span>批量注册、登录、改姓和状态检查</span>
      </button>
      <button type="button" @click="routeTo('/ticket/mailbox-account')">
        <strong>检查邮箱池</strong>
        <span>同步验证码邮件和激活链接</span>
      </button>
      <button type="button" @click="routeTo('/ticket/order-execution')">
        <strong>追踪执行结果</strong>
        <span>处理失败、超时、待支付订单</span>
      </button>
    </section>
  </div>
</template>

<style scoped>
.ticket-ops-home {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 100%;
}

.ops-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 20px 22px;
  overflow: hidden;
  color: #172033;
  background:
    linear-gradient(90deg, rgba(37, 99, 235, 0.08), transparent 52%),
    linear-gradient(180deg, #ffffff 0%, #f8fafc 100%);
  border: 1px solid rgba(148, 163, 184, 0.18);
  border-radius: 8px;
  box-shadow: 0 10px 28px rgba(15, 23, 42, 0.06);
}

.ops-kicker {
  margin: 0 0 5px;
  font-size: 12px;
  font-weight: 700;
  color: #2563eb;
  letter-spacing: 0;
  text-transform: uppercase;
}

.ops-header h1 {
  margin: 0;
  font-size: 25px;
  font-weight: 760;
  line-height: 1.25;
  color: #0f172a;
}

.ops-subtitle {
  max-width: 620px;
  margin: 8px 0 0;
  font-size: 14px;
  line-height: 1.6;
  color: #64748b;
}

.ops-header__actions {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 12px;
}

.ops-refresh-time {
  font-size: 12px;
  color: #64748b;
  white-space: nowrap;
}

.metric-card {
  display: flex;
  flex-direction: column;
  width: 100%;
  min-height: 154px;
  padding: 16px;
  text-align: left;
  cursor: pointer;
  background: #ffffff;
  border: 1px solid rgba(148, 163, 184, 0.2);
  border-left: 4px solid var(--metric-color);
  border-radius: 8px;
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.05);
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease,
    transform 0.2s ease;
}

.metric-card:hover {
  border-color: color-mix(in srgb, var(--metric-color) 52%, #d5deea);
  box-shadow: 0 16px 34px rgba(15, 23, 42, 0.08);
  transform: translateY(-1px);
}

.metric-card--blue {
  --metric-color: #2563eb;
}

.metric-card--green {
  --metric-color: #059669;
}

.metric-card--amber {
  --metric-color: #d97706;
}

.metric-card--slate {
  --metric-color: #475569;
}

.metric-card__label {
  font-size: 13px;
  font-weight: 700;
  color: #475569;
}

.metric-card__value {
  margin-top: 10px;
  font-size: 34px;
  font-weight: 780;
  line-height: 1;
  color: #0f172a;
}

.metric-card__value small {
  margin-left: 4px;
  font-size: 13px;
  font-weight: 600;
  color: #64748b;
}

.metric-card__desc {
  min-height: 22px;
  margin-top: 8px;
  font-size: 13px;
  color: #64748b;
}

.metric-card__items {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: auto;
  padding-top: 14px;
}

.metric-card__item {
  padding: 4px 8px;
  font-size: 12px;
  color: #475569;
  background: #f1f5f9;
  border-radius: 6px;
}

.metric-card__item.is-good {
  color: #047857;
  background: #ecfdf5;
}

.metric-card__item.is-danger {
  color: #b91c1c;
  background: #fef2f2;
}

.metric-card__item.is-warn {
  color: #b45309;
  background: #fffbeb;
}

.ops-card {
  height: 100%;
  border: 1px solid rgba(148, 163, 184, 0.16);
  border-radius: 8px;
  box-shadow: 0 8px 22px rgba(15, 23, 42, 0.05);
}

.card-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-size: 15px;
  font-weight: 740;
  color: #172033;
}

.ops-table {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.ops-table__head,
.ops-table__row {
  display: grid;
  gap: 12px;
  align-items: center;
}

.ops-table__row--tasks {
  grid-template-columns: minmax(180px, 1.6fr) 70px 72px 92px 108px;
}

.ops-table__head {
  padding: 0 10px 4px;
  font-size: 12px;
  color: #94a3b8;
}

.ops-table__row {
  width: 100%;
  padding: 10px;
  font-size: 13px;
  color: #334155;
  text-align: left;
  cursor: pointer;
  background: #f8fafc;
  border: 1px solid transparent;
  border-radius: 8px;
}

.ops-table__row:hover,
.execution-item:hover,
.batch-item:hover,
.todo-item:hover,
.quick-actions button:hover {
  background: #ffffff;
  border-color: rgba(37, 99, 235, 0.26);
}

.cell-main,
.execution-item__left,
.batch-item__main {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.cell-main strong,
.execution-item strong,
.batch-item strong,
.todo-item strong,
.quick-actions strong {
  overflow: hidden;
  font-weight: 720;
  color: #0f172a;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cell-main small,
.execution-item small,
.batch-item small,
.todo-item small,
.quick-actions span {
  overflow: hidden;
  font-size: 12px;
  line-height: 1.45;
  color: #64748b;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cell-time {
  color: #64748b;
}

.todo-list,
.execution-list,
.batch-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.todo-item,
.execution-item,
.batch-item,
.quick-actions button {
  display: flex;
  align-items: center;
  width: 100%;
  min-width: 0;
  padding: 10px 12px;
  text-align: left;
  cursor: pointer;
  background: #f8fafc;
  border: 1px solid transparent;
  border-radius: 8px;
}

.todo-item {
  gap: 12px;
}

.todo-item__count {
  display: grid;
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  place-items: center;
  font-size: 16px;
  font-weight: 800;
  border-radius: 8px;
}

.todo-item--danger .todo-item__count {
  color: #b91c1c;
  background: #fef2f2;
}

.todo-item--warn .todo-item__count {
  color: #b45309;
  background: #fffbeb;
}

.todo-empty {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 28px 12px;
  text-align: center;
  background: #f8fafc;
  border-radius: 8px;
}

.todo-empty strong {
  color: #047857;
}

.todo-empty span {
  font-size: 13px;
  color: #64748b;
}

.execution-item {
  justify-content: space-between;
  gap: 12px;
}

.execution-item__right {
  display: flex;
  flex-shrink: 0;
  flex-direction: column;
  align-items: flex-end;
  gap: 5px;
}

.batch-item {
  gap: 12px;
}

.batch-item__type {
  display: grid;
  flex-shrink: 0;
  width: 44px;
  height: 32px;
  place-items: center;
  font-size: 12px;
  font-weight: 760;
  border-radius: 6px;
}

.batch-item__type.is-register {
  color: #1d4ed8;
  background: #eff6ff;
}

.batch-item__type.is-login {
  color: #047857;
  background: #ecfdf5;
}

.batch-item__result {
  display: flex;
  flex-shrink: 0;
  flex-direction: column;
  align-items: flex-end;
  gap: 5px;
  margin-left: auto;
}

.quick-actions {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.quick-actions button {
  flex-direction: column;
  align-items: flex-start;
  gap: 5px;
  min-height: 76px;
  background: #ffffff;
  border-color: rgba(148, 163, 184, 0.18);
}

@media (max-width: 1024px) {
  .quick-actions {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .ops-header {
    flex-direction: column;
    align-items: flex-start;
    padding: 16px;
  }

  .ops-header__actions {
    justify-content: space-between;
    width: 100%;
  }

  .ops-table__head {
    display: none;
  }

  .ops-table__row--tasks {
    grid-template-columns: 1fr;
    gap: 7px;
  }

  .execution-item,
  .batch-item {
    align-items: flex-start;
  }

  .execution-item__right,
  .batch-item__result {
    align-items: flex-start;
  }

  .quick-actions {
    grid-template-columns: 1fr;
  }
}
</style>
