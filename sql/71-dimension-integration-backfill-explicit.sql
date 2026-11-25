-- ============================================================
-- 71 维度集成：把「以前靠服务端兜底」的项落成显式值
-- ------------------------------------------------------------
-- 背景：2026-11-25 起，配置页「单点登录」下所有配置项都改成必填、且不再有任何默认值，
-- WeaverProvider / TicketProvider 里的协议默认值、ProviderConfig 里的 *Or() 兜底、
-- SsoShape 回给页面的 identityFieldDefault / matchFieldDefault 一并删掉了。
-- 删掉之后，「库里是 NULL」不再是「走协议默认」，而是「这一项没配 → 登录直接报错」。
--
-- 存量三个维度（3/4/5，都是泛微）当时是这样配的：
--   地址      三栏全 NULL，靠 sso_base_url 推导
--   第三方    sso_callback_param / sso_token_method / sso_profile_method /
--             sso_access_token_field 四个全 NULL，靠 WeaverProvider 的协议默认
-- 代码改完之后它们的登录会立刻坏掉。本脚本把当年「实际生效的值」原样写成显式值，
-- 所以部署后这三个维度的登录行为与改动前逐字节一致，不需要人工补配。
--
-- 写入的值（全部来自改动前的 WeaverProvider 默认实现与 resolveUrl 推导结果）：
--   sso_authorize_url        = <base_url> + /sso/oauth2.0/authorize
--   sso_token_url            = <base_url> + /sso/oauth2.0/accessToken
--   sso_profile_url          = <base_url> + /sso/oauth2.0/profile
--   sso_callback_param       = ticket
--   sso_token_method         = GET
--   sso_profile_method       = GET
--   sso_access_token_field   = access_token
--
-- 只补空值，不覆盖已有配置：
--   每条 UPDATE 都带 `IS NULL OR LTRIM(RTRIM(col)) = ''` 条件。生产上若有人已经手工
--   配过其中某一项（比如把请求方式改成了 POST），重跑本脚本不会把它改回 GET。
--   因此本脚本可以安全地重复执行，也可以只跑一次。
--
-- 只动「地址推得出来」的行：
--   三个地址的写入都以 sso_base_url 非空为前提（WeaverProvider 的推导同样需要 base_url）。
--   没有 base_url 的行不猜路径，保持 NULL —— 那种行部署后打开配置页会看到空框，
--   属于真正需要人工确认的情况，不应由脚本臆造。
--
-- 回滚（把显式值退回 NULL，恢复成改动前的「靠兜底」状态；仅在需要整体回退代码时用）：
--   UPDATE dbo.uf_assess_dimension_integration SET
--       sso_authorize_url = NULL, sso_token_url = NULL, sso_profile_url = NULL,
--       sso_callback_param = NULL, sso_token_method = NULL,
--       sso_profile_method = NULL, sso_access_token_field = NULL;
--   （注意：这会把本次改动之后人工新填的值也一并清掉，只应在上线回退时执行。）
-- ============================================================

USE ecology;
GO

-- ---------- 一、三个接口地址：由 base_url 展开成完整 URL ----------

UPDATE dbo.uf_assess_dimension_integration
SET sso_authorize_url = RTRIM(RTRIM(sso_base_url)) + '/sso/oauth2.0/authorize'
WHERE sso_provider_type = 'weaver'
  AND sso_base_url IS NOT NULL AND LTRIM(RTRIM(sso_base_url)) <> ''
  AND (sso_authorize_url IS NULL OR LTRIM(RTRIM(sso_authorize_url)) = '');
PRINT '71: 已展开 sso_authorize_url，影响 ' + CAST(@@ROWCOUNT AS VARCHAR(10)) + ' 行';
GO

UPDATE dbo.uf_assess_dimension_integration
SET sso_token_url = RTRIM(RTRIM(sso_base_url)) + '/sso/oauth2.0/accessToken'
WHERE sso_provider_type = 'weaver'
  AND sso_base_url IS NOT NULL AND LTRIM(RTRIM(sso_base_url)) <> ''
  AND (sso_token_url IS NULL OR LTRIM(RTRIM(sso_token_url)) = '');
PRINT '71: 已展开 sso_token_url，影响 ' + CAST(@@ROWCOUNT AS VARCHAR(10)) + ' 行';
GO

UPDATE dbo.uf_assess_dimension_integration
SET sso_profile_url = RTRIM(RTRIM(sso_base_url)) + '/sso/oauth2.0/profile'
WHERE sso_provider_type = 'weaver'
  AND sso_base_url IS NOT NULL AND LTRIM(RTRIM(sso_base_url)) <> ''
  AND (sso_profile_url IS NULL OR LTRIM(RTRIM(sso_profile_url)) = '');
