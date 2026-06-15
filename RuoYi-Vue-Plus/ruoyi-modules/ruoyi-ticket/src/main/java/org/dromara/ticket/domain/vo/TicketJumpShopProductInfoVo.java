package org.dromara.ticket.domain.vo;

import lombok.Data;

@Data
public class TicketJumpShopProductInfoVo {

    private String productUrl;
    private String title;
    private String imageUrl;
    private Long variantId;
    private Long productId;
    private String sectionId;
    private Boolean available;
    private Integer maxQuantity;
    private String currency;
    private String purchaseMode;
    private String paymentMode;
}
