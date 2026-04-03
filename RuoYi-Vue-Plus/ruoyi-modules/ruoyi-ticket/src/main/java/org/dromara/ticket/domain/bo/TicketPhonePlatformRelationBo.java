package org.dromara.ticket.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import org.dromara.ticket.domain.TicketPhonePlatformRelation;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = TicketPhonePlatformRelation.class, reverseConvertGenerate = false)
public class TicketPhonePlatformRelationBo extends BaseEntity {

    private Long relationId;
    private Long phoneId;
    private Long platformId;
    private Long accountId;
    private String status;
}
