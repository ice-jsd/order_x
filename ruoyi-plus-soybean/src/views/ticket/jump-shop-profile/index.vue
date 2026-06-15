<script setup lang="tsx">
import { computed, ref } from 'vue';
import { NButton, NSwitch, NTag } from 'naive-ui';
import { useAuth } from '@/hooks/business/auth';
import { defaultTransform, useNaivePaginatedTable } from '@/hooks/common/table';
import {
  fetchCreateTicketJumpShopProfile,
  fetchDeleteTicketJumpShopProfile,
  fetchGetTicketJumpShopProfile,
  fetchGetTicketJumpShopProfileList,
  fetchUpdateTicketJumpShopProfile
} from '@/service/api/ticket';
import { useAppStore } from '@/store/modules/app';
import { renderTicketEllipsis } from '../common';

defineOptions({
  name: 'TicketJumpShopProfileList'
});

const appStore = useAppStore();
const { hasAuth } = useAuth();

interface JumpShopProfileFormModel {
  profileId?: CommonType.IdType;
  profileName: string;
  enabled: boolean;
  lastName: string;
  firstName: string;
  phone: string;
  postalCode: string;
  province: string;
  city: string;
  address1: string;
  address2: string;
  countryCode: string;
  billingSameAsShipping: boolean;
  billingLastName: string;
  billingFirstName: string;
  billingPhone: string;
  billingPostalCode: string;
  billingProvince: string;
  billingCity: string;
  billingAddress1: string;
  billingAddress2: string;
  billingCountryCode: string;
  cardHolderName: string;
  cardNumber: string;
  expMonth: string;
  expYear: string;
  cvv: string;
  issueMonth: string;
  issueYear: string;
  issueNumber: string;
  remark: string;
}

function createSearchParams(): Api.Ticket.JumpShopProfileSearchParams {
  return {
    pageNum: 1,
    pageSize: 10,
    profileName: null,
    enabled: null,
    params: {}
  };
}

function createFormModel(): JumpShopProfileFormModel {
  return {
    profileId: undefined,
    profileName: '',
    enabled: true,
    lastName: '',
    firstName: '',
    phone: '',
    postalCode: '',
    province: '',
    city: '',
    address1: '',
    address2: '',
    countryCode: 'JP',
    billingSameAsShipping: true,
    billingLastName: '',
    billingFirstName: '',
    billingPhone: '',
    billingPostalCode: '',
    billingProvince: '',
    billingCity: '',
    billingAddress1: '',
    billingAddress2: '',
    billingCountryCode: 'JP',
    cardHolderName: '',
    cardNumber: '',
    expMonth: '',
    expYear: '',
    cvv: '',
    issueMonth: '',
    issueYear: '',
    issueNumber: '',
    remark: ''
  };
}

const searchParams = ref<Api.Ticket.JumpShopProfileSearchParams>(createSearchParams());
const checkedRowKeys = ref<CommonType.IdType[]>([]);
const modalVisible = ref(false);
const operateType = ref<NaiveUI.TableOperateType>('add');
const saving = ref(false);
const formModel = ref<JumpShopProfileFormModel>(createFormModel());
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

const modalTitle = computed(() => (operateType.value === 'add' ? '新增 Jump Shop 资料' : '编辑 Jump Shop 资料'));