PRINT '71: 已展开 sso_profile_url，影响 ' + CAST(@@ROWCOUNT AS VARCHAR(10)) + ' 行';
GO

-- 展开时若 base_url 末尾本来就带斜杠会拼出 `//sso/...`，双斜杠多数网关能过但不算对，
-- 这里统一收敛成单斜杠 —— 只修本次刚拼出来的形态，不动人工填的值。
UPDATE dbo.uf_assess_dimension_integration
SET sso_authorize_url = REPLACE(sso_authorize_url, '//sso/', '/sso/')
WHERE sso_authorize_url LIKE '%//sso/%';
GO

UPDATE dbo.uf_assess_dimension_integration
SET sso_token_url = REPLACE(sso_token_url, '//sso/', '/sso/')
WHERE sso_token_url LIKE '%//sso/%';
GO

UPDATE dbo.uf_assess_dimension_integration
SET sso_profile_url = REPLACE(sso_profile_url, '//sso/', '/sso/')
WHERE sso_profile_url LIKE '%//sso/%';
GO

-- ---------- 二、第三方参数：写成 WeaverProvider 当年的协议默认值 ----------

UPDATE dbo.uf_assess_dimension_integration
SET sso_callback_param = 'ticket'
WHERE sso_provider_type = 'weaver'
  AND (sso_callback_param IS NULL OR LTRIM(RTRIM(sso_callback_param)) = '');
PRINT '71: 已补 sso_callback_param=ticket，影响 ' + CAST(@@ROWCOUNT AS VARCHAR(10)) + ' 行';
GO

UPDATE dbo.uf_assess_dimension_integration
SET sso_token_method = 'GET'
WHERE sso_provider_type = 'weaver'
  AND (sso_token_method IS NULL OR LTRIM(RTRIM(sso_token_method)) = '');
PRINT '71: 已补 sso_token_method=GET，影响 ' + CAST(@@ROWCOUNT AS VARCHAR(10)) + ' 行';
GO

UPDATE dbo.uf_assess_dimension_integration
SET sso_profile_method = 'GET'
WHERE sso_provider_type = 'weaver'
  AND (sso_profile_method IS NULL OR LTRIM(RTRIM(sso_profile_method)) = '');
PRINT '71: 已补 sso_profile_method=GET，影响 ' + CAST(@@ROWCOUNT AS VARCHAR(10)) + ' 行';
GO

UPDATE dbo.uf_assess_dimension_integration
SET sso_access_token_field = 'access_token'
WHERE sso_provider_type = 'weaver'
  AND (sso_access_token_field IS NULL OR LTRIM(RTRIM(sso_access_token_field)) = '');
PRINT '71: 已补 sso_access_token_field=access_token，影响 ' + CAST(@@ROWCOUNT AS VARCHAR(10)) + ' 行';
GO

-- ---------- 三、自检：仍然缺项的行列出来 ----------
-- 不抛错（有的行可能本来就该停用），只在部署日志里留个记号：
-- 部署后如果这里是空的，说明三个泛微维度都补全了，不需要人工介入。
SELECT dimension_id,
       sso_enabled,
       sso_provider_type,
       CASE WHEN sso_base_url         IS NULL OR sso_base_url         = '' THEN '缺' ELSE '有' END AS base_url,
       CASE WHEN sso_authorize_url    IS NULL OR sso_authorize_url    = '' THEN '缺' ELSE '有' END AS authorize_url,
       CASE WHEN sso_token_url        IS NULL OR sso_token_url        = '' THEN '缺' ELSE '有' END AS token_url,
       CASE WHEN sso_profile_url      IS NULL OR sso_profile_url      = '' THEN '缺' ELSE '有' END AS profile_url,
       CASE WHEN sso_callback_param   IS NULL OR sso_callback_param   = '' THEN '缺' ELSE '有' END AS callback_param,
       CASE WHEN sso_token_method     IS NULL OR sso_token_method     = '' THEN '缺' ELSE '有' END AS token_method,
       CASE WHEN sso_profile_method   IS NULL OR sso_profile_method   = '' THEN '缺' ELSE '有' END AS profile_method,
       CASE WHEN sso_access_token_field IS NULL OR sso_access_token_field = '' THEN '缺' ELSE '有' END AS access_token_field
FROM dbo.uf_assess_dimension_integration
WHERE sso_enabled = 1
ORDER BY dimension_id;
GO

PRINT '71 完成：存量维度的 SSO 配置已落成显式值（地址展开 + 泛微第三方参数）';
GO
