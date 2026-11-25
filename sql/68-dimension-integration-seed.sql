-- ============================================================
-- 68 种子数据：现有三个维度灌入当前泛微配置
-- ------------------------------------------------------------
-- 目的：改造上线当天，行为与改造前**完全一致**。
--       三个维度全部指向泛微，登录参数就是现在 application.yml 里那一套。
--
-- 为什么 client_secret 不能直接写在这里：
--   表里存的是 AES-GCM 密文（前缀 enc:v1:），而密文依赖主密钥
--   INTEGRATION_MASTER_KEY —— 那是运行时的环境变量，SQL 脚本里算不出来。
--   把明文写进脚本又违背「仓库零明文」的规矩（secret.yml 之所以被 gitignore 就是为此）。
--
-- 处理方式：这里先写占位符 __PENDING__，由应用启动时回填：
--   · 检测到 __PENDING__ → 读旧的 oa.sso.client-secret（首次部署时 yml 仍在）
--     → 用主密钥加密 → 写回表并记一条 INFO 日志
--   · 旧配置已删且仍是 __PENDING__ → 启动失败，提示设置环境变量 OA_SSO_CLIENT_SECRET
-- 这样第一轮部署自动完成迁移，第二轮才能安全删掉 yml 里的 oa.sso.*（验收项 A7）。
--
-- 存量数据：只在表为空时插入。重复执行不会覆盖已填好的真实密文 ——
--           这条很重要，否则每次重跑脚本都会把加密值冲成占位符。
--
-- 幂等：表非空时跳过。
-- 回滚：
--   DELETE FROM dbo.uf_assess_dimension_integration;
-- ============================================================

USE ecology;
GO

IF NOT EXISTS (SELECT 1 FROM dbo.uf_assess_dimension_integration)
BEGIN
    INSERT INTO dbo.uf_assess_dimension_integration
        (dimension_id, sso_enabled, sso_provider_type, sso_client_id, sso_client_secret,
         sso_base_url, sso_userinfo_field, sso_match_field, sso_redirect_path,
         push_enabled, push_channel, push_extra_json)
    SELECT d.id,
           1,                                   -- sso_enabled
           'weaver',                            -- sso_provider_type
           '1feab8b2-bd67-42da-8a31-2dce003fc73a',  -- 泛微注册应用 CBA（weaver_sso_app.id=5）
           '__PENDING__',                       -- 待应用启动时用主密钥加密回填
           'http://xcx.zhongda.cn:8080',        -- 认证中心；路径由 WeaverProvider 拼
           'id',                                -- 泛微 profile 顶层 id 即 OA 登录名
           'loginid',                           -- 必须 loginid：164 人工号≠登录名，改工号会登录失败
           NULL,                                -- 回调路径用默认 /sso/callback
           1,                                   -- push_enabled
           'weaver',                            -- 仍走 OA 侧 PushMsgJob
           '{"msgType":596}'                    -- 能力测评提醒，见 AssessPushService.MSG_TYPE_ASSESS
    FROM dbo.uf_assess_dimension d
    WHERE d.deleted = 0;

    PRINT '已为现有维度灌入泛微集成配置（client_secret 待应用启动回填）';
END
ELSE
    PRINT 'uf_assess_dimension_integration 已有数据，跳过种子（不覆盖已加密的密钥）';
GO

PRINT '68 完成：维度集成配置种子就绪';
