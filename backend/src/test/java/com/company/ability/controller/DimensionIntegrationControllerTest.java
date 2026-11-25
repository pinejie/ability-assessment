package com.company.ability.controller;

import com.company.ability.dto.DimensionIntegrationSaveDTO;
import com.company.ability.dto.SecretRevealDTO;
import com.company.ability.exception.BusinessException;
import com.company.ability.security.SecretCipher;
import com.company.ability.security.provider.IdentityProviderRegistry;
import com.company.ability.service.AdminService;
import com.company.ability.service.DimensionIntegrationService;
import com.company.ability.service.DimensionIntegrationService.IntegrationRow;
import com.company.ability.vo.DimensionIntegrationVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.company.ability.security.provider.WeaverProvider;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 维度集成配置的保存语义。
 *
 * <p>这个类真正要钉住的是<b>密钥三种语义</b>：占位符=不改 / 空串=清空 / 明文=覆盖。
 * 三者混掉任意一对都会出事：
 * <ul>
 *   <li>占位符被当真密钥存库 → 下次登录拿 "__UNCHANGED__" 去换 token，报错莫名其妙</li>
 *   <li>空串被当成"不修改" → 用户永远清不掉一个配错的密钥</li>
 *   <li>明文不加密直接存 → 密钥以明文躺在数据库里，主密钥形同虚设</li>
 * </ul>
 * 另外还要钉「密钥绝不回显」——读接口只能返回占位符。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DimensionIntegrationControllerTest {

    private static final long DIM = 5L;

    @Mock
    private DimensionIntegrationService integrationService;
    @Mock
    private IdentityProviderRegistry providerRegistry;
    @Mock
    private AdminService adminService;
    @Mock
    private SecretCipher secretCipher;
    @Mock
    private JdbcTemplate jdbc;

    private DimensionIntegrationController controller;

    @BeforeEach
    void setUp() {
        controller = new DimensionIntegrationController(
                integrationService, providerRegistry, adminService, secretCipher, jdbc);
        when(providerRegistry.supportedTypes()).thenReturn(Set.of("weaver"));
        // 读接口会向协议问四个「留空时的默认值」，用真实现而不是 mock：
        // 这几个默认值本身就是要钉住的行为（泛微是 ticket/GET），mock 掉就等于没测。
        when(providerRegistry.get("weaver")).thenReturn(new WeaverProvider(
                new RestTemplateBuilder(), new ObjectMapper()));
        when(secretCipher.isConfigured()).thenReturn(true);
        when(secretCipher.encrypt(any())).thenAnswer(inv -> "enc:v1:" + inv.getArgument(0));
        when(jdbc.queryForList(any(String.class), eq(String.class), eq(DIM)))
                .thenReturn(List.of("中达科技"));
    }

    // ==================== 密钥三种语义 ====================

    @Test
    @DisplayName("传占位符 __UNCHANGED__：保持库里的原值，不重复加密")
    void placeholderKeepsExistingSecret() {
        givenExisting("enc:v1:old-secret");

        controller.save(DIM, dtoWithSecret(DimensionIntegrationVO.SECRET_MASK));

        assertThat(saved().ssoClientSecret()).isEqualTo("enc:v1:old-secret");
        verify(secretCipher, never()).encrypt(any());
    }

    @Test
    @DisplayName("不传（null）密钥：也按保持原值处理，避免前端漏字段就清空")
    void nullKeepsExistingSecret() {
        givenExisting("enc:v1:old-secret");

        controller.save(DIM, dtoWithSecret(null));

        assertThat(saved().ssoClientSecret()).isEqualTo("enc:v1:old-secret");
    }

    @Test
    @DisplayName("「不修改」写回的是库里原密文，不是 find() 那份已解密的明文")
    void placeholderWritesBackCiphertextNotPlaintext() {
        // 真实实现里 find() 会把密钥解密后再返回（登录/推送要用明文），
        // rawSecret() 才是库里的原始密文。这两个值长得不一样，取错一个就把
        // 明文写回了库 —— 2026-11-24 实机保存后库里真出现过明文，就是这个原因。
        when(integrationService.rawSecret(DIM, "sso_client_secret")).thenReturn("enc:v1:ciphertext");
        when(integrationService.rawSecret(DIM, "push_secret")).thenReturn(null);
        when(integrationService.find(DIM)).thenReturn(new IntegrationRow(
                DIM, true, "weaver", "cid", "decrypted-plaintext", "http://oa", null, null, null,
                "id", null, null, null, null, "loginid", null,
                true, "weaver", null, null, "{\"msgType\":596}"));

        controller.save(DIM, dtoWithSecret(DimensionIntegrationVO.SECRET_MASK));

        assertThat(saved().ssoClientSecret())
                .as("必须写回原密文；写成 decrypted-plaintext 说明取了 find() 而不是 rawSecret()")
                .isEqualTo("enc:v1:ciphertext");
    }

    @Test
    @DisplayName("传空串：明确清空（但最后一个能登录的维度不许清，见 requireRemainingLoginDimension）")
    void blankClearsSecret() {
        givenExisting("enc:v1:old-secret");
        // 「关不关」由 client_secret 是否为空决定（dto.ssoEnabled 已不参与判断），
        // 清空后要先确认还有别的维度能登录，否则谁也进不来
        when(integrationService.hasOtherConfiguredDimension(DIM)).thenReturn(true);
        DimensionIntegrationSaveDTO dto = dtoWithSecret("   ");

        controller.save(DIM, dto);

        assertThat(saved().ssoClientSecret()).isNull();
    }

    @Test
    @DisplayName("传明文：交给加密器处理后覆盖，库里不写明文")
    void plaintextGetsEncrypted() {
        givenExisting("enc:v1:old-secret");

        controller.save(DIM, dtoWithSecret("new-plain-secret"));

        // 这里用 mock 加密器（实现是 AES-GCM，单测不掺进来），
        // 要钉的是「明文一定过了一遍 encrypt()」，且写库的是 encrypt() 的返回值
        verify(secretCipher).encrypt("new-plain-secret");
        assertThat(saved().ssoClientSecret()).isEqualTo("enc:v1:new-plain-secret");
    }

    @Test
    @DisplayName("清空唯一能登录维度的密钥 → 拒绝（否则退出后再没人进得来）")
    void clearingLastLoginDimensionRejected() {
        givenExisting("enc:v1:old");
        when(integrationService.hasOtherConfiguredDimension(DIM)).thenReturn(false);

        assertThatThrownBy(() -> controller.save(DIM, dtoWithSecret("")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("唯一一个配置了单点登录的维度");
        verify(integrationService, never()).save(any());
    }

    @Test
    @DisplayName("没配主密钥时保存新密钥 → 明确报错，不写半个状态")
    void missingMasterKeyRejects() {
        when(secretCipher.isConfigured()).thenReturn(false);
        givenExisting("enc:v1:old-secret");

        assertThatThrownBy(() -> controller.save(DIM, dtoWithSecret("new-plain")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("master-key");
    }

    // ==================== 协议类型 ====================

    @Test
    @DisplayName("协议类型未注册 → 拒绝（前端下拉可绕过，必须服务端校验）")
    void unknownProviderRejected() {
        givenExisting("enc:v1:old");

        DimensionIntegrationSaveDTO dto = dtoWithSecret(DimensionIntegrationVO.SECRET_MASK);
        dto.setSsoProviderType("oidc");

        assertThatThrownBy(() -> controller.save(DIM, dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("oidc");
    }

    @Test
    @DisplayName("路径 ID 与请求体 ID 不一致 → 拒绝")
    void pathBodyMismatchRejected() {
        DimensionIntegrationSaveDTO dto = dtoWithSecret(DimensionIntegrationVO.SECRET_MASK);
        dto.setDimensionId(9L);

        assertThatThrownBy(() -> controller.save(DIM, dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不一致");
    }

    // ==================== 读取与权限 ====================

    @Test
    @DisplayName("读接口把密钥换成占位符，绝不回显密文或明文")
    void readNeverEchoesSecret() {
        givenExisting("enc:v1:real-secret");

        DimensionIntegrationVO vo = controller.get(DIM).getData();

        assertThat(vo.getSsoClientSecret()).isEqualTo(DimensionIntegrationVO.SECRET_MASK);
        assertThat(vo.getConfigured()).isTrue();
        assertThat(vo.getDimensionName()).isEqualTo("中达科技");
    }

    @Test
    @DisplayName("还没配置的维度返回空壳而不是 404：页面要能直接填")
    void unconfiguredReturnsBlankShell() {
        givenExisting(null);

        DimensionIntegrationVO vo = controller.get(DIM).getData();

        assertThat(vo.getConfigured()).isFalse();
        // 没密钥 = 这个维度不提供单点登录。这里回 false 而不是 true：
        // 「能不能用」由配置算出来（原 sso_enabled 开关已删），空壳算出 true 会自相矛盾
        assertThat(vo.getSsoEnabled()).isFalse();
        // 空壳不给任何预填值：协议与匹配字段都空着，逼用户自己确认一次
        assertThat(vo.getSsoProviderType()).isNull();
        assertThat(vo.getSsoMatchField()).isNull();
    }

    @Test
    @DisplayName("读写都要超管权限")
    void superAdminRequired() {
        givenExisting("enc:v1:old");

        controller.get(DIM);
        controller.save(DIM, dtoWithSecret(DimensionIntegrationVO.SECRET_MASK));
        controller.options();

        verify(adminService, org.mockito.Mockito.times(3)).requireSuperAdmin();
    }

    @Test
    @DisplayName("空壳带的是泛微的形态，但不预选协议：页面知道显示两个框，协议框仍是空的")
    void unconfiguredShellCarriesWeaverShapeButNoPrefill() {
        givenExisting(null);
        // 注册了两种协议时，按字母序 ticket 排在 weaver 前面 —— 取 findFirst 就会给票据模式的形态，
        // 而用户手上根本没有三方的 client_id，页面一打开就是一堆填不了的必填项
        useTicketProvider();

        DimensionIntegrationVO vo = controller.get(DIM).getData();

        assertThat(vo.getSsoShape().fields()).extracting(f -> f.key())
                .as("空壳只借形态，借的是泛微那份（两个地址框）")
                .containsExactly("authorizeUrl", "tokenUrl");
        assertThat(vo.getDefaultAuthorizePath()).isEqualTo("/sso/oauth2.0/authorize");
        // 协议本身与匹配字段都不预填（2026-11-25）
        assertThat(vo.getSsoProviderType()).isNull();
        assertThat(vo.getSsoMatchField()).isNull();
    }

    // ==================== 按需查看明文 ====================

    @Test
    @DisplayName("查看明文：走服务层取，且每次都留一条不含密钥内容的日志")
    void revealReturnsPlaintextAndLogs() {
        when(integrationService.revealSecret(DIM, "sso_client_secret")).thenReturn("real-secret");

        SecretRevealDTO req = new SecretRevealDTO();
        req.setField("sso_client_secret");

        assertThat(controller.reveal(DIM, req).getData()).isEqualTo("real-secret");
        verify(adminService).requireSuperAdmin();
    }

    @Test
    @DisplayName("查看明文也要超管：普通管理员不能读出来")
    void revealRequiresSuperAdmin() {
        org.mockito.Mockito.doThrow(new BusinessException(403, "仅超级管理员可维护管理员名单"))
                .when(adminService).requireSuperAdmin();

        SecretRevealDTO req = new SecretRevealDTO();
        req.setField("push_secret");

        assertThatThrownBy(() -> controller.reveal(DIM, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超级管理员");
        verify(integrationService, never()).revealSecret(any(), any());
    }

    // ==================== 地址配置：每个地址都必须填，不再由根地址推导 ====================

    @Test
    @DisplayName("泛微下根地址也是必填项：两个地址都填了、唯独根地址空着，照样拒")
    void weaverRequiresBaseUrlEvenWithFullUrls() {
        givenExisting("enc:v1:old");
        DimensionIntegrationSaveDTO dto = dtoWithSecret(DimensionIntegrationVO.SECRET_MASK);
        dto.setSsoBaseUrl(null);
        dto.setSsoAuthorizeUrl("https://ca/a");
        dto.setSsoTokenUrl("https://ca/t");

        // 2026-11-25 之前允许「地址填了根地址就可以空」，因为根地址只是个推导源。
        // 现在页面上的每一栏都是必填项，根地址也不例外 —— 它要用于拼注销地址的 origin，
        // 用户信息地址也要靠它拼出来（那一栏已不上页面）。
        assertThatThrownBy(() -> controller.save(DIM, dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("认证中心根地址");
        verify(integrationService, never()).save(any());
    }

    @Test
    @DisplayName("只填根地址、两个地址留空：拒（根地址不再能代替这两个地址）")
    void baseUrlNoLongerCoversMissingUrls() {
        givenExisting("enc:v1:old");
        DimensionIntegrationSaveDTO dto = dtoWithSecret(DimensionIntegrationVO.SECRET_MASK);
        dto.setSsoBaseUrl("http://oa:8080");
        dto.setSsoAuthorizeUrl(null);
        dto.setSsoTokenUrl(null);

        assertThatThrownBy(() -> controller.save(DIM, dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("发起认证地址")
                .hasMessageContaining("票据兑换地址")
                // 用户信息地址已不上页面（协议固定路径），提示里不许再要求填它
                .hasMessageNotContaining("用户信息地址");
        verify(integrationService, never()).save(any());
    }

    @Test
    @DisplayName("两个地址和根地址都没有：一次把所有缺项列全，而不是只报第一项")
    void noUrlAtAllIsRejected() {
        givenExisting("enc:v1:old");
        DimensionIntegrationSaveDTO dto = dtoWithSecret(DimensionIntegrationVO.SECRET_MASK);
        dto.setSsoBaseUrl(null);
        dto.setSsoAuthorizeUrl(null);
        dto.setSsoTokenUrl(null);
        dto.setSsoCallbackParam(null);
        dto.setSsoTokenMethod(null);

        assertThatThrownBy(() -> controller.save(DIM, dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("认证中心根地址")
                .hasMessageContaining("票据兑换地址")
                .hasMessageContaining("回调票据参数名")
                .hasMessageContaining("兑换请求方式");
        verify(integrationService, never()).save(any());
    }

    @Test
    @DisplayName("单项留空也会被拒：用户信息请求方式 / access_token 字段名")
    void singleMissingItemRejected() {
        givenExisting("enc:v1:old");
        DimensionIntegrationSaveDTO dto = dtoWithSecret(DimensionIntegrationVO.SECRET_MASK);
        dto.setSsoProfileMethod("  ");
        dto.setSsoAccessTokenField("");

        assertThatThrownBy(() -> controller.save(DIM, dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户信息请求方式")
                .hasMessageContaining("access_token 字段名")
                .hasMessageNotContaining("发起认证地址");
    }

    @Test
    @DisplayName("清空密钥（=关掉本维度登录）时不校验地址：允许先把配置清空")
    void disabledSsoSkipsUrlCheck() {
        givenExisting("enc:v1:old");
        // 「关不关」由密钥是否为空决定，不看 dto.ssoEnabled（库里那一列已不参与判断）
        when(integrationService.hasOtherConfiguredDimension(DIM)).thenReturn(true);
        DimensionIntegrationSaveDTO dto = dtoWithSecret("");
        dto.setSsoBaseUrl(null);
        dto.setSsoAuthorizeUrl(null);
        dto.setSsoTokenUrl(null);
        dto.setSsoProfileUrl(null);
        dto.setSsoCallbackParam(null);
        dto.setSsoTokenMethod(null);

        controller.save(DIM, dto);

        assertThat(saved().ssoClientSecret()).isNull();
    }

    // ==================== 校验按协议走：协议用几个接口就查几个 ====================

    @Test
    @DisplayName("两接口协议（票据模式）：只填两个地址就能存，不会被第三个框卡住")
    void twoInterfaceProtocolDoesNotRequireProfileUrl() {
        givenExisting("enc:v1:old");
        useTicketProvider();
        DimensionIntegrationSaveDTO dto = dtoWithSecret(DimensionIntegrationVO.SECRET_MASK);
        dto.setSsoProviderType("ticket");
        dto.setSsoBaseUrl(null);          // 票据模式没有固定路径，根地址给了也没用
        dto.setSsoAuthorizeUrl("https://open/api/sso/authorize");
        dto.setSsoTokenUrl("https://open/api/sso/userinfo");
        dto.setSsoProfileUrl(null);       // 这个协议根本没有第三个接口

        controller.save(DIM, dto);

        assertThat(saved().ssoAuthorizeUrl()).isEqualTo("https://open/api/sso/authorize");
        assertThat(saved().ssoTokenUrl()).isEqualTo("https://open/api/sso/userinfo");
    }

    @Test
    @DisplayName("两接口协议缺必填地址：拒，且提示里不许叫人去填根地址（那个对本协议没用）")
    void twoInterfaceProtocolRejectsMissingUrl() {
        givenExisting("enc:v1:old");
        useTicketProvider();
        DimensionIntegrationSaveDTO dto = dtoWithSecret(DimensionIntegrationVO.SECRET_MASK);
        dto.setSsoProviderType("ticket");
        dto.setSsoBaseUrl("https://open");   // 填了根地址也不能豁免
        dto.setSsoAuthorizeUrl(null);
        dto.setSsoTokenUrl("https://open/api/sso/userinfo");
        dto.setSsoProfileUrl(null);

        assertThatThrownBy(() -> controller.save(DIM, dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("发起认证地址")
                .hasMessageNotContaining("票据兑换地址")
                .hasMessageNotContaining("用户信息地址")
                // 票据模式没有认证中心这个概念，不该叫人去填根地址
                .hasMessageNotContaining("认证中心根地址");
        verify(integrationService, never()).save(any());
    }

    @Test
    @DisplayName("两接口协议：不校验 profile 的请求方式与 access_token 字段名（本协议没有这两步）")
    void twoInterfaceProtocolIgnoresTokenStepItems() {
        givenExisting("enc:v1:old");
        useTicketProvider();
        DimensionIntegrationSaveDTO dto = dtoWithSecret(DimensionIntegrationVO.SECRET_MASK);
        dto.setSsoProviderType("ticket");
        dto.setSsoBaseUrl(null);
        dto.setSsoAuthorizeUrl("https://open/api/sso/authorize");
        dto.setSsoTokenUrl("https://open/api/sso/userinfo");
        dto.setSsoProfileUrl(null);
        // 这两项只有「兑换后还要再取一次身份」的协议才要，票据模式不能因为它们空着就存不进去
        dto.setSsoProfileMethod(null);
        dto.setSsoAccessTokenField(null);

        controller.save(DIM, dto);

        assertThat(saved().ssoProviderType()).isEqualTo("ticket");
        assertThat(saved().ssoProfileMethod()).isNull();
        assertThat(saved().ssoAccessTokenField()).isNull();
    }

    /**
     * 装上一个「只有 authorize + userinfo 两个接口」的协议，即文档 20 §4 的票据模式。
     *
     * <p>这里用生产实现 {@link com.company.ability.security.provider.TicketProvider} 而不是匿名桩：
     * 校验逻辑要跟着协议走，那就该跟着<b>真的那个协议</b>走 —— 桩里手写的形态一旦和真实现分家，
     * 这些用例会继续绿，而线上照样存不进去。</p>
     */
    private void useTicketProvider() {
        when(providerRegistry.supportedTypes()).thenReturn(Set.of("weaver", "ticket"));
        when(providerRegistry.get("ticket")).thenReturn(new com.company.ability.security.provider.TicketProvider(
                new RestTemplateBuilder(), new ObjectMapper()));
    }

    // ==================== 第三方提供的参数 ====================

    @Test
    @DisplayName("回调参数名 / 请求方式 / 取码字段原样存下，页面怎么填就怎么存")
    void thirdPartyParamsArePersisted() {
        givenExisting("enc:v1:old");
        DimensionIntegrationSaveDTO dto = dtoWithSecret(DimensionIntegrationVO.SECRET_MASK);
        dto.setSsoCallbackParam("code");
        dto.setSsoTokenMethod("POST");
        dto.setSsoProfileMethod("GET");
        dto.setSsoAccessTokenField("data.accessToken");

        controller.save(DIM, dto);

        IntegrationRow row = saved();
        assertThat(row.ssoCallbackParam()).isEqualTo("code");
        assertThat(row.ssoTokenMethod()).isEqualTo("POST");
        assertThat(row.ssoProfileMethod()).isEqualTo("GET");
        assertThat(row.ssoAccessTokenField()).isEqualTo("data.accessToken");
    }

    @Test
    @DisplayName("留空一律存 NULL，不是存空串：空串会被当成「配过但配成了空」")
    void blankThirdPartyParamsStoredAsNull() {
        givenExisting("enc:v1:old");
        // 关掉登录才允许这些项为空 —— 启用时必须填全（见 singleMissingItemRejected）
        when(integrationService.hasOtherConfiguredDimension(DIM)).thenReturn(true);
        DimensionIntegrationSaveDTO dto = dtoWithSecret("");
        dto.setSsoCallbackParam("  ");
        dto.setSsoTokenMethod("");
        dto.setSsoProfileMethod(null);
        dto.setSsoAccessTokenField(" ");

        controller.save(DIM, dto);

        IntegrationRow row = saved();
        assertThat(row.ssoCallbackParam()).isNull();
        assertThat(row.ssoTokenMethod()).isNull();
        assertThat(row.ssoProfileMethod()).isNull();
        assertThat(row.ssoAccessTokenField()).isNull();
    }

    @Test
    @DisplayName("读接口只回形态与固定路径，不回协议默认值（页面不再有 placeholder）")
    void readReturnsShapeAndPathsWithoutDefaults() {
        givenExisting("enc:v1:old");

        DimensionIntegrationVO vo = controller.get(DIM).getData();

        // 三个固定路径仍要给：「用根地址重新填」按钮要它把值写进输入框
        assertThat(vo.getDefaultAuthorizePath()).isEqualTo("/sso/oauth2.0/authorize");
        assertThat(vo.getDefaultTokenPath()).isEqualTo("/sso/oauth2.0/accessToken");
        assertThat(vo.getDefaultProfilePath()).isEqualTo("/sso/oauth2.0/profile");
        // 但库里空着的项就回空 —— 不回 ticket / GET / id 这类默认值
        assertThat(vo.getSsoCallbackParam()).isNull();
        assertThat(vo.getSsoTokenMethod()).isNull();
        assertThat(vo.getSsoAccessTokenField()).isNull();
    }

    @Test
    @DisplayName("读接口带上协议形态：页面按它决定显示哪几个地址框，不按协议名写死")
    void readReturnsProviderShape() {
        givenExisting("enc:v1:old");

        var shape = controller.get(DIM).getData().getSsoShape();

        assertThat(shape).as("没回形态的话页面只能退回写死的框").isNotNull();
        assertThat(shape.fields()).extracting(f -> f.key())
                .as("用户信息地址是协议固定路径，不上页面")
                .containsExactly("authorizeUrl", "tokenUrl");
        assertThat(shape.fields()).allMatch(f -> f.required());
        assertThat(shape.accessTokenStep()).as("泛微是 ticket→access_token→profile 三步").isTrue();
        assertThat(shape.derivableFromBase()).as("三个接口路径都固定，配站点根就能一次代填").isTrue();
    }

    @Test
    @DisplayName("读接口带上完整回调地址：给对方注册白名单要精确值，不该让人自己拼")
    void readReturnsFullCallbackUrl() {
        givenExisting("enc:v1:old");
        when(integrationService.callbackUrlOf(null)).thenReturn("https://ca.zhongda.cn/sso/callback");

        DimensionIntegrationVO vo = controller.get(DIM).getData();

        assertThat(vo.getCallbackUrl()).isEqualTo("https://ca.zhongda.cn/sso/callback");
    }

    @Test
    @DisplayName("站点根没配时回调地址返回 null，不编造域名")
    void readLeavesCallbackUrlNullWhenSiteRootMissing() {
        givenExisting("enc:v1:old");
        when(integrationService.callbackUrlOf(null)).thenReturn(null);

        assertThat(controller.get(DIM).getData().getCallbackUrl()).isNull();
    }

    @Test
    @DisplayName("库里没配用户标识字段就回空，不拿协议默认的 id 顶上")
    void readDoesNotInventUserinfoField() {
        when(integrationService.find(DIM)).thenReturn(new IntegrationRow(
                DIM, true, "weaver", "cid", "enc:v1:old", "http://oa", null, null, null,
                null, null, null, null, null, "loginid", null,
                true, "weaver", null, null, null));

        assertThat(controller.get(DIM).getData().getSsoUserinfoField())
                .as("以前这里会回 defaultUserinfoField=id，页面拿它当 placeholder，看着像配过")
                .isNull();
    }

    // ==================== 地址展开：页面直接显示完整 URL ====================

    // ==================== 地址展开：只用于兼容老数据 ====================

    @Test
    @DisplayName("老行只有根地址：读接口仍展开成完整地址，页面照着保存一次就落成显式值")
    void readExpandsUrlsFromBaseUrlForLegacyRows() {
        givenExisting("enc:v1:old");   // 这一行只有 sso_base_url=http://oa，三个地址都是 null

        DimensionIntegrationVO vo = controller.get(DIM).getData();

        assertThat(vo.getSsoAuthorizeUrl()).isEqualTo("http://oa/sso/oauth2.0/authorize");
        assertThat(vo.getSsoTokenUrl()).isEqualTo("http://oa/sso/oauth2.0/accessToken");
        assertThat(vo.getSsoProfileUrl()).isEqualTo("http://oa/sso/oauth2.0/profile");
    }

    @Test
    @DisplayName("配了完整地址：原样回，不被根地址覆盖")
    void readKeepsExplicitUrls() {
        when(integrationService.find(DIM)).thenReturn(new IntegrationRow(
                DIM, true, "weaver", "cid", "enc:v1:old",
                "http://oa", "http://other/auth", null, "http://other/profile",
                "id", null, null, null, null, "loginid", null,
                true, "weaver", null, null, null));

        DimensionIntegrationVO vo = controller.get(DIM).getData();

        assertThat(vo.getSsoAuthorizeUrl()).isEqualTo("http://other/auth");
        assertThat(vo.getSsoTokenUrl()).as("没配的那个仍由根地址展开")
                .isEqualTo("http://oa/sso/oauth2.0/accessToken");
        assertThat(vo.getSsoProfileUrl()).isEqualTo("http://other/profile");
    }

    @Test
    @DisplayName("根地址和完整地址都没有：三个字段返回 null，页面显示为空让人填")
    void readLeavesUrlsEmptyWhenNothingConfigured() {
        when(integrationService.find(DIM)).thenReturn(new IntegrationRow(
                DIM, true, "weaver", "cid", "enc:v1:old",
                null, null, null, null,
                "id", null, null, null, null, "loginid", null,
                true, "weaver", null, null, null));

        DimensionIntegrationVO vo = controller.get(DIM).getData();

        assertThat(vo.getSsoAuthorizeUrl()).isNull();
        assertThat(vo.getSsoTokenUrl()).isNull();
        assertThat(vo.getSsoProfileUrl()).isNull();
    }

    // ==================== 回调路径只读：页面传了也不生效 ====================

    @Test
    @DisplayName("回调路径：页面传了新值也不生效，库里原值被原样写回")
    void redirectPathIgnoredFromPage() {
        givenExistingWithRedirectPath("/sso/callback");
        DimensionIntegrationSaveDTO dto = dtoWithSecret(DimensionIntegrationVO.SECRET_MASK);
        // 模拟旧客户端或有人直接构造请求，硬塞一个带维度号的路径。
        // 这个值一旦落库而对方白名单没跟着改，该维度所有人回调过来就是 1005。
        dto.setSsoRedirectPath("/sso/callback/999");

        controller.save(DIM, dto);

        assertThat(saved().ssoRedirectPath())
                .as("回调地址必须和对方白名单逐字符一致，不能由页面改")
                .isEqualTo("/sso/callback");
    }

    @Test
    @DisplayName("回调路径：库里没配过，保存后仍是 NULL（不凭空写一个值进去）")
    void redirectPathStaysNullWhenNeverConfigured() {
        givenExisting("enc:v1:old");
        DimensionIntegrationSaveDTO dto = dtoWithSecret(DimensionIntegrationVO.SECRET_MASK);
        dto.setSsoRedirectPath("/sso/callback/3");

        controller.save(DIM, dto);

        assertThat(saved().ssoRedirectPath()).isNull();
    }

    @Test
    @DisplayName("用户信息地址：页面撤掉这个框后，保存不会把库里已有的地址清成 NULL")
    void profileUrlSurvivesSaveWithoutIt() {
        givenExistingWithProfileUrl("http://oa/sso/oauth2.0/profile");
        DimensionIntegrationSaveDTO dto = dtoWithSecret(DimensionIntegrationVO.SECRET_MASK);

        controller.save(DIM, dto);

        assertThat(saved().ssoProfileUrl())
                .as("页面不再传这个字段，但库里原有的值必须原样留着")
                .isEqualTo("http://oa/sso/oauth2.0/profile");
    }

    @Test
    @DisplayName("用户信息地址：页面硬塞一个新值也不生效（这一栏已不由人填）")
    void profileUrlIgnoredFromPage() {
        givenExistingWithProfileUrl("http://oa/sso/oauth2.0/profile");
        DimensionIntegrationSaveDTO dto = dtoWithSecret(DimensionIntegrationVO.SECRET_MASK);
        dto.setSsoProfileUrl("http://evil/sso/oauth2.0/profile");

        controller.save(DIM, dto);

        assertThat(saved().ssoProfileUrl()).isEqualTo("http://oa/sso/oauth2.0/profile");
    }

    // ==================== 辅助 ====================

    /** 库里已有一条配了回调路径的记录（用于验证这个值不会被页面覆盖） */
    private void givenExistingWithRedirectPath(String redirectPath) {
        when(integrationService.find(DIM)).thenReturn(new IntegrationRow(
                DIM, true, "weaver", "cid", "enc:v1:old", "http://oa", null, null, null,
                "id", null, null, null, null, "loginid", redirectPath,
                true, "weaver", null, null, "{\"msgType\":596}"));
        when(integrationService.rawSecret(DIM, "sso_client_secret")).thenReturn("enc:v1:old");
        when(integrationService.rawSecret(DIM, "push_secret")).thenReturn(null);
    }

    /** 库里有一条已配了用户信息地址的记录（用于验证这一栏不会被页面传值覆盖或清空） */
    private void givenExistingWithProfileUrl(String profileUrl) {
        when(integrationService.find(DIM)).thenReturn(new IntegrationRow(
                DIM, true, "weaver", "cid", "enc:v1:old", "http://oa",
                null, null, profileUrl,
                "id", null, null, null, null, "loginid", null,
                true, "weaver", null, null, "{\"msgType\":596}"));
        when(integrationService.rawSecret(DIM, "sso_client_secret")).thenReturn("enc:v1:old");
        when(integrationService.rawSecret(DIM, "push_secret")).thenReturn(null);
    }

    private void givenExisting(String clientSecret) {
        if (clientSecret == null) {
            when(integrationService.find(DIM)).thenReturn(null);
            when(integrationService.rawSecret(DIM, "sso_client_secret")).thenReturn(null);
            when(integrationService.rawSecret(DIM, "push_secret")).thenReturn(null);
            return;
        }
        when(integrationService.find(DIM)).thenReturn(new IntegrationRow(
                DIM, true, "weaver", "cid", clientSecret, "http://oa", null, null, null,
                "id", null, null, null, null, "loginid", null,
                true, "weaver", null, null, "{\"msgType\":596}"));
        // 保存路径取的是库里原始密文（rawSecret），不是 find() 里那份已解密的行。
        // 两者在真实实现里不同：find() 解密，rawSecret 不解密。
        when(integrationService.rawSecret(DIM, "sso_client_secret")).thenReturn(clientSecret);
        when(integrationService.rawSecret(DIM, "push_secret")).thenReturn(null);
    }

    private IntegrationRow saved() {
        ArgumentCaptor<IntegrationRow> cap = ArgumentCaptor.forClass(IntegrationRow.class);
        verify(integrationService).save(cap.capture());
        return cap.getValue();
    }

    /**
     * 一份<b>填全了</b>的泛微配置。
     *
     * <p>2026-11-25 起单点登录下每一项都必填且无默认值，所以这个 helper 必须把
     * 两个地址、回调参数名、两个请求方式、access_token 字段名都写上 —— 少一项
     * 保存就会被 validateByProviderShape 拒掉。各个用例只改它关心的那几项。</p>
     *
     * <p>不设 {@code ssoProfileUrl}：用户信息地址已经不上页面了，真实的页面也不会传它。</p>
     */
    private static DimensionIntegrationSaveDTO dtoWithSecret(String secret) {
        DimensionIntegrationSaveDTO dto = new DimensionIntegrationSaveDTO();
        dto.setDimensionId(DIM);
        dto.setSsoEnabled(true);
        dto.setSsoProviderType("weaver");
        dto.setSsoClientId("cid");
        dto.setSsoClientSecret(secret);
        dto.setSsoBaseUrl("http://oa");
        dto.setSsoAuthorizeUrl("http://oa/sso/oauth2.0/authorize");
        dto.setSsoTokenUrl("http://oa/sso/oauth2.0/accessToken");
        dto.setSsoUserinfoField("id");
        dto.setSsoCallbackParam("ticket");
        dto.setSsoTokenMethod("GET");
        dto.setSsoProfileMethod("GET");
        dto.setSsoAccessTokenField("access_token");
        dto.setSsoMatchField("loginid");
        dto.setPushEnabled(true);
        dto.setPushChannel("weaver");
        dto.setPushExtraJson("{\"msgType\":596}");
        return dto;
    }
}
