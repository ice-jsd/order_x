package org.dromara.ticket.service.impl;

import cn.hutool.extra.mail.MailAccount;
import cn.hutool.extra.mail.JakartaMail;
import org.dromara.ticket.service.TicketMailClient;
import org.springframework.stereotype.Service;

import java.util.Collection;

@Service
public class TicketMailClientImpl implements TicketMailClient {

    @Override
    public String send(MailAccount account, Collection<String> tos, String subject, String content) {
        return JakartaMail.create(account)
            .setUseGlobalSession(false)
            .setTos(tos.toArray(new String[0]))
            .setTitle(subject)
            .setContent(content)
            .setHtml(false)
            .send();
    }
}
