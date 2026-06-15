package org.dromara.ticket.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TicketLotteryEventParseBo {

    @NotNull(message = "平台不能为空")
    private Long platformId;

    @NotBlank(message = "票链接不能为空")
    private String eventUrl;
}
