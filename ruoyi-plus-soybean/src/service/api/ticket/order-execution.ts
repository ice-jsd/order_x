import { request } from '@/service/request';

export function fetchGetTicketOrderExecutionList(params?: Api.Ticket.OrderExecutionSearchParams) {
  return request<Api.Ticket.OrderExecutionList>({ url: '/ticket/order-execution/list', method: 'get', params });
}