const { columns, columnChecks, data, getData, getDataByPage, loading, mobilePagination, scrollX } =
  useNaivePaginatedTable({
    api: () => fetchGetTicketJumpShopProfileList(searchParams.value),
    transform: response => defaultTransform(response),
    onPaginationParamsChange: params => {
      searchParams.value.pageNum = params.page;
      searchParams.value.pageSize = params.pageSize;
    },
    columns: () => [
      { type: 'selection', align: 'center', width: 48 },
      { key: 'profileName', title: '资料名称', align: 'left', minWidth: 180, render: row => renderTicketEllipsis(row.profileName) },
      {
        key: 'shipping',
        title: '收件信息',
        align: 'left',
        minWidth: 220,
        render: row => renderTicketEllipsis([row.lastName, row.firstName, row.phone].filter(Boolean).join(' / '))
      },
      {
        key: 'address',
        title: '地址',
        align: 'left',
        minWidth: 320,
        render: row =>
          renderTicketEllipsis([row.countryCode, row.province, row.city, row.address1, row.address2].filter(Boolean).join(' '))
      },
      {
        key: 'card',
        title: '信用卡',
        align: 'left',
        minWidth: 220,
        render: row => {
          const parts = [row.cardHolderName, row.cardNumberMasked, row.expMonth && row.expYear ? `${row.expMonth}/${row.expYear}` : '']
            .filter(Boolean)
            .join(' / ');
          return renderTicketEllipsis(parts || '-');
        }
      },
      {
        key: 'status',
        title: '状态',
        align: 'center',
        width: 90,
        render: row =>
          row.enabled
            ? <NTag type="success" bordered={false}>启用</NTag>
            : <NTag type="default" bordered={false}>停用</NTag>
      },
      {
        key: 'operate',
        title: '操作',
        align: 'center',
        fixed: 'right',
        width: 140,
        render: row => (
          <div class="flex-center gap-8px">
            {hasAuth('ticket:jumpShopProfile:edit') && (
              <NButton text type="primary" onClick={() => handleEdit(row.profileId)}>
                编辑
              </NButton>
            )}
            {hasAuth('ticket:jumpShopProfile:remove') && (
              <NButton text type="error" onClick={() => handleDelete([row.profileId])}>
                删除
              </NButton>
            )}
          </div>
        )
      },
      {
        key: 'enabled',
        title: '启用',
        align: 'center',
        width: 90,
        render: row => (
          <NSwitch
            value={row.enabled}
            size="small"
            disabled={!hasAuth('ticket:jumpShopProfile:edit')}
            onUpdateValue={value => handleToggleEnabled(row, value)}
          />
        )
      }
    ]
  });

void getData();

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

async function handleEdit(profileId: CommonType.IdType) {
  const { data: detail, error } = await fetchGetTicketJumpShopProfile(profileId);
  if (error || !detail) return;
  operateType.value = 'edit';
  formModel.value = {
    ...createFormModel(),
    ...detail,
    cardNumber: '',
    cvv: '',
    issueNumber: ''
  };
  modalVisible.value = true;
}

async function handleSubmit() {
  saving.value = true;
  try {
    const requestFn = operateType.value === 'add' ? fetchCreateTicketJumpShopProfile : fetchUpdateTicketJumpShopProfile;
    const { error } = await requestFn(formModel.value);
    if (error) return;
    window.$message?.success(operateType.value === 'add' ? 'Jump Shop 资料已创建' : 'Jump Shop 资料已更新');
    modalVisible.value = false;
    await getData();
  } finally {
    saving.value = false;
  }
}

async function handleDelete(profileIds: CommonType.IdType[]) {
  if (!profileIds.length) return;
  const { error } = await fetchDeleteTicketJumpShopProfile(profileIds);
  if (error) return;
  checkedRowKeys.value = checkedRowKeys.value.filter(item => !profileIds.includes(item));
  window.$message?.success('删除成功');
  await getData();
}

