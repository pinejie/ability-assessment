-- 22 清理默认维度（id=1）全部测试数据并移除该维度；顺带清理旧版/废弃/调试备份表
-- 背景：维度 1 为系统早期默认挂载点，现存内容全部为联调测试数据；
--       中达集团（维度3）等正式维度已自建完整配置，与维度 1 无引用关系（2026-10-22 已盘点核实）。
-- 使用方式：先执行【A 预检查】，核对数字与盘点一致后，再执行【B 清理】。
-- 数据库：ecology（与泛微共用；本脚本只操作测评项目自己的 uf_* 表，不触碰泛微平台表）

-- ==================== A. 预检查（只读，可反复执行） ====================
SELECT '1_能力类别' AS 表, COUNT(*) AS 维度1行数 FROM uf_ability_category WHERE dimension_id = 1
UNION ALL SELECT '2_能力要素', COUNT(*) FROM uf_ability_element WHERE dimension_id = 1
UNION ALL SELECT '3_要素等级', COUNT(*) FROM uf_ability_element_level WHERE dimension_id = 1
UNION ALL SELECT '4_组织要求', COUNT(*) FROM uf_org_ability_req WHERE dimension_id = 1
UNION ALL SELECT '5_岗位要求主', COUNT(*) FROM uf_position_ability_req_new WHERE dimension_id = 1
UNION ALL SELECT '6_岗位要求明细', COUNT(*) FROM uf_position_ability_req_item WHERE dimension_id = 1
UNION ALL SELECT '7_人员要求主', COUNT(*) FROM uf_user_ability_req_new WHERE dimension_id = 1
UNION ALL SELECT '8_人员要求明细', COUNT(*) FROM uf_user_ability_req_item WHERE dimension_id = 1
UNION ALL SELECT '9_能力类型权重', COUNT(*) FROM uf_assess_weight_ability WHERE dimension_id = 1
UNION ALL SELECT '10_测评对象权重', COUNT(*) FROM uf_assess_weight_assessor WHERE dimension_id = 1
UNION ALL SELECT '11_等级标准', COUNT(*) FROM uf_assess_grade_level WHERE dimension_id = 1
UNION ALL SELECT '12_等级分值', COUNT(*) FROM uf_assess_level_score WHERE dimension_id = 1
UNION ALL SELECT '13_年度期间', COUNT(*) FROM uf_assess_period WHERE dimension_id = 1
UNION ALL SELECT '14_测评表', COUNT(*) FROM uf_assess_sheet WHERE dimension_id = 1
UNION ALL SELECT '15_sheet行', COUNT(*) FROM uf_assess_sheet_row r WHERE EXISTS (SELECT 1 FROM uf_assess_sheet s WHERE s.id = r.sheet_id AND s.dimension_id = 1)
UNION ALL SELECT '16_评级', COUNT(*) FROM uf_assess_sheet_rating ra WHERE EXISTS (SELECT 1 FROM uf_assess_sheet s WHERE s.id = ra.sheet_id AND s.dimension_id = 1)
UNION ALL SELECT '17_意见', COUNT(*) FROM uf_assess_comment cm WHERE EXISTS (SELECT 1 FROM uf_assess_sheet s WHERE s.id = cm.sheet_id AND s.dimension_id = 1)
UNION ALL SELECT '18_日志', COUNT(*) FROM uf_assess_sheet_log l WHERE EXISTS (SELECT 1 FROM uf_assess_sheet s WHERE s.id = l.sheet_id AND s.dimension_id = 1)
UNION ALL SELECT '19_快照', COUNT(*) FROM uf_assess_report_snapshot p WHERE EXISTS (SELECT 1 FROM uf_assess_sheet s WHERE s.id = p.sheet_id AND s.dimension_id = 1)
UNION ALL SELECT '20_维度授权', COUNT(*) FROM uf_assess_dimension_auth WHERE dimension_id = 1;

-- 安全护栏：正式维度（3/4/5）不在删除范围，这里确认它们自己的行数不受影响（清理前后各查一次）
SELECT d.id, d.dimension_name,
       (SELECT COUNT(*) FROM uf_ability_category c WHERE c.dimension_id = d.id) AS 类别数,
       (SELECT COUNT(*) FROM uf_assess_period p WHERE p.dimension_id = d.id) AS 年度数
FROM uf_assess_dimension d WHERE d.id <> 1;

-- ==================== B. 清理（单事务，失败全回滚） ====================
SET NOCOUNT ON;
SET XACT_ABORT ON;
BEGIN TRAN;

-- B1. 维度 1 的测评表子数据（先子后父，经 sheet 关联）
DELETE r FROM uf_assess_sheet_row r
    JOIN uf_assess_sheet s ON s.id = r.sheet_id WHERE s.dimension_id = 1;
PRINT 'sheet行: ' + CAST(@@ROWCOUNT AS VARCHAR);

DELETE ra FROM uf_assess_sheet_rating ra
    JOIN uf_assess_sheet s ON s.id = ra.sheet_id WHERE s.dimension_id = 1;
