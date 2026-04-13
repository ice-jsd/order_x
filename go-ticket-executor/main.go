package main

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/redis/go-redis/v9"

	_ "github.com/go-sql-driver/mysql"
)

type config struct {
	// 这里只保留执行器真正需要的最小配置，避免把 Java 侧业务配置再复制一份过来。
	Port                  string `json:"port"`
	DSN                   string `json:"dsn"`
	Workers               int    `json:"workers"`
	RequestTimeoutSeconds int    `json:"requestTimeoutSeconds"`

	RedisAddr                 string `json:"redisAddr"`
	RedisPassword             string `json:"redisPassword"`
	RedisDB                   int    `json:"redisDb"`
	StreamKey                 string `json:"streamKey"`
	DelayedZSetKey            string `json:"delayedZsetKey"`
	JobKeyPrefix              string `json:"jobKeyPrefix"`
	HeartbeatKeyPrefix        string `json:"heartbeatKeyPrefix"`
	AccountLockKeyPrefix      string `json:"accountLockKeyPrefix"`
	ConsumerGroup             string `json:"consumerGroup"`
	ConsumerNamePrefix        string `json:"consumerNamePrefix"`
	AccountLockTTLSeconds     int    `json:"accountLockTtlSeconds"`
	HeartbeatIntervalSeconds  int    `json:"heartbeatIntervalSeconds"`
	PendingReclaimIdleSeconds int    `json:"pendingReclaimIdleSeconds"`
	DelayedPollIntervalMs     int    `json:"delayedPollIntervalMs"`
	RetryDelaySeconds         int    `json:"retryDelaySeconds"`
}

type flexibleTime struct {
	time.Time
}

type flexibleInt64 int64

func (f *flexibleInt64) UnmarshalJSON(data []byte) error {
	value, err := parseFlexibleInt64(data)
	if err != nil {
		return err
	}
	*f = flexibleInt64(value)
	return nil
}

func (f flexibleInt64) Value() int64 {
	return int64(f)
}

type flexibleInt int

func (f *flexibleInt) UnmarshalJSON(data []byte) error {
	value, err := parseFlexibleInt64(data)
	if err != nil {
		return err
	}
	*f = flexibleInt(value)
	return nil
}

func (f flexibleInt) Value() int {
	return int(f)
}

func parseFlexibleInt64(data []byte) (int64, error) {
	text := strings.TrimSpace(string(data))
	if text == "" || text == "null" || text == `""` {
		return 0, nil
	}
	if strings.HasPrefix(text, "\"") {
		text = strings.Trim(text, "\"")
		if strings.TrimSpace(text) == "" {
			return 0, nil
		}
	}
	return strconv.ParseInt(text, 10, 64)
}

func (f *flexibleTime) UnmarshalJSON(data []byte) error {
	text := strings.TrimSpace(string(data))
	if text == "" || text == "null" || text == `""` {
		return nil
	}
	if strings.HasPrefix(text, "\"") {
		text = strings.Trim(text, "\"")
		for _, layout := range []string{
			time.RFC3339Nano,
			time.RFC3339,
			"2006-01-02 15:04:05",
			"2006-01-02T15:04:05",
		} {
			if parsed, err := time.ParseInLocation(layout, text, time.Local); err == nil {
				f.Time = parsed
				return nil
			}
		}
		return fmt.Errorf("unsupported time string: %s", text)
	}
	raw, err := strconv.ParseInt(text, 10, 64)
	if err != nil {
		return err
	}
	if len(text) <= 10 {
		raw *= 1000
	}
	f.Time = time.UnixMilli(raw)
	return nil
}

func (f *flexibleTime) Ptr() *time.Time {
	if f == nil || f.IsZero() {
		return nil
	}
	value := f.Time
	return &value
}

type dispatchRequest struct {
	// 这个结构就是 Java 分发给 Go 的任务载荷。
	// Go 不负责创建任务，只负责接单、执行、回写结果。
	ExecutionID      flexibleInt64 `json:"executionId"`
	TaskID           flexibleInt64 `json:"taskId"`
	PlatformID       flexibleInt64 `json:"platformId"`
	PlatformCode     string        `json:"platformCode"`
	PlatformName     string        `json:"platformName"`
	AdapterType      string        `json:"adapterType"`
	OrderSubmitURL   string        `json:"orderSubmitUrl"`
	AccountID        flexibleInt64 `json:"accountId"`
	Email            string        `json:"email"`
	AccountInfo      string        `json:"accountInfo"`
	ReqData          string        `json:"reqData"`
	ProductID        string        `json:"productId"`
	PurchaseQuantity flexibleInt   `json:"purchaseQuantity"`
	ScheduleVersion  flexibleInt64 `json:"scheduleVersion"`
	OrderFlowType    string        `json:"orderFlowType"`
	FulfillmentType  string        `json:"fulfillmentType"`
	PaymentMode      string        `json:"paymentMode"`
	TaskOptions      string        `json:"taskOptions"`
	FlowSteps        []flowStep    `json:"flowSteps"`
	ScheduledTime    *flexibleTime `json:"scheduledTime"`
	WarmupTime       *flexibleTime `json:"warmupTime"`
}

type flowStep struct {
	StepType    string         `json:"stepType"`
	StepCode    string         `json:"stepCode"`
	CurrentStep string         `json:"currentStep"`
	Label       string         `json:"label"`
	Options     map[string]any `json:"options"`
}

type stepTraceEntry struct {
	StepType    string         `json:"stepType"`
	CurrentStep string         `json:"currentStep"`
	Label       string         `json:"label"`
	Status      string         `json:"status"`
	Message     string         `json:"message"`
	StartedAt   string         `json:"startedAt"`
	FinishedAt  string         `json:"finishedAt"`
	Detail      map[string]any `json:"detail,omitempty"`
}

type flowRuntime struct {
	OrderNo       string
	ExecutionStatus string
	PaymentStatus string
	CurrentStep   string
	StepStatus    string
	ResultMessage string
	RawResult     string
	TaskOptions   map[string]any
	StepTrace     []stepTraceEntry
	LastResponse  map[string]any
}

type dispatchResponse struct {
	Accepted    bool  `json:"accepted"`
	ExecutionID int64 `json:"executionId"`
}

type platformStepResponse struct {
	Success       bool           `json:"success"`
	OrderNo       string         `json:"orderNo"`
	Message       string         `json:"message"`
	Status        string         `json:"status"`
	PaymentStatus string         `json:"paymentStatus"`
	MockData      map[string]any `json:"mockData"`
}

var (
	appConfig    config
	db           *sql.DB
	httpClient   *http.Client
	redisClient  *redis.Client
	consumerName string

	releaseLockScript = redis.NewScript(`
if redis.call("GET", KEYS[1]) == ARGV[1] then
	return redis.call("DEL", KEYS[1])
end
return 0
`)
)

