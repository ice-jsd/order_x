<script setup lang="tsx">
import { computed, onMounted, reactive, ref } from 'vue';
import { NSwitch } from 'naive-ui';
import { useAuth } from '@/hooks/business/auth';
import { defaultTransform, useNaivePaginatedTable } from '@/hooks/common/table';
import {
  fetchBulkImportTicketPhones,
  fetchChangeTicketPhoneStatus,
  fetchGetTicketPhoneList
} from '@/service/api/ticket';
import { useAppStore } from '@/store/modules/app';
import {
  countryCodeOptions,
  phoneStatusOptions,
  renderCountryCode,
  renderPhoneStatusTag,
  renderTicketEllipsis
} from '../common';

defineOptions({
  name: 'TicketPhoneList'
});

const appStore = useAppStore();
const { hasAuth } = useAuth();

function createSearchParams(): Api.Ticket.PhoneSearchParams {
  return {
    pageNum: 1,
    pageSize: 10,
    phoneNumber: null,
    countryCode: null,
    supplier: null,
    status: null,
    params: {}
  };
}

function createImportForm(): Api.Ticket.PhoneImportParams {
  return {
    supplier: 'default-pool',
    countryCode: '+81',
    status: 'available',
    note: '',
    numbers: ''
  };
}

const searchParams = ref<Api.Ticket.PhoneSearchParams>(createSearchParams());
const importForm = ref<Api.Ticket.PhoneImportParams>(createImportForm());
const checkedRowKeys = ref<CommonType.IdType[]>([]);
const phoneStatusLoadingMap = reactive<Record<string, boolean>>({});

const importPlaceholder = computed(() => {
  if (importForm.value.countryCode === '+86') {
    return '一行一个号码，例如\n13800000001\n13900000002';
  }

  return '一行一个号码，例如\n09012345678\n08012345678';
});

const { columns, columnChecks, data, getData, getDataByPage, loading, mobilePagination, scrollX } =
  useNaivePaginatedTable({
    api: () => fetchGetTicketPhoneList(searchParams.value),
    transform: response => defaultTransform(response),
    onPaginationParamsChange: params => {
      searchParams.value.pageNum = params.page;
      searchParams.value.pageSize = params.pageSize;
    },
    columns: () => [
      { type: 'selection', align: 'center', width: 48 },
      { key: 'phoneNumber', title: '号码', align: 'center', minWidth: 160 },
      {
        key: 'countryCode',
        title: '国家区号',
        align: 'center',
        width: 120,
        render: row => renderCountryCode(row.countryCode)
      },
      { key: 'supplier', title: '供应商', align: 'center', minWidth: 120 },
      {
        key: 'status',
        title: '号码状态',
        align: 'center',
        width: 100,
        render: row => renderPhoneStatusTag(row.status)
      },
      { key: 'registeredPlatformCount', title: '已注册平台数', align: 'center', width: 120 },
      { key: 'loggedInPlatformCount', title: '已登录平台数', align: 'center', width: 120 },
      {
        key: 'note',
        title: '备注',
        align: 'center',
        minWidth: 220,
        render: row => renderTicketEllipsis(row.note)
      },
      { key: 'createTime', title: '创建时间', align: 'center', minWidth: 160 },
      {
        key: 'enabledSwitch',
        title: '启用',
        align: 'center',
        width: 96,
        render: row => {
          const canToggle = ['available', 'disabled'].includes(row.status);
          return (
            <NSwitch
              value={row.status === 'available'}
              size="small"
              loading={phoneStatusLoadingMap[String(row.phoneId)]}
              disabled={!hasAuth('ticket:phone:edit') || !canToggle}
              onUpdateValue={value => handleTogglePhoneStatus(row, value)}
            />
          );
        }
      }
    ]
  });

function resetSearch() {
  searchParams.value = createSearchParams();
  checkedRowKeys.value = [];
  void getDataByPage();
}

function normalizeImportedNumbers(input: string) {
  return input
    .split(/\r?\n/)
    .map((item, index) => ({
      line: index + 1,
      raw: item,
      value: item.trim().replace(/[\s-]/g, '')
    }))
    .filter(item => item.value);
}

