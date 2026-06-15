package org.dromara.ticket.domain.bo;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TicketMailForwardSendBo {

    @NotNull(message = "邮件记录不能为空")
    private Long recordId;

    @NotBlank(message = "目标邮箱不能为空")
    @Email(message = "目标邮箱格式不正确")
    private String targetEmail;
}