func main() {
	appConfig = loadConfig()
	if appConfig.DSN == "" {
		log.Fatal("missing database dsn, please set it in config.local.json or TICKET_EXECUTOR_DSN")
	}

	var err error
	db, err = sql.Open("mysql", appConfig.DSN)
	if err != nil {
		log.Fatalf("open mysql failed: %v", err)
	}
	if err = db.Ping(); err != nil {
		log.Fatalf("ping mysql failed: %v", err)
	}

	redisClient = redis.NewClient(&redis.Options{
		Addr:     appConfig.RedisAddr,
		Password: appConfig.RedisPassword,
		DB:       appConfig.RedisDB,
	})
	if err = redisClient.Ping(context.Background()).Err(); err != nil {
		log.Fatalf("ping redis failed: %v", err)
	}

	consumerName = buildConsumerName()
	ensureConsumerGroup(context.Background())

	// 一个可复用的 httpClient 即可，避免每次请求都重新创建。
	httpClient = &http.Client{Timeout: time.Duration(appConfig.RequestTimeoutSeconds) * time.Second}

	for i := 0; i < appConfig.Workers; i++ {
		go worker()
	}
	go delayedPromoterLoop()
	go pendingReclaimerLoop()

	// 兼容入口仍然保留，便于本地调试或旧调用方手工压测。
	mux := http.NewServeMux()
	mux.HandleFunc("/internal/purchase-task/dispatch", handleDispatch)
	mux.HandleFunc("/health", handleHealth)

	server := &http.Server{
		Addr:              ":" + appConfig.Port,
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
	}

	log.Printf(
		"ticket order executor listening on :%s with %d workers, redis=%s, stream=%s, consumer=%s",
		appConfig.Port,
		appConfig.Workers,
		appConfig.RedisAddr,
		appConfig.StreamKey,
		consumerName,
	)
	log.Fatal(server.ListenAndServe())
}

func loadConfig() config {
	// 配置优先级：
	// 默认值 < 配置文件 < 环境变量
	cfg := defaultConfig()
	loadConfigFile(&cfg)
	overrideConfigFromEnv(&cfg)
	normalizeConfig(&cfg)
	return cfg
}

func defaultConfig() config {
	return config{
		Port:                      "8099",
		Workers:                   32,
		RequestTimeoutSeconds:     30,
		RedisAddr:                 "127.0.0.1:6379",
		RedisDB:                   0,
		StreamKey:                 "ticket:purchase:stream:ready",
		DelayedZSetKey:            "ticket:purchase:zset:delayed",
		JobKeyPrefix:              "ticket:purchase:job:",
		HeartbeatKeyPrefix:        "ticket:purchase:heartbeat:",
		AccountLockKeyPrefix:      "ticket:purchase:lock:account:",
		ConsumerGroup:             "ticket-order-executor",
		ConsumerNamePrefix:        "ticket-order-executor",
		AccountLockTTLSeconds:     60,
		HeartbeatIntervalSeconds:  5,
		PendingReclaimIdleSeconds: 60,
		DelayedPollIntervalMs:     200,
		RetryDelaySeconds:         3,
	}
}

func loadConfigFile(cfg *config) {
	// 本地优先读 config.local.json，方便每台机器放自己的连接串。
	// 如果没有，再退回通用的 config.json。
	for _, path := range []string{"config.local.json", "config.json"} {
		if err := mergeConfigFile(path, cfg); err == nil {
			log.Printf("loaded config from %s", path)
			return
		} else if !errors.Is(err, os.ErrNotExist) {
			log.Printf("skip invalid config file %s: %v", path, err)
		}
	}
}

func mergeConfigFile(path string, cfg *config) error {
	fileBytes, err := os.ReadFile(path)
	if err != nil {
		return err
	}

	var fileCfg config
	if err = json.Unmarshal(fileBytes, &fileCfg); err != nil {
		return fmt.Errorf("parse %s failed: %w", filepath.Base(path), err)
	}

	mergeConfig(cfg, fileCfg)
	return nil
}

func mergeConfig(target *config, source config) {
	if strings.TrimSpace(source.Port) != "" {
		target.Port = strings.TrimSpace(source.Port)
	}
	if strings.TrimSpace(source.DSN) != "" {
		target.DSN = strings.TrimSpace(source.DSN)
	}
	if source.Workers > 0 {
		target.Workers = source.Workers
	}
	if source.RequestTimeoutSeconds > 0 {
		target.RequestTimeoutSeconds = source.RequestTimeoutSeconds
	}
	if strings.TrimSpace(source.RedisAddr) != "" {
		target.RedisAddr = strings.TrimSpace(source.RedisAddr)
	}
	if source.RedisPassword != "" {
		target.RedisPassword = source.RedisPassword
	}
	if source.RedisDB >= 0 {
		target.RedisDB = source.RedisDB
	}
	if strings.TrimSpace(source.StreamKey) != "" {
		target.StreamKey = strings.TrimSpace(source.StreamKey)
	}
	if strings.TrimSpace(source.DelayedZSetKey) != "" {
		target.DelayedZSetKey = strings.TrimSpace(source.DelayedZSetKey)
	}
	if strings.TrimSpace(source.JobKeyPrefix) != "" {
		target.JobKeyPrefix = strings.TrimSpace(source.JobKeyPrefix)
	}
	if strings.TrimSpace(source.HeartbeatKeyPrefix) != "" {
		target.HeartbeatKeyPrefix = strings.TrimSpace(source.HeartbeatKeyPrefix)
	}
	if strings.TrimSpace(source.AccountLockKeyPrefix) != "" {
		target.AccountLockKeyPrefix = strings.TrimSpace(source.AccountLockKeyPrefix)
	}
	if strings.TrimSpace(source.ConsumerGroup) != "" {
		target.ConsumerGroup = strings.TrimSpace(source.ConsumerGroup)
	}
	if strings.TrimSpace(source.ConsumerNamePrefix) != "" {
		target.ConsumerNamePrefix = strings.TrimSpace(source.ConsumerNamePrefix)
	}
	if source.AccountLockTTLSeconds > 0 {
		target.AccountLockTTLSeconds = source.AccountLockTTLSeconds
	}
	if source.HeartbeatIntervalSeconds > 0 {
		target.HeartbeatIntervalSeconds = source.HeartbeatIntervalSeconds
	}
	if source.PendingReclaimIdleSeconds > 0 {
		target.PendingReclaimIdleSeconds = source.PendingReclaimIdleSeconds
	}
	if source.DelayedPollIntervalMs > 0 {
		target.DelayedPollIntervalMs = source.DelayedPollIntervalMs
	}
	if source.RetryDelaySeconds > 0 {
		target.RetryDelaySeconds = source.RetryDelaySeconds
	}
}

