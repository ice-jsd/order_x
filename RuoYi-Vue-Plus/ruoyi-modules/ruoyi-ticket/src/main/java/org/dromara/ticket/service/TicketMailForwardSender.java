package org.dromara.ticket.service;

public interface TicketMailForwardSender {

    String send(String targetEmail, String subject, String content);
}
