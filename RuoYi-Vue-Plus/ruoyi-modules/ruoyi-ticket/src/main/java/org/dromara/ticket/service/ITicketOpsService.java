package org.dromara.ticket.service;

import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.ticket.domain.bo.*;
import org.dromara.ticket.domain.vo.*;

import java.util.Map;

public interface ITicketOpsService {

    TicketDashboardOverviewVo selectDashboardOverview();

    TableDataInfo<TicketPlatformConfigVo> selectPlatformPage(TicketPlatformConfigBo bo, PageQuery pageQuery);

    TicketPlatformConfigVo selectPlatformById(Long platformId);

    TicketPurchaseTemplateVo getPurchaseTemplate(Long platformId, String purchaseType);

    int savePlatform(TicketPlatformConfigBo bo);

    int updatePlatform(TicketPlatformConfigBo bo);

    int removePlatforms(Long[] platformIds);

    TableDataInfo<TicketPhoneNumberVo> selectPhonePage(TicketPhoneNumberBo bo, PageQuery pageQuery);

    TicketPhoneBulkImportResultVo importPhones(TicketPhoneBulkImportBo bo);

    boolean changePhoneStatus(TicketPhoneStatusBo bo);

    TableDataInfo<TicketPhonePlatformRelationVo> selectRelationPage(TicketPhonePlatformRelationBo bo, PageQuery pageQuery);

    TableDataInfo<TicketPhoneNumberVo> selectRegisterablePhonePage(Long platformId, TicketPhoneNumberBo bo, PageQuery pageQuery);

    R<Long> registerFromPhones(Long platformId, TicketBatchRegisterBo bo);

    TableDataInfo<TicketManagedAccountVo> selectAccountPage(TicketManagedAccountBo bo, PageQuery pageQuery);

    TicketSelectableAccountIdsVo selectSelectableAccountIds(TicketManagedAccountBo bo);

    TableDataInfo<TicketPhoneNumberVo> selectBindablePhonePage(Long platformId, TicketPhoneNumberBo bo, PageQuery pageQuery);

    int createManagedAccount(TicketManagedAccountCreateBo bo);

    TicketHandsFormQuickCreateResultVo quickCreateHandsFormAccounts(TicketHandsFormQuickCreateBo bo);

    int updateManagedAccount(TicketManagedAccountUpdateBo bo);

    int updateManagedAccountLastName(Long accountId, TicketManagedAccountLastNameBo bo);

    int removeManagedAccounts(Long[] accountIds);

    TableDataInfo<TicketManagedAccountVo> selectLoginableAccountPage(Long platformId, TicketManagedAccountBo bo, PageQuery pageQuery);

    R<Long> loginAccounts(Long platformId, TicketBatchLoginBo bo);

    TableDataInfo<TicketRegistrationBatchVo> selectRegistrationBatchPage(TicketRegistrationBatchBo bo, PageQuery pageQuery);

    TicketRegistrationBatchVo selectRegistrationBatchById(Long batchId);

    java.util.List<TicketRegistrationBatchDetailVo> selectRegistrationBatchDetails(Long batchId);

    int updateRegistrationBatchStatus(Long batchId, TicketBatchStatusUpdateBo bo);

    TableDataInfo<TicketLoginBatchVo> selectLoginBatchPage(TicketLoginBatchBo bo, PageQuery pageQuery);

    TicketLoginBatchVo selectLoginBatchById(Long batchId);

    java.util.List<TicketLoginBatchDetailVo> selectLoginBatchDetails(Long batchId);

    R<Void> submitLoginEmailCode(Long batchId, Long detailId, TicketLoginEmailCodeBo bo);

    int updateLoginBatchStatus(Long batchId, TicketBatchStatusUpdateBo bo);

    TableDataInfo<TicketEventConfigVo> selectEventPage(TicketEventConfigBo bo, PageQuery pageQuery);

    TicketEventConfigVo selectEventById(Long eventId);

    int saveEvent(TicketEventConfigBo bo);

    int updateEvent(TicketEventConfigBo bo);

    int removeEvents(Long[] eventIds);

    TableDataInfo<TicketSaleTaskVo> selectSaleTaskPage(TicketSaleTaskBo bo, PageQuery pageQuery);

    TicketSaleTaskVo selectSaleTaskById(Long taskId);

    TicketSaleTaskProcessVo selectSaleTaskProcess(Long taskId);

    TableDataInfo<TicketOrderExecutionVo> selectSaleTaskProcessExecutions(Long taskId, String status, Long scheduleId, PageQuery pageQuery);

    int saveSaleTask(TicketSaleTaskBo bo);

    int updateSaleTask(TicketSaleTaskBo bo);

    TicketLivePocketQuestionnairePreviewVo previewLivePocketQuestionnaire(TicketLivePocketQuestionnairePreviewBo bo);

    int cancelSaleTask(Long taskId);

    int removeSaleTasks(Long[] taskIds);

    R<Long> executeSaleTask(Long taskId);

    R<Long> executeSaleTaskNow(Long taskId);

    R<Long> retryFailedLotteryExecutions(Long taskId);

    TableDataInfo<TicketLotteryBatchTaskVo> selectLotteryBatchTaskPage(TicketLotteryBatchTaskBo bo, PageQuery pageQuery);

    TicketLotteryBatchTaskVo selectLotteryBatchTaskById(Long batchTaskId);

    TicketLotteryBatchTaskProcessVo selectLotteryBatchTaskProcess(Long batchTaskId);

    int saveLotteryBatchTask(TicketLotteryBatchTaskBo bo);

    int updateLotteryBatchTask(TicketLotteryBatchTaskBo bo);

    int updateLotteryBatchTaskStatus(Long batchTaskId, TicketBatchStatusUpdateBo bo);

    R<Long> executeLotteryBatchTaskNow(Long batchTaskId);

    TableDataInfo<TicketOrderExecutionVo> selectOrderExecutionPage(TicketOrderExecutionBo bo, PageQuery pageQuery);

    TicketOrderExecutionVo selectOrderExecutionDetail(Long executionId);

    int markOrderExecutionPaid(Long executionId, TicketOrderExecutionPaymentBo bo);

    TableDataInfo<TicketAuditEventVo> selectAuditPage(TicketAuditEventBo bo, PageQuery pageQuery);

    R<String> handleCallback(String platformCode, Map<String, Object> payload);

    R<Void> reportExternalLoginSuccess(TicketExternalLoginReportBo bo);

    R<Void> submitExternalLoginReqData(TicketExternalLoginReqDataBo bo);

    R<Void> requestExternalLoginEmailCode(TicketExternalLoginCodeRequestBo bo);

    R<TicketExternalManualLoginCodeVo> fetchExternalLoginEmailCode(String requestId);

    R<Void> markExternalLoginEmailCodeInvalid(TicketExternalLoginCodeInvalidBo bo);

    R<TicketExternalOfflineAccountVo> fetchNextOfflineAccount(String platformCode);

    R<TicketExternalVerifyCodeVo> verifyCode(String platformCode, String email);

    R<TicketExternalVerifyCodeVo> emailVerifyCode(String platformCode, String email);

    R<TicketExternalVerifyCodeVo> emailActivationLink(String platformCode, String email);
}