func overrideConfigFromEnv(cfg *config) {
	if raw := os.Getenv("TICKET_EXECUTOR_PORT"); strings.TrimSpace(raw) != "" {
		cfg.Port = strings.TrimSpace(raw)
	}
	if raw := os.Getenv("TICKET_EXECUTOR_DSN"); strings.TrimSpace(raw) != "" {
		cfg.DSN = strings.TrimSpace(raw)
	}
	if raw := os.Getenv("TICKET_EXECUTOR_WORKERS"); raw != "" {
		if parsed, err := strconv.Atoi(raw); err == nil && parsed > 0 {
			cfg.Workers = parsed
		}
	}
	if raw := os.Getenv("TICKET_EXECUTOR_REQUEST_TIMEOUT_SECONDS"); raw != "" {
		if parsed, err := strconv.Atoi(raw); err == nil && parsed > 0 {
			cfg.RequestTimeoutSeconds = parsed
		}
	}
	if raw := os.Getenv("TICKET_EXECUTOR_REDIS_ADDR"); strings.TrimSpace(raw) != "" {
		cfg.RedisAddr = strings.TrimSpace(raw)
	}
	if raw := os.Getenv("TICKET_EXECUTOR_REDIS_PASSWORD"); raw != "" {
		cfg.RedisPassword = raw
	}
	if raw := os.Getenv("TICKET_EXECUTOR_REDIS_DB"); raw != "" {
		if parsed, err := strconv.Atoi(raw); err == nil && parsed >= 0 {
			cfg.RedisDB = parsed
		}
	}
	if raw := os.Getenv("TICKET_EXECUTOR_STREAM_KEY"); strings.TrimSpace(raw) != "" {
		cfg.StreamKey = strings.TrimSpace(raw)
	}
	if raw := os.Getenv("TICKET_EXECUTOR_DELAYED_ZSET_KEY"); strings.TrimSpace(raw) != "" {
		cfg.DelayedZSetKey = strings.TrimSpace(raw)
	}
	if raw := os.Getenv("TICKET_EXECUTOR_JOB_KEY_PREFIX"); strings.TrimSpace(raw) != "" {
		cfg.JobKeyPrefix = strings.TrimSpace(raw)
	}
	if raw := os.Getenv("TICKET_EXECUTOR_HEARTBEAT_KEY_PREFIX"); strings.TrimSpace(raw) != "" {
		cfg.HeartbeatKeyPrefix = strings.TrimSpace(raw)
	}
	if raw := os.Getenv("TICKET_EXECUTOR_ACCOUNT_LOCK_KEY_PREFIX"); strings.TrimSpace(raw) != "" {
		cfg.AccountLockKeyPrefix = strings.TrimSpace(raw)
	}
	if raw := os.Getenv("TICKET_EXECUTOR_CONSUMER_GROUP"); strings.TrimSpace(raw) != "" {
		cfg.ConsumerGroup = strings.TrimSpace(raw)
	}
	if raw := os.Getenv("TICKET_EXECUTOR_CONSUMER_NAME_PREFIX"); strings.TrimSpace(raw) != "" {
		cfg.ConsumerNamePrefix = strings.TrimSpace(raw)
	}
	if raw := os.Getenv("TICKET_EXECUTOR_ACCOUNT_LOCK_TTL_SECONDS"); raw != "" {
		if parsed, err := strconv.Atoi(raw); err == nil && parsed > 0 {
			cfg.AccountLockTTLSeconds = parsed
		}
	}
	if raw := os.Getenv("TICKET_EXECUTOR_HEARTBEAT_INTERVAL_SECONDS"); raw != "" {
		if parsed, err := strconv.Atoi(raw); err == nil && parsed > 0 {
			cfg.HeartbeatIntervalSeconds = parsed
		}
	}
	if raw := os.Getenv("TICKET_EXECUTOR_PENDING_RECLAIM_IDLE_SECONDS"); raw != "" {
		if parsed, err := strconv.Atoi(raw); err == nil && parsed > 0 {
			cfg.PendingReclaimIdleSeconds = parsed
		}
	}
	if raw := os.Getenv("TICKET_EXECUTOR_DELAYED_POLL_INTERVAL_MS"); raw != "" {
		if parsed, err := strconv.Atoi(raw); err == nil && parsed > 0 {
			cfg.DelayedPollIntervalMs = parsed
		}
	}
	if raw := os.Getenv("TICKET_EXECUTOR_RETRY_DELAY_SECONDS"); raw != "" {
		if parsed, err := strconv.Atoi(raw); err == nil && parsed > 0 {
			cfg.RetryDelaySeconds = parsed
		}
	}
}

func normalizeConfig(cfg *config) {
	if strings.TrimSpace(cfg.Port) == "" {
		cfg.Port = "8099"
	}
	if cfg.Workers <= 0 {
		cfg.Workers = 32
	}
	if cfg.RequestTimeoutSeconds <= 0 {
		cfg.RequestTimeoutSeconds = 30
	}
	if strings.TrimSpace(cfg.RedisAddr) == "" {
		cfg.RedisAddr = "127.0.0.1:6379"
	}
	if strings.TrimSpace(cfg.StreamKey) == "" {
		cfg.StreamKey = "ticket:purchase:stream:ready"
	}
	if strings.TrimSpace(cfg.DelayedZSetKey) == "" {
		cfg.DelayedZSetKey = "ticket:purchase:zset:delayed"
	}
	if strings.TrimSpace(cfg.JobKeyPrefix) == "" {
		cfg.JobKeyPrefix = "ticket:purchase:job:"
	}
	if strings.TrimSpace(cfg.HeartbeatKeyPrefix) == "" {
		cfg.HeartbeatKeyPrefix = "ticket:purchase:heartbeat:"
	}
	if strings.TrimSpace(cfg.AccountLockKeyPrefix) == "" {
		cfg.AccountLockKeyPrefix = "ticket:purchase:lock:account:"
	}
	if strings.TrimSpace(cfg.ConsumerGroup) == "" {
		cfg.ConsumerGroup = "ticket-order-executor"
	}
	if strings.TrimSpace(cfg.ConsumerNamePrefix) == "" {
		cfg.ConsumerNamePrefix = "ticket-order-executor"
	}
	if cfg.AccountLockTTLSeconds <= 0 {
		cfg.AccountLockTTLSeconds = 60
	}
	if cfg.HeartbeatIntervalSeconds <= 0 {
		cfg.HeartbeatIntervalSeconds = 5
	}
	if cfg.PendingReclaimIdleSeconds <= 0 {
		cfg.PendingReclaimIdleSeconds = 60
	}
	if cfg.DelayedPollIntervalMs <= 0 {
		cfg.DelayedPollIntervalMs = 200
	}
	if cfg.RetryDelaySeconds <= 0 {
		cfg.RetryDelaySeconds = 3
	}
}

func buildConsumerName() string {
	hostname, _ := os.Hostname()
	return fmt.Sprintf("%s-%s-%d", appConfig.ConsumerNamePrefix, hostname, os.Getpid())
}

func ensureConsumerGroup(ctx context.Context) {
	err := redisClient.XGroupCreateMkStream(ctx, appConfig.StreamKey, appConfig.ConsumerGroup, "0").Err()
	if err == nil || strings.Contains(err.Error(), "BUSYGROUP") {
		return
	}
	log.Fatalf("create consumer group failed: %v", err)
}

