package org.dromara.ticket.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.ticket.config.TicketStalwartProperties;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TicketStalwartClient {

    private final TicketStalwartProperties properties;
    private final ObjectMapper objectMapper;

    public CreatePrincipalResult createMailboxAccount(String username, String email) {
        validateConfig();
        try {
            String requestBody = objectMapper.writeValueAsString(Map.ofEntries(
                Map.entry("type", "individual"),
                Map.entry("name", username),
                Map.entry("emails", List.of(email)),
                Map.entry("secrets", List.of(email)),
                Map.entry("quota", 0),
                Map.entry("description", ""),
                Map.entry("memberOf", List.of()),
                Map.entry("roles", List.of("user")),
                Map.entry("lists", List.of()),
                Map.entry("urls", List.of()),
                Map.entry("members", List.of()),
                Map.entry("enabledPermissions", List.of()),
                Map.entry("disabledPermissions", List.of()),
                Map.entry("externalMembers", List.of())
            ));

            HttpURLConnection connection = openConnection(URI.create(trimRightSlash(properties.getBaseUrl()) + "/api/principal"));
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(properties.getConnectTimeoutMs());
            connection.setReadTimeout(properties.getReadTimeoutMs());
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + properties.getApiKey());
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");

            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int statusCode = connection.getResponseCode();
            String responseBody = readResponseBody(connection, statusCode);
            if (statusCode < 200 || statusCode >= 300) {
                throw new ServiceException("Stalwart 创建邮箱失败：" + statusCode + " " + responseBody);
            }

            CreatePrincipalResult result = new CreatePrincipalResult();
            result.setRawResponse(responseBody);
            if (StrUtil.isNotBlank(responseBody)) {
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode errorNode = root.get("error");
                if (errorNode != null && !errorNode.isNull()) {
                    String details = root.path("details").asText("");
                    String reason = root.path("reason").asText("");
                    String message = StrUtil.join(" ",
                        "Stalwart 创建邮箱失败：",
                        errorNode.asText(),
                        details,
                        reason);
                    throw new ServiceException(StrUtil.trim(message));
                }
                JsonNode dataNode = root.get("data");
                if (dataNode != null && !dataNode.isNull()) {
                    result.setPrincipalId(dataNode.asText());
                }
            }
            if (StrUtil.isBlank(result.getPrincipalId())) {
                throw new ServiceException("Stalwart 创建邮箱失败：响应缺少 principal id");
            }
            return result;
        } catch (ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ServiceException("Stalwart 创建邮箱异常：" + ex.getMessage());
        }
    }

    private void validateConfig() {
        if (!properties.isEnabled()) {
            throw new ServiceException("Stalwart 管理 API 未启用");
        }
        if (StrUtil.isBlank(properties.getBaseUrl())) {
            throw new ServiceException("Stalwart base-url 未配置");
        }
        if (StrUtil.isBlank(properties.getApiKey())) {
            throw new ServiceException("Stalwart api-key 未配置");
        }
    }

    private HttpURLConnection openConnection(URI uri) throws Exception {
        URL url = uri.toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        if (properties.isSslTrustAll() && connection instanceof HttpsURLConnection httpsConnection) {
            httpsConnection.setSSLSocketFactory(trustAllSslContext().getSocketFactory());
            httpsConnection.setHostnameVerifier((hostname, session) -> true);
        }
        return connection;
    }

    private String readResponseBody(HttpURLConnection connection, int statusCode) throws Exception {
        InputStream inputStream = statusCode >= 200 && statusCode < 300
            ? connection.getInputStream()
            : connection.getErrorStream();
        if (inputStream == null) {
            return "";
        }
        try (inputStream) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private SSLContext trustAllSslContext() throws Exception {
        TrustManager[] trustManagers = new TrustManager[]{
            new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }
        };
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagers, new SecureRandom());
        return sslContext;
    }

    private String trimRightSlash(String value) {
        return StrUtil.removeSuffix(value, "/");
    }

    @Data
    public static class CreatePrincipalResult {
        private String principalId;
        private String rawResponse;
    }
}
