package org.dromara.ticket.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TicketLivePocketQuestionnairePreviewBo {

    @NotNull(message = "平台不能为空")
    private Long platformId;

    @NotNull(message = "预读账号不能为空")
    private Long accountId;

    @NotBlank(message = "抽票链接不能为空")
    private String lotteryEventUrl;

    @NotBlank(message = "预读场次不能为空")
    private String selectedSessionId;

    @NotBlank(message = "任务配置不能为空")
    private String taskOptions;
}
