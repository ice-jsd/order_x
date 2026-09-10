declare namespace Api {
  namespace Ticket {
    type RelationStatus =
      | 'available'
      | 'registering'
      | 'registered'
      | 'register_failed'
      | 'logged_in'
      | 'login_failed'
      | 'blocked';

    type BatchStatus = 'draft' | 'executing' | 'completed' | 'partial' | 'blocked';

    type BatchStatusUpdateParams = {
      batchStatus: BatchStatus | string;
      remark?: string;
    };

    type Platform = Common.CommonTenantRecord<{
      platformId: CommonType.IdType;
      platformCode: string;
      adapterType?: string;
      platformName: string;
      enabled: boolean;
      supportsBatchRegister?: boolean;
      supportsBatchLogin?: boolean;
      supportsSms?: boolean;
      supportsEmail?: boolean;
      supportsPhoneIdentity?: boolean;
      orderSubmitUrl: string;
    }>;

    type PlatformSearchParams = CommonType.RecordNullable<
      Pick<Platform, 'platformCode' | 'platformName' | 'enabled'> & Api.Common.CommonSearchParams
    >;

    type PlatformOperateParams = CommonType.RecordNullable<{
      platformId: CommonType.IdType;
      platformCode: string;
      adapterType?: string;
      platformName: string;
      enabled: boolean;
      orderSubmitUrl?: string;
    }>;

    type PlatformList = Api.Common.PaginatingQueryRecord<Platform>;

    type Phone = Common.CommonTenantRecord<{
      phoneId: CommonType.IdType;
      phoneNumber: string;
      countryCode: string;
      supplier: string;
      status: string;
      note?: string;
      registeredPlatformCount: number;
      loggedInPlatformCount: number;
    }>;

    type PhoneSearchParams = CommonType.RecordNullable<
      Pick<Phone, 'phoneNumber' | 'countryCode' | 'supplier' | 'status'> & Api.Common.CommonSearchParams
    >;

    type PhoneImportParams = CommonType.RecordNullable<{
      supplier: string;
      countryCode: string;
      status: string;
      note: string;
      numbers: string;
    }>;

    type PhoneStatusParams = {
      phoneIds: CommonType.IdType[];
      status: string;
    };

    type PhoneImportResult = {
      totalCount: number;
      importedCount: number;
      skippedCount: number;
      skippedNumbers: string[];
    };

    type PhoneList = Api.Common.PaginatingQueryRecord<Phone>;

    type Relation = Common.CommonTenantRecord<{
      relationId: CommonType.IdType;
      phoneId: CommonType.IdType;
      platformId: CommonType.IdType;
      accountId: CommonType.IdType;
      status: RelationStatus | string;
      lastError?: string;
      lastOperateTime: string;
      phoneNumber: string;
      platformName: string;
      email: string;
    }>;

    type RelationSearchParams = CommonType.RecordNullable<
      Pick<Relation, 'phoneId' | 'platformId' | 'accountId' | 'status'> & Api.Common.CommonSearchParams
    >;

    type RelationList = Api.Common.PaginatingQueryRecord<Relation>;

    type Account = Common.CommonTenantRecord<{
      accountId: CommonType.IdType;
      platformId: CommonType.IdType;
      platformCode?: string;
      phoneId: CommonType.IdType | null;
      email: string;
      accountInfo?: string;
      reqData?: string;
      loginReqData?: string;
      accountStatus: string;
      loginStatus: string;
      lastLoginTime: string;
      lastError?: string;
      latestVerifyCode?: string;
      latestActivationUrl?: string;
      latestMailSubject?: string;
      latestMailReceivedAt?: string;
      latestMailMessageId?: string;
      platformName: string;
      phoneNumber: string;
      mailboxId?: CommonType.IdType;
      mailboxBindingStatus?: 'bound' | 'shared' | 'available' | 'internal_domain' | 'external' | string;
      mailboxBoundAccountId?: CommonType.IdType;
      lotteryLinkOccupied?: boolean;
      lotteryLinkOccupiedTaskId?: CommonType.IdType;
      lotteryLinkOccupiedTaskName?: string;
      lotteryLinkOccupiedAt?: string;
      lotteryLinkOccupiedStatus?: string;
    }>;

    type AccountSearchParams = CommonType.RecordNullable<{
      accountId: number;
      platformId: CommonType.IdType;
      phoneId: CommonType.IdType;
      email: string;
      accountStatus: string;
      loginStatus: string;
      purchaseType: 'flash_sale' | 'lottery' | string;
      lotteryEventUrl: string;
    } & Api.Common.CommonSearchParams>;

    type AccountOperateParams = CommonType.RecordNullable<{
      accountId: CommonType.IdType;
      platformId: CommonType.IdType;
      phoneId: CommonType.IdType | null;
      email: string;
      accountInfo: string;
      reqData: string;
      loginReqData: string;
      accountStatus: string;
      loginStatus: string;
      lastError: string;
    }>;

    type AccountLastNameUpdateParams = {
      lastName: string;
    };

    type AccountBatchRegisterParams = {
      platformId: CommonType.IdType;
      count: number;
    };

    type AccountBatchLoginParams = {
      platformId: CommonType.IdType;
      accountIds?: CommonType.IdType[];
      loginMode?: 'auto_mailbox' | 'manual_email_code';
    };

    type LoginEmailCodeParams = {
      verifyCode: string;
    };

    type HandsFormQuickCreateParams = {
      count: number;
    };

    type HandsFormQuickCreateResult = {
      requestedCount: number;
      successCount: number;
      failedCount: number;
      createdAccountIds: CommonType.IdType[];
      createdEmails: string[];
      failedMessages: string[];
    };

    type AccountBindablePhoneSearchParams = CommonType.RecordNullable<{
      platformId: CommonType.IdType;
      phoneNumber: string;
      countryCode: string;
      supplier: string;
    } & Api.Common.CommonSearchParams>;

    type AccountList = Api.Common.PaginatingQueryRecord<Account>;

    type SelectableAccountIds = {
      accountIds: CommonType.IdType[];
      totalCount: number;
      availableCount: number;
      occupiedCount: number;
    };

    type RegistrationBatch = Common.CommonTenantRecord<{
      batchId: CommonType.IdType;
      platformId: CommonType.IdType;
      batchNo: string;
      batchStatus: BatchStatus | string;
      totalCount: number;
      successCount: number;
      failedCount: number;
      skippedCount: number;
      resultSummary?: string;
      executedAt?: string;
      platformName?: string;
    }>;

    type RegistrationBatchDetail = Common.CommonTenantRecord<{
      detailId: CommonType.IdType;
      batchId: CommonType.IdType;
      phoneId?: CommonType.IdType;
      platformId: CommonType.IdType;
      executeStatus: string;
      resultMessage?: string;
      accountId?: CommonType.IdType;
      email?: string;
      executedAt?: string;
      phoneNumber?: string;
      platformName?: string;
    }>;

    type RegistrationBatchSearchParams = CommonType.RecordNullable<{
      platformId: CommonType.IdType;
      batchNo: string;
      batchStatus: string;
    } & Api.Common.CommonSearchParams>;

    type RegistrationBatchList = Api.Common.PaginatingQueryRecord<RegistrationBatch>;

    type LoginBatch = Common.CommonTenantRecord<{
      batchId: CommonType.IdType;
      platformId: CommonType.IdType;
      batchNo: string;
      batchStatus: BatchStatus | string;
      totalCount: number;
      successCount: number;
      failedCount: number;
      resultSummary?: string;
      executedAt?: string;
      platformName?: string;
    }>;

    type LoginBatchDetail = Common.CommonTenantRecord<{
      detailId: CommonType.IdType;
      batchId: CommonType.IdType;
      accountId?: CommonType.IdType;
      platformId: CommonType.IdType;
      executeStatus: string;
      resultMessage?: string;
      reqData?: string;
      executedAt?: string;
      loginMode?: 'auto_mailbox' | 'manual_email_code' | string;
      verifyCodeRequestId?: string;
      verifyCodeExpiresAt?: string;
      verifyCodeAttemptCount?: number;
      email?: string;
      platformName?: string;
    }>;

    type LoginBatchSearchParams = CommonType.RecordNullable<{
      platformId: CommonType.IdType;
      batchNo: string;
      batchStatus: string;
    } & Api.Common.CommonSearchParams>;

    type LoginBatchList = Api.Common.PaginatingQueryRecord<LoginBatch>;

    type MailboxAccount = Common.CommonTenantRecord<{
      mailboxId: CommonType.IdType;
      email: string;
      username: string;
      password: string;
      domain: string;
      provider: string;
      stalwartPrincipalId?: string;
      status: string;
      usedAccountId?: CommonType.IdType;
      usedTime?: string;
      lastError?: string;
      latestMailSubject?: string;
      latestMailFrom?: string;
      latestMailReceivedAt?: string;
      latestMailMessageId?: string;
      latestMailExcerpt?: string;
      latestVerifyCode?: string;
        latestActivationUrl?: string;
        lastMailSyncTime?: string;
        lastMailSyncError?: string;
        usedAccountEmail?: string;
        matchedMailRecordId?: CommonType.IdType;
        matchedMailSubject?: string;
        matchedMailFrom?: string;
        matchedMailReceivedAt?: string;
        matchedMailExcerpt?: string;
      }>;

      type MailboxAccountSearchParams = CommonType.RecordNullable<{
        email: string;
        status: string;
        mailKeyword: string;
      } & Api.Common.CommonSearchParams>;

    type MailRecord = Common.CommonTenantRecord<{
      recordId: CommonType.IdType;
      mailboxId: CommonType.IdType;
      accountId?: CommonType.IdType;
      email: string;
      username?: string;
      folderName?: string;
      messageId?: string;
      subject?: string;
      fromAddress?: string;
      receivedAt?: string;
      bodyExcerpt?: string;
      bodyContent?: string;
      parseType?: string;
      verifyCode?: string;
      activationUrl?: string;
      lotteryApplicationNo?: string;
      lotteryResultStatus?: string;
      parsed?: boolean;
      readSource?: string;
      syncTime?: string;
    }>;

    type MailRecordSearchParams = CommonType.RecordNullable<Api.Common.CommonSearchParams>;

    type MailRecordList = Api.Common.PaginatingQueryRecord<MailRecord>;

    type MailFeedMode = 'latest' | 'timeline';

    type MailFeedSearchParams = CommonType.RecordNullable<{
      mode: MailFeedMode;
      platformId: CommonType.IdType;
      email: string;
      keyword: string;
      parseType: string;
      beginReceivedAt: string;
      endReceivedAt: string;
    } & Api.Common.CommonSearchParams>;

    type MailFeedList = Api.Common.PaginatingQueryRecord<MailRecord>;

    type JumpShopProfile = Common.CommonTenantRecord<{
      profileId: CommonType.IdType;
      profileName: string;
      enabled: boolean;
      lastName?: string;
      firstName?: string;
      phone?: string;
      postalCode?: string;
      province?: string;
      city?: string;
      address1?: string;
      address2?: string;
      countryCode?: string;
      billingSameAsShipping?: boolean;
      billingLastName?: string;
      billingFirstName?: string;
      billingPhone?: string;
      billingPostalCode?: string;
      billingProvince?: string;
      billingCity?: string;
      billingAddress1?: string;
      billingAddress2?: string;
      billingCountryCode?: string;
      cardHolderName?: string;
      cardNumberMasked?: string;
      cardNumberConfigured?: boolean;
      expMonth?: string;
      expYear?: string;
      cvvConfigured?: boolean;
      issueMonth?: string;
      issueYear?: string;
      issueNumberMasked?: string;
      remark?: string;
    }>;

    type JumpShopProfileSearchParams = CommonType.RecordNullable<{
      profileName: string;
      enabled: boolean;
    } & Api.Common.CommonSearchParams>;

    type JumpShopProfileOperateParams = CommonType.RecordNullable<{
      profileId: CommonType.IdType;
      profileName: string;
      enabled: boolean;
      lastName: string;
      firstName: string;
      phone: string;
      postalCode: string;
      province: string;
      city: string;
      address1: string;
      address2: string;
      countryCode: string;
      billingSameAsShipping: boolean;
      billingLastName: string;
      billingFirstName: string;
      billingPhone: string;
      billingPostalCode: string;
      billingProvince: string;
      billingCity: string;
      billingAddress1: string;
      billingAddress2: string;
      billingCountryCode: string;
      cardHolderName: string;
      cardNumber: string;
      expMonth: string;
      expYear: string;
      cvv: string;
      issueMonth: string;
      issueYear: string;
      issueNumber: string;
      remark: string;
    }>;

    type JumpShopProfileList = Api.Common.PaginatingQueryRecord<JumpShopProfile>;

    type JumpShopProductInfo = {
      productUrl: string;
      title: string;
      imageUrl?: string;
      variantId: CommonType.IdType;
      productId: CommonType.IdType;
      sectionId: string;
      available: boolean;
      maxQuantity: number;
      currency?: string;
      purchaseMode?: string;
      paymentMode?: string;
    };

    type MailForwardRecord = Common.CommonTenantRecord<{
      forwardId: CommonType.IdType;
      recordId: CommonType.IdType;
      mailboxId?: CommonType.IdType;
      sourceEmail?: string;
      sourceMessageId?: string;
      mailSubject?: string;
      targetEmail: string;
      senderFrom?: string;
      forwardMessageId?: string;
      sendStatus?: 'success' | 'failed' | string;
      errorMessage?: string;
      forwardSubject?: string;
      forwardContent?: string;
      operatorName?: string;
      sentAt?: string;
    }>;

    type MailForwardSearchParams = CommonType.RecordNullable<{
      sourceEmail: string;
      targetEmail: string;
      sendStatus: 'success' | 'failed' | string;
      keyword: string;
    } & Api.Common.CommonSearchParams>;

    type MailForwardSendParams = {
      recordId: CommonType.IdType;
      targetEmail: string;
    };

    type MailForwardList = Api.Common.PaginatingQueryRecord<MailForwardRecord>;

    type MailboxBatchCreateParams = {
      count: number;
    };

    type MailboxBatchCreateResult = {
      requestedCount: number;
      successCount: number;
      failedCount: number;
      attemptCount: number;
      createdEmails: string[];
      failedMessages: string[];
    };

    type MailboxStatusParams = {
      mailboxIds: CommonType.IdType[];
      status: string;
    };

    type MailboxMailSyncParams = {
      mailboxIds: CommonType.IdType[];
    };

    type MailRecordReparseResult = {
      started: boolean;
      running: boolean;
      message: string;
      totalScanned: number;
      updatedCount: number;
      unknownToAppliedCount: number;
      unknownToSelectedCount: number;
      unknownToRejectedCount: number;
      unknownToPurchaseCompletedCount: number;
      failedCount: number;
      startedAt?: string;
      finishedAt?: string;
    };

    type MailboxAccountList = Api.Common.PaginatingQueryRecord<MailboxAccount>;

    type Event = Common.CommonTenantRecord<{
      eventId: CommonType.IdType;
      platformId: CommonType.IdType;
      eventCode: string;
      eventName: string;
      saleTime: string;
      eventStatus: string;
      inventoryPolicy: string;
      remark?: string;
      platformName: string;
    }>;

    type EventSearchParams = CommonType.RecordNullable<
      Pick<Event, 'platformId' | 'eventCode' | 'eventName' | 'eventStatus'> & Api.Common.CommonSearchParams
    >;

    type EventOperateParams = CommonType.RecordNullable<{
      eventId: CommonType.IdType;
      platformId: CommonType.IdType;
      eventCode: string;
      eventName: string;
      saleTime: string;
      eventStatus: string;
      inventoryPolicy: string;
      remark: string;
    }>;

    type EventList = Api.Common.PaginatingQueryRecord<Event>;

    type SaleTaskSchedule = {
      scheduleId?: CommonType.IdType;
      taskId?: CommonType.IdType;
      scheduledTime: string | null;
      sessionId?: string;
      sessionLabel?: string;
      accountCount: number;
      scheduleStatus?: string;
      dispatchedTime?: string;
      finishedTime?: string;
      resultMessage?: string;
    };

    type LotteryEventSession = {
      sessionId: string;
      sessionLabel: string;
      eventUrl?: string;
      receptionId?: string;
      ticketId?: string;
      ticketField?: string;
      receptionTitle?: string;
      salesType?: string;
      notes?: string;
      maxPurchaseQuantity?: number;
    };

    type LotteryEventInfo = {
      recordId?: CommonType.IdType;
      platformId?: CommonType.IdType;
      eventUrl: string;
      ticketEntryUrl?: string;
      eventTitle: string;
      entryStartTime: string;
      entryEndTime: string;
      sessions: LotteryEventSession[];
      rawSummary?: string;
      cacheHit?: boolean;
      parsedAt?: string;
      parseStatus?: 'queued' | 'running' | 'completed' | 'failed' | string;
      parseRequestId?: string;
      parseMessage?: string;
    };

    type LotteryEventParseParams = {
      platformId: CommonType.IdType;
      eventUrl: string;
    };

    type SaleTask = Common.CommonTenantRecord<{
      taskId: CommonType.IdType;
      platformId: CommonType.IdType;
      taskName: string;
      taskStatus: string;
      purchaseType: 'flash_sale' | 'lottery' | string;
      configSchemaKey?: string;
      scheduleVersion?: number;
      warmupTime: string | null;
      scheduledTime: string | null;
      lastExecutedTime: string;
      purchaseQuantity: number;
      eventTitle?: string;
      taskOptions?: string;
      lotteryEventUrl?: string;
      selectedSessions?: LotteryEventSession[];
      remark?: string;
      platformName: string;
      accountIds?: CommonType.IdType[];
      boundAccountCount?: number;
      accountEmails?: string;
      lotteryScheduleCount?: number;
      lotterySchedules?: SaleTaskSchedule[];
      executionSummary?: Record<string, number>;
      createTime?: string;
      updateTime?: string;
    }>;

    type SaleTaskSearchParams = CommonType.RecordNullable<
      Pick<SaleTask, 'taskId' | 'platformId' | 'purchaseType' | 'taskName' | 'taskStatus'> & Api.Common.CommonSearchParams
    >;

    type SaleTaskOperateParams = CommonType.RecordNullable<{
      taskId: CommonType.IdType;
      platformId: CommonType.IdType;
      taskName: string;
      taskStatus: string;
      purchaseType: 'flash_sale' | 'lottery' | string;
      configSchemaKey?: string;
      warmupTime: string | null;
      scheduledTime: string | null;
      purchaseQuantity: number;
      taskOptions: string;
      lotteryEventUrl?: string;
      selectedSessions?: LotteryEventSession[];
      remark: string;
      accountIds: CommonType.IdType[];
      lotterySchedules?: SaleTaskSchedule[];
    }>;

    type LivePocketQuestionnaireOption = {
      value: string;
      label: string;
    };

    type LivePocketQuestionnaireQuestion = {
      name: string;
      label: string;
      type: 'text' | 'textarea' | 'radio' | 'checkbox' | 'select' | string;
      required: boolean;
      options: LivePocketQuestionnaireOption[];
    };

    type LivePocketQuestionnaireConfig = {
      enabled: boolean;
      mode: string;
      previewAccountId?: CommonType.IdType;
      previewSessionId?: string;
      previewAt?: string;
      schemaSignature?: string;
      questions: LivePocketQuestionnaireQuestion[];
      answers: Record<string, string | string[]>;
    };

    type LivePocketQuestionnairePreviewParams = {
      platformId: CommonType.IdType;
      accountId: CommonType.IdType;
      lotteryEventUrl: string;
      selectedSessionId: string;
      taskOptions: string;
    };

    type LivePocketQuestionnairePreviewResult = {
      questionnaireConfig?: LivePocketQuestionnaireConfig;
      confirmUrl?: string;
      requestUrl?: string;
      resultUrl?: string;
      eventId?: string;
      reserveId?: string;
      sessionId?: string;
      sessionLabel?: string;
      authSource?: string;
      loginReqData?: string;
      questionCount?: number;
      rawResult?: string;
    };

    type SaleTaskList = Api.Common.PaginatingQueryRecord<SaleTask>;

    type OrderExecution = Common.CommonTenantRecord<{
      executionId: CommonType.IdType;
      taskId: CommonType.IdType;
      lotteryScheduleId?: CommonType.IdType;
      platformId: CommonType.IdType;
      accountId: CommonType.IdType;
      purchaseType: 'flash_sale' | 'lottery' | string;
      purchaseQuantity: number;
      configSnapshot?: string;
      currentStep: string;
      stepStatus: string;
      stepTrace?: string;
      hasStepTrace?: boolean;
      paymentStatus: string;
      orderNo: string;
      executionStatus: string;
      resultMessage: string;
      rawResult?: string;
      hasRawResult?: boolean;
      lotteryResultStatus?: string;
      lotteryResultMailRecordId?: CommonType.IdType;
      lotteryResultAt?: string;
      workerId?: string;
      attemptCount?: number;
      heartbeatAt?: string;
      startedAt?: string;
      executedAt: string;
      platformName: string;
      email: string;
      accountInfo?: string;
      reqData?: string;
      loginReqData?: string;
      taskName: string;
      eventUrl?: string;
      ticketEntryUrl?: string;
      eventTitle?: string;
      lotterySessionLabel?: string;
      lotteryScheduledTime?: string;
    }>;

    type SaleTaskProcessStep = {
      stepKey: string;
      title: string;
      description?: string;
      status: 'finish' | 'process' | 'wait' | 'error' | string;
      time?: string;
    };

    type SaleTaskProcess = {
      task: SaleTask;
      createSteps: SaleTaskProcessStep[];
      schedules: SaleTaskSchedule[];
      executionSummary: Record<string, number>;
      executions?: OrderExecution[];
    };

    type LotteryBatchTaskItemSchedule = {
      scheduleId?: CommonType.IdType;
      batchItemId?: CommonType.IdType;
      sessionId?: string;
      sessionLabel?: string;
      scheduledTime: string | null;
      accountCount: number;
      scheduleStatus?: string;
      dispatchedTime?: string;
      finishedTime?: string;
      resultMessage?: string;
    };

    type LotteryBatchTaskItem = {
      batchItemId?: CommonType.IdType;
      batchTaskId?: CommonType.IdType;
      eventUrl: string;
      eventTitle: string;
      receptionId?: string;
      receptionTitle?: string;
      salesType?: string;
      itemStatus?: string;
      selectedSessions: LotteryEventSession[];
      schedules: LotteryBatchTaskItemSchedule[];
    };

    type LotteryBatchTask = Common.CommonTenantRecord<{
      batchTaskId: CommonType.IdType;
      platformId: CommonType.IdType;
      taskName: string;
      taskStatus: string;
      sourceUrl: string;
      taskOptions?: string;
      scheduleVersion?: number;
      remark?: string;
      platformName?: string;
      accountIds?: CommonType.IdType[];
      boundAccountCount?: number;
      items?: LotteryBatchTaskItem[];
      executionSummary?: Record<string, number>;
    }>;

    type LotteryBatchTaskSearchParams = CommonType.RecordNullable<{
      batchTaskId: CommonType.IdType;
      platformId: CommonType.IdType;
      taskName: string;
      taskStatus: string;
    } & Api.Common.CommonSearchParams>;

    type LotteryBatchTaskOperateParams = CommonType.RecordNullable<{
      batchTaskId: CommonType.IdType;
      platformId: CommonType.IdType;
      taskName: string;
      taskStatus: string;
      sourceUrl: string;
      taskOptions?: string;
      remark: string;
      accountIds: CommonType.IdType[];
      items: LotteryBatchTaskItem[];
    }>;

    type LotteryBatchTaskList = Api.Common.PaginatingQueryRecord<LotteryBatchTask>;

    type LotteryBatchTaskProcess = {
      task: LotteryBatchTask;
      createSteps: SaleTaskProcessStep[];
      executionSummary: Record<string, number>;
      items: LotteryBatchTaskItem[];
    };

    type SaleTaskProcessExecutionSearchParams = CommonType.RecordNullable<
      Pick<Api.Common.CommonSearchParams, 'pageNum' | 'pageSize'> & {
        status: string;
        scheduleId: CommonType.IdType;
      }
    >;

    type OrderExecutionSearchParams = CommonType.RecordNullable<
      Pick<
        OrderExecution,
        | 'taskId'
        | 'platformId'
        | 'accountId'
        | 'email'
        | 'purchaseType'
        | 'orderNo'
        | 'executionStatus'
        | 'paymentStatus'
        | 'lotteryResultStatus'
      > &
        Api.Common.CommonSearchParams & {
          excludeCancelled: boolean;
        }
    >;

    type OrderExecutionList = Api.Common.PaginatingQueryRecord<OrderExecution>;

    type OrderExecutionPaymentParams = {
      resultMessage: string;
    };

    type DashboardOverview = {
      platformTotal: number;
      enabledPlatformCount: number;
      taskTotal: number;
      runningTaskCount: number;
      abnormalTaskCount: number;
      executionTotal: number;
      runningExecutionCount: number;
      successExecutionCount: number;
      abnormalExecutionCount: number;
      accountTotal: number;
      loggedInAccountCount: number;
      activatedAccountCount: number;
      accountErrorCount: number;
      mailboxTotal: number;
      mailboxErrorCount: number;
      unusedMailboxCount: number;
      recentTasks: SaleTask[];
      recentExecutions: OrderExecution[];
      recentRegistrationBatches: RegistrationBatch[];
      recentLoginBatches: LoginBatch[];
    };

    type PurchaseTemplate = {
      purchaseType: 'flash_sale' | 'lottery' | string;
      configSchemaKey: string;
      configTemplate: Record<string, any>;
      editableFields: string[];
    };

    type AuditLog = Common.CommonTenantRecord<{
      auditId: CommonType.IdType;
      moduleName: string;
      actionType: string;
      businessType: string;
      businessKey: string;
      auditStatus: string;
      message: string;
      payload: string;
      eventTime: string;
    }>;

    type AuditLogSearchParams = CommonType.RecordNullable<
      Pick<AuditLog, 'moduleName' | 'actionType' | 'businessType' | 'auditStatus'> & Api.Common.CommonSearchParams
    >;

    type AuditLogList = Api.Common.PaginatingQueryRecord<AuditLog>;

  }
}
