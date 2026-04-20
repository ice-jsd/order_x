package main

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"html"
	"io"
	"log"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/PuerkitoBio/goquery"
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
	PurchaseType     string        `json:"purchaseType"`
	PurchaseQuantity flexibleInt   `json:"purchaseQuantity"`
	ScheduleVersion  flexibleInt64 `json:"scheduleVersion"`
	ConfigSchemaKey  string        `json:"configSchemaKey"`
	ConfigSnapshot   string        `json:"configSnapshot"`
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
	OrderNo         string
	ExecutionStatus string
	PaymentStatus   string
	CurrentStep     string
	StepStatus      string
	ResultMessage   string
	RawResult       string
	TaskOptions     map[string]any
	StepTrace       []stepTraceEntry
	LastResponse    map[string]any
	LivePocket      *livePocketState
}

type livePocketState struct {
	// 这里只存“本次执行过程中动态长出来”的中间参数。
	// 它们由前一步页面返回，供后一步继续提交使用，不适合提前固化到数据库字段中。
	Client                 *http.Client
	UserAgent              string
	TicketsPageURL         string
	SelectSeatURL          string
	ConfirmURL             string
	PurchaseURL            string
	Step1AuthenticityToken string
	TicketFieldName        string
	EventID                string
	ReserveID              string
	Step3AuthenticityToken string
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
	renewLockScript = redis.NewScript(`
if redis.call("GET", KEYS[1]) == ARGV[1] then
	return redis.call("EXPIRE", KEYS[1], ARGV[2])
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
		DelayedPollIntervalMs:     50,
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
		cfg.DelayedPollIntervalMs = 50
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

	if req.ExecutionID.Value() == 0 || req.AccountID.Value() == 0 || strings.TrimSpace(req.PurchaseType) == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"message": "missing execution/account/purchase type info"})
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
		"execution %d started, task=%d account=%d purchaseType=%s config=%s",
		executionID,
		req.TaskID.Value(),
		req.AccountID.Value(),
		req.PurchaseType,
		req.ConfigSchemaKey,
	)

	heartbeatCtx, stopHeartbeat := context.WithCancel(context.Background())
	var heartbeatWG sync.WaitGroup
	heartbeatWG.Add(2)
	go keepHeartbeat(heartbeatCtx, &heartbeatWG, executionID)
	go keepAccountLock(heartbeatCtx, &heartbeatWG, req.AccountID.Value(), lockToken)

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

func keepAccountLock(ctx context.Context, wg *sync.WaitGroup, accountID int64, token string) {
	defer wg.Done()
	ttl := time.Duration(appConfig.AccountLockTTLSeconds) * time.Second
	interval := ttl / 3
	if interval < time.Second {
		interval = time.Second
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	renewAccountLock(context.Background(), accountID, token)
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			renewAccountLock(context.Background(), accountID, token)
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
		TaskOptions:     parseJSONObject(req.TaskOptions),
		ExecutionStatus: "running",
		PaymentStatus:   initialPaymentStatus(resolvePaymentMode(req.AdapterType, parseJSONObject(req.TaskOptions))),
		CurrentStep:     "queued",
		StepStatus:      "queued",
		ResultMessage:   "等待执行",
	}

	steps := req.FlowSteps
	if len(steps) == 0 {
		steps = defaultFlowSteps(req)
	}

	for _, step := range steps {
		if err := waitForScheduledTrigger(req, runtime, step); err != nil {
			return runtime, err
		}

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
	if isLivePocketAdapter(req.AdapterType) {
		return executeLivePocketStep(req, runtime, step)
	}

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
			runtime.ExecutionStatus = normalizeExecutionStatus(resp.Status, resolvePaymentMode(req.AdapterType, runtime.TaskOptions))
			runtime.ResultMessage = defaultString(resp.Message, "订单已提交")
		case "CREATE_ONLINE_PAYMENT":
			runtime.ExecutionStatus = normalizeExecutionStatus(resp.Status, resolvePaymentMode(req.AdapterType, runtime.TaskOptions))
			runtime.PaymentStatus = defaultString(resp.PaymentStatus, "pending_online")
			runtime.ResultMessage = defaultString(resp.Message, "支付单已创建，等待线上支付")
		case "CONFIRM_PENDING_PAYMENT":
			runtime.ExecutionStatus = normalizeExecutionStatus(resp.Status, resolvePaymentMode(req.AdapterType, runtime.TaskOptions))
			runtime.PaymentStatus = defaultString(resp.PaymentStatus, initialPaymentStatus(resolvePaymentMode(req.AdapterType, runtime.TaskOptions)))
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
		return mergeDetail(step.Options, map[string]any{"paymentMode": resolvePaymentMode(req.AdapterType, runtime.TaskOptions)}), nil
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
		runtime.ExecutionStatus = normalizeExecutionStatus(resp.Status, resolvePaymentMode(req.AdapterType, runtime.TaskOptions))
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
		runtime.PaymentStatus = initialPaymentStatus(resolvePaymentMode(req.AdapterType, runtime.TaskOptions))
		runtime.ResultMessage = "订单已提交，等待后续支付"
		return mergeDetail(step.Options, runtime.LastResponse), nil
	default:
		return step.Options, fmt.Errorf("unsupported step type: %s", step.StepType)
	}
}

func waitForScheduledTrigger(req dispatchRequest, runtime *flowRuntime, step flowStep) error {
	if !isCriticalSubmitStep(step) {
		return nil
	}
	triggerAt := scheduledTriggerTime(req)
	if triggerAt == nil {
		return nil
	}
	delay := time.Until(*triggerAt)
	if delay <= 0 {
		return nil
	}

	runtime.CurrentStep = currentStepLabel(step)
	runtime.StepStatus = "waiting"
	runtime.ResultMessage = fmt.Sprintf("%s等待计划抢购时间", stepLabel(step))
	if err := updateExecutionStep(req.ExecutionID.Value(), runtime); err != nil {
		return err
	}

	log.Printf(
		"execution %d waiting %s until scheduled trigger %s before %s",
		req.ExecutionID.Value(),
		delay.Truncate(time.Millisecond),
		triggerAt.Format(time.RFC3339Nano),
		step.StepType,
	)
	timer := time.NewTimer(delay)
	defer timer.Stop()
	<-timer.C
	return nil
}

func scheduledTriggerTime(req dispatchRequest) *time.Time {
	if req.ScheduledTime == nil {
		return nil
	}
	return req.ScheduledTime.Ptr()
}

func isCriticalSubmitStep(step flowStep) bool {
	switch strings.ToUpper(strings.TrimSpace(step.StepType)) {
	case "SUBMIT_ORDER", "LP_SUBMIT_PURCHASE":
		return true
	default:
		return false
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
		"purchaseType":     req.PurchaseType,
		"purchaseQuantity": req.PurchaseQuantity.Value(),
		"configSchemaKey":  req.ConfigSchemaKey,
		"configSnapshot":   parseJSONString(req.ConfigSnapshot),
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
		Success:       readBool(raw["success"], true),
		OrderNo:       readString(raw["orderNo"], readString(raw["order_no"], "")),
		Message:       readString(raw["message"], ""),
		Status:        readString(raw["status"], ""),
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

func executeLivePocketStep(req dispatchRequest, runtime *flowRuntime, step flowStep) (map[string]any, error) {
	// LivePocket 不是一个“单接口下单”平台，而是页面驱动的多步骤链路：
	// 第一步取页面 token，第二步创建 reserve，第三步再取最终确认 token，第四步才真正提交订单。
	state, err := ensureLivePocketState(req, runtime)
	if err != nil {
		return nil, err
	}

	switch step.StepType {
	case "LP_FETCH_TICKETS":
		return livePocketFetchTickets(req, runtime, state, step)
	case "LP_SELECT_SEAT":
		return livePocketSelectSeat(req, runtime, state, step)
	case "LP_CONFIRM_PURCHASE":
		return livePocketConfirmPurchase(req, runtime, state, step)
	case "LP_SUBMIT_PURCHASE":
		return livePocketSubmitPurchase(req, runtime, state, step)
	default:
		return nil, fmt.Errorf("unsupported live pocket step: %s", step.StepType)
	}
}

func ensureLivePocketState(req dispatchRequest, runtime *flowRuntime) (*livePocketState, error) {
	if runtime.LivePocket != nil {
		return runtime.LivePocket, nil
	}

	// taskOptions 放“任务固定参数”，例如 ticketsPageUrl / paymentMethod。
	// reqData 放“账号登录态参数”，例如 cookies / userAgent。
	// 两者组合后，Go 才有能力像浏览器一样继续走后续页面流程。
	ticketsPageURL := strings.TrimSpace(readString(runtime.TaskOptions["ticketsPageUrl"], ""))
	if ticketsPageURL == "" {
		return nil, errors.New("LivePocket 缺少 ticketsPageUrl 配置")
	}
	pageURL, err := url.Parse(ticketsPageURL)
	if err != nil {
		return nil, fmt.Errorf("ticketsPageUrl 非法: %w", err)
	}

	jar, _ := cookiejar.New(nil)
	cookies := extractLivePocketCookies(parseJSONString(req.ReqData))
	if len(cookies) == 0 {
		return nil, errors.New("LivePocket 登录态缺失，reqData 中没有可用 cookies")
	}
	jar.SetCookies(pageURL, cookies)

	userAgent := defaultString(extractLivePocketUserAgent(parseJSONString(req.ReqData)), livePocketDefaultUserAgent())
	selectSeatURL := resolveLivePocketSelectSeatURL(pageURL)
	purchaseURL := pageURL.ResolveReference(&url.URL{Path: "/purchase"}).String()

	runtime.LivePocket = &livePocketState{
		Client: &http.Client{
			Timeout: httpClient.Timeout,
			Jar:     jar,
		},
		UserAgent:      userAgent,
		TicketsPageURL: pageURL.String(),
		SelectSeatURL:  selectSeatURL,
		PurchaseURL:    purchaseURL,
	}
	return runtime.LivePocket, nil
}

func livePocketFetchTickets(req dispatchRequest, runtime *flowRuntime, state *livePocketState, step flowStep) (map[string]any, error) {
	// 第一步的目标不是下单，而是把第二步需要的两个关键参数抠出来：
	// 1. hidden form 的 authenticity_token
	// 2. 动态票种字段名 tickets[票种ID]
	body, finalURL, statusCode, err := livePocketDoRequest(state, http.MethodGet, state.TicketsPageURL, "", nil)
	if err != nil {
		return nil, fmt.Errorf("获取票务页面失败: %w", err)
	}
	doc, err := goquery.NewDocumentFromReader(strings.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("解析票务页面失败: %w", err)
	}

	token := strings.TrimSpace(doc.Find(`input[name="authenticity_token"]`).First().AttrOr("value", ""))
	if token == "" {
		return nil, errors.New("票务页面缺少 authenticity_token，可能登录态失效或页面结构已变化")
	}
	ticketFieldName := detectLivePocketTicketFieldName(doc, body)
	if ticketFieldName == "" {
		return nil, errors.New("票务页面未找到 tickets[票种ID] 字段")
	}

	state.Step1AuthenticityToken = token
	if finalURL != "" {
		state.TicketsPageURL = finalURL
		if parsedURL, parseErr := url.Parse(finalURL); parseErr == nil {
			state.SelectSeatURL = resolveLivePocketSelectSeatURL(parsedURL)
			state.PurchaseURL = parsedURL.ResolveReference(&url.URL{Path: "/purchase"}).String()
		}
	}
	state.TicketFieldName = ticketFieldName

	detail := map[string]any{
		"requestUrl":      state.TicketsPageURL,
		"finalUrl":        finalURL,
		"method":          http.MethodGet,
		"httpStatus":      statusCode,
		"ticketFieldName": ticketFieldName,
		"authToken":       maskToken(token),
		"bodyPreview":     bodyPreview(body),
	}
	runtime.RawResult = marshalJSON(detail)
	runtime.LastResponse = detail
	runtime.ResultMessage = "已获取 LivePocket 票务页面"
	return mergeDetail(step.Options, detail), nil
}

func livePocketSelectSeat(req dispatchRequest, runtime *flowRuntime, state *livePocketState, step flowStep) (map[string]any, error) {
	// 第二步用“第一步提取的动态票种字段名”+“任务配置里的购买数量”拼出表单，
	// 成功后最重要的结果是 event_id 和 reserve_id，后续确认页与最终提交都依赖它们。
	if state.Step1AuthenticityToken == "" || state.TicketFieldName == "" {
		return nil, errors.New("LivePocket 第一步尚未完成，缺少票种字段或 authenticity_token")
	}

	quantity := readPositiveInt(step.Options["ticketQuantity"], req.PurchaseQuantity.Value(), 1)
	form := url.Values{}
	form.Set("authenticity_token", state.Step1AuthenticityToken)
	form.Set(state.TicketFieldName, strconv.Itoa(quantity))

	body, finalURL, statusCode, err := livePocketDoRequest(state, http.MethodPost, state.SelectSeatURL, state.TicketsPageURL, strings.NewReader(form.Encode()))
	if err != nil {
		return nil, fmt.Errorf("创建 LivePocket 预留失败: %w", err)
	}

	eventID, reserveID := extractLivePocketReserveInfo(finalURL, body)
	if eventID == "" || reserveID == "" {
		return nil, errors.New("LivePocket 预留成功页未提取到 id 或 reserve_id")
	}

	state.EventID = eventID
	state.ReserveID = reserveID
	state.ConfirmURL = buildLivePocketConfirmURL(state.PurchaseURL, eventID, reserveID)

	detail := map[string]any{
		"requestUrl":  state.SelectSeatURL,
		"finalUrl":    finalURL,
		"method":      http.MethodPost,
		"httpStatus":  statusCode,
		"eventId":     eventID,
		"reserveId":   reserveID,
		"ticketField": state.TicketFieldName,
		"quantity":    quantity,
		"bodyPreview": bodyPreview(body),
	}
	runtime.RawResult = marshalJSON(detail)
	runtime.LastResponse = detail
	runtime.ResultMessage = "已创建 LivePocket 预留"
	return mergeDetail(step.Options, detail), nil
}

func livePocketConfirmPurchase(req dispatchRequest, runtime *flowRuntime, state *livePocketState, step flowStep) (map[string]any, error) {
	// 第三步进入确认页，主要是拿最终提交表单需要的新 token，
	// 同时确认 payment_method 这类字段在页面里是存在的。
	if state.EventID == "" || state.ReserveID == "" || state.ConfirmURL == "" {
		return nil, errors.New("LivePocket 缺少确认页参数，无法进入 purchase/confirm")
	}

	body, finalURL, statusCode, err := livePocketDoRequest(state, http.MethodGet, state.ConfirmURL, state.TicketsPageURL, nil)
	if err != nil {
		return nil, fmt.Errorf("获取 LivePocket 确认页失败: %w", err)
	}
	doc, err := goquery.NewDocumentFromReader(strings.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("解析 LivePocket 确认页失败: %w", err)
	}

	token := strings.TrimSpace(doc.Find(`input[name="authenticity_token"]`).First().AttrOr("value", ""))
	if token == "" {
		return nil, errors.New("LivePocket 确认页缺少 authenticity_token")
	}
	paymentFieldFound := doc.Find(`[name="order_form[payment_method]"]`).Length() > 0
	cvsFieldFound := doc.Find(`[name="order_form[sbps_web_cvs_type]"]`).Length() > 0
	if !paymentFieldFound {
		return nil, errors.New("LivePocket 确认页缺少 order_form[payment_method] 字段")
	}

	state.Step3AuthenticityToken = token
	if finalURL != "" {
		state.ConfirmURL = finalURL
	}

	detail := map[string]any{
		"requestUrl":        state.ConfirmURL,
		"finalUrl":          finalURL,
		"method":            http.MethodGet,
		"httpStatus":        statusCode,
		"eventId":           state.EventID,
		"reserveId":         state.ReserveID,
		"authToken":         maskToken(token),
		"paymentFieldFound": paymentFieldFound,
		"cvsFieldFound":     cvsFieldFound,
		"bodyPreview":       bodyPreview(body),
	}
	runtime.RawResult = marshalJSON(detail)
	runtime.LastResponse = detail
	runtime.ResultMessage = "已加载 LivePocket 确认页"
	return mergeDetail(step.Options, detail), nil
}

func livePocketSubmitPurchase(req dispatchRequest, runtime *flowRuntime, state *livePocketState, step flowStep) (map[string]any, error) {
	// 第四步才是真正下单。
	// 这里会把第二步生成的 reserve/event 参数，与第三步拿到的最终 token 一起提交。
	if state.Step3AuthenticityToken == "" || state.EventID == "" || state.ReserveID == "" {
		return nil, errors.New("LivePocket 缺少最终提交所需参数")
	}

	paymentMethod := defaultString(readString(step.Options["paymentMethod"], ""), "cvs")
	cvsType := defaultString(readString(step.Options["sbpsWebCvsType"], ""), "016")
	followNotification := readPositiveInt(step.Options["followNotification"], 1, 1)
	purchaseAgreementContent := readPositiveInt(step.Options["purchaseAgreementContent"], 1, 1)

	form := url.Values{}
	form.Set("authenticity_token", state.Step3AuthenticityToken)
	form.Set("id", state.EventID)
	form.Set("order_form[reserve_id]", state.ReserveID)
	form.Set("order_form[event_id]", state.EventID)
	form.Set("order_form[payment_method]", paymentMethod)
	form.Set("order_form[sbps_web_cvs_type]", cvsType)
	form.Set("order_form[follow_notification]", strconv.Itoa(followNotification))
	form.Set("order_form[purchase_agreement_content]", strconv.Itoa(purchaseAgreementContent))

	body, finalURL, statusCode, err := livePocketDoRequest(state, http.MethodPost, state.PurchaseURL, state.ConfirmURL, strings.NewReader(form.Encode()))
	if err != nil {
		return nil, fmt.Errorf("提交 LivePocket 订单失败: %w", err)
	}

	orderNo := extractLivePocketOrderNo(body)
	if orderNo == "" {
		// 如果结果页里没法稳定提取订单号，至少保留一个可追踪的兜底标识，
		// 这样执行记录、日志和问题排查不会断链。
		orderNo = fmt.Sprintf("livepocket-%s-%s", state.EventID, state.ReserveID)
	}
	runtime.OrderNo = orderNo
	runtime.ExecutionStatus = normalizeExecutionStatus("submitted", resolvePaymentMode(req.AdapterType, runtime.TaskOptions))
	runtime.ResultMessage = "LivePocket 订单提交成功"

	detail := map[string]any{
		"requestUrl":     state.PurchaseURL,
		"finalUrl":       finalURL,
		"method":         http.MethodPost,
		"httpStatus":     statusCode,
		"eventId":        state.EventID,
		"reserveId":      state.ReserveID,
		"orderNo":        orderNo,
		"paymentMethod":  paymentMethod,
		"sbpsWebCvsType": cvsType,
		"bodyPreview":    bodyPreview(body),
	}
	runtime.RawResult = marshalJSON(detail)
	runtime.LastResponse = detail
	return mergeDetail(step.Options, detail), nil
}

func livePocketDoRequest(state *livePocketState, method string, requestURL string, referer string, body io.Reader) (string, string, int, error) {
	// 统一在这里补浏览器常见 header。
	// Cookie 不手写在 Header 中，而是恢复到 cookiejar 里，由 http.Client 自动带上。
	httpReq, err := http.NewRequestWithContext(context.Background(), method, requestURL, body)
	if err != nil {
		return "", "", 0, err
	}
	httpReq.Header.Set("User-Agent", state.UserAgent)
	httpReq.Header.Set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
	httpReq.Header.Set("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
	if referer != "" {
		httpReq.Header.Set("Referer", referer)
	}
	if method == http.MethodPost {
		httpReq.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		if parsedURL, parseErr := url.Parse(requestURL); parseErr == nil {
			httpReq.Header.Set("Origin", parsedURL.Scheme+"://"+parsedURL.Host)
		}
	}

	httpResp, err := state.Client.Do(httpReq)
	if err != nil {
		if errors.Is(err, context.DeadlineExceeded) || errors.Is(err, syscall.ETIMEDOUT) {
			return "", "", 0, fmt.Errorf("请求超时: %w", err)
		}
		return "", "", 0, err
	}
	defer httpResp.Body.Close()

	bodyBytes, err := io.ReadAll(httpResp.Body)
	if err != nil {
		return "", "", httpResp.StatusCode, err
	}
	finalURL := requestURL
	if httpResp.Request != nil && httpResp.Request.URL != nil {
		finalURL = httpResp.Request.URL.String()
	}
	if httpResp.StatusCode < 200 || httpResp.StatusCode >= 300 {
		return "", finalURL, httpResp.StatusCode, fmt.Errorf("HTTP %d: %s", httpResp.StatusCode, bodyPreview(string(bodyBytes)))
	}
	return string(bodyBytes), finalURL, httpResp.StatusCode, nil
}

func resolveLivePocketSelectSeatURL(pageURL *url.URL) string {
	selectURL := *pageURL
	if strings.HasSuffix(selectURL.Path, "/tickets") {
		selectURL.Path = strings.TrimSuffix(selectURL.Path, "/tickets") + "/select_seat"
	}
	return selectURL.String()
}

func buildLivePocketConfirmURL(purchaseURL string, eventID string, reserveID string) string {
	confirmURL, err := url.Parse(purchaseURL)
	if err != nil {
		return fmt.Sprintf("https://livepocket.jp/purchase/confirm?id=%s&reserve_id=%s", eventID, reserveID)
	}
	confirmURL.Path = "/purchase/confirm"
	query := confirmURL.Query()
	query.Set("id", eventID)
	query.Set("reserve_id", reserveID)
	confirmURL.RawQuery = query.Encode()
	return confirmURL.String()
}

func detectLivePocketTicketFieldName(doc *goquery.Document, body string) string {
	ticketFieldName := ""
	doc.Find(`[name]`).EachWithBreak(func(_ int, selection *goquery.Selection) bool {
		name, ok := selection.Attr("name")
		if ok && strings.HasPrefix(name, "tickets[") && strings.HasSuffix(name, "]") {
			ticketFieldName = name
			return false
		}
		return true
	})
	if ticketFieldName != "" {
		return ticketFieldName
	}
	// 真实页面不一定把 tickets[...] 直接渲染成 input[name]，
	// 你给的 Apifox 样例里可以从票卡 DOM id 里的 ev_2660700 反推出 ticket id。
	re := regexp.MustCompile(`ev_(\d+)(?:_|")`)
	match := re.FindStringSubmatch(body)
	if len(match) == 2 {
		return fmt.Sprintf("tickets[%s]", match[1])
	}
	return ""
}

func extractLivePocketReserveInfo(finalURL string, body string) (string, string) {
	// reserve 信息优先从最终 URL 取；如果 URL 不够直观，再从 HTML 文本里兜底提取。
	checkCandidates := []string{finalURL, html.UnescapeString(body)}
	patterns := []*regexp.Regexp{
		regexp.MustCompile(`/purchase/confirm\?id=([0-9]+)&reserve_id=([0-9]+)`),
		regexp.MustCompile(`reserve_id=([0-9]+)`),
	}
	for _, candidate := range checkCandidates {
		if candidate == "" {
			continue
		}
		if parsedURL, err := url.Parse(candidate); err == nil {
			query := parsedURL.Query()
			if strings.TrimSpace(query.Get("id")) != "" && strings.TrimSpace(query.Get("reserve_id")) != "" {
				return query.Get("id"), query.Get("reserve_id")
			}
		}
		if match := patterns[0].FindStringSubmatch(candidate); len(match) == 3 {
			return match[1], match[2]
		}
		if strings.Contains(candidate, "reserve_id=") {
			if reserveMatch := patterns[1].FindStringSubmatch(candidate); len(reserveMatch) == 2 {
				eventMatch := regexp.MustCompile(`id=([0-9]+)`).FindStringSubmatch(candidate)
				if len(eventMatch) == 2 {
					return eventMatch[1], reserveMatch[1]
				}
			}
		}
	}
	return "", ""
}

func extractLivePocketOrderNo(body string) string {
	patterns := []*regexp.Regexp{
		regexp.MustCompile(`申込(?:み)?番号[^0-9A-Za-z]*([0-9A-Za-z-]{6,})`),
		regexp.MustCompile(`注文番号[^0-9A-Za-z]*([0-9A-Za-z-]{6,})`),
		regexp.MustCompile(`order[_ ]?no[^0-9A-Za-z]*([0-9A-Za-z-]{6,})`),
	}
	for _, pattern := range patterns {
		if match := pattern.FindStringSubmatch(body); len(match) == 2 {
			return match[1]
		}
	}
	return ""
}

func extractLivePocketCookies(reqData any) []*http.Cookie {
	// reqData 允许多种来源形态：
	// - cookies 数组
	// - cookieHeader 字符串
	// - name/value map
	// 这里先兼容常见格式，减少外部登录回传时的耦合。
	value := reqData
	if root, ok := reqData.(map[string]any); ok {
		if cookies, exists := root["cookies"]; exists {
			value = cookies
		} else if cookieHeader, exists := root["cookieHeader"]; exists {
			value = cookieHeader
		} else if cookieValue, exists := root["cookie"]; exists {
			value = cookieValue
		}
	}

	switch cookies := value.(type) {
	case string:
		return parseCookieHeader(cookies)
	case []any:
		result := make([]*http.Cookie, 0, len(cookies))
		for _, item := range cookies {
			if cookie := parseCookieItem(item); cookie != nil {
				result = append(result, cookie)
			}
		}
		return result
	case map[string]any:
		result := make([]*http.Cookie, 0, len(cookies))
		for name, rawValue := range cookies {
			value := readString(rawValue, "")
			if strings.TrimSpace(name) != "" && strings.TrimSpace(value) != "" {
				result = append(result, &http.Cookie{Name: name, Value: value, Path: "/"})
			}
		}
		return result
	default:
		return nil
	}
}

func parseCookieItem(item any) *http.Cookie {
	if item == nil {
		return nil
	}
	if cookieMap, ok := item.(map[string]any); ok {
		name := defaultString(readString(cookieMap["name"], ""), readString(cookieMap["Name"], ""))
		value := defaultString(readString(cookieMap["value"], ""), readString(cookieMap["Value"], ""))
		if strings.TrimSpace(name) == "" || strings.TrimSpace(value) == "" {
			return nil
		}
		cookie := &http.Cookie{
			Name:   name,
			Value:  value,
			Path:   defaultString(readString(cookieMap["path"], ""), "/"),
			Domain: readString(cookieMap["domain"], ""),
		}
		return cookie
	}
	if text, ok := item.(string); ok {
		cookies := parseCookieHeader(text)
		if len(cookies) > 0 {
			return cookies[0]
		}
	}
	return nil
}

func parseCookieHeader(header string) []*http.Cookie {
	// 这里只做最小可用的 Cookie 恢复：
	// 目标是恢复会话，不是完整实现浏览器 Cookie 规范。
	parts := strings.Split(header, ";")
	result := make([]*http.Cookie, 0, len(parts))
	for _, part := range parts {
		segment := strings.TrimSpace(part)
		if segment == "" {
			continue
		}
		name, value, found := strings.Cut(segment, "=")
		if !found {
			continue
		}
		name = strings.TrimSpace(name)
		value = strings.TrimSpace(value)
		if name == "" || value == "" {
			continue
		}
		result = append(result, &http.Cookie{Name: name, Value: value, Path: "/"})
	}
	return result
}

func extractLivePocketUserAgent(reqData any) string {
	root, ok := reqData.(map[string]any)
	if !ok {
		return ""
	}
	if userAgent := readString(root["userAgent"], ""); userAgent != "" {
		return userAgent
	}
	if headers, ok := root["headers"].(map[string]any); ok {
		for _, key := range []string{"User-Agent", "user-agent", "userAgent"} {
			if userAgent := readString(headers[key], ""); userAgent != "" {
				return userAgent
			}
		}
	}
	return ""
}

func livePocketDefaultUserAgent() string {
	return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"
}

func readPositiveInt(value any, fallback int, minimum int) int {
	switch parsed := value.(type) {
	case float64:
		if int(parsed) >= minimum {
			return int(parsed)
		}
	case int:
		if parsed >= minimum {
			return parsed
		}
	case string:
		if number, err := strconv.Atoi(strings.TrimSpace(parsed)); err == nil && number >= minimum {
			return number
		}
	}
	if fallback >= minimum {
		return fallback
	}
	return minimum
}

func maskToken(value string) string {
	value = strings.TrimSpace(value)
	if value == "" {
		return ""
	}
	if len(value) <= 12 {
		return value
	}
	return value[:6] + "..." + value[len(value)-4:]
}

func bodyPreview(body string) string {
	body = strings.Join(strings.Fields(html.UnescapeString(body)), " ")
	if len(body) > 320 {
		return body[:320]
	}
	return body
}

func marshalJSON(value any) string {
	bytes, _ := json.Marshal(value)
	return string(bytes)
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
	switch resolvePaymentMode(req.AdapterType, runtime.TaskOptions) {
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
	taskOptions := parseJSONObject(req.TaskOptions)
	steps := make([]flowStep, 0, 5)
	purchaseMode := readString(taskOptions["purchaseMode"], "")
	if purchaseMode == "cart_checkout" {
		steps = append(steps, flowStep{StepType: "ADD_TO_CART", CurrentStep: "carting", Label: "加入购物车"})
	} else {
		steps = append(steps, flowStep{StepType: "DIRECT_BUY", CurrentStep: "checking_out", Label: "直接下单"})
	}
	if readString(taskOptions["fulfillmentMode"], "") == "pickup_store" {
		steps = append(steps, flowStep{StepType: "SELECT_PICKUP_STORE", CurrentStep: "selecting_fulfillment", Label: "选择门店自提"})
	} else {
		steps = append(steps, flowStep{StepType: "SELECT_DELIVERY", CurrentStep: "selecting_fulfillment", Label: "选择配送方式"})
	}
	steps = append(steps, flowStep{StepType: "SELECT_PAYMENT_MODE", CurrentStep: "selecting_payment", Label: "选择支付方式"})
	steps = append(steps, flowStep{StepType: "SUBMIT_ORDER", CurrentStep: "creating_order", Label: "提交订单"})
	switch resolvePaymentMode(req.AdapterType, taskOptions) {
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

func resolvePaymentMode(adapterType string, taskOptions map[string]any) string {
	if paymentMode := readString(taskOptions["paymentMode"], ""); paymentMode != "" {
		return paymentMode
	}
	if isLivePocketAdapter(adapterType) {
		if strings.EqualFold(readString(taskOptions["paymentMethod"], ""), "cvs") {
			return "cod_store"
		}
	}
	return "pending_manual"
}

func isLivePocketAdapter(adapterType string) bool {
	return strings.EqualFold(strings.TrimSpace(adapterType), "livepocket")
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

func renewAccountLock(ctx context.Context, accountID int64, token string) {
	if err := renewLockScript.Run(
		ctx,
		redisClient,
		[]string{accountLockKey(accountID)},
		token,
		strconv.Itoa(appConfig.AccountLockTTLSeconds),
	).Err(); err != nil && err != redis.Nil {
		log.Printf("renew account lock failed for account %d: %v", accountID, err)
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
