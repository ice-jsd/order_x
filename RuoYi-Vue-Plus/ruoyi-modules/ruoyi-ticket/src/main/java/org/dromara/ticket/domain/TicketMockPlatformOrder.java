package org.dromara.ticket.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;
import java.util.Date;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ticket_mock_platform_order")
public class TicketMockPlatformOrder extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "mock_order_id")
    private Long mockOrderId;

    private String platformCode;
    private Long executionId;
    private Long taskId;
    private Long accountId;
    private String flowType;
    private String fulfillmentType;
    private String paymentMode;
    private String stepType;
    private String productId;
    private Integer purchaseQuantity;
    private String pickupStoreCode;
    private String deliveryOption;
    private String requestPayload;
    private String responsePayload;
    private String mockStatus;
    private String mockOrderNo;
    private String paymentStatus;
    private Date createdAt;

    @TableLogic
    private Long delFlag;
}