PRINT '评级: ' + CAST(@@ROWCOUNT AS VARCHAR);

DELETE cm FROM uf_assess_comment cm
    JOIN uf_assess_sheet s ON s.id = cm.sheet_id WHERE s.dimension_id = 1;
PRINT '意见: ' + CAST(@@ROWCOUNT AS VARCHAR);

DELETE l FROM uf_assess_sheet_log l
    JOIN uf_assess_sheet s ON s.id = l.sheet_id WHERE s.dimension_id = 1;
PRINT '日志: ' + CAST(@@ROWCOUNT AS VARCHAR);

DELETE p FROM uf_assess_report_snapshot p
    JOIN uf_assess_sheet s ON s.id = p.sheet_id WHERE s.dimension_id = 1;
PRINT '快照: ' + CAST(@@ROWCOUNT AS VARCHAR);

-- B2. 带 dimension_id 的维度 1 业务表
DELETE FROM uf_assess_sheet WHERE dimension_id = 1;              PRINT '测评表: ' + CAST(@@ROWCOUNT AS VARCHAR);
DELETE FROM uf_assess_period WHERE dimension_id = 1;             PRINT '年度期间: ' + CAST(@@ROWCOUNT AS VARCHAR);
DELETE FROM uf_assess_level_score WHERE dimension_id = 1;        PRINT '等级分值: ' + CAST(@@ROWCOUNT AS VARCHAR);
DELETE FROM uf_assess_grade_level WHERE dimension_id = 1;        PRINT '等级标准: ' + CAST(@@ROWCOUNT AS VARCHAR);
DELETE FROM uf_assess_weight_assessor WHERE dimension_id = 1;    PRINT '对象权重: ' + CAST(@@ROWCOUNT AS VARCHAR);
DELETE FROM uf_assess_weight_ability WHERE dimension_id = 1;     PRINT '能力权重: ' + CAST(@@ROWCOUNT AS VARCHAR);
DELETE FROM uf_user_ability_req_item WHERE dimension_id = 1;     PRINT '人员要求明细: ' + CAST(@@ROWCOUNT AS VARCHAR);
DELETE FROM uf_user_ability_req_new WHERE dimension_id = 1;      PRINT '人员要求主: ' + CAST(@@ROWCOUNT AS VARCHAR);
DELETE FROM uf_position_ability_req_item WHERE dimension_id = 1; PRINT '岗位要求明细: ' + CAST(@@ROWCOUNT AS VARCHAR);
DELETE FROM uf_position_ability_req_new WHERE dimension_id = 1;  PRINT '岗位要求主: ' + CAST(@@ROWCOUNT AS VARCHAR);
DELETE FROM uf_org_ability_req WHERE dimension_id = 1;           PRINT '组织要求: ' + CAST(@@ROWCOUNT AS VARCHAR);
DELETE FROM uf_ability_element_level WHERE dimension_id = 1;     PRINT '要素等级: ' + CAST(@@ROWCOUNT AS VARCHAR);
DELETE FROM uf_ability_element WHERE dimension_id = 1;           PRINT '能力要素: ' + CAST(@@ROWCOUNT AS VARCHAR);
DELETE FROM uf_ability_category WHERE dimension_id = 1;          PRINT '能力类别: ' + CAST(@@ROWCOUNT AS VARCHAR);

-- B3. 维度本体与授权
DELETE FROM uf_assess_dimension_auth WHERE dimension_id = 1;     PRINT '维度授权: ' + CAST(@@ROWCOUNT AS VARCHAR);
DELETE FROM uf_assess_dimension WHERE id = 1;                    PRINT '维度行(默认维度): ' + CAST(@@ROWCOUNT AS VARCHAR);

-- B4.（可选，不需要就整段删掉再执行）旧版/废弃/调试备份表整体清理
IF OBJECT_ID('dbo.uf_position_ability_req', 'U') IS NOT NULL BEGIN DROP TABLE dbo.uf_position_ability_req; PRINT '已删 旧岗位表'; END
IF OBJECT_ID('dbo.uf_user_ability_req', 'U') IS NOT NULL BEGIN DROP TABLE dbo.uf_user_ability_req; PRINT '已删 旧人员表'; END
IF OBJECT_ID('dbo.uf_score_weight', 'U') IS NOT NULL BEGIN DROP TABLE dbo.uf_score_weight; PRINT '已删 废弃权重表'; END
IF OBJECT_ID('dbo.uf_assess_sheet_rating_bak_20261016', 'U') IS NOT NULL BEGIN DROP TABLE dbo.uf_assess_sheet_rating_bak_20261016; PRINT '已删 评级备份表'; END

-- 复核：以下应全部为 0 / 维度表仅剩 3、4、5
SELECT COUNT(*) AS 维度1残留 FROM uf_ability_category WHERE dimension_id = 1;
SELECT id, dimension_name FROM uf_assess_dimension ORDER BY id;

COMMIT;
-- 如需只演练不落地：把上一行改成 ROLLBACK; 执行后数据不变
