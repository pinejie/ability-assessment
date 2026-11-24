-- ============================================================
-- 52 停用泛微流程 1840 的旧测评钩子（方案 17 · P3）
-- ------------------------------------------------------------
-- 背景：
--   流程「能力测评申请」（workflow_base.id = 1840）在节点 7143 上挂着
--   `AssessHookAction`（workflowactionset.id = 4341）。这个钩子只干一件事：
--   读 uf_assess_sheet_row 取该页有效要素，然后 EXEC usp_assess_rate /
--   usp_assess_annual_score 往旧表写评级。
--
--   上面这些对象已在 sql/51 全部 DROP：
--     uf_assess_sheet / _row / _rating / _comment / _log、
--     usp_assess_rate / usp_assess_annual_score / usp_assess_recalc …
--   钩子现在一触发就会抛「对象名无效」。修补它没有意义 ——
--   新模型的评级只能从网页填写页（/pa/assess-fill）产生，
--   流程侧写评级正是方案 17 §二 要取消的那条通道（口径 6：得分全部来自网页评价）。
--
-- 所以这里只做一件事：把 action 停用（isused = 0）。不删行 ——
-- 留着能查历史、能一键回滚；删了的话 workflowactionset 与流程节点配置的对应关系就丢了。
--
-- 幂等：重复执行只是再把 isused 置 0。
-- 回滚：UPDATE workflowactionset SET isused = 1 WHERE id = 4341;
-- ============================================================

USE ecology;
GO

PRINT '--- 停用前 ---';
GO
SELECT id, actionname, workflowid, nodeid, interfaceid, isused, ispreoperator
FROM dbo.workflowactionset WHERE id = 4341;
GO

UPDATE dbo.workflowactionset SET isused = 0 WHERE id = 4341;
GO

PRINT '--- 停用后（isused 应为 0） ---';
GO
SELECT id, actionname, workflowid, nodeid, interfaceid, isused, ispreoperator
FROM dbo.workflowactionset WHERE id = 4341;
GO

-- 顺带核对：全库不应再有任何启用中的测评钩子
SELECT COUNT(*) AS active_assess_hooks
FROM dbo.workflowactionset
WHERE isused = 1 AND (interfaceid LIKE '%ssess%' OR actionname LIKE '%测评%');
GO
