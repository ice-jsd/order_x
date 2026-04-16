import { request } from '@/service/request';

export function fetchGetTicketAccountList(params?: Api.Ticket.AccountSearchParams) {
  return request<Api.Ticket.AccountList>({ url: '/ticket/account/list', method: 'get', params });
}

export function fetchGetTicketBindablePhoneList(params?: Api.Ticket.AccountBindablePhoneSearchParams) {
  return request<Api.Ticket.PhoneList>({ url: '/ticket/account/available-phone/list', method: 'get', params });
}

export function fetchCreateTicketAccount(data: Api.Ticket.AccountOperateParams) {
  return request<boolean>({ url: '/ticket/account', method: 'post', data });
}
