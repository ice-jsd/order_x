import { request } from '@/service/request';

export function fetchGetTicketOrderExecutionList(params?: Api.Ticket.OrderExecutionSearchParams) {
  return request<Api.Ticket.OrderExecutionList>({ url: '/ticket/order-execution/list', method: 'get', params });
}

export function fetchGetTicketOrderExecutionDetail(executionId: CommonType.IdType) {
  return request<Api.Ticket.OrderExecution>({ url: `/ticket/order-execution/${executionId}`, method: 'get' });
}

export function fetchMarkTicketOrderExecutionPaid(
  executionId: CommonType.IdType,
  data: Api.Ticket.OrderExecutionPaymentParams
) {
  return request<boolean>({ url: `/ticket/order-execution/${executionId}/mark-paid`, method: 'post', data });
}

export function fetchDeleteTicketOrderExecutions(executionIds: CommonType.IdType[]) {
  return request<boolean>({ url: `/ticket/order-execution/${executionIds.join(',')}`, method: 'delete' });
}