func handleHealth(w http.ResponseWriter, _ *http.Request) {
	ctx := context.Background()
	streamLen, _ := redisClient.XLen(ctx, appConfig.StreamKey).Result()
	delayedCount, _ := redisClient.ZCard(ctx, appConfig.DelayedZSetKey).Result()
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":            true,
		"workers":       appConfig.Workers,
		"stream":        appConfig.StreamKey,
		"streamLength":  streamLen,
		"delayedCount":  delayedCount,
		"consumerGroup": appConfig.ConsumerGroup,
		"consumerName":  consumerName,
	})
}

func handleDispatch(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"message": "method not allowed"})
		return
	}

	var req dispatchRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"message": "invalid json"})
		return
	}

	if req.ExecutionID.Value() == 0 || req.AccountID.Value() == 0 || strings.TrimSpace(req.ProductID) == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"message": "missing execution/account/product info"})
		return
	}

	if err := enqueueDispatch(context.Background(), req); err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]any{"message": err.Error()})
		return
	}

	writeJSON(w, http.StatusAccepted, dispatchResponse{Accepted: true, ExecutionID: req.ExecutionID.Value()})
}

func enqueueDispatch(ctx context.Context, req dispatchRequest) error {
	body, err := json.Marshal(req)
	if err != nil {
		return fmt.Errorf("serialize dispatch request failed: %w", err)
	}
	if err = redisClient.Set(ctx, jobKey(req.ExecutionID.Value()), body, 24*time.Hour).Err(); err != nil {
		return fmt.Errorf("store dispatch payload failed: %w", err)
	}
	dispatchAt := resolveDispatchTime(req)
	if dispatchAt.After(time.Now()) {
		return redisClient.ZAdd(ctx, appConfig.DelayedZSetKey, redis.Z{
			Score:  float64(dispatchAt.UnixMilli()),
			Member: strconv.FormatInt(req.ExecutionID.Value(), 10),
		}).Err()
	}
	return pushReady(ctx, req.ExecutionID.Value())
}

func resolveDispatchTime(req dispatchRequest) time.Time {
	if req.WarmupTime != nil && req.WarmupTime.Ptr() != nil {
		return *req.WarmupTime.Ptr()
	}
	if req.ScheduledTime != nil && req.ScheduledTime.Ptr() != nil {
		return *req.ScheduledTime.Ptr()
	}
	return time.Time{}
}

func pushReady(ctx context.Context, executionID int64) error {
	return redisClient.XAdd(ctx, &redis.XAddArgs{
		Stream: appConfig.StreamKey,
		Values: map[string]any{
			"executionId": strconv.FormatInt(executionID, 10),
			"enqueuedAt":  strconv.FormatInt(time.Now().UnixMilli(), 10),
		},
	}).Err()
}

func delayedPromoterLoop() {
	ticker := time.NewTicker(time.Duration(appConfig.DelayedPollIntervalMs) * time.Millisecond)
	defer ticker.Stop()
	for range ticker.C {
		promoteDelayedJobs(context.Background())
	}
}

func promoteDelayedJobs(ctx context.Context) {
	for {
		items, err := redisClient.ZPopMin(ctx, appConfig.DelayedZSetKey, 50).Result()
		if err != nil || len(items) == 0 {
			return
		}
		now := time.Now().UnixMilli()
		for _, item := range items {
			if item.Score > float64(now) {
				_ = redisClient.ZAdd(ctx, appConfig.DelayedZSetKey, item).Err()
				continue
			}
			executionID, parseErr := strconv.ParseInt(fmt.Sprint(item.Member), 10, 64)
			if parseErr != nil {
				continue
			}
			if err = pushReady(ctx, executionID); err != nil {
				_ = redisClient.ZAdd(ctx, appConfig.DelayedZSetKey, redis.Z{
					Score:  item.Score,
					Member: item.Member,
				}).Err()
				return
			}
		}
		if len(items) < 50 {
			return
		}
	}
}

func worker() {
	ctx := context.Background()
	for {
		streams, err := redisClient.XReadGroup(ctx, &redis.XReadGroupArgs{
			Group:    appConfig.ConsumerGroup,
			Consumer: consumerName,
			Streams:  []string{appConfig.StreamKey, ">"},
			Count:    1,
			Block:    5 * time.Second,
		}).Result()
		if err == redis.Nil {
			continue
		}
		if err != nil {
			log.Printf("xreadgroup failed: %v", err)
			time.Sleep(time.Second)
			continue
		}
		for _, stream := range streams {
			for _, message := range stream.Messages {
				processStreamMessage(ctx, message)
			}
		}
	}
}

func pendingReclaimerLoop() {
	ticker := time.NewTicker(15 * time.Second)
	defer ticker.Stop()
	for range ticker.C {
		reclaimPendingMessages(context.Background())
	}
}

func reclaimPendingMessages(ctx context.Context) {
	start := "0-0"
	for {
		result, nextStart, err := redisClient.XAutoClaim(ctx, &redis.XAutoClaimArgs{
			Stream:   appConfig.StreamKey,
			Group:    appConfig.ConsumerGroup,
			Consumer: consumerName,
			MinIdle:  time.Duration(appConfig.PendingReclaimIdleSeconds) * time.Second,
			Start:    start,
			Count:    20,
		}).Result()
		if err != nil {
			log.Printf("xautoclaim failed: %v", err)
			return
		}
		for _, message := range result {
			processStreamMessage(ctx, message)
		}
		if len(result) == 0 {
			return
		}
		start = nextStart
	}
}

