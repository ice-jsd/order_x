package org.dromara.ticket.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import org.dromara.ticket.domain.TicketMailForwardRecord;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = TicketMailForwardRecord.class, reverseConvertGenerate = false)
public class TicketMailForwardRecordBo extends BaseEntity {

    private Long forwardId;
    private String sourceEmail;
    private String targetEmail;
    private String sendStatus;
    private String keyword;
}
