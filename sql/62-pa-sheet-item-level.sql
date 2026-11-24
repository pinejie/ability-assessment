-- ============================================================
-- 62 测评页行增加「项得分刻度名」快照
-- ------------------------------------------------------------
-- 背景：报告明细「综合得分」要显示成「3.67 · 精进/能手」。
--   刻度名来自该项自己的等级分值切出的左闭右开区间
--   （[1,2) 入门/新手、[2,3) 胜任/熟手、[3,4) 精进/能手、[4,5) 引领/高手、[5,+∞) 开创/专家）。
--
-- 为什么要落库而不是报告时现算：
--   等级可以被改名、改分值（模型页随时能编辑）。现算的口径下，去年那张报告上
--   「3.67 · 精进/能手」会在今年改完名字后变成别的字样 —— 同一份历史报告读两次不一样。
--   item_score 本身早就是快照（口径 #15），刻度名是它的解释，必须同一时刻一起冻结。
--
-- 与 uf_pa_band 的区别（别改错表）：
--   · uf_pa_band 按 post_id 分、无 item_id，区间解释的是**总得分**，页面顶部「等级说明」用它；
--   · 本列解释的是**单个能力项的项得分**，按 uf_pa_item_level 的分值点切，两者是两把尺子。
--
-- 写入方（三处，改逻辑时别漏）：
--   1. 算分 PaScoreServiceImpl.doRecalculate —— 算几分就写几分的刻度名，含 NULL 显式写回；
--   2. 建页 PaSheetServiceImpl.writeItems —— 插入时留 NULL（此时还没出分）；
--   3. 撤回 PaTaskOpServiceImpl / 导入回滚 PaImportApplier —— 清空时一并置 NULL。
--
-- 长度取 50：现有最长刻度名「开创/专家」6 个字符，与 uf_pa_item_level.level_name 同宽。
--
-- 幂等：重复执行不会重复加列。
-- 回滚：
--   ALTER TABLE uf_pa_sheet_item DROP COLUMN item_level_name;
-- ============================================================

USE ecology;
GO

IF NOT EXISTS (SELECT 1 FROM sys.columns
               WHERE object_id = OBJECT_ID('dbo.uf_pa_sheet_item') AND name = 'item_level_name')
BEGIN
    ALTER TABLE dbo.uf_pa_sheet_item ADD item_level_name NVARCHAR(50) NULL;
    PRINT N'uf_pa_sheet_item.item_level_name 已添加';
END
ELSE
    PRINT N'uf_pa_sheet_item.item_level_name 已存在，跳过';
GO

-- ------------------------------------------------------------
-- 存量回填（只补已算分、且本列还是空的行）
-- ------------------------------------------------------------
-- 口径与算分代码完全一致：取该项「score <= item_score」里分值最高的那一档，
-- 剥掉「N分 · 」前缀（库里 62 个能力项里 16 个带前缀、46 个不带）。
-- 跌破最低档（脏数据，如 0.5 分）回落最低档 —— 与 PaItemLevelScale.resolve 同口径，
-- 页面显示「—」会被误读成「没出分」，而实际是出了分只是跌破刻度。
--
-- NULLIF(RTRIM(...), '') 防御等级名是空串的脏数据：那会把刻度名回填成空串，
-- 报告上看起来就是「没配刻度」。空串一律当没配上，留 NULL 走报告侧的兜底。
--
-- 只跑一次有效：condition 里 item_level_name IS NULL，重跑不会覆盖已有快照。
UPDATE si
   SET si.item_level_name = CASE
         WHEN hit.level_name IS NULL OR NULLIF(RTRIM(hit.level_name), '') IS NULL THEN NULL
         -- 「3分 · 精进/能手」→「精进/能手」：只剥「数字+分+可选分隔点」这一种形状
         WHEN hit.level_name LIKE N'%分%·%'
              THEN LTRIM(RTRIM(SUBSTRING(hit.level_name, CHARINDEX(N'·', hit.level_name) + 1, 50)))
         ELSE LTRIM(RTRIM(hit.level_name))
       END
  FROM dbo.uf_pa_sheet_item si
 OUTER APPLY (
       SELECT TOP 1 l.level_name
         FROM dbo.uf_pa_item_level l
        WHERE l.item_id = si.item_id
          AND l.score <= si.item_score
        ORDER BY l.score DESC
 ) hit
 WHERE si.item_score IS NOT NULL
   AND si.item_level_name IS NULL;

PRINT N'62-pa-sheet-item-level 执行完成';
GO