func processStreamMessage(ctx context.Context, message redis.XMessage) {
	executionID, err := parseExecutionID(message)
	if err != nil {
		log.Printf("skip invalid stream message %s: %v", message.ID, err)
		ackMessage(ctx, message.ID)
		return
	}

	req, err := loadDispatchRequest(ctx, executionID)
	if err != nil {
		log.Printf("load dispatch payload failed for execution %d: %v", executionID, err)
		_ = updateExecutionFailure(executionID, &flowRuntime{
			ExecutionStatus: "blocked",
			CurrentStep:     "queued",
			StepStatus:      "failed",
			PaymentStatus:   "manual_pending",
			StepTrace:       []stepTraceEntry{},
		}, err.Error())
		ackMessage(ctx, message.ID)
		return
	}

	validPlan, err := validateExecutionPlan(req)
	if err != nil {
		log.Printf("validate execution plan failed for execution %d: %v", executionID, err)
		ackMessage(ctx, message.ID)
		return
	}
	if !validPlan {
		log.Printf("skip stale execution %d because queued plan is no longer current", executionID)
		cleanupExecutionCache(ctx, executionID)
		ackMessage(ctx, message.ID)
		return
	}

	lockToken := fmt.Sprintf("%s:%d:%d", consumerName, executionID, time.Now().UnixNano())
	acquired, err := acquireAccountLock(ctx, req.AccountID.Value(), lockToken)
	if err != nil {
		log.Printf("acquire account lock failed for execution %d: %v", executionID, err)
		ackMessage(ctx, message.ID)
		return
	}
	if !acquired {
		if requeueErr := requeueDelayed(ctx, executionID, time.Duration(appConfig.RetryDelaySeconds)*time.Second); requeueErr != nil {
			log.Printf("requeue delayed failed for execution %d: %v", executionID, requeueErr)
		}
		ackMessage(ctx, message.ID)
		return
	}
	defer releaseAccountLock(ctx, req.AccountID.Value(), lockToken)

	claimed, err := claimExecution(executionID, req.ScheduleVersion.Value())
	if err != nil {
		log.Printf("claim execution failed for execution %d: %v", executionID, err)
		ackMessage(ctx, message.ID)
		return
	}
	if !claimed {
		log.Printf("skip execution %d because it is no longer claimable", executionID)
		ackMessage(ctx, message.ID)
		return
	}
	log.Printf(
		"execution %d started, task=%d account=%d flow=%s payment=%s",
		executionID,
		req.TaskID.Value(),
		req.AccountID.Value(),
		req.OrderFlowType,
		req.PaymentMode,
	)

	heartbeatCtx, stopHeartbeat := context.WithCancel(context.Background())
	var heartbeatWG sync.WaitGroup
	heartbeatWG.Add(1)
	go keepHeartbeat(heartbeatCtx, &heartbeatWG, executionID)

	runtime, flowErr := executeOrderFlow(req)
	stopHeartbeat()
	heartbeatWG.Wait()

	if flowErr != nil {
		log.Printf("execution %d failed: %v", executionID, flowErr)
		if updateErr := updateExecutionFailure(executionID, runtime, flowErr.Error()); updateErr != nil {
			log.Printf("execution %d update failed status failed: %v", executionID, updateErr)
		}
		cleanupExecutionCache(ctx, executionID)
		ackMessage(ctx, message.ID)
		return
	}

	if err = updateExecutionSuccess(executionID, runtime); err != nil {
		log.Printf("execution %d update final status failed: %v", executionID, err)
	} else {
		log.Printf("execution %d finished with status=%s payment=%s orderNo=%s", executionID, runtime.ExecutionStatus, runtime.PaymentStatus, runtime.OrderNo)
	}
	cleanupExecutionCache(ctx, executionID)
	ackMessage(ctx, message.ID)
}

func parseExecutionID(message redis.XMessage) (int64, error) {
	raw, ok := message.Values["executionId"]
	if !ok {
		return 0, errors.New("executionId missing")
	}
	return strconv.ParseInt(fmt.Sprint(raw), 10, 64)
}

func loadDispatchRequest(ctx context.Context, executionID int64) (dispatchRequest, error) {
	body, err := redisClient.Get(ctx, jobKey(executionID)).Result()
	if err != nil {
		if err == redis.Nil {
			return dispatchRequest{}, errors.New("任务载荷不存在")
		}
		return dispatchRequest{}, err
	}
	var req dispatchRequest
	if err = json.Unmarshal([]byte(body), &req); err != nil {
		return dispatchRequest{}, err
	}
	return req, nil
}

func validateExecutionPlan(req dispatchRequest) (bool, error) {
	var executionStatus sql.NullString
	var executionVersion sql.NullInt64
	var taskVersion sql.NullInt64
	err := db.QueryRow(`
		SELECT e.execution_status,
		       COALESCE(e.schedule_version, 1),
		       COALESCE(t.schedule_version, 0)
		FROM ticket_order_execution e
		LEFT JOIN ticket_sale_task t
		  ON t.task_id = e.task_id
		 AND t.del_flag = 0
		WHERE e.execution_id = ?
		  AND e.del_flag = 0`,
		req.ExecutionID.Value(),
	).Scan(&executionStatus, &executionVersion, &taskVersion)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return false, nil
		}
		return false, err
	}
	if executionStatus.String != "queued" {
		return false, nil
	}
	expectedVersion := req.ScheduleVersion.Value()
	if expectedVersion <= 0 {
		expectedVersion = executionVersion.Int64
	}
	if expectedVersion <= 0 {
		expectedVersion = 1
	}
	if executionVersion.Int64 != expectedVersion {
		return false, nil
	}
	if taskVersion.Int64 != expectedVersion {
		return false, nil
	}
	return true, nil
}

func claimExecution(executionID int64, scheduleVersion int64) (bool, error) {
	if scheduleVersion <= 0 {
		scheduleVersion = 1
	}
	result, err := db.Exec(`
		UPDATE ticket_order_execution
		SET execution_status = 'running',
		    result_message = '执行中',
		    worker_id = ?,
		    attempt_count = COALESCE(attempt_count, 0) + 1,
		    started_at = NOW(),
		    heartbeat_at = NOW(),
		    update_time = NOW()
		WHERE execution_id = ?
		  AND execution_status = 'queued'
		  AND COALESCE(schedule_version, 1) = ?`,
		consumerName, executionID, scheduleVersion,
	)
	if err != nil {
		return false, err
	}
	rows, err := result.RowsAffected()
	if err != nil {
		return false, err
	}
	return rows > 0, nil
}

func keepHeartbeat(ctx context.Context, wg *sync.WaitGroup, executionID int64) {
	defer wg.Done()
	ticker := time.NewTicker(time.Duration(appConfig.HeartbeatIntervalSeconds) * time.Second)
	defer ticker.Stop()

	// 首次进入 running 就先打一次心跳，避免短任务还没轮到 ticker 就被误判。
	writeHeartbeat(context.Background(), executionID)

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			writeHeartbeat(context.Background(), executionID)
		}
	}
}

func writeHeartbeat(ctx context.Context, executionID int64) {
	ttl := time.Duration(appConfig.HeartbeatIntervalSeconds*3) * time.Second
	if err := redisClient.Set(ctx, heartbeatKey(executionID), consumerName, ttl).Err(); err != nil {
		log.Printf("write heartbeat key failed for execution %d: %v", executionID, err)
	}
	if _, err := db.Exec(`
		UPDATE ticket_order_execution
		SET heartbeat_at = NOW(), update_time = NOW()
		WHERE execution_id = ? AND execution_status = 'running'`,
		executionID,
	); err != nil {
		log.Printf("update heartbeat_at failed for execution %d: %v", executionID, err)
	}
}

