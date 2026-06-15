import { request } from '@/service/request';

export function fetchGetTicketAccountList(params?: Api.Ticket.AccountSearchParams) {
  return request<Api.Ticket.AccountList>({ url: '/ticket/account/list', method: 'get', params });
}

export function fetchGetTicketSelectableAccountIds(params?: Api.Ticket.AccountSearchParams) {
  return request<Api.Ticket.SelectableAccountIds>({ url: '/ticket/account/selectable-ids', method: 'get', params });
}

export function fetchGetTicketBindablePhoneList(params?: Api.Ticket.AccountBindablePhoneSearchParams) {
  return request<Api.Ticket.PhoneList>({ url: '/ticket/account/available-phone/list', method: 'get', params });
}

export function fetchCreateTicketAccount(data: Api.Ticket.AccountOperateParams) {
  return request<boolean>({ url: '/ticket/account', method: 'post', data });
}

export function fetchUpdateTicketAccount(data: Api.Ticket.AccountOperateParams) {
  return request<boolean>({ url: '/ticket/account', method: 'put', data });
}

export function fetchUpdateTicketAccountLastName(accountId: CommonType.IdType, data: Api.Ticket.AccountLastNameUpdateParams) {
  return request<boolean>({ url: `/ticket/account/${accountId}/last-name`, method: 'put', data });
}

export function fetchGetTicketAccountLastNameRecordList(params?: Api.Ticket.AuditLogSearchParams) {
  return request<Api.Ticket.AuditLogList>({ url: '/ticket/account/last-name-record/list', method: 'get', params });
}

export function fetchTicketAccountBatchRegister(data: Api.Ticket.AccountBatchRegisterParams) {
  return request<CommonType.IdType>({ url: '/ticket/account/batch-register', method: 'post', data });
}

export function fetchTicketAccountBatchLogin(data: Api.Ticket.AccountBatchLoginParams) {
  return request<CommonType.IdType>({ url: '/ticket/account/batch-login', method: 'post', data });
}

export function fetchTicketAccountHandsFormQuickCreate(data: Api.Ticket.HandsFormQuickCreateParams) {
  return request<Api.Ticket.HandsFormQuickCreateResult>({
    url: '/ticket/account/hands-form/quick-create',
    method: 'post',
    data
  });
}

export function fetchGetTicketRegistrationBatchList(params?: Api.Ticket.RegistrationBatchSearchParams) {
  return request<Api.Ticket.RegistrationBatchList>({
    url: '/ticket/account/registration-batch/list',
    method: 'get',
    params
  });
}

export function fetchGetTicketRegistrationBatchDetails(batchId: CommonType.IdType) {
  return request<Api.Ticket.RegistrationBatchDetail[]>({
    url: `/ticket/account/registration-batch/${batchId}/details`,
    method: 'get'
  });
}

export function fetchUpdateTicketRegistrationBatchStatus(
  batchId: CommonType.IdType,
  data: Api.Ticket.BatchStatusUpdateParams
) {
  return request<boolean>({
    url: `/ticket/account/registration-batch/${batchId}/status`,
    method: 'put',
    data
  });
}

export function fetchGetTicketLoginBatchList(params?: Api.Ticket.LoginBatchSearchParams) {
  return request<Api.Ticket.LoginBatchList>({ url: '/ticket/account/login-batch/list', method: 'get', params });
}

export function fetchGetTicketLoginBatchDetails(batchId: CommonType.IdType) {
  return request<Api.Ticket.LoginBatchDetail[]>({
    url: `/ticket/account/login-batch/${batchId}/details`,
    method: 'get'
  });
}

export function fetchSubmitTicketLoginEmailCode(
  batchId: CommonType.IdType,
  detailId: CommonType.IdType,
  data: Api.Ticket.LoginEmailCodeParams
) {
  return request<boolean>({
    url: `/ticket/account/login-batch/${batchId}/details/${detailId}/email-code`,
    method: 'post',
    data
  });
}

export function fetchUpdateTicketLoginBatchStatus(batchId: CommonType.IdType, data: Api.Ticket.BatchStatusUpdateParams) {
  return request<boolean>({
    url: `/ticket/account/login-batch/${batchId}/status`,
    method: 'put',
    data
  });
}

export function fetchDeleteTicketAccounts(accountIds: CommonType.IdType[]) {
  return request<boolean>({ url: `/ticket/account/${accountIds.join(',')}`, method: 'delete' });
}
