package org.dromara.ticket.adapter;

import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TicketPlatformAdapterRegistry {

    private final List<TicketPlatformAdapter> adapters;

    public TicketPlatformAdapterRegistry(List<TicketPlatformAdapter> adapters) {
        this.adapters = adapters;
    }

    public TicketPlatformAdapter getAdapter(String adapterType) {
        return adapters.stream()
            .filter(adapter -> adapter.adapterType().equalsIgnoreCase(adapterType == null ? "mock" : adapterType))
            .findFirst()
            .orElseThrow(() -> new ServiceException("未找到可用的平台适配器: " + adapterType));
    }
}