func executeOrderFlow(req dispatchRequest) (*flowRuntime, error) {
	runtime := &flowRuntime{
		TaskOptions:      parseJSONObject(req.TaskOptions),
		ExecutionStatus:  "running",
		PaymentStatus:    initialPaymentStatus(req.PaymentMode),
		CurrentStep:      "queued",
		StepStatus:       "queued",
		ResultMessage:    "等待执行",
	}

	steps := req.FlowSteps
	if len(steps) == 0 {
		steps = defaultFlowSteps(req)
	}

	for _, step := range steps {
		startedAt := time.Now()
		runtime.CurrentStep = currentStepLabel(step)
		runtime.StepStatus = "running"
		runtime.ResultMessage = stepMessage(step, "执行中")
		if err := updateExecutionStep(req.ExecutionID.Value(), runtime); err != nil {
			return runtime, err
		}

		detail, err := executeFlowStep(req, runtime, step)
		entry := stepTraceEntry{
			StepType:    step.StepType,
			CurrentStep: runtime.CurrentStep,
			Label:       stepLabel(step),
			Status:      "success",
			Message:     runtime.ResultMessage,
			StartedAt:   startedAt.Format(time.RFC3339),
			FinishedAt:  time.Now().Format(time.RFC3339),
			Detail:      detail,
		}

		if err != nil {
			runtime.StepStatus = "failed"
			runtime.ExecutionStatus = failureStatus(runtime.ExecutionStatus)
			runtime.ResultMessage = err.Error()
			entry.Status = "failed"
			entry.Message = err.Error()
			runtime.StepTrace = append(runtime.StepTrace, entry)
			_ = updateExecutionStep(req.ExecutionID.Value(), runtime)
			return runtime, err
		}

		runtime.StepStatus = "success"
		runtime.StepTrace = append(runtime.StepTrace, entry)
		if err = updateExecutionStep(req.ExecutionID.Value(), runtime); err != nil {
			return runtime, err
		}
	}

	finalizeRuntime(req, runtime)
	return runtime, nil
}

func executeFlowStep(req dispatchRequest, runtime *flowRuntime, step flowStep) (map[string]any, error) {
	if shouldCallPlatformForStep(req, step) {
		resp, rawResult, rawMap, err := callPlatformStep(req, step, runtime)
		if rawResult != "" {
			runtime.RawResult = rawResult
		}
		if len(rawMap) > 0 {
			runtime.LastResponse = rawMap
		}
		if err != nil {
			return rawMap, err
		}
		if strings.TrimSpace(resp.Message) != "" {
			runtime.ResultMessage = resp.Message
		}
		if strings.TrimSpace(resp.PaymentStatus) != "" {
			runtime.PaymentStatus = resp.PaymentStatus
		}
		switch step.StepType {
		case "SUBMIT_ORDER":
			runtime.OrderNo = resp.OrderNo
			if runtime.OrderNo == "" {
				runtime.OrderNo = fmt.Sprintf("pending-%d", req.ExecutionID.Value())
			}
			runtime.ExecutionStatus = normalizeExecutionStatus(resp.Status, req.PaymentMode)
			runtime.ResultMessage = defaultString(resp.Message, "订单已提交")
		case "CREATE_ONLINE_PAYMENT":
			runtime.ExecutionStatus = normalizeExecutionStatus(resp.Status, req.PaymentMode)
			runtime.PaymentStatus = defaultString(resp.PaymentStatus, "pending_online")
			runtime.ResultMessage = defaultString(resp.Message, "支付单已创建，等待线上支付")
		case "CONFIRM_PENDING_PAYMENT":
			runtime.ExecutionStatus = normalizeExecutionStatus(resp.Status, req.PaymentMode)
			runtime.PaymentStatus = defaultString(resp.PaymentStatus, initialPaymentStatus(req.PaymentMode))
			runtime.ResultMessage = defaultString(resp.Message, "订单已提交，等待后续支付")
		}
		return mergeDetail(step.Options, rawMap), nil
	}

	switch step.StepType {
	case "ADD_TO_CART":
		runtime.ResultMessage = "已加入购物车"
		return mergeDetail(step.Options, runtime.TaskOptions), nil
	case "DIRECT_BUY":
		runtime.ResultMessage = "已准备直接下单"
		return mergeDetail(step.Options, runtime.TaskOptions), nil
	case "SELECT_PICKUP_STORE":
		runtime.ResultMessage = "已选择门店自提"
		return mergeDetail(step.Options, runtime.TaskOptions), nil
	case "SELECT_DELIVERY":
		runtime.ResultMessage = "已选择配送方式"
		return mergeDetail(step.Options, runtime.TaskOptions), nil
	case "SELECT_PAYMENT_MODE":
		runtime.ResultMessage = "已选择支付方式"
		return mergeDetail(step.Options, map[string]any{"paymentMode": req.PaymentMode}), nil
	case "SUBMIT_ORDER":
		resp, rawResult, rawMap, err := callPlatformStep(req, step, runtime)
		if rawResult != "" {
			runtime.RawResult = rawResult
		}
		runtime.LastResponse = rawMap
		if err != nil {
			return rawMap, err
		}
		runtime.OrderNo = resp.OrderNo
		if runtime.OrderNo == "" {
			runtime.OrderNo = fmt.Sprintf("pending-%d", req.ExecutionID.Value())
		}
		runtime.ExecutionStatus = normalizeExecutionStatus(resp.Status, req.PaymentMode)
		runtime.PaymentStatus = defaultString(resp.PaymentStatus, runtime.PaymentStatus)
		runtime.ResultMessage = defaultString(resp.Message, "订单已提交")
		return rawMap, nil
	case "CREATE_ONLINE_PAYMENT":
		runtime.ExecutionStatus = "pending_payment"
		runtime.PaymentStatus = "pending_online"
		runtime.ResultMessage = "支付单已创建，等待线上支付"
		return mergeDetail(step.Options, runtime.LastResponse), nil
	case "CONFIRM_PENDING_PAYMENT":
		runtime.ExecutionStatus = "pending_payment"
		runtime.PaymentStatus = initialPaymentStatus(req.PaymentMode)
		runtime.ResultMessage = "订单已提交，等待后续支付"
		return mergeDetail(step.Options, runtime.LastResponse), nil
	default:
		return step.Options, fmt.Errorf("unsupported step type: %s", step.StepType)
	}
}

func shouldCallPlatformForStep(req dispatchRequest, step flowStep) bool {
	if step.StepType == "SUBMIT_ORDER" {
		return true
	}
	if strings.EqualFold(strings.TrimSpace(req.AdapterType), "mock") {
		return true
	}
	return strings.Contains(strings.ToLower(strings.TrimSpace(req.OrderSubmitURL)), "/ticket/mock-platform/")
}

