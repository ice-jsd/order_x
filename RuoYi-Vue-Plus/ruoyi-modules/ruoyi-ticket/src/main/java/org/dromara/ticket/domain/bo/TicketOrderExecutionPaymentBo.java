package org.dromara.ticket.domain.bo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TicketOrderExecutionPaymentBo {

    @NotBlank(message = "支付备注不能为空")
    private String resultMessage;
}
