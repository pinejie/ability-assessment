package com.company.ability.security.provider;

import com.company.ability.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 泛微 weaversso OAuth2 适配器。
 *
 * <p>接口契约依据《异构系统接入 OAuth2 认证开发说明》v4.0（2024-12-13）：
 * <ul>
 *   <li>authorize：页面重定向，成功回调携带 {@code ticket}（临时授权码，60 秒一次有效）</li>
 *   <li>accessToken：服务端调用，{@code code} 参数即 ticket（<b>同一个值两个名字</b>）</li>
 *   <li>profile：服务端调用，返回 {@code id}（OA 登录名）等</li>
 *   <li>logout：统一注销，页面重定向</li>
 * </ul>
 *
 * <p>本类是原 {@code OaSsoClient} 的搬迁，逻辑一行未改，只是参数来源从
 * {@code application.yml} 换成每个维度自己的配置行。搬迁时刻意保持方法体一致，
 * 便于逐行对比确认「改造前后行为相同」。</p>
 *
 * <p><b>关于返回的 id 是 loginid 不是工号</b>：泛微 profile 的 {@code id} 就是 OA 登录名，
 * 而实测 164 人的 loginid 与工号不同。所以泛微维度的 {@code sso_match_field}
 * 必须是 {@code loginid}，这条约束在 sql/68 的种子数据里有注释。
 * 如果哪天有人把它改成 workcode，这 164 人会直接登不进来。</p>
 */
@Slf4j
@Component
public class WeaverProvider implements IdentityProvider {

