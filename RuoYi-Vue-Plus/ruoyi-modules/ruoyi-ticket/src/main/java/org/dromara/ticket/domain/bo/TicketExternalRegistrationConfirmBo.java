package org.dromara.ticket.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class TicketExternalRegistrationConfirmBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "platformCode不能为空")
    private String platformCode;

    @NotBlank(message = "email不能为空")
    private String email;

    @NotNull(message = "success不能为空")
    private Boolean success;

    private String accountInfo;
    private String reqData;
    private String message;
}
