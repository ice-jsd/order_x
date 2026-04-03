import { request } from '@/service/request';

export function fetchGetTicketAccountList(params?: Api.Ticket.AccountSearchParams) {
  return request<Api.Ticket.AccountList>({ url: '/ticket/account/list', method: 'get', params });
}
