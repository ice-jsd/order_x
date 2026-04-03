import { request } from '@/service/request';

export function fetchGetTicketPlatformList(params?: Api.Ticket.PlatformSearchParams) {
  return request<Api.Ticket.PlatformList>({ url: '/ticket/platform/list', method: 'get', params });
}

export function fetchGetTicketPlatform(platformId: CommonType.IdType) {
  return request<Api.Ticket.Platform>({ url: `/ticket/platform/${platformId}`, method: 'get' });
}

export function fetchCreateTicketPlatform(data: Api.Ticket.PlatformOperateParams) {
  return request<boolean>({ url: '/ticket/platform', method: 'post', data });
}

export function fetchUpdateTicketPlatform(data: Api.Ticket.PlatformOperateParams) {
  return request<boolean>({ url: '/ticket/platform', method: 'put', data });
}

export function fetchDeleteTicketPlatform(platformIds: CommonType.IdType[]) {
  return request<boolean>({ url: `/ticket/platform/${platformIds.join(',')}`, method: 'delete' });
}

export function fetchGetTicketRegisterablePhones(
  platformId: CommonType.IdType,
  params?: Api.Ticket.PhoneRegisterableSearchParams
) {
  return request<Api.Ticket.PhoneList>({
    url: `/ticket/platform/${platformId}/registerable-phones`,
    method: 'get',
    params
  });
}

export function fetchRegisterFromPhones(platformId: CommonType.IdType, data: Api.Ticket.BatchRegisterParams) {
  return request<CommonType.IdType>({
    url: `/ticket/platform/${platformId}/register-from-phones`,
    method: 'post',
    data
  });
}

export function fetchLoginAccounts(platformId: CommonType.IdType, data: Api.Ticket.BatchLoginParams) {
  return request<CommonType.IdType>({ url: `/ticket/platform/${platformId}/login-accounts`, method: 'post', data });
}

export function fetchGetTicketLoginableAccounts(
  platformId: CommonType.IdType,
  params?: Api.Ticket.AccountSearchParams
) {
  return request<Api.Ticket.AccountList>({
    url: `/ticket/platform/${platformId}/loginable-accounts`,
    method: 'get',
    params
  });
}
