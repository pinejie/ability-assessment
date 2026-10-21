package com.company.ability.client;

import com.company.ability.config.OaSsoProperties;
import com.company.ability.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpStatusCodeException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 泛微 weaversso OAuth2 接口客户端。
 * 接口契约依据《异构系统接入 OAuth2 认证开发说明》v4.0（2024-12-13）：
 * - authorize：页面重定向（浏览器），成功回调携带 ticket 参数（即临时授权码，60 秒一次有效）
 * - accessToken：服务端调用，code=ticket
 * - profile：服务端调用，返回 id（OA 登录名）等
 * - logout：统一注销（页面重定向）
 */
@Slf4j
@Component
public class OaSsoClient {

    /** 认证中心错误代码表（文档 1.6） */
    private static final Map<String, String> OA_ERROR_MESSAGES = Map.ofEntries(
            Map.entry("1001", "请求方法错误"),
            Map.entry("1002", "参数 client_id 缺失"),
            Map.entry("1003", "参数 redirect_uri 缺失"),
            Map.entry("1004", "参数 client_id 非法（应用未在认证中心注册）"),
            Map.entry("1005", "参数 redirect_uri 非法（回调地址与注册信息不符）"),
            Map.entry("1006", "认证失败"),
            Map.entry("1007", "参数 client_secret 缺失"),
            Map.entry("1008", "临时码缺失"),
            Map.entry("1009", "参数 client_secret 非法（密钥错误）"),
            Map.entry("1010", "临时码不正确或已过期（60 秒内一次性有效）"),
            Map.entry("1011", "缺少参数 access_token"),
            Map.entry("1012", "参数 access_token 不正确或已过期")
    );

    private final OaSsoProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public OaSsoClient(OaSsoProperties properties, RestTemplateBuilder builder, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofMillis(properties.getConnectTimeout()))
                .setReadTimeout(Duration.ofMillis(properties.getReadTimeout()))
                .build();
    }

    /**
     * 拼装获取授权码接口地址（供浏览器 302 跳转）
     */
    public String buildAuthorizeUrl(String state) {
        return properties.getBaseUrl() + "/sso/oauth2.0/authorize"
                + "?client_id=" + properties.getClientId()
                + "&response_type=code"
                + "&redirect_uri=" + encode(properties.getRedirectUri())
                + "&state=" + state;
    }

    /**
     * 拼装统一注销接口地址（service 为注销后的回调地址，取前端站点根地址）
     */
    public String buildLogoutUrl() {
        String service = frontendOrigin();
        return properties.getBaseUrl() + "/sso/logout?service=" + encode(service);
    }

    /**
     * 用临时授权码（回调 ticket）换取 access_token
     *
     * @return access_token 字符串
     */
    public String getAccessToken(String ticket) {
        String url = properties.getBaseUrl() + "/sso/oauth2.0/accessToken"
                + "?client_id=" + properties.getClientId()
                + "&client_secret=" + encode(properties.getClientSecret())
                + "&grant_type=authorization_code"
                + "&code=" + encode(ticket)
                + "&redirect_uri=" + encode(properties.getRedirectUri());
        JsonNode response = callAndParse(url, "获取 Access Token");
        if (isSuccess(response)) {
            String accessToken = response.path("access_token").asText(null);
            if (accessToken == null || accessToken.isBlank()) {
                throw new BusinessException(401, "泛微返回的 access_token 为空");
            }
            return accessToken;
        }
        throw oaException(response, "获取 Access Token 失败");
    }

    /**
     * 用 access_token 获取用户信息，返回 OA 登录名（profile 顶层 id 字段）
     */
    public String getProfileLoginid(String accessToken) {
        String url = properties.getBaseUrl() + "/sso/oauth2.0/profile?access_token=" + encode(accessToken);
        JsonNode response = callAndParse(url, "获取用户信息");
        if (isSuccess(response)) {
            String loginid = response.path("id").asText(null);
            if (loginid == null || loginid.isBlank()) {
                throw new BusinessException(401, "泛微返回的用户信息缺少登录名");
            }
            return loginid;
        }
        throw oaException(response, "获取用户信息失败");
    }

    // ==================== 内部方法 ====================

    private JsonNode callAndParse(String url, String action) {
        try {
            String body = restTemplate.getForObject(URI.create(url), String.class);
            return parseJson(body, action);
        } catch (HttpStatusCodeException e) {
            // 认证中心对错误返回 HTTP 400 + JSON（文档 1.6 错误码表），须解析后走统一错误转换，
            // 不能当作连接失败
            JsonNode errorNode = tryParseJson(e.getResponseBodyAsString());
            if (errorNode != null) {
                return errorNode;
            }
            log.error("{}：泛微返回 HTTP {} 且响应体非 JSON", action, e.getStatusCode().value());
            throw new BusinessException(401, action + "失败：泛微认证中心返回 HTTP " + e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            log.error("{}：无法连接泛微认证中心 url={}", action, maskUrl(url), e);
            throw new BusinessException(502, action + "失败：无法连接泛微认证中心");
        } catch (RestClientException e) {
            log.error("{}：调用泛微接口异常 url={}", action, maskUrl(url), e);
            throw new BusinessException(502, action + "失败：调用泛微认证中心异常");
        }
    }

    private JsonNode parseJson(String body, String action) {
        JsonNode node = tryParseJson(body);
        if (node == null) {
            throw new BusinessException(502, action + "失败：泛微响应格式异常");
        }
        return node;
    }

    private JsonNode tryParseJson(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    /** 文档约定：status=200 且 code="0" 为成功 */
    private boolean isSuccess(JsonNode response) {
        return response.path("status").asInt(-1) == 200 && "0".equals(response.path("code").asText());
    }

    private BusinessException oaException(JsonNode response, String prefix) {
        String code = response.path("code").asText("");
        String msg = response.path("msg").asText("");
        String oaMsg = OA_ERROR_MESSAGES.getOrDefault(code, msg.isBlank() ? "未知错误" : msg);
        log.warn("泛微 OAuth2 返回错误 code={} msg={}", code, msg);
        return new BusinessException(401, prefix + "：" + oaMsg);
    }

    private String frontendOrigin() {
        try {
            URI uri = URI.create(properties.getRedirectUri());
            return uri.getScheme() + "://" + uri.getAuthority();
        } catch (Exception e) {
            return properties.getRedirectUri();
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** 日志脱敏：抹掉密钥与票据 */
    private String maskUrl(String url) {
        return url.replaceAll("client_secret=[^&]*", "client_secret=***")
                  .replaceAll("code=[^&]*", "code=***")
                  .replaceAll("access_token=[^&]*", "access_token=***");
    }
}