func callPlatformStep(req dispatchRequest, step flowStep, runtime *flowRuntime) (*platformStepResponse, string, map[string]any, error) {
	if strings.TrimSpace(req.OrderSubmitURL) == "" {
		return nil, "", nil, errors.New("平台未配置下单接口地址")
	}

	// 这里发给平台的是“已经准备好的执行上下文”：
	// Java 负责选账号、建任务；Go 负责把账号态和商品参数带去请求平台。
	payload := map[string]any{
		"executionId":      req.ExecutionID.Value(),
		"taskId":           req.TaskID.Value(),
		"platformId":       req.PlatformID.Value(),
		"platformCode":     req.PlatformCode,
		"platformName":     req.PlatformName,
		"adapterType":      req.AdapterType,
		"accountId":        req.AccountID.Value(),
		"email":            req.Email,
		"accountInfo":      parseJSONString(req.AccountInfo),
		"reqData":          parseJSONString(req.ReqData),
		"productId":        req.ProductID,
		"purchaseQuantity": req.PurchaseQuantity.Value(),
		"orderFlowType":    req.OrderFlowType,
		"fulfillmentType":  req.FulfillmentType,
		"paymentMode":      req.PaymentMode,
		"orderNo":          runtime.OrderNo,
		"currentStep":      runtime.CurrentStep,
		"stepStatus":       runtime.StepStatus,
		"paymentStatus":    runtime.PaymentStatus,
		"taskOptions":      runtime.TaskOptions,
		"step": map[string]any{
			"stepType":    step.StepType,
			"currentStep": currentStepLabel(step),
			"options":     step.Options,
		},
	}

	body, _ := json.Marshal(payload)
	httpReq, err := http.NewRequestWithContext(context.Background(), http.MethodPost, req.OrderSubmitURL, bytes.NewReader(body))
	if err != nil {
		return nil, "", nil, fmt.Errorf("构建下单请求失败: %w", err)
	}
	httpReq.Header.Set("Content-Type", "application/json")

	httpResp, err := httpClient.Do(httpReq)
	if err != nil {
		if errors.Is(err, context.DeadlineExceeded) || errors.Is(err, syscall.ETIMEDOUT) {
			return nil, "", nil, fmt.Errorf("调用平台下单接口超时: %w", err)
		}
		return nil, "", nil, fmt.Errorf("调用平台下单接口失败: %w", err)
	}
	defer httpResp.Body.Close()

	bodyBytes, err := io.ReadAll(httpResp.Body)
	if err != nil {
		return nil, "", nil, fmt.Errorf("读取平台响应失败: %w", err)
	}

	var raw map[string]any
	if err = json.Unmarshal(bodyBytes, &raw); err != nil {
		return nil, "", nil, fmt.Errorf("平台下单响应不是有效 JSON: %w", err)
	}
	rawBytes, _ := json.Marshal(raw)

	// 即使 HTTP 状态码异常，也尽量把原始响应保存下来，后面排查更方便。
	if httpResp.StatusCode < 200 || httpResp.StatusCode >= 300 {
		return nil, string(rawBytes), raw, fmt.Errorf("平台下单接口返回 HTTP %d", httpResp.StatusCode)
	}

	resp := &platformStepResponse{
		Success: readBool(raw["success"], true),
		OrderNo: readString(raw["orderNo"], readString(raw["order_no"], "")),
		Message: readString(raw["message"], ""),
		Status:  readString(raw["status"], ""),
		PaymentStatus: readString(raw["paymentStatus"], readString(raw["payment_status"], "")),
	}
	if mockData, ok := raw["mockData"].(map[string]any); ok {
		resp.MockData = mockData
	}
	if !resp.Success {
		if resp.Message == "" {
			resp.Message = "平台返回下单失败"
		}
		return nil, string(rawBytes), raw, errors.New(resp.Message)
	}
	return resp, string(rawBytes), raw, nil
}

func updateExecutionStep(executionID int64, runtime *flowRuntime) error {
	_, err := db.Exec(`
		UPDATE ticket_order_execution
		SET current_step = ?,
		    step_status = ?,
		    step_trace = ?,
		    payment_status = ?,
		    result_message = ?,
		    raw_result = CASE WHEN ? = '' THEN raw_result ELSE ? END,
		    update_time = NOW()
		WHERE execution_id = ?`,
		runtime.CurrentStep,
		runtime.StepStatus,
		marshalStepTrace(runtime.StepTrace),
		runtime.PaymentStatus,
		runtime.ResultMessage,
		runtime.RawResult,
		runtime.RawResult,
		executionID,
	)
	return err
}

func updateExecutionFailure(executionID int64, runtime *flowRuntime, message string) error {
	raw := runtime.RawResult
	_, err := db.Exec(`
		UPDATE ticket_order_execution
		SET execution_status = ?, current_step = ?, step_status = ?, step_trace = ?, payment_status = ?, result_message = ?, raw_result = ?, executed_at = NOW(), update_time = NOW()
		WHERE execution_id = ?`,
		failureStatus(runtime.ExecutionStatus), runtime.CurrentStep, "failed", marshalStepTrace(runtime.StepTrace), runtime.PaymentStatus, message, raw, executionID,
	)
	return err
}

func updateExecutionSuccess(executionID int64, runtime *flowRuntime) error {
	raw := runtime.RawResult
	_, err := db.Exec(`
		UPDATE ticket_order_execution
		SET execution_status = ?,
		    order_no = CASE WHEN ? = '' THEN order_no ELSE ? END,
		    current_step = ?,
		    step_status = ?,
		    step_trace = ?,
		    payment_status = ?,
		    result_message = ?,
		    raw_result = ?,
		    executed_at = NOW(),
		    update_time = NOW()
		WHERE execution_id = ?`,
		runtime.ExecutionStatus, runtime.OrderNo, runtime.OrderNo, runtime.CurrentStep, runtime.StepStatus, marshalStepTrace(runtime.StepTrace), runtime.PaymentStatus, runtime.ResultMessage, raw, executionID,
	)
	return err
}

func normalizeRawResult(rawResult any) string {
	switch value := rawResult.(type) {
	case nil:
		return ""
	case string:
		return value
	default:
		bytes, _ := json.Marshal(value)
		return string(bytes)
	}
}

func finalizeRuntime(req dispatchRequest, runtime *flowRuntime) {
	runtime.CurrentStep = "completed"
	runtime.StepStatus = "success"
	if runtime.OrderNo == "" {
		runtime.OrderNo = fmt.Sprintf("pending-%d", req.ExecutionID.Value())
	}
	switch req.PaymentMode {
	case "online":
		if runtime.ExecutionStatus == "paid" || runtime.PaymentStatus == "paid" {
			runtime.ExecutionStatus = "paid"
			runtime.PaymentStatus = "paid"
			runtime.ResultMessage = defaultString(runtime.ResultMessage, "订单已支付")
		} else {
			runtime.ExecutionStatus = "pending_payment"
			runtime.PaymentStatus = "pending_online"
			runtime.ResultMessage = defaultString(runtime.ResultMessage, "订单已提交，等待线上支付")
		}
	case "cod_store":
		runtime.ExecutionStatus = "submitted"
		runtime.PaymentStatus = "offline_pending"
		runtime.ResultMessage = defaultString(runtime.ResultMessage, "订单已提交，等待门店付款")
	default:
		if runtime.ExecutionStatus != "paid" {
			runtime.ExecutionStatus = "pending_payment"
		}
		if runtime.PaymentStatus == "" {
			runtime.PaymentStatus = "manual_pending"
		}
		runtime.ResultMessage = defaultString(runtime.ResultMessage, "订单已提交，等待人工支付")
	}
}

