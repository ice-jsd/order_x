package org.dromara.ticket.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.ticket.config.TicketPythonExecutorProperties;
import org.dromara.ticket.domain.vo.TicketJumpShopProductInfoVo;
import org.dromara.ticket.domain.vo.TicketLivePocketQuestionnairePreviewVo;
import org.dromara.ticket.domain.vo.TicketLotteryEventInfoVo;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
@RequiredArgsConstructor
public class TicketPythonExecutorClient {

    private final TicketPythonExecutorProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().build();

    public void startLivePocketRegisterBatch(Long batchId, String platformCode, Integer count) {
        postAccepted("/livepocket/register-batch", Map.of(
            "batchId", batchId,
            "platformCode", platformCode,
            "count", count,
            "backendBaseUrl", requireBackendBaseUrl()
        ), "Python 批量注册");
    }

    public void startLivePocketLoginBatch(Long batchId, String platformCode, List<Map<String, Object>> accounts) {
        postAccepted("/livepocket/login-batch", Map.of(
            "batchId", batchId,
            "platformCode", platformCode,
            "accounts", accounts,
            "backendBaseUrl", requireBackendBaseUrl()
        ), "Python 批量登录");
    }

    public void startJumpShopRegisterBatch(Long batchId, String platformCode, Integer count) {
        postAccepted("/jump-shop/register-batch", Map.of(
            "batchId", batchId,
            "platformCode", platformCode,
            "count", count,
            "backendBaseUrl", requireBackendBaseUrl()
        ), "Jump Shop Python 批量注册");
    }

    public void startJumpShopLoginBatch(Long batchId, String platformCode, List<Map<String, Object>> accounts) {
        postAccepted("/jump-shop/login-batch", Map.of(
            "batchId", batchId,
            "platformCode", platformCode,
            "accounts", accounts,
            "backendBaseUrl", requireBackendBaseUrl()
        ), "Jump Shop Python 批量登录");
    }

    public TicketLotteryEventInfoVo fetchLivePocketLotteryEventInfo(String eventUrl) {
        try {
            String encodedUrl = URLEncoder.encode(eventUrl, StandardCharsets.UTF_8);
            Map<String, Object> result = requestJson("/livepocket/lottery-event-info?url=" + encodedUrl, "Python 抽票页面解析");
            return objectMapper.convertValue(result.get("data"), TicketLotteryEventInfoVo.class);
        } catch (ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("fetch livepocket lottery event info failed, eventUrl={}", eventUrl, ex);
            throw new ServiceException("Python 抽票页面解析异常: " + ex.getMessage());
        }
    }

