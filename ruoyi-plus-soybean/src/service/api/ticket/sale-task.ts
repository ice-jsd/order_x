import { request } from '@/service/request';

function normalizeSaleTaskPayload(data: Api.Ticket.SaleTaskOperateParams) {
  const payload = JSON.parse(JSON.stringify(data)) as Api.Ticket.SaleTaskOperateParams;

  if (typeof payload.taskOptions === 'string' && payload.taskOptions.trim()) {
    try {
      payload.taskOptions = JSON.stringify(JSON.parse(payload.taskOptions));
    } catch {
      // Keep the original text so the backend can continue returning the validation error.
    }
  }

  return payload;
}

export function fetchGetTicketSaleTaskList(params?: Api.Ticket.SaleTaskSearchParams) {
  return request<Api.Ticket.SaleTaskList>({ url: '/ticket/sale-task/list', method: 'get', params });
}

export function fetchGetTicketSaleTask(taskId: CommonType.IdType) {
  return request<Api.Ticket.SaleTask>({ url: `/ticket/sale-task/${taskId}`, method: 'get' });
}

export function fetchGetTicketSaleTaskProcess(taskId: CommonType.IdType) {
  return request<Api.Ticket.SaleTaskProcess>({ url: `/ticket/sale-task/${taskId}/process`, method: 'get' });
}

export function fetchGetTicketSaleTaskProcessExecutions(
  taskId: CommonType.IdType,
  params?: Api.Ticket.SaleTaskProcessExecutionSearchParams
) {
  return request<Api.Ticket.OrderExecutionList>({
    url: `/ticket/sale-task/${taskId}/process-executions`,
    method: 'get',
    params
  });
}

export function fetchPreviewLivePocketQuestionnaire(data: Api.Ticket.LivePocketQuestionnairePreviewParams) {
  return request<Api.Ticket.LivePocketQuestionnairePreviewResult>({
    url: '/ticket/sale-task/livepocket/questionnaire-preview',
    method: 'post',
    data
  });
}

export function fetchCreateTicketSaleTask(data: Api.Ticket.SaleTaskOperateParams) {
  return request<boolean>({ url: '/ticket/sale-task', method: 'post', data: normalizeSaleTaskPayload(data) });
}

export function fetchUpdateTicketSaleTask(data: Api.Ticket.SaleTaskOperateParams) {
  return request<boolean>({ url: '/ticket/sale-task', method: 'put', data: normalizeSaleTaskPayload(data) });
}

export function fetchCancelTicketSaleTask(taskId: CommonType.IdType) {
  return request<boolean>({ url: `/ticket/sale-task/${taskId}/cancel`, method: 'post' });
}

export function fetchDeleteTicketSaleTask(taskIds: CommonType.IdType[]) {
  return request<boolean>({ url: `/ticket/sale-task/${taskIds.join(',')}`, method: 'delete' });
}

export function fetchExecuteTicketSaleTask(taskId: CommonType.IdType) {
  return request<CommonType.IdType>({ url: `/ticket/sale-task/${taskId}/execute`, method: 'post' });
}

export function fetchExecuteNowTicketSaleTask(taskId: CommonType.IdType) {
  return request<CommonType.IdType>({ url: `/ticket/sale-task/${taskId}/execute-now`, method: 'post' });
}

export function fetchRetryFailedTicketLotteryTask(taskId: CommonType.IdType) {
  return request<CommonType.IdType>({ url: `/ticket/sale-task/${taskId}/retry-failed-lottery`, method: 'post' });
}

export function fetchParseTicketLotteryEvent(data: Api.Ticket.LotteryEventParseParams) {
  return request<Api.Ticket.LotteryEventInfo>({ url: '/ticket/lottery-event/parse', method: 'post', data });
}

export function fetchGetTicketLotteryEventParseRecord(recordId: CommonType.IdType) {
  return request<Api.Ticket.LotteryEventInfo>({ url: `/ticket/lottery-event/parse/${recordId}`, method: 'get' });
}

export function fetchGetTicketLotteryEventHistory(platformId?: CommonType.IdType | null) {
  return request<Api.Ticket.LotteryEventInfo[]>({ url: '/ticket/lottery-event/history', method: 'get', params: { platformId } });
}