func failureStatus(status string) string {
	if status == "timeout" {
		return "timeout"
	}
	if status == "blocked" {
		return "blocked"
	}
	return "failed"
}

func defaultFlowSteps(req dispatchRequest) []flowStep {
	steps := make([]flowStep, 0, 5)
	if req.OrderFlowType == "cart_checkout" {
		steps = append(steps, flowStep{StepType: "ADD_TO_CART", CurrentStep: "carting", Label: "加入购物车"})
	}
	if req.FulfillmentType == "pickup_store" {
		steps = append(steps, flowStep{StepType: "SELECT_PICKUP_STORE", CurrentStep: "selecting_fulfillment", Label: "选择门店自提"})
	} else {
		steps = append(steps, flowStep{StepType: "SELECT_DELIVERY", CurrentStep: "selecting_fulfillment", Label: "选择配送方式"})
	}
	steps = append(steps, flowStep{StepType: "SELECT_PAYMENT_MODE", CurrentStep: "selecting_payment", Label: "选择支付方式"})
	steps = append(steps, flowStep{StepType: "SUBMIT_ORDER", CurrentStep: "creating_order", Label: "提交订单"})
	switch req.PaymentMode {
	case "online":
		steps = append(steps, flowStep{StepType: "CREATE_ONLINE_PAYMENT", CurrentStep: "awaiting_payment", Label: "创建线上支付"})
	case "pending_manual":
		steps = append(steps, flowStep{StepType: "CONFIRM_PENDING_PAYMENT", CurrentStep: "awaiting_payment", Label: "等待人工支付"})
	}
	return steps
}

func initialPaymentStatus(paymentMode string) string {
	switch paymentMode {
	case "online":
		return "pending_online"
	case "cod_store":
		return "offline_pending"
	case "pending_manual":
		return "manual_pending"
	default:
		return "manual_pending"
	}
}

func currentStepLabel(step flowStep) string {
	if strings.TrimSpace(step.CurrentStep) != "" {
		return step.CurrentStep
	}
	switch step.StepType {
	case "ADD_TO_CART":
		return "carting"
	case "DIRECT_BUY":
		return "checking_out"
	case "SELECT_PICKUP_STORE", "SELECT_DELIVERY":
		return "selecting_fulfillment"
	case "SELECT_PAYMENT_MODE":
		return "selecting_payment"
	case "SUBMIT_ORDER":
		return "creating_order"
	case "CREATE_ONLINE_PAYMENT", "CONFIRM_PENDING_PAYMENT":
		return "awaiting_payment"
	default:
		return "running"
	}
}

func stepLabel(step flowStep) string {
	if strings.TrimSpace(step.Label) != "" {
		return step.Label
	}
	return step.StepType
}

func stepMessage(step flowStep, suffix string) string {
	return fmt.Sprintf("%s%s", stepLabel(step), suffix)
}

func mergeDetail(primary map[string]any, secondary map[string]any) map[string]any {
	result := make(map[string]any)
	for key, value := range secondary {
		result[key] = value
	}
	for key, value := range primary {
		result[key] = value
	}
	return result
}

func parseJSONObject(value string) map[string]any {
	if strings.TrimSpace(value) == "" {
		return map[string]any{}
	}
	var parsed map[string]any
	if err := json.Unmarshal([]byte(value), &parsed); err == nil && parsed != nil {
		return parsed
	}
	return map[string]any{}
}

func marshalStepTrace(trace []stepTraceEntry) string {
	if len(trace) == 0 {
		return "[]"
	}
	bytes, _ := json.Marshal(trace)
	return string(bytes)
}

func acquireAccountLock(ctx context.Context, accountID int64, token string) (bool, error) {
	return redisClient.SetNX(
		ctx,
		accountLockKey(accountID),
		token,
		time.Duration(appConfig.AccountLockTTLSeconds)*time.Second,
	).Result()
}

func releaseAccountLock(ctx context.Context, accountID int64, token string) {
	if err := releaseLockScript.Run(ctx, redisClient, []string{accountLockKey(accountID)}, token).Err(); err != nil && err != redis.Nil {
		log.Printf("release account lock failed for account %d: %v", accountID, err)
	}
}

func requeueDelayed(ctx context.Context, executionID int64, delay time.Duration) error {
	return redisClient.ZAdd(ctx, appConfig.DelayedZSetKey, redis.Z{
		Score:  float64(time.Now().Add(delay).UnixMilli()),
		Member: strconv.FormatInt(executionID, 10),
	}).Err()
}

func cleanupExecutionCache(ctx context.Context, executionID int64) {
	pipe := redisClient.Pipeline()
	pipe.Del(ctx, heartbeatKey(executionID))
	pipe.Del(ctx, jobKey(executionID))
	_, _ = pipe.Exec(ctx)
}

func ackMessage(ctx context.Context, messageID string) {
	if err := redisClient.XAck(ctx, appConfig.StreamKey, appConfig.ConsumerGroup, messageID).Err(); err != nil {
		log.Printf("xack failed for %s: %v", messageID, err)
	}
}

func jobKey(executionID int64) string {
	return appConfig.JobKeyPrefix + strconv.FormatInt(executionID, 10)
}

func heartbeatKey(executionID int64) string {
	return appConfig.HeartbeatKeyPrefix + strconv.FormatInt(executionID, 10)
}

func accountLockKey(accountID int64) string {
	return appConfig.AccountLockKeyPrefix + strconv.FormatInt(accountID, 10)
}

func parseJSONString(value string) any {
	// 库里 accountInfo / reqData 目前是字符串。
	// 如果本身是 JSON，就先转成对象再发给平台；不是 JSON 就原样透传。
	if strings.TrimSpace(value) == "" {
		return map[string]any{}
	}
	var parsed any
	if err := json.Unmarshal([]byte(value), &parsed); err == nil {
		return parsed
	}
	return value
}

func normalizeExecutionStatus(status string, paymentMode string) string {
	// 平台状态先归一化成系统内部的执行状态，避免不同平台各说各话。
	switch strings.ToLower(strings.TrimSpace(status)) {
	case "submitted":
		return "submitted"
	case "paid":
		return "paid"
	case "pending_payment", "pendingpayment", "pending-pay":
		return "pending_payment"
	case "timeout":
		return "timeout"
	case "blocked":
		return "blocked"
	default:
		if paymentMode == "cod_store" {
			return "submitted"
		}
		return "pending_payment"
	}
}

func defaultString(value string, fallback string) string {
	if strings.TrimSpace(value) == "" {
		return fallback
	}
	return value
}

func readString(value any, fallback string) string {
	if value == nil {
		return fallback
	}
	if text, ok := value.(string); ok && strings.TrimSpace(text) != "" {
		return text
	}
	return fallback
}

func readBool(value any, fallback bool) bool {
	if value == nil {
		return fallback
	}
	if flag, ok := value.(bool); ok {
		return flag
	}
	return fallback
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}
