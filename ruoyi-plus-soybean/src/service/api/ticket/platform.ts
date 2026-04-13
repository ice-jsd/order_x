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
