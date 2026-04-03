import { request } from '@/service/request';

export function fetchGetTicketLoginBatchList(params?: Api.Ticket.LoginBatchSearchParams) {
  return request<Api.Ticket.LoginBatchList>({ url: '/ticket/login-batch/list', method: 'get', params });
}

export function fetchGetTicketLoginBatch(batchId: CommonType.IdType) {
  return request<Api.Ticket.LoginBatch>({ url: `/ticket/login-batch/${batchId}`, method: 'get' });
}

export function fetchGetTicketLoginBatchDetails(batchId: CommonType.IdType) {
  return request<Api.Ticket.LoginBatchDetail[]>({
    url: `/ticket/login-batch/${batchId}/details`,
    method: 'get'
  });
}
