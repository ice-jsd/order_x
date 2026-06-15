package org.dromara.ticket.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TicketExternalLoginCodeRequestBo {

    @NotBlank(message = "platformCode不能为空")
    private String platformCode;

    @NotNull(message = "batchId不能为空")
    private Long batchId;

    @NotNull(message = "accountId不能为空")
    private Long accountId;

    @NotBlank(message = "email不能为空")
    private String email;

    @NotBlank(message = "requestId不能为空")
    private String requestId;

    private Integer timeoutSeconds;
}