function validateImportedNumbers(countryCode: string, numbers: string) {
  const rows = normalizeImportedNumbers(numbers);

  if (!rows.length) {
    return {
      valid: false,
      normalizedNumbers: [] as string[],
      message: '请先输入要导入的号码'
    };
  }

  const ruleMap: Record<string, RegExp> = {
    '+86': /^1[3-9]\d{9}$/,
    '+81': /^(070|080|090)\d{8}$/
  };

  const rule = ruleMap[countryCode];
  if (!rule) {
    return {
      valid: false,
      normalizedNumbers: [] as string[],
      message: '暂不支持当前国家区号'
    };
  }

  const invalidRows = rows.filter(item => !rule.test(item.value));
  if (invalidRows.length) {
    const preview = invalidRows
      .slice(0, 3)
      .map(item => `第 ${item.line} 行：${item.raw || item.value}`)
      .join('；');
    const countryLabel = countryCode === '+86' ? '中国大陆手机号' : '日本手机号';
    return {
      valid: false,
      normalizedNumbers: [] as string[],
      message: `${countryLabel}格式不正确，${preview}${invalidRows.length > 3 ? ' 等' : ''}`
    };
  }

  return {
    valid: true,
    normalizedNumbers: rows.map(item => item.value),
    message: ''
  };
}

async function handleImport() {
  const validation = validateImportedNumbers(importForm.value.countryCode || '', importForm.value.numbers || '');
  if (!validation.valid) {
    window.$message?.warning(validation.message);
    return;
  }

  const payload: Api.Ticket.PhoneImportParams = {
    ...importForm.value,
    numbers: validation.normalizedNumbers.join('\n')
  };

  const { data: result, error } = await fetchBulkImportTicketPhones(payload);
  if (error) return;
  window.$message?.success(`导入完成，新增 ${result.importedCount} 条，跳过 ${result.skippedCount} 条`);
  importForm.value.numbers = '';
  await getData();
}

async function handleTogglePhoneStatus(row: Api.Ticket.Phone, enabled: boolean) {
  const nextStatus = enabled ? 'available' : 'disabled';
  if (row.status === nextStatus) {
    return;
  }

  const actionText = enabled ? '启用' : '停用';
  window.$dialog?.warning({
    title: '状态确认',
    content: `确定要${actionText}号码 ${row.phoneNumber} 吗？`,
    positiveText: '确定',
    negativeText: '取消',
    onPositiveClick: async () => {
      const rowKey = String(row.phoneId);
      phoneStatusLoadingMap[rowKey] = true;
      const { error } = await fetchChangeTicketPhoneStatus({
        phoneIds: [row.phoneId],
        status: nextStatus
      });
      phoneStatusLoadingMap[rowKey] = false;
      if (error) {
        return;
      }
      row.status = nextStatus;
      window.$message?.success(`号码已${actionText}`);
    }
  });
}

onMounted(() => {
  void getData();
});
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <NCard title="批量导入号码" :bordered="false" size="small" class="card-wrapper">
      <NGrid :cols="24" :x-gap="16">
        <NFormItemGi :span="6" label="供应商">
          <NInput v-model:value="importForm.supplier" placeholder="请输入供应商" />
        </NFormItemGi>
        <NFormItemGi :span="4" label="国家区号">
          <NSelect v-model:value="importForm.countryCode" :options="countryCodeOptions" placeholder="请选择国家区号" />
        </NFormItemGi>
        <NFormItemGi :span="4" label="初始状态">
          <NSelect v-model:value="importForm.status" :options="phoneStatusOptions" />
        </NFormItemGi>
        <NFormItemGi :span="10" label="备注">
          <NInput v-model:value="importForm.note" placeholder="本批次备注" />
        </NFormItemGi>
        <NFormItemGi :span="24" label="号码列表">
          <NInput v-model:value="importForm.numbers" type="textarea" :rows="5" :placeholder="importPlaceholder" />
        </NFormItemGi>
      </NGrid>
      <div class="mt-16px flex justify-end">
        <NButton type="primary" @click="handleImport">一键导入</NButton>
      </div>
    </NCard>

    <NCard title="号码池筛选" :bordered="false" size="small" class="card-wrapper">
      <NForm inline label-placement="left" :label-width="80">
        <NFormItem label="号码">
          <NInput v-model:value="searchParams.phoneNumber" clearable placeholder="请输入号码" />
        </NFormItem>
        <NFormItem label="国家区号">
          <NSelect
            v-model:value="searchParams.countryCode"
            clearable
            :options="countryCodeOptions"
            placeholder="请选择国家区号"
            class="w-160px"
          />
        </NFormItem>
        <NFormItem label="供应商">
          <NInput v-model:value="searchParams.supplier" clearable placeholder="请输入供应商" />
        </NFormItem>
        <NFormItem label="号码状态">
          <NSelect
            v-model:value="searchParams.status"
            clearable
            :options="phoneStatusOptions"
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

    <NCard title="号码池管理" :bordered="false" size="small" class="card-wrapper sm:flex-1-hidden">
      <template #header-extra>
        <TableHeaderOperation
          v-model:columns="columnChecks"
          :disabled-delete="checkedRowKeys.length === 0"
          :show-add="false"
          :show-delete="false"
          :loading="loading"
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
        :row-key="row => row.phoneId"
        :pagination="mobilePagination"
        class="sm:h-full"
      />
    </NCard>
  </div>
</template>