    public static final String TYPE = "weaver";

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

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public WeaverProvider(RestTemplateBuilder builder, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        // 超时沿用原 OaSsoProperties 的默认值（5s / 10s）。
        // 没有做成每维度可配：认证中心是同一个，超时差异没有实际意义，
        // 多一个配置项就多一处能配错的地方。
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofMillis(5000))
                .setReadTimeout(Duration.ofMillis(10000))
                .build();
    }

    @Override
    public String providerType() {
        return TYPE;
    }

    // ==================== 泛微这套协议的默认值 ====================
    //
    // 注：回调参数名 / 请求方式 / 取码字段名这类「三方给的值」已经<b>不再有默认值</b>
    // （2026-11-25）：它们必须在维度配置里逐项填写。这里只保留三个固定路径，
    // 供配置页「用根地址重新填」按钮省一次手工输入 —— 按下去是填进输入框，不是服务端兜底。

    // 泛微三个认证接口的路径是固定的，配了站点根就能一次算出三个地址
    @Override
    public String defaultAuthorizePath() {
        return "/sso/oauth2.0/authorize";
    }

    @Override
    public String defaultTokenPath() {
        return "/sso/oauth2.0/accessToken";
    }

    @Override
    public String defaultProfilePath() {
        return "/sso/oauth2.0/profile";
    }

    /**
     * 泛微的配置页形态：<b>两个</b>地址要填完整 URL，用户信息地址不上页面。
     *
     * <p><b>为什么不再允许「只填根地址」</b>：这两个地址原来可以由根地址推出来，
     * 于是常见的情形是页面上永远是空的、库里也是空的，登录时临时拼 —— 拼对了没人知道，
     * 拼错了（认证中心换了域名、或者某个接口在网关另一侧）要等到有人点登录才 500。
     * 现在两个框必须真的填出字来，配置页显示什么，请求就打到哪里。</p>
     *
     * <p><b>用户信息地址（2026-11-25 起不显示）</b>：泛微这套协议的三个接口路径是固定的，
     * 用户信息地址永远是「根地址 + {@code /sso/oauth2.0/profile}」，让每个维度手填一遍
     * 一个不可能填错、也不该有第二个答案的值，只是多一个能配错的地方。所以它不算本协议的
     * 配置项，由 {@link #urlOf} 按根地址 + {@link #defaultProfilePath()} 得出。
     * 库里那一列仍然保留（存量行有值，请求时也会优先用它），只是页面不再呈现、保存不再校验。</p>
     *
     * <p>根地址框仍然保留：注销地址要用它的 origin，且「用根地址重新填」按一下就能
     * 把两个地址填好，比手打快。</p>
     */
    @Override
    public SsoShape shape() {
        return new SsoShape(
                List.of(
                        SsoField.required("authorizeUrl", "发起认证地址",
                                "浏览器 302 到这里，由泛微判断登录态并出票。"),
                        SsoField.required("tokenUrl", "票据兑换地址",
                                "服务端直连，用票据换 access_token。")),
                true,
                true);
    }

    @Override
    public String buildAuthorizeUrl(ProviderConfig config, String state) {
        return urlOf(config, config.authorizeUrl(), defaultAuthorizePath())
                + "?client_id=" + config.clientId()
                + "&response_type=code"
                + "&redirect_uri=" + encode(config.redirectUri())
                + "&state=" + state;
    }

    @Override
    public String exchange(ProviderConfig config, String ticket) {
        String accessToken = getAccessToken(config, ticket);
        return getProfileIdentity(config, accessToken);
    }

    @Override
    public String buildLogoutUrl(ProviderConfig config) {
        String service = frontendOrigin(config.redirectUri());
        return originOf(config) + "/sso/logout?service=" + encode(service);
    }

    // ==================== 协议步骤 ====================

    /**
     * 用临时授权码（回调 ticket）换取 access_token
     *
     * <p>请求方式与取码字段名都取维度配置，<b>没有默认值</b>（2026-11-25 起）：
     * 这两个值以前留空时会静默变成 GET / access_token，于是配置页上看着空着、
     * 登录也一直正常，直到换了一家只收 POST 的认证方才当场 405。
     * 现在缺了就报明确的一条「维度 X 的兑换请求方式未配置」。</p>
     */
    private String getAccessToken(ProviderConfig config, String ticket) {
        String url = urlOf(config, config.tokenUrl(), defaultTokenPath());
        // 参数放 body 而不是继续拼在 URL 上：POST 时 URL 里的 query 有些认证方会忽略，
        // 且 client_secret 出现在 URL 上会进对方的访问日志。
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", config.clientId());
        params.put("client_secret", config.clientSecret());
        params.put("grant_type", "authorization_code");
        params.put("code", ticket);
        params.put("redirect_uri", config.redirectUri());

        String method = require("兑换请求方式（sso_token_method）", config.tokenMethod(), config.dimensionId());
        JsonNode response = "POST".equalsIgnoreCase(method)
                ? callAndParsePost(url, params, "获取 Access Token")
                : callAndParse(withQuery(url, params), "获取 Access Token");

        if (isSuccess(response)) {
            String field = require("access_token 字段名（sso_access_token_field）",
                    config.accessTokenField(), config.dimensionId());
            String accessToken = response.path(field).asText(null);
            if (accessToken == null || accessToken.isBlank()) {
                throw new BusinessException(401, "认证中心返回的 " + field + " 为空");
            }
            return accessToken;
        }
        throw oaException(response, "获取 Access Token 失败");
    }

    /**
     * 用 access_token 取用户标识。字段名取配置，<b>没有默认值</b> ——
     * 取不到就报错并指明是哪一栏，不替用户猜 {@code id}。
     */
    private String getProfileIdentity(ProviderConfig config, String accessToken) {
        String url = urlOf(config, config.profileUrl(), defaultProfilePath());
        Map<String, String> params = new LinkedHashMap<>();
        params.put("access_token", accessToken);

        String method = require("用户信息请求方式（sso_profile_method）",
                config.profileMethod(), config.dimensionId());
        JsonNode response = "POST".equalsIgnoreCase(method)
                ? callAndParsePost(url, params, "获取用户信息")
                : callAndParse(withQuery(url, params), "获取用户信息");

        if (isSuccess(response)) {
            String field = require("用户标识字段（sso_userinfo_field）",
                    config.userinfoField(), config.dimensionId());
            String identity = response.path(field).asText(null);
            if (identity == null || identity.isBlank()) {
                throw new BusinessException(401, "认证中心返回的用户信息缺少字段 " + field);
            }
            return identity;
        }
        throw oaException(response, "获取用户信息失败");
    }

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

    /** 把参数拼成 query 串（GET 用）；值统一编码 */
    private String withQuery(String url, Map<String, String> params) {
        StringBuilder sb = new StringBuilder(url);
        char sep = url.indexOf('?') >= 0 ? '&' : '?';
        for (Map.Entry<String, String> e : params.entrySet()) {
            sb.append(sep).append(e.getKey()).append('=').append(encode(e.getValue()));
            sep = '&';
        }
        return sb.toString();
    }

    // ==================== 内部方法（与改造前一致） ====================

    private JsonNode callAndParse(String url, String action) {
        return doCall(action, url, () -> restTemplate.getForObject(URI.create(url), String.class));
    }

    /**
     * POST 表单提交。有些认证方的兑换接口只接受 POST，且参数必须在 body 里 ——
     * 拼在 URL 上传过去，对方要么忽略（表现为「临时码缺失」），要么直接 405。
     */
    private JsonNode callAndParsePost(String url, Map<String, String> params, String action) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        params.forEach(form::add);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, headers);
        return doCall(action, url, () -> restTemplate.postForObject(URI.create(url), entity, String.class));
    }

    /**
     * 真正发请求 + 统一异常转换。
     *
     * @param url 仅用于日志脱敏与错误信息，POST 时它不带参数，GET 时带
     */
    private JsonNode doCall(String action, String url, Supplier<String> call) {
        try {
            String body = call.get();
            return parseJson(body, action);
        } catch (HttpStatusCodeException e) {
            // 认证中心对错误返回 HTTP 400 + JSON（文档 1.6 错误码表），须解析后走统一错误转换，
            // 不能当作连接失败
            JsonNode errorNode = tryParseJson(e.getResponseBodyAsString());
            if (errorNode != null) {
                return errorNode;
            }
            log.error("{}：认证中心返回 HTTP {} 且响应体非 JSON", action, e.getStatusCode().value());
            throw new BusinessException(401, action + "失败：认证中心返回 HTTP " + e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            log.error("{}：无法连接认证中心 url={}", action, maskUrl(url), e);
            throw new BusinessException(502, action + "失败：无法连接认证中心");
        } catch (RestClientException e) {
            log.error("{}：调用认证中心接口异常 url={}", action, maskUrl(url), e);
            throw new BusinessException(502, action + "失败：调用认证中心接口异常");
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

    /**
     * 成功判定。
     *
     * <p>两种形态都能认：</p>
     * <ul>
     *   <li><b>泛微形态</b>：响应里同时有 {@code status} 与 {@code code}，
     *       文档约定 {@code status=200 && code="0"} 为成功，错误响应也长这样。</li>
     *   <li><b>标准 OAuth2 / OIDC 形态</b>：没有 {@code error} 字段就是成功
     *       （成功响应形如 {@code {"access_token":"…"}}，失败是 {@code {"error":"invalid_grant"}}）。</li>
     * </ul>
     *
     * <p>之所以按「响应形态」自动判断而不是也做成配置项：这个判断本身不依赖第三方告诉我什么，
     * 而是依赖它返回的结构 —— 两种结构互斥且可辨识，认错了也会在下一步取字段时立刻暴露
     * （取不到 access_token），不会静默放行。多一个配置项只会多一处能配错的地方。</p>
     */
    private boolean isSuccess(JsonNode response) {
        if (response.has("status") && response.has("code")) {
            return response.path("status").asInt(-1) == 200 && "0".equals(response.path("code").asText());
        }
        return !response.has("error");
    }

    /**
     * 错误转成能直接读的中文。
     *
     * <p>泛微按文档 §1.6 的 code 表翻译；其它认证方按 OAuth2 的 {@code error} /
     * {@code error_description} 原样带出 —— 那些值本身就是给人看的（invalid_grant 之类），
     * 硬翻成中文反而不好排查。</p>
     */
    private BusinessException oaException(JsonNode response, String prefix) {
        String code = response.path("code").asText("");
        String msg = response.path("msg").asText("");
        if (!response.has("status") || !response.has("code")) {
            String error = response.path("error").asText("");
            String desc = response.path("error_description").asText("");
            log.warn("OAuth2 返回错误 error={} description={}", error, desc);
            String detail = desc.isBlank() ? error : error + "（" + desc + "）";
            return new BusinessException(401, prefix + "：" + (detail.isBlank() ? "未知错误" : detail));
        }
        String oaMsg = OA_ERROR_MESSAGES.getOrDefault(code, msg.isBlank() ? "未知错误" : msg);
        log.warn("泛微 OAuth2 返回错误 code={} msg={}", code, msg);
        return new BusinessException(401, prefix + "：" + oaMsg);
    }

    /**
     * 求实际请求的地址：配置里存的完整 URL 优先，其次根地址 + 固定路径。
     *
     * <p>「根地址 + 路径」这条只是为了兼容老库里没展开的行（种子/脚本仍可能只写根地址），
     * 配置页上三个地址现在是真填写项，正常不会走到第二条。两条都不通时报一条能照着改的错，
     * 而不是拿 null 去拼字符串拼出 {@code "null/sso/..."}。</p>
     *
     * <p>展开逻辑复用 {@link #resolveUrl}，与配置页「用根地址重新填」用的是同一份实现 ——
     * 页面上看到什么地址，请求就打到哪里。</p>
     */
    private String urlOf(ProviderConfig config, String explicitUrl, String path) {
        String resolved = resolveUrl(explicitUrl, config.baseUrl(), path);
        if (resolved != null) {
            return resolved;
        }
        throw new BusinessException(500,
                "维度 " + config.dimensionId() + " 的认证中心接口地址未配置，"
                        + "请到「维度管理 → 集成配置」里填完整地址（或填根地址 http://host:port）");
    }

    /** 认证中心 origin（用于拼注销地址） */
    private String originOf(ProviderConfig config) {
        String any = config.authorizeUrl() != null && !config.authorizeUrl().isBlank()
                ? config.authorizeUrl()
                : config.baseUrl();
        if (any == null || any.isBlank()) {
            throw new BusinessException(500,
                    "维度 " + config.dimensionId() + " 的泛微认证中心地址未配置，无法拼注销地址");
        }
        // baseUrl 可能只到 origin（没有路径），显式地址可能带路径 —— 都只取 scheme://authority
        URI uri = URI.create(any);
        return uri.getScheme() + "://" + uri.getAuthority();
    }

    private String frontendOrigin(String redirectUri) {
        try {
            URI uri = URI.create(redirectUri);
            return uri.getScheme() + "://" + uri.getAuthority();
        } catch (Exception e) {
            return redirectUri;
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
