package org.dromara.ticket.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ticket_platform_config")
public class TicketPlatformConfig extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "platform_id")
    private Long platformId;

    private String platformCode;
    private String platformName;
    private String adapterType;
    private String environment;
    private Boolean enabled;
    private Boolean supportsBatchRegister;
    private Boolean supportsBatchLogin;
    private Boolean supportsSms;
    private Boolean supportsEmail;
    private Boolean supportsPhoneIdentity;
    private String callbackUrl;
    private String orderSubmitUrl;
    private String callbackSecretMask;
    private String registrationTemplate;
    private String loginStrategy;
    private String remark;

    @TableLogic
    private Long delFlag;
}
