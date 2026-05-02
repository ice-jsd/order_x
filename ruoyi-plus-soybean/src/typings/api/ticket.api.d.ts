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

    type Platform = Common.CommonTenantRecord<{
      platformId: CommonType.IdType;
      platformCode: string;
      platformName: string;
      enabled: boolean;
      orderSubmitUrl: string;
    }>;

    type PlatformSearchParams = CommonType.RecordNullable<
      Pick<Platform, 'platformCode' | 'platformName' | 'enabled'> & Api.Common.CommonSearchParams
    >;

    type PlatformOperateParams = CommonType.RecordNullable<{
      platformId: CommonType.IdType;
      platformCode: string;
      platformName: string;
      enabled: boolean;
      orderSubmitUrl: string;
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
      phoneId: CommonType.IdType;
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
    }>;

    type AccountSearchParams = CommonType.RecordNullable<{
      accountId: number;
      platformId: CommonType.IdType;
      phoneId: CommonType.IdType;
      email: string;
      accountStatus: string;
      loginStatus: string;
    } & Api.Common.CommonSearchParams>;

    type AccountOperateParams = CommonType.RecordNullable<{
      accountId: CommonType.IdType;
      platformId: CommonType.IdType;
      phoneId: CommonType.IdType;
      email: string;
      accountInfo: string;
      reqData: string;
      loginReqData: string;
      accountStatus: string;
      loginStatus: string;
      lastError: string;
    }>;

    type AccountBindablePhoneSearchParams = CommonType.RecordNullable<{
      platformId: CommonType.IdType;
      phoneNumber: string;
      countryCode: string;
      supplier: string;
    } & Api.Common.CommonSearchParams>;

    type AccountList = Api.Common.PaginatingQueryRecord<Account>;

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
    }>;

    type MailboxAccountSearchParams = CommonType.RecordNullable<{
      email: string;
      status: string;
    } & Api.Common.CommonSearchParams>;

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

    type SaleTask = Common.CommonTenantRecord<{
      taskId: CommonType.IdType;
      platformId: CommonType.IdType;
      taskName: string;
      taskStatus: string;
      purchaseType: 'flash_sale' | 'lottery' | string;
      configSchemaKey?: string;
      warmupTime: string;
      scheduledTime: string;
      lastExecutedTime: string;
      purchaseQuantity: number;
      taskOptions?: string;
      remark?: string;
      platformName: string;
      accountIds?: CommonType.IdType[];
      boundAccountCount?: number;
      accountEmails?: string;
    }>;

    type SaleTaskSearchParams = CommonType.RecordNullable<
      Pick<SaleTask, 'platformId' | 'purchaseType' | 'taskName' | 'taskStatus'> & Api.Common.CommonSearchParams
    >;

    type SaleTaskOperateParams = CommonType.RecordNullable<{
      taskId: CommonType.IdType;
      platformId: CommonType.IdType;
      taskName: string;
      taskStatus: string;
      purchaseType: 'flash_sale' | 'lottery' | string;
      configSchemaKey?: string;
      warmupTime: string;
      scheduledTime: string;
      purchaseQuantity: number;
      taskOptions: string;
      remark: string;
      accountIds: CommonType.IdType[];
    }>;

    type SaleTaskList = Api.Common.PaginatingQueryRecord<SaleTask>;

    type OrderExecution = Common.CommonTenantRecord<{
      executionId: CommonType.IdType;
      taskId: CommonType.IdType;
      platformId: CommonType.IdType;
      accountId: CommonType.IdType;
      purchaseType: 'flash_sale' | 'lottery' | string;
      purchaseQuantity: number;
      configSnapshot?: string;
      currentStep: string;
      stepStatus: string;
      stepTrace?: string;
      paymentStatus: string;
      orderNo: string;
      executionStatus: string;
      resultMessage: string;
      rawResult?: string;
      executedAt: string;
      platformName: string;
      email: string;
      accountInfo?: string;
      reqData?: string;
      loginReqData?: string;
      taskName: string;
    }>;

    type OrderExecutionSearchParams = CommonType.RecordNullable<
      Pick<OrderExecution, 'taskId' | 'platformId' | 'accountId' | 'purchaseType' | 'orderNo' | 'executionStatus' | 'paymentStatus'> &
        Api.Common.CommonSearchParams
    >;

    type OrderExecutionList = Api.Common.PaginatingQueryRecord<OrderExecution>;

    type OrderExecutionPaymentParams = {
      resultMessage: string;
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
