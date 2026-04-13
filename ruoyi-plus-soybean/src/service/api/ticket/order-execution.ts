import { request } from '@/service/request';

export function fetchGetTicketOrderExecutionList(params?: Api.Ticket.OrderExecutionSearchParams) {
  return request<Api.Ticket.OrderExecutionList>({ url: '/ticket/order-execution/list', method: 'get', params });
}

export function fetchMarkTicketOrderExecutionPaid(
  executionId: CommonType.IdType,
  data: Api.Ticket.OrderExecutionPaymentParams
) {
  return request<boolean>({ url: `/ticket/order-execution/${executionId}/mark-paid`, method: 'post', data });
}
