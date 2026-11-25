-- ============================================================
-- 66 维度集成配置（登录 + 推送）
-- ------------------------------------------------------------
-- 背景：原来「接哪套单点登录」「消息往哪推」都是写死在代码里的泛微一套。
-- 泛微购买的账号数有限（在岗 2305 人里仅 774 人有泛微账号，1531 人没有），
-- 大量员工只能从其它系统进来，消息也只能发到他们在那边有账号的系统。
-- 接第二个系统时如果照抄一份代码，第三个、第四个会继续膨胀，
-- 所以把「接谁」从代码搬到配置：每个维度一行，配它的登录参数与推送参数。
--
-- 为什么独立成表而不是加进 uf_assess_dimension：
--   · 这张表存 client_secret / push_secret，需要单独的读写权限（仅超管），
--     与维度本身的增删改不是一个权限级别
--   · 今后可能要配主备两条通道，塞进维度主表会让主表字段与业务无关地膨胀
--
-- 关键字段说明：
--   · sso_provider_type  用枚举而不是存实现类名。存 FQCN 的话，
--       类改名/挪包后数据库里就是一条永远打不通的死配置，而且启动不报错，
--       要等用户点登录才崩。枚举在启动时就校验，未知类型直接失败。
--   · sso_base_url / sso_authorize_url 两级二选一：
--       泛微这类协议固定的，只填 base_url（如 http://xcx.zhongda.cn:8080），
--       路径 /sso/oauth2.0/authorize 由 Provider 自己拼 —— 这是改造前的形态，
--       保持最少的配置项。OIDC 这类没有统一路径的，才分别填三个完整地址。
--       两者都给时以显式地址为准。
--   · sso_userinfo_field 对方返回的 JSON 里，哪个字段放身份值。
--       泛微是顶层 id；其它系统可能是 workcode / employeeNo / userId。
--   · sso_match_field    拿到身份值后，去 HrmResource 的哪一列匹配。
--       **泛微必须是 loginid**：实测 164 人的 loginid 与 workcode 不一致，
--       改用工号会让这 164 人登录失败。其它系统用工号（全公司统一、零重复）。
--   · sso_client_secret / push_secret  AES-GCM 加密存储，前缀 enc:v1:。
--       主密钥走环境变量 INTEGRATION_MASTER_KEY，不进库、不进 Git。
--   · push_extra_json    渠道专有参数。泛微是 {"msgType":596}，
--       企微/钉钉是模板 ID、corp_id 之类。不拆成列是因为每个渠道要的都不一样，
--       拆了大部分列对大部分渠道永远为 NULL。
--
-- 存量数据：种子脚本 68 会给现有三个维度灌现有泛微配置，
--           上线当天行为与改造前完全一致。
--
-- 幂等：表已存在时跳过。
-- 回滚：
--   DROP TABLE dbo.uf_assess_dimension_integration;
-- ============================================================

USE ecology;
GO

IF OBJECT_ID('dbo.uf_assess_dimension_integration', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.uf_assess_dimension_integration (
        dimension_id        BIGINT        NOT NULL,

        -- ===== 登录（进）=====
        sso_enabled         BIT           NOT NULL CONSTRAINT DF_di_sso_enabled DEFAULT 1,
        sso_provider_type   VARCHAR(32)   NOT NULL,       -- weaver / oidc / wecom / dingtalk / cas
        sso_client_id       VARCHAR(128)  NOT NULL,
        sso_client_secret   VARCHAR(512)  NOT NULL,       -- enc:v1: + AES-GCM 密文
        sso_base_url        VARCHAR(500)  NULL,           -- 认证中心基地址（泛微必填，路径由 Provider 拼）
        sso_authorize_url   VARCHAR(500)  NULL,           -- 显式给全地址时用，优先于 base_url
        sso_token_url       VARCHAR(500)  NULL,
        sso_profile_url     VARCHAR(500)  NULL,
        sso_userinfo_field  VARCHAR(64)   NULL,           -- 响应里取哪个字段作身份值
        sso_match_field     VARCHAR(32)   NOT NULL,       -- 去 HrmResource 哪列匹配
        sso_redirect_path   VARCHAR(200)  NULL,           -- 空则用默认 /sso/callback

        -- ===== 推送（出）=====
        push_enabled        BIT           NOT NULL CONSTRAINT DF_di_push_enabled DEFAULT 1,
        push_channel        VARCHAR(32)   NOT NULL,       -- weaver / wecom / dingtalk / email / sms
        push_agent_id       VARCHAR(64)   NULL,           -- 企微/钉钉 agentid
        push_secret         VARCHAR(512)  NULL,           -- enc:v1: + AES-GCM 密文
        push_extra_json     NVARCHAR(MAX) NULL,           -- 渠道专有参数

        update_by           BIGINT        NULL,
        update_time         DATETIME2     NULL,

        CONSTRAINT PK_dim_integration PRIMARY KEY (dimension_id),
        CONSTRAINT FK_dim_integration FOREIGN KEY (dimension_id)
            REFERENCES dbo.uf_assess_dimension(id)
    );
    PRINT 'uf_assess_dimension_integration 已创建';
END
ELSE
    PRINT 'uf_assess_dimension_integration 已存在，跳过';
GO

-- 匹配字段只允许这两列。写错列名会让登录静默失效（SQL 报错还好，怕的是拼成
-- 一个不存在的列后有人图省事改成 SELECT *），从约束层挡住。
IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'CK_di_match_field')
BEGIN
    ALTER TABLE dbo.uf_assess_dimension_integration
        ADD CONSTRAINT CK_di_match_field CHECK (sso_match_field IN ('loginid', 'workcode'));
    PRINT 'CK_di_match_field 已添加：sso_match_field 只允许 loginid / workcode';
END
ELSE
    PRINT 'CK_di_match_field 已存在，跳过';
GO

PRINT '66 完成：维度集成配置表就绪';
