import { request } from '@/service/request';

export function fetchGetTicketSaleTaskList(params?: Api.Ticket.SaleTaskSearchParams) {
  return request<Api.Ticket.SaleTaskList>({ url: '/ticket/sale-task/list', method: 'get', params });
}

export function fetchGetTicketSaleTask(taskId: CommonType.IdType) {
  return request<Api.Ticket.SaleTask>({ url: `/ticket/sale-task/${taskId}`, method: 'get' });
}

export function fetchCreateTicketSaleTask(data: Api.Ticket.SaleTaskOperateParams) {
  return request<boolean>({ url: '/ticket/sale-task', method: 'post', data });
}

export function fetchUpdateTicketSaleTask(data: Api.Ticket.SaleTaskOperateParams) {
  return request<boolean>({ url: '/ticket/sale-task', method: 'put', data });
}

export function fetchDeleteTicketSaleTask(taskIds: CommonType.IdType[]) {
  return request<boolean>({ url: `/ticket/sale-task/${taskIds.join(',')}`, method: 'delete' });
}

export function fetchExecuteTicketSaleTask(taskId: CommonType.IdType) {
  return request<CommonType.IdType>({ url: `/ticket/sale-task/${taskId}/execute`, method: 'post' });
}
