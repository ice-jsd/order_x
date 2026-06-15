package org.dromara.ticket.domain.bo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TicketLoginEmailCodeBo {

    @NotBlank(message = "验证码不能为空")
    private String verifyCode;
}
