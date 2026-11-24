-- ============================================================
-- 61 能力项增加「评价主体描述」（原文）
-- ------------------------------------------------------------
-- 背景：导入时「评价主体」这一列的文字原文被 parseRater 解析成 rater_leader / rater_360
-- 两个 0/1 就丢掉了。但 Excel 里写的其实是「跨部门360＋直接上级」「内部客户360」这类
-- 带业务含义的描述 —— 同样是 360，写「内部客户」和写「跨部门」不是一回事，
-- 拆成两个开关后这个区别在页面上再也看不见。
--
-- 取舍：
--   · 不把 rater 改成自由文本表 —— 算分、生成待评人、页面判断都要用这两个布尔，
--     改成文本就要在每处再解析一遍，且「无法解析」的项会让测评页推不动。
--   · 原文作为**附注列**保留，只用于显示与追溯，不参与任何计算。
--     两者不一致时（理论上导入器同一处写入，不会不一致）以布尔为准。
--
-- 长度取 100：现有最长写法「跨部门360＋直接上级」11 个字符，
--   留足空间给「技术委员会＋HRBP＋直接上级」这类写法。NVARCHAR(100) 按字符计。
--
-- uf_pa_sheet_item 同步加一列：测评页行是快照，历史报告要能回显当时的写法，
--   不能跟着岗位模型后续的修改变。
--
-- 幂等：重复执行不会重复加列。
-- 回滚：
--   ALTER TABLE uf_pa_item DROP COLUMN rater_text;
--   ALTER TABLE uf_pa_sheet_item DROP COLUMN rater_text;
-- ============================================================

USE ecology;
GO

IF NOT EXISTS (SELECT 1 FROM sys.columns
               WHERE object_id = OBJECT_ID('dbo.uf_pa_item') AND name = 'rater_text')
BEGIN
    ALTER TABLE dbo.uf_pa_item ADD rater_text NVARCHAR(100) NULL;
    PRINT N'uf_pa_item.rater_text 已添加';
END
ELSE
    PRINT N'uf_pa_item.rater_text 已存在，跳过';
GO

IF NOT EXISTS (SELECT 1 FROM sys.columns
               WHERE object_id = OBJECT_ID('dbo.uf_pa_sheet_item') AND name = 'rater_text')
BEGIN
    ALTER TABLE dbo.uf_pa_sheet_item ADD rater_text NVARCHAR(100) NULL;
    PRINT N'uf_pa_sheet_item.rater_text 已添加';
END
ELSE
    PRINT N'uf_pa_sheet_item.rater_text 已存在，跳过';
GO

-- ------------------------------------------------------------
-- 存量回填
-- ------------------------------------------------------------
-- 分两步，都只写 rater_text IS NULL 的行，重跑安全。
--
-- 第一步（兜底）：按布尔反推成最小写法。适用于没有原始解析结果的岗位
--   （法务专员、中层管理人员），不编造「跨部门」「内部客户」这类业务含义。
UPDATE i
   SET i.rater_text = CASE
         WHEN i.rater_leader = 1 AND i.rater_360 = 1 THEN N'直接上级＋360 测评人'
         WHEN i.rater_360 = 1 THEN N'360 测评人'
         ELSE N'直接上级'
       END
  FROM dbo.uf_pa_item i
 WHERE i.deleted = 0
   AND i.rater_text IS NULL
   AND i.rater_leader >= 0
   AND i.rater_360 >= 0;

-- 第二步（精确）：技术人员 / 信息化管理人员这两个岗位的原文在
--   sql/pa_sheets-20261121.json 的 K 列里，逐条按原文写回 —— 覆盖第一步的反推值。
--   注意「直接上级＋跨部门360」和「跨部门360＋直接上级」是 Excel 里的两种写法，
--   都按原文保留，不统一措辞（用户可能就是在表达主次）。
UPDATE dbo.uf_pa_item SET rater_text = N'直接上级'
 WHERE deleted = 0 AND post_id = 13 AND item_code IN (N'T1', N'T2', N'T3', N'T4', N'T5', N'T6');
UPDATE dbo.uf_pa_item SET rater_text = N'跨部门360＋直接上级'
 WHERE deleted = 0 AND post_id = 13 AND item_code = N'T7';
UPDATE dbo.uf_pa_item SET rater_text = N'直接上级＋跨部门360'
 WHERE deleted = 0 AND post_id = 13 AND item_code = N'T8';

UPDATE dbo.uf_pa_item SET rater_text = N'内部客户360'
 WHERE deleted = 0 AND post_id = 14 AND item_code IN (N'I1', N'I3');
UPDATE dbo.uf_pa_item SET rater_text = N'直接上级'
 WHERE deleted = 0 AND post_id = 14 AND item_code IN (N'I2', N'I4', N'I5', N'I6', N'I8');
UPDATE dbo.uf_pa_item SET rater_text = N'内部客户360＋直接上级'
 WHERE deleted = 0 AND post_id = 14 AND item_code = N'I7';

-- 第三步：测评页行快照跟着回填（历史报告能回显原文）。
--   sheet.status：0 未评价 / 1 部分提交 / 2 已算分。只补还没算分的（0、1），
--   已算分的那份快照是当时的记录，回填等于改写历史，不做。
UPDATE si
   SET si.rater_text = i.rater_text
  FROM dbo.uf_pa_sheet_item si
  JOIN dbo.uf_pa_item i ON i.id = si.item_id
  JOIN dbo.uf_pa_sheet s ON s.id = si.sheet_id
 WHERE si.rater_text IS NULL
   AND i.rater_text IS NOT NULL
   AND s.status IN (0, 1);

PRINT N'61-pa-item-rater-text 执行完成';
GO
