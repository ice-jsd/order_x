package org.dromara.ticket.adapter;

import org.dromara.ticket.domain.TicketPlatformConfig;
import org.dromara.ticket.domain.vo.TicketPurchaseTemplateVo;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class HandsFormAdapter extends MockTicketPlatformAdapter {

    @Override
    public String adapterType() {
        return "hands-form";
    }

    @Override
    public TicketPurchaseTemplateVo getPurchaseTemplate(TicketPlatformConfig platform, String purchaseType) {
        if (!TicketOrderFlowSupport.isLottery(purchaseType)) {
            return super.getPurchaseTemplate(platform, purchaseType);
        }
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("eventUrl", "");
        template.put("ticketEntryUrl", "");
        template.put("lotteryEntryUrl", "");
        template.put("entryQuantity", 1);
        template.put("selectedSessions", List.of());
        template.put("successKeywords", List.of("登録番号", "申込", "完了"));
        template.put("failureKeywords", List.of("エラー", "失敗", "入力してください"));
        template.put("notes", "Hands 公开表单抽票由 Python Playwright 执行器提交，成功后会回写登録番号");
        return TicketOrderFlowSupport.buildTemplate(
            platform,
            purchaseType,
            template,
            List.of("eventUrl", "entryQuantity", "selectedSessions", "successKeywords", "failureKeywords", "notes")
        );
    }
}
