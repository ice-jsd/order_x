import { request } from '@/service/request';

export function fetchGetTicketRegistrationBatchList(params?: Api.Ticket.RegistrationBatchSearchParams) {
  return request<Api.Ticket.RegistrationBatchList>({ url: '/ticket/registration-batch/list', method: 'get', params });
}

export function fetchGetTicketRegistrationBatch(batchId: CommonType.IdType) {
  return request<Api.Ticket.RegistrationBatch>({ url: `/ticket/registration-batch/${batchId}`, method: 'get' });
}

export function fetchGetTicketRegistrationBatchDetails(batchId: CommonType.IdType) {
  return request<Api.Ticket.RegistrationBatchDetail[]>({
    url: `/ticket/registration-batch/${batchId}/details`,
    method: 'get'
  });
}
