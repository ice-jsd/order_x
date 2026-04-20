import { request } from '@/service/request';

export function fetchGetTicketMailboxAccountList(params?: Api.Ticket.MailboxAccountSearchParams) {
  return request<Api.Ticket.MailboxAccountList>({ url: '/ticket/mailbox-account/list', method: 'get', params });
}

export function fetchBatchCreateTicketMailboxAccounts(data: Api.Ticket.MailboxBatchCreateParams) {
  return request<Api.Ticket.MailboxBatchCreateResult>({
    url: '/ticket/mailbox-account/batch-create',
    method: 'post',
    data
  });
}

export function fetchChangeTicketMailboxStatus(data: Api.Ticket.MailboxStatusParams) {
  return request<boolean>({ url: '/ticket/mailbox-account/changeStatus', method: 'post', data });
}

export function fetchSyncTicketMailboxMail(mailboxId: CommonType.IdType) {
  return request<boolean>({ url: `/ticket/mailbox-account/${mailboxId}/sync-mail`, method: 'post' });
}

export function fetchSyncTicketMailboxMails(data: Api.Ticket.MailboxMailSyncParams) {
  return request<boolean>({ url: '/ticket/mailbox-account/sync-mail', method: 'post', data });
}
