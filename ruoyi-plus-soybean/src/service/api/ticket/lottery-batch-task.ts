import { request } from '@/service/request';

export function fetchGetTicketLotteryBatchTaskList(params?: Api.Ticket.LotteryBatchTaskSearchParams) {
  return request<Api.Ticket.LotteryBatchTaskList>({ url: '/ticket/lottery-batch-task/list', method: 'get', params });
}

export function fetchGetTicketLotteryBatchTask(batchTaskId: CommonType.IdType) {
  return request<Api.Ticket.LotteryBatchTask>({ url: `/ticket/lottery-batch-task/${batchTaskId}`, method: 'get' });
}

export function fetchGetTicketLotteryBatchTaskProcess(batchTaskId: CommonType.IdType) {
  return request<Api.Ticket.LotteryBatchTaskProcess>({ url: `/ticket/lottery-batch-task/${batchTaskId}/process`, method: 'get' });
}

export function fetchCreateTicketLotteryBatchTask(data: Api.Ticket.LotteryBatchTaskOperateParams) {
  return request<boolean>({ url: '/ticket/lottery-batch-task', method: 'post', data });
}

export function fetchUpdateTicketLotteryBatchTask(data: Api.Ticket.LotteryBatchTaskOperateParams) {
  return request<boolean>({ url: '/ticket/lottery-batch-task', method: 'put', data });
}

export function fetchUpdateTicketLotteryBatchTaskStatus(
  batchTaskId: CommonType.IdType,
  data: Api.Ticket.BatchStatusUpdateParams
) {
  return request<boolean>({ url: `/ticket/lottery-batch-task/${batchTaskId}/status`, method: 'put', data });
}

export function fetchExecuteNowTicketLotteryBatchTask(batchTaskId: CommonType.IdType) {
  return request<CommonType.IdType>({ url: `/ticket/lottery-batch-task/${batchTaskId}/execute-now`, method: 'post' });
}