async function handleToggleEnabled(row: Api.Ticket.JumpShopProfile, enabled: boolean) {
  if (row.enabled === enabled) return;
  const { data: detail, error: detailError } = await fetchGetTicketJumpShopProfile(row.profileId);
  if (detailError || !detail) return;
  const payload: JumpShopProfileFormModel = {
    ...createFormModel(),
    ...detail,
    enabled,
    cardNumber: '',
    cvv: '',
    issueNumber: ''
  };
  const { error } = await fetchUpdateTicketJumpShopProfile(payload);
  if (error) return;
  row.enabled = enabled;
  window.$message?.success(enabled ? '资料已启用' : '资料已停用');
}
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <NCard title="Jump Shop 资料筛选" :bordered="false" size="small" class="card-wrapper">
      <NForm inline label-placement="left" :label-width="72">
        <NFormItem label="资料名称">
          <NInput v-model:value="searchParams.profileName" clearable placeholder="请输入资料名称" />
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

    <NCard title="Jump Shop 资料管理" :bordered="false" size="small" class="card-wrapper sm:flex-1-hidden">
      <template #header-extra>
        <TableHeaderOperation
          v-model:columns="columnChecks"
          :loading="loading"
          :show-add="hasAuth('ticket:jumpShopProfile:add')"
          :show-delete="hasAuth('ticket:jumpShopProfile:remove')"
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
        :row-key="row => row.profileId"
        :pagination="mobilePagination"
        class="sm:h-full"
      />
    </NCard>

    <NModal v-model:show="modalVisible" preset="card" :title="modalTitle" class="w-980px">
      <NForm label-placement="top" :model="formModel">
        <NGrid :cols="24" :x-gap="16">
          <NFormItemGi :span="8" label="资料名称">
            <NInput v-model:value="formModel.profileName" placeholder="例如 默认信用卡资料" />
          </NFormItemGi>
          <NFormItemGi :span="8" label="国家">
            <NInput v-model:value="formModel.countryCode" placeholder="JP" />
          </NFormItemGi>
          <NFormItemGi :span="8" label="启用">
            <NCheckbox v-model:checked="formModel.enabled">启用资料</NCheckbox>
          </NFormItemGi>

          <NFormItemGi :span="24" label="收件信息">
            <div class="text-12px text-text-3">订单页会优先使用这里的收件人与地址，不读取注册时的随机资料。</div>
          </NFormItemGi>
          <NFormItemGi :span="6" label="姓">
            <NInput v-model:value="formModel.lastName" />
          </NFormItemGi>
          <NFormItemGi :span="6" label="名">
            <NInput v-model:value="formModel.firstName" />
          </NFormItemGi>
          <NFormItemGi :span="6" label="电话">
            <NInput v-model:value="formModel.phone" />
          </NFormItemGi>
          <NFormItemGi :span="6" label="邮编">
            <NInput v-model:value="formModel.postalCode" />
          </NFormItemGi>
          <NFormItemGi :span="8" label="都道府县">
            <NInput v-model:value="formModel.province" />
          </NFormItemGi>
          <NFormItemGi :span="8" label="城市">
            <NInput v-model:value="formModel.city" />
          </NFormItemGi>
          <NFormItemGi :span="8" label="地址1">
            <NInput v-model:value="formModel.address1" />
          </NFormItemGi>
          <NFormItemGi :span="24" label="地址2">
            <NInput v-model:value="formModel.address2" />
          </NFormItemGi>

          <NFormItemGi :span="24" label="账单信息">
            <NCheckbox v-model:checked="formModel.billingSameAsShipping">账单地址与收件地址一致</NCheckbox>
          </NFormItemGi>
          <template v-if="!formModel.billingSameAsShipping">
            <NFormItemGi :span="6" label="账单姓">
              <NInput v-model:value="formModel.billingLastName" />
            </NFormItemGi>
            <NFormItemGi :span="6" label="账单名">
              <NInput v-model:value="formModel.billingFirstName" />
            </NFormItemGi>
            <NFormItemGi :span="6" label="账单电话">
              <NInput v-model:value="formModel.billingPhone" />
            </NFormItemGi>
            <NFormItemGi :span="6" label="账单邮编">
              <NInput v-model:value="formModel.billingPostalCode" />
            </NFormItemGi>
            <NFormItemGi :span="8" label="账单都道府县">
              <NInput v-model:value="formModel.billingProvince" />
            </NFormItemGi>
            <NFormItemGi :span="8" label="账单城市">
              <NInput v-model:value="formModel.billingCity" />
            </NFormItemGi>
            <NFormItemGi :span="8" label="账单地址1">
              <NInput v-model:value="formModel.billingAddress1" />
            </NFormItemGi>
            <NFormItemGi :span="24" label="账单地址2">
              <NInput v-model:value="formModel.billingAddress2" />
            </NFormItemGi>
          </template>

          <NFormItemGi :span="24" label="信用卡信息">
            <div class="text-12px text-text-3">
              编辑已有资料时，卡号 / CVV / Issue Number 留空表示保持原值，后端会继续使用已加密保存的旧值。
            </div>
          </NFormItemGi>
          <NFormItemGi :span="8" label="持卡人">
            <NInput v-model:value="formModel.cardHolderName" />
          </NFormItemGi>
          <NFormItemGi :span="8" label="卡号">
            <NInput v-model:value="formModel.cardNumber" placeholder="留空则保持原值" />
          </NFormItemGi>
          <NFormItemGi :span="4" label="有效月">
            <NInput v-model:value="formModel.expMonth" placeholder="MM" />
          </NFormItemGi>
          <NFormItemGi :span="4" label="有效年">
            <NInput v-model:value="formModel.expYear" placeholder="YYYY" />
          </NFormItemGi>
          <NFormItemGi :span="8" label="CVV">
            <NInput v-model:value="formModel.cvv" placeholder="留空则保持原值" />
          </NFormItemGi>
          <NFormItemGi :span="4" label="Issue Month">
            <NInput v-model:value="formModel.issueMonth" />
          </NFormItemGi>
          <NFormItemGi :span="4" label="Issue Year">
            <NInput v-model:value="formModel.issueYear" />
          </NFormItemGi>
          <NFormItemGi :span="8" label="Issue Number">
            <NInput v-model:value="formModel.issueNumber" placeholder="留空则保持原值" />
          </NFormItemGi>

          <NFormItemGi :span="24" label="备注">
            <NInput v-model:value="formModel.remark" type="textarea" :rows="3" />
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
