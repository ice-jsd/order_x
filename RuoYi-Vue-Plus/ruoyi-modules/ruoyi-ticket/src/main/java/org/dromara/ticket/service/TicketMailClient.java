package org.dromara.ticket.service;

import cn.hutool.extra.mail.MailAccount;

import java.util.Collection;

public interface TicketMailClient {

    String send(MailAccount account, Collection<String> tos, String subject, String content);
}
