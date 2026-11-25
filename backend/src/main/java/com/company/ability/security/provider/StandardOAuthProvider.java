package com.company.ability.security.provider;

import com.company.ability.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 标准 OAuth2 / OIDC 授权码模式适配器。
 *
 * <p><b>与泛微那个的区别</b>：泛微是一套「路径固定、参数名有点怪」的 OAuth2 变种
 * （接口路径永远是 {@code /sso/oauth2.0/*}）。本类面对的是「照 RFC 6749 写的」那一类系统 ——
 * 钉钉、企业微信网页授权、Keycloak、Azure AD、GitLab、自建 Spring Security OAuth2 等等，
 * 流程完全一样，差异只在网址、client 凭证、以及对方返回的身份字段名。</p>
 *
 * <p><b>所以接这类系统不写代码，靠配置</b>：三个地址、请求方式、字段名、参数名全部读
 * {@code uf_assess_dimension_integration}。接第二家、第三家标准 OAuth2 系统时，
 * 加一行维度配置即可，不需要新增 {@link IdentityProvider} 实现。</p>
 *
 * <p><b>授权码模式三步</b>（RFC 6749 §4.1）：
 * <ol>
 *   <li>浏览器 302 到 authorize，带 {@code client_id / response_type=code / redirect_uri / state}</li>
 *   <li>回调带回授权码，服务端拿它 + client 凭证去 token 接口换 {@code access_token}</li>
 *   <li>用 {@code Authorization: Bearer} 调 profile 接口，取出身份字段</li>
 * </ol></p>
 *
 * <p><b>client 凭证只走表单参数</b>：RFC 6749 §2.3.1 也允许 HTTP Basic，但要求先把
 * client_id/secret 做 URL 编码再 base64，各家实现对这两种的宽容度差异很大。
 * 参数方式是最普遍的实现，遇到只认 Basic 的对方再扩展。</p>
 *
 * <p><b>身份值取普通 JSON 接口的字段，不解析 id_token</b>：标准 OIDC 的身份在 id_token
 * （JWT）里，用它必须先验签；而各家网页授权大多另给一个明文 JSON 接口
 * （钉钉 {@code user/get}、企微 {@code user/getuserinfo}）直接返回 {@code userid} 这类值。
 * 本类走后者 —— 不验签，因此只适合已经信任的内网认证中心。</p>
 */
@Slf4j
@Component
public class StandardOAuthProvider implements IdentityProvider {

    public static final String TYPE = "oauth2";

    /**
     * 常见 OAuth2 错误码 → 给人看的中文（RFC 6749 §5.2）。
     *
     * <p>翻译成中文是因为这些码会显示在登录失败页上：用户拿着 {@code invalid_grant}
     * 去找管理员，双方都不知道该改什么。不在表里的保留原码 + {@code error_description} ——
     * 那本来就是给人看的。</p>
     */
    private static final Map<String, String> ERROR_MESSAGES = Map.ofEntries(
            Map.entry("invalid_request", "认证中心认为请求参数不合法，请检查地址与参数配置"),
            Map.entry("invalid_client", "client_id 或 client_secret 不正确，请检查凭证配置"),
            Map.entry("invalid_grant", "授权码不正确或已过期，请重新从入口登录"),
            Map.entry("unauthorized_client", "该应用未被允许使用授权码模式，请联系对方管理员"),
            Map.entry("unsupported_grant_type", "认证中心不支持授权码模式，请联系对方管理员"),
            Map.entry("invalid_scope", "请求的权限范围不被支持，请联系对方管理员"),
            Map.entry("access_denied", "用户在认证中心取消了授权"),
            Map.entry("server_error", "认证中心内部错误，请稍后重试"),
            Map.entry("temporarily_unavailable", "认证中心暂时不可用，请稍后重试")
    );

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public StandardOAuthProvider(RestTemplateBuilder builder, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        // 与另外两个适配器同样的超时：接的是同一批内网认证中心，
        // 「连不上就尽快报错」这条没有区别
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofMillis(5000))
                .setReadTimeout(Duration.ofMillis(10000))
                .build();
    }

    @Override
    public String providerType() {
        return TYPE;
    }

    // ==================== 本协议的形态 ====================
    //
    // 不覆盖 shape()：{@link IdentityProvider#shape()} 的默认实现描述的就是这一形态 ——
    // 三个接口全手填、全部必填、没有「根地址 + 固定路径」那套规律。那份默认实现本来就是
    // 照着标准 OAuth2 写的（泛微那种路径固定的才需要覆盖）。标准 OAuth2 的 authorize /
    // token / userinfo 地址由对方的应用注册页决定，各自可能在不同域名或路径下，猜不出来，
    // 所以页面不显示「认证中心根地址」框 —— 那框留着只会让人以为填了能自动推导。

    // ==================== 三步 ====================

    /**
     * 拼授权地址：{@code client_id / response_type=code / redirect_uri / state}。
     *
     * <p><b>不带 scope</b>：有的认证中心会校验 scope 是否在应用注册时声明的列表里，
     * 多传一个没登记的直接返回 {@code invalid_scope}。要权限范围的那些接口
     * （如 OIDC 的 {@code openid profile}）在真有需要时再做成配置项。</p>
     */
    @Override
    public String buildAuthorizeUrl(ProviderConfig config, String state) {
        String base = requireUrl(config, config.authorizeUrl(), "发起认证地址（sso_authorize_url）");
        StringBuilder sb = new StringBuilder(base);
        char sep = base.indexOf('?') >= 0 ? '&' : '?';
        sb.append(sep).append("client_id=").append(encode(config.clientId()));
        // response_type 不能省：标准 OAuth2 认证中心靠它决定「回跳时给 code 还是 token」
        sb.append('&').append("response_type=code");
        sb.append('&').append("redirect_uri=").append(encode(config.redirectUri()));
        if (state != null && !state.isBlank()) {
            // state 对方会原样带回，本端靠它恢复回跳地址与维度号，不能省
            sb.append('&').append("state=").append(encode(state));
        }
        return sb.toString();
    }

    /**
     * 换身份：先「授权码 → access_token」，再「access_token → 身份值」。
     *
     * <p>令牌请求的参数集按 RFC 6749 §4.1.3：{@code grant_type / 授权码 /
     * redirect_uri / client_id / client_secret}。{@code redirect_uri} 必须与授权时
     * 用的<b>逐字符一致</b>，不少认证中心拿它做二次校验，缺了会返回 {@code invalid_grant} ——
     * 报错里看不出是这个原因，所以一律带上，值就是配置里那一个回调地址。</p>
     *
     * <p>授权码的参数名用配置的「回调票据参数名」而不是写死 {@code code}：
     * 标准 OAuth2 里它叫 {@code code}，但半标准系统可能叫别的；回调里叫什么、
     * 兑换时就用什么，这两处本就是同一个值，分开配只会多一处能配错的地方。</p>
     */
    @Override
    public String exchange(ProviderConfig config, String ticket) {
        if (ticket == null || ticket.isBlank()) {
            throw new BusinessException(401, "回调没有带回授权码，请重新从入口登录");
        }
        String url = requireUrl(config, config.tokenUrl(), "票据兑换地址（sso_token_url）");
        String codeParam = require("回调票据参数名（sso_callback_param）",
                config.callbackParam(), config.dimensionId());

        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "authorization_code");
        params.put(codeParam, ticket);
        params.put("client_id", config.clientId());
        params.put("client_secret", config.clientSecret());
        params.put("redirect_uri", config.redirectUri());

        String tokenMethod = require("兑换请求方式（sso_token_method）",
                config.tokenMethod(), config.dimensionId());
        JsonNode tokenResponse = call(url, method(tokenMethod), params, null, "换取 access_token");
        requireSuccess(tokenResponse, "换取 access_token");

        String tokenField = require("access_token 字段名（sso_access_token_field）",
                config.accessTokenField(), config.dimensionId());
        String accessToken = tokenResponse.path(tokenField).asText(null);
        if (accessToken == null || accessToken.isBlank()) {
            // 报错里列出实际字段名：对方叫 accessToken 时，这一句能让人立刻知道改哪一栏
            throw new BusinessException(502, "认证中心返回的内容里没有 " + tokenField
                    + "，实际字段：" + fieldNames(tokenResponse) + "，请检查「access_token 字段名」配置");
        }
        return fetchIdentity(config, accessToken);
    }

    /** 第二步：access_token 换身份值 */
    private String fetchIdentity(ProviderConfig config, String accessToken) {
        String url = requireUrl(config, config.profileUrl(), "用户信息地址（sso_profile_url）");
        String profileMethod = require("用户信息请求方式（sso_profile_method）",
                config.profileMethod(), config.dimensionId());

        // 走 Bearer 头而不是把 access_token 拼进 query：query 会进双方的访问日志，
        // 日志里不该出现能直接当凭证用的东西
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        JsonNode response = call(url, method(profileMethod), null, headers, "获取用户信息");
        requireSuccess(response, "获取用户信息");

        String field = require("用户标识字段（sso_userinfo_field）",
                config.userinfoField(), config.dimensionId());
        String identity = response.path(field).asText(null);
        if (identity == null || identity.isBlank()) {
            throw new BusinessException(401, "认证中心返回的用户信息里没有字段 " + field
                    + "，实际字段：" + fieldNames(response) + "，请检查「用户标识字段」配置");
        }
        // 前导零是身份值的一部分（00123 与 123 是两个不同的人），只去前后空白
        return identity.trim();
    }

    // ==================== 发请求 ====================

    /**
     * 发一次请求并返回解析后的 JSON。
     *
     * <p>4xx/5xx 不在这里抛：对方的错误说明（{@code error_description} / {@code message}）
     * 在响应体里，那是最有用的一句话，交给 {@link #requireSuccess} 翻译成人话。
     * 只有「连不上」「响应不是 JSON」这种本端无法继续的情况才从这里抛。</p>
     *
     * @param params 非空且为 POST 时按表单放进 body，为 GET 时拼进 query
     */
    private JsonNode call(String url, HttpMethod method, Map<String, String> params,
                          HttpHeaders extraHeaders, String action) {
        HttpHeaders headers = extraHeaders == null ? new HttpHeaders() : extraHeaders;
        if (method == HttpMethod.POST && params != null && !params.isEmpty()) {
            // 表单而不是 JSON：client_id/client_secret/code 是标准的表单三元组，
            // 表单在「只认其中一种」的实现上兼容性更宽
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            params.forEach(form::add);
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            return send(action, url, () -> restTemplate.exchange(URI.create(url), HttpMethod.POST,
                    new HttpEntity<MultiValueMap<String, String>>(form, headers), String.class));
        }
        String target = (method == HttpMethod.GET && params != null && !params.isEmpty())
                ? withQuery(url, params)
                : url;
        return send(action, target, () -> restTemplate.exchange(URI.create(target), method,
                new HttpEntity<>(headers), String.class));
    }

    /** @param url 仅用于日志脱敏与错误信息，POST 时它不带参数 */
    private JsonNode send(String action, String url, Supplier<ResponseEntity<String>> send) {
        try {
            ResponseEntity<String> resp = send.get();
            JsonNode node = tryParseJson(resp.getBody());
            if (node == null) {
                log.error("{}：对方返回 HTTP {} 且响应体不是 JSON", action, resp.getStatusCode().value());
                throw new BusinessException(502, action + "失败：对方返回的内容无法解析（HTTP "
                        + resp.getStatusCode().value() + "）");
            }
            return node;
        } catch (HttpStatusCodeException e) {
            // 4xx/5xx：响应体里是 OAuth2 的错误说明，翻成人话再抛
            JsonNode body = tryParseJson(e.getResponseBodyAsString());
            throw oauth2Error(body, action, e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            log.error("{}：无法连接认证中心 url={}", action, maskUrl(url), e);
            throw new BusinessException(502, action + "失败：无法连接认证中心");
        } catch (RestClientException e) {
            log.error("{}：调用认证中心接口异常 url={}", action, maskUrl(url), e);
            throw new BusinessException(502, action + "失败：调用认证中心接口异常");
        }
    }

    /**
     * 判定成功。
     *
     * <p>标准 OAuth2 的成功响应没有 {@code error} 字段（RFC 6749 §5.1），
     * 失败才有（§5.2）。这里只认这一条 —— 不像泛微那样去认 {@code status/code}，
     * 那套是泛微自己的约定，标准认证中心不会返回。</p>
     */
    private void requireSuccess(JsonNode response, String action) {
        if (response.has("error")) {
            throw oauth2Error(response, action, 200);
        }
    }

    /**
     * 把一次失败的回答翻成能直接给人看的一句话。
     *
     * <p>优先级：认识的 {@code error} 码 > {@code error_description} > 原样带出码 >
     * 只有 HTTP 状态。最后一档故意不写「未知错误」—— 那等于把排查线索丢干净了。</p>
     *
     * <p><b>502 与 401 的分界</b>：对方 5xx 或压根不是 JSON，说明它自己出了问题、
     * 与我方配置无关，报 502；其余一律 401（登录失败），让用户去重新登录而不是去找管理员。</p>
     */
    private BusinessException oauth2Error(JsonNode body, String action, int httpStatus) {
        String error = body == null ? "" : body.path("error").asText("");
        String description = body == null ? "" : body.path("error_description").asText("");
        String translated = ERROR_MESSAGES.get(error);
        String detail;
        if (translated != null) {
            detail = translated;
        } else if (!description.isBlank()) {
            detail = error.isBlank() ? description : error + "（" + description + "）";
        } else if (!error.isBlank()) {
            detail = error;
        } else {
            detail = "对方返回 HTTP " + httpStatus + (body == null ? "（响应不是 JSON）" : "");
        }
        log.warn("OAuth2{}失败 error={} description={} http={}", action, error, description, httpStatus);
        boolean upstreamBroken = httpStatus >= 500 || body == null;
        return new BusinessException(upstreamBroken ? 502 : 401, action + "失败：" + detail);
    }

    // ==================== 工具 ====================

    /**
     * 必填的运行时配置：空就报一条能直接照着改的错。
     *
     * <p>报错里带维度号和字段名 —— 同一个应用里有多个维度，只说「未配置」等于让人挨个翻。</p>
     */
    private static String require(String what, String value, Long dimensionId) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(500,
                    "维度 " + dimensionId + " 的「" + what + "」未配置，请到「维度管理 → 集成配置」里填写");
        }
        return value.trim();
    }

    /** 取地址；没配就报可操作的错，而不是拿 null 拼出 "null?client_id=..." */
    private static String requireUrl(ProviderConfig config, String explicitUrl, String what) {
        if (explicitUrl == null || explicitUrl.isBlank()) {
            throw new BusinessException(500,
                    "维度 " + config.dimensionId() + " 未填写「" + what
                            + "」，请到「维度管理 → 集成配置」里填完整地址");
        }
        return explicitUrl.trim();
    }

    /** GET / POST 转成 HttpMethod；保存时已受 Pattern 约束，这里只是兜底 */
    private static HttpMethod method(String name) {
        return "POST".equalsIgnoreCase(name.trim()) ? HttpMethod.POST : HttpMethod.GET;
    }

    private String withQuery(String url, Map<String, String> params) {
        StringBuilder sb = new StringBuilder(url);
        char sep = url.indexOf('?') >= 0 ? '&' : '?';
        for (Map.Entry<String, String> e : params.entrySet()) {
            sb.append(sep).append(e.getKey()).append('=').append(encode(e.getValue()));
            sep = '&';
        }
        return sb.toString();
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

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** 日志脱敏：密钥、授权码、凭证绝不能被写进日志 */
    private String maskUrl(String url) {
        return url.replaceAll("client_secret=[^&]*", "client_secret=***")
                  .replaceAll("code=[^&]*", "code=***")
                  .replaceAll("ticket=[^&]*", "ticket=***")
                  .replaceAll("access_token=[^&]*", "access_token=***");
    }

    /** 取不到字段时把响应的字段名列出来，方便一眼看出对方用的是别的名字 */
    private String fieldNames(JsonNode node) {
        if (node == null || !node.isObject()) {
            return "（非 JSON 对象）";
        }
        StringBuilder sb = new StringBuilder();
        node.fieldNames().forEachRemaining(n -> sb.append(sb.length() == 0 ? "" : ", ").append(n));
        return sb.toString();
    }

    // ==================== 注销 ====================

    /**
     * 标准 OAuth2 没有「统一注销地址」这个概念（RFC 7009 的 revocation 是撤销令牌，
     * 不是让浏览器退出）。OIDC 有 {@code end_session_endpoint}，但地址由对方的
     * discovery 文档给出、各家不同，做成配置项才有意义 —— 真有需要时再加。
     * 返回 null 表示本端只清自己的会话，不跳转。
     */
    @Override
    public String buildLogoutUrl(ProviderConfig config) {
        return null;
    }
}
