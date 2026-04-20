package org.dromara.ticket.adapter;

import org.springframework.stereotype.Component;

@Component
public class JumpShopOnlineAdapter extends MockTicketPlatformAdapter {

    @Override
    public String adapterType() {
        return "jump-shop-online";
    }
}
