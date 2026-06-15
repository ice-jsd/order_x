import { request } from '@/service/request';

export function fetchGetTicketMailForwardList(params?: Api.Ticket.MailForwardSearchParams) {
  return request<Api.Ticket.MailForwardList>({ url: '/ticket/mail-forward/list', method: 'get', params });
}

export function fetchSendTicketMailForward(data: Api.Ticket.MailForwardSendParams) {
  return request<boolean>({ url: '/ticket/mail-forward/send', method: 'post', data });
}
