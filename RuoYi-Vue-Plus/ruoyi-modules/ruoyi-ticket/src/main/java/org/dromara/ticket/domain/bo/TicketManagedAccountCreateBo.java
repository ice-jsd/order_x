package org.dromara.ticket.domain.bo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.dromara.common.mybatis.core.domain.BaseEntity;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class TicketManagedAccountCreateBo extends BaseEntity {

    @NotNull(message = "平台不能为空")
    private Long platformId;
    @NotNull(message = "来源号码不能为空")
    private Long phoneId;
    @NotBlank(message = "邮箱不能为空")
    private String email;
    private String accountInfo;
    private String reqData;
    private String loginReqData;
}
