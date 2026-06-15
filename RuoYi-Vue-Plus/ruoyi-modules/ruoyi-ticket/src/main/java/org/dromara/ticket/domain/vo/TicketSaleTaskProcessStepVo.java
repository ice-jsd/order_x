package org.dromara.ticket.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

@Data
public class TicketSaleTaskProcessStepVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String stepKey;
    private String title;
    private String description;
    private String status;
    private Date time;
}
