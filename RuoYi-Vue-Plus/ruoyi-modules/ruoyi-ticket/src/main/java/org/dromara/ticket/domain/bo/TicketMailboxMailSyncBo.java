package org.dromara.ticket.domain.bo;

import lombok.Data;

import java.util.List;

@Data
public class TicketMailboxMailSyncBo {

    private List<Long> mailboxIds;
}
