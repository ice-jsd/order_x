import { request } from '@/service/request';

export function fetchGetTicketDashboardOverview() {
  return request<Api.Ticket.DashboardOverview>({ url: '/ticket/dashboard/overview', method: 'get' });
}
