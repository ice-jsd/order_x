package org.dromara.ticket.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
public class TicketPurchaseTemplateVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String purchaseType;
    private String configSchemaKey;
    private Map<String, Object> configTemplate;
    private List<String> editableFields;
}
