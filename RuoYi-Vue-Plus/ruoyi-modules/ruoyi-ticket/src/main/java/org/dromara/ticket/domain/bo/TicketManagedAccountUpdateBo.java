package org.dromara.ticket.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.mybatis.core.domain.BaseEntity;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class TicketManagedAccountUpdateBo extends BaseEntity {

    @NotNull(message = "账号ID不能为空")
    private Long accountId;

    @NotBlank(message = "邮箱不能为空")
    private String email;

    private String accountInfo;
    private String reqData;
    private String accountStatus;
    private String loginStatus;
    private String lastError;
}