    public TicketLivePocketQuestionnairePreviewVo previewLivePocketQuestionnaire(Map<String, Object> payload) {
        if (!properties.isEnabled()) {
            throw new ServiceException("Python 抽票执行器未启用");
        }
        if (StringUtils.isBlank(properties.getBaseUrl())) {
            throw new ServiceException("未配置 Python 抽票执行器地址");
        }
        try {
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.getBaseUrl() + "/livepocket/questionnaire-preview"))
                .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> result = objectMapper.readValue(response.body(), new TypeReference<>() {
            });
            if (response.statusCode() < 200 || response.statusCode() >= 300 || !Boolean.TRUE.equals(result.get("success"))) {
                String message = StringUtils.defaultString(String.valueOf(result.get("message")), "未知错误");
                throw new ServiceException("Python 读取 LivePocket 确认页问卷失败: " + message);
            }
            return objectMapper.convertValue(result.get("questionnaireConfig") == null ? result.get("data") : result, TicketLivePocketQuestionnairePreviewVo.class);
        } catch (ServiceException ex) {
            throw ex;
        } catch (HttpTimeoutException ex) {
            log.warn("preview livepocket questionnaire timeout, payload={}", payload, ex);
            throw new ServiceException("Python 读取 LivePocket 确认页问卷超时");
        } catch (Exception ex) {
            log.error("preview livepocket questionnaire failed, payload={}", payload, ex);
            throw new ServiceException("Python 读取 LivePocket 确认页问卷异常: " + ex.getMessage());
        }
    }

    public TicketJumpShopProductInfoVo fetchJumpShopProductInfo(String productUrl) {
        try {
            String encodedUrl = URLEncoder.encode(productUrl, StandardCharsets.UTF_8);
            Map<String, Object> result = requestJson("/jump-shop/product-info?url=" + encodedUrl, "Jump Shop 商品解析");
            return objectMapper.convertValue(result.get("data"), TicketJumpShopProductInfoVo.class);
        } catch (ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("fetch jump shop product info failed, productUrl={}", productUrl, ex);
            throw new ServiceException("Jump Shop 商品解析异常: " + ex.getMessage());
        }
    }

    public Map<String, Object> updateLivePocketProfileLastName(String email, String password, String lastName, String loginReqData) {
        if (!properties.isEnabled()) {
            throw new ServiceException("Python 抽票执行器未启用");
        }
        if (StringUtils.isBlank(properties.getBaseUrl())) {
            throw new ServiceException("未配置 Python 抽票执行器地址");
        }
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("email", email);
            payload.put("password", password);
            payload.put("lastName", lastName);
            payload.put("platformCode", "livepocket");
            payload.put("backendBaseUrl", requireBackendBaseUrl());
            if (StringUtils.isNotBlank(loginReqData)) {
                payload.put("loginReqData", loginReqData);
            }
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.getBaseUrl() + "/livepocket/profile-last-name"))
                .timeout(Duration.ofMillis(properties.getProfileTimeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> result = objectMapper.readValue(response.body(), new TypeReference<>() {
            });
            if (response.statusCode() < 200 || response.statusCode() >= 300 || !Boolean.TRUE.equals(result.get("success"))) {
                String message = StringUtils.defaultString(String.valueOf(result.get("message")), "未知错误");
                throw new ServiceException("Python 修改 LivePocket 姓氏失败: " + message);
            }
            return result;
        } catch (ServiceException ex) {
            throw ex;
        } catch (HttpTimeoutException ex) {
            log.error("update livepocket profile last name timeout, email={}, timeoutMs={}", email, properties.getProfileTimeoutMs(), ex);
            throw new ServiceException("Python 修改 LivePocket 姓氏超时，请稍后查看账号状态");
        } catch (Exception ex) {
            log.error("update livepocket profile last name failed, email={}", email, ex);
            throw new ServiceException("Python 修改 LivePocket 姓氏异常: " + ex.getMessage());
        }
    }

    public Map<String, Object> keepAliveLivePocketLogin(String email, String loginReqData) {
        if (!properties.isEnabled()) {
            throw new ServiceException("Python 抽票执行器未启用");
        }
        if (StringUtils.isBlank(properties.getBaseUrl())) {
            throw new ServiceException("未配置 Python 抽票执行器地址");
        }
        if (StringUtils.isBlank(loginReqData)) {
            throw new ServiceException("loginReqData不能为空");
        }
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("email", email);
            payload.put("loginReqData", loginReqData);
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.getBaseUrl() + "/livepocket/login-keepalive"))
                .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> result = objectMapper.readValue(response.body(), new TypeReference<>() {
            });
            if (response.statusCode() < 200 || response.statusCode() >= 300 || !Boolean.TRUE.equals(result.get("success"))) {
                String message = StringUtils.defaultString(String.valueOf(result.get("message")), "未知错误");
                throw new ServiceException("Python LivePocket 登录态保活失败: " + message);
            }
            return result;
        } catch (ServiceException ex) {
            throw ex;
        } catch (HttpTimeoutException ex) {
            log.warn("livepocket login keepalive timeout, email={}, timeoutMs={}", email, properties.getTimeoutMs(), ex);
            throw new ServiceException("Python LivePocket 登录态保活超时");
        } catch (Exception ex) {
            log.warn("livepocket login keepalive failed, email={}", email, ex);
            throw new ServiceException("Python LivePocket 登录态保活异常: " + ex.getMessage());
        }
    }

    private String requireBackendBaseUrl() {
        if (StringUtils.isBlank(properties.getBackendBaseUrl())) {
            throw new ServiceException("未配置 Python 回调 Java 外部账号接口地址");
        }
        return properties.getBackendBaseUrl();
    }

    private void postAccepted(String path, Map<String, Object> payload, String actionName) {
        if (!properties.isEnabled()) {
            throw new ServiceException("Python 执行器未启用");
        }
        if (StringUtils.isBlank(properties.getBaseUrl())) {
            throw new ServiceException("未配置 Python 执行器地址");
        }
        try {
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.getBaseUrl() + path))
                .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> result = objectMapper.readValue(response.body(), new TypeReference<>() {
            });
            if (response.statusCode() < 200 || response.statusCode() >= 300 || !Boolean.TRUE.equals(result.get("success"))) {
                String message = StringUtils.defaultString(String.valueOf(result.get("message")), "未知错误");
                throw new ServiceException(actionName + "提交失败: " + message);
            }
        } catch (ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("{} submit failed, payload={}", actionName, payload, ex);
            throw new ServiceException(actionName + "提交异常: " + ex.getMessage());
        }
    }

    private Map<String, Object> requestJson(String path, String actionName) throws Exception {
        if (!properties.isEnabled()) {
            throw new ServiceException("Python 执行器未启用");
        }
        if (StringUtils.isBlank(properties.getBaseUrl())) {
            throw new ServiceException("未配置 Python 执行器地址");
        }
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(properties.getBaseUrl() + path))
            .timeout(Duration.ofMillis(properties.getTimeoutMs()))
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ServiceException(actionName + "返回异常: HTTP " + response.statusCode());
        }
        Map<String, Object> result = objectMapper.readValue(response.body(), new TypeReference<>() {
        });
        if (!Boolean.TRUE.equals(result.get("success"))) {
            throw new ServiceException(actionName + "失败: " + StringUtils.defaultString(String.valueOf(result.get("message")), "未知错误"));
        }
        return result;
    }
}
