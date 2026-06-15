package org.dromara.ticket.domain.bo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.mybatis.core.domain.BaseEntity;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class TicketMailFeedBo extends BaseEntity {

    private String mode;
    private Long platformId;
    private String email;
    private String keyword;
    private String parseType;
    private String beginReceivedAt;
    private String endReceivedAt;
}
