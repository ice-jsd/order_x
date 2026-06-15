package org.dromara.ticket.domain.vo;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
@ExcelIgnoreUnannotated
public class TicketMailFeedSelectedExportVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @ExcelProperty("平台")
    private String platformName;

    @ExcelProperty("标题")
    private String title;

    @ExcelProperty("邮箱账号")
    private String mailboxEmail;

    @ExcelProperty("邮箱密码")
    private String mailboxPassword;

    @ExcelProperty("姓名")
    private String fullName;

    @ExcelProperty("平假名")
    private String furigana;

    @ExcelProperty("受付番号")
    private String lotteryApplicationNo;

    @ExcelProperty("场次信息")
    private String sessionInfo;
}
