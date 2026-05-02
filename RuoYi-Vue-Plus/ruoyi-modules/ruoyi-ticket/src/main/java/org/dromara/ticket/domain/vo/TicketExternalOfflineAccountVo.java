package org.dromara.ticket.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class TicketExternalOfflineAccountVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String email;
    private String password;
}
