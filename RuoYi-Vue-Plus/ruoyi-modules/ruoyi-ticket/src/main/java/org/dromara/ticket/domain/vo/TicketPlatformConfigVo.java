package org.dromara.ticket.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ticket.domain.TicketPlatformConfig;

import java.io.Serial;
import java.io.Serializable;

@Data
@AutoMapper(target = TicketPlatformConfig.class)
public class TicketPlatformConfigVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long platformId;
    private String tenantId;
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
    private String callbackSecretMask;
    private String registrationTemplate;
    private String loginStrategy;
    private String remark;
}
