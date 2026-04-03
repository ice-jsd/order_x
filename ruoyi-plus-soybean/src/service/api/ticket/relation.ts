import { request } from '@/service/request';

export function fetchGetTicketRelationList(params?: Api.Ticket.RelationSearchParams) {
  return request<Api.Ticket.RelationList>({ url: '/ticket/phone-platform-relation/list', method: 'get', params });
}
