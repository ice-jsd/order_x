package org.dromara.ticket.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import org.dromara.ticket.domain.TicketPlatformConfig;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = TicketPlatformConfig.class, reverseConvertGenerate = false)
public class TicketPlatformConfigBo extends BaseEntity {

    private Long platformId;
    private String platformCode;
    private String platformName;
    private Boolean enabled;
    private String orderSubmitUrl;
}
