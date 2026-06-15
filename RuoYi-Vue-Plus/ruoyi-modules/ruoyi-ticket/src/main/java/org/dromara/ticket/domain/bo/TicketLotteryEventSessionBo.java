package org.dromara.ticket.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class TicketLotteryEventSessionBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String sessionId;
    private String sessionLabel;
    private String eventUrl;
    private String receptionId;
    private String ticketId;
    private String ticketField;
    private String receptionTitle;
    private String salesType;
    private String notes;
    private Integer maxPurchaseQuantity;
}
