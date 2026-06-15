import { request } from '@/service/request';

const MAIL_SYNC_TIMEOUT = 120 * 1000;

export function fetchGetTicketMailboxAccountList(params?: Api.Ticket.MailboxAccountSearchParams) {
  return request<Api.Ticket.MailboxAccountList>({ url: '/ticket/mailbox-account/list', method: 'get', params });
}

export function fetchGetTicketMailboxMailRecords(
  mailboxId: CommonType.IdType,
  params?: Api.Ticket.MailRecordSearchParams
) {
  return request<Api.Ticket.MailRecordList>({
    url: `/ticket/mailbox-account/${mailboxId}/mail-records`,
    method: 'get',
    params
  });
}

export function fetchGetTicketMailboxMailFeed(params?: Api.Ticket.MailFeedSearchParams) {
  return request<Api.Ticket.MailFeedList>({
    url: '/ticket/mailbox-account/mail-feed',
    method: 'get',
    params
  });
}

export function fetchDeleteTicketMailboxMailFeed(data?: Api.Ticket.MailFeedSearchParams) {
  return request<number>({
    url: '/ticket/mailbox-account/mail-feed/remove',
    method: 'post',
    data
  });
}

export function fetchDeleteTicketMailboxMailRecords(recordIds: CommonType.IdType[]) {
  return request<boolean>({ url: `/ticket/mailbox-account/mail-records/${recordIds.join(',')}`, method: 'delete' });
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
  return request<boolean>({
    url: `/ticket/mailbox-account/${mailboxId}/sync-mail`,
    method: 'post',
    timeout: MAIL_SYNC_TIMEOUT
  });
}

export function fetchSyncTicketMailboxMails(data: Api.Ticket.MailboxMailSyncParams) {
  return request<boolean>({
    url: '/ticket/mailbox-account/sync-mail',
    method: 'post',
    data,
    timeout: MAIL_SYNC_TIMEOUT
  });
}

export function fetchReparseTicketMailboxMailRecords() {
  return request<Api.Ticket.MailRecordReparseResult>({
    url: '/ticket/mailbox-account/reparse-mail-records',
    method: 'post'
  });
}
