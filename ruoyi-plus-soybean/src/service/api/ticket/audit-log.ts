import { request } from '@/service/request';

export function fetchGetTicketAuditLogList(params?: Api.Ticket.AuditLogSearchParams) {
  return request<Api.Ticket.AuditLogList>({ url: '/ticket/audit-log/list', method: 'get', params });
}
