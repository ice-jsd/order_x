package org.dromara.ticket.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class TicketHandsFormExtensionReportBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "executionId不能为空")
    private Long executionId;

    @NotBlank(message = "workerId不能为空")
    private String workerId;

    @NotNull(message = "success不能为空")
    private Boolean success;

    private String message;

    private String orderId;

    private String resultUrl;

    private String rawResult;
}
