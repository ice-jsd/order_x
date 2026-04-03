import { request } from '@/service/request';

export function fetchGetTicketPhoneList(params?: Api.Ticket.PhoneSearchParams) {
  return request<Api.Ticket.PhoneList>({ url: '/ticket/phone/list', method: 'get', params });
}

export function fetchBulkImportTicketPhones(data: Api.Ticket.PhoneImportParams) {
  return request<Api.Ticket.PhoneImportResult>({ url: '/ticket/phone/bulk-import', method: 'post', data });
}

export function fetchChangeTicketPhoneStatus(data: Api.Ticket.PhoneStatusParams) {
  return request<boolean>({ url: '/ticket/phone/changeStatus', method: 'post', data });
}
