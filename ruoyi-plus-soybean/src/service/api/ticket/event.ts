import { request } from '@/service/request';

export function fetchGetTicketEventList(params?: Api.Ticket.EventSearchParams) {
  return request<Api.Ticket.EventList>({ url: '/ticket/event/list', method: 'get', params });
}

export function fetchGetTicketEvent(eventId: CommonType.IdType) {
  return request<Api.Ticket.Event>({ url: `/ticket/event/${eventId}`, method: 'get' });
}

export function fetchCreateTicketEvent(data: Api.Ticket.EventOperateParams) {
  return request<boolean>({ url: '/ticket/event', method: 'post', data });
}

export function fetchUpdateTicketEvent(data: Api.Ticket.EventOperateParams) {
  return request<boolean>({ url: '/ticket/event', method: 'put', data });
}

export function fetchDeleteTicketEvent(eventIds: CommonType.IdType[]) {
  return request<boolean>({ url: `/ticket/event/${eventIds.join(',')}`, method: 'delete' });
}
