-- 18 维度模板复制：把源维度的配置类主数据整套复制到目标维度
-- 复制范围（7 张配置表 + 年度）：
--   能力类别 → 能力要素 → 要素等级 → 能力类型权重 → 测评对象权重 → 等级分值 → 等级标准 → 年度
-- 不复制：组织/岗位/人员能力要求、测评页面/评级/结果（业务过程数据，按维度自行配置）。
-- 用法：改 @src_dim / @dst_dim 后整批执行（脚本自带防重守卫，重复执行会被中止）。
-- 依赖：sql/19 的唯一约束修正（assessor/level_score/period 唯一键需含 dimension_id，
--       否则第二个维度的这三张表复制会被 2627 拦截）。19 已应用，重复复制其他维度不再受影响。
-- 实现：游标逐行插入 + 旧ID→新ID映射表，保证要素挂新类别、等级挂新要素、权重挂新类别。

SET NOCOUNT ON;
DECLARE @src_dim BIGINT = 1;   -- 源维度（默认维度）
DECLARE @dst_dim BIGINT = 3;   -- 目标维度（中达集团）

-- ============ 前置校验 ============
IF NOT EXISTS (SELECT 1 FROM uf_assess_dimension WHERE id = @dst_dim AND deleted = 0)
    THROW 50301, N'目标维度不存在', 1;

-- 防重：目标维度已有任何配置数据则中止
IF EXISTS (SELECT 1 FROM uf_ability_category WHERE dimension_id = @dst_dim AND deleted = 0)
    THROW 50302, N'目标维度已有类别数据，中止复制（避免重复复制两套）', 1;
IF EXISTS (SELECT 1 FROM uf_ability_element WHERE dimension_id = @dst_dim AND deleted = 0)
    THROW 50302, N'目标维度已有要素数据，中止复制（避免重复复制两套）', 1;
IF EXISTS (SELECT 1 FROM uf_assess_weight_ability WHERE dimension_id = @dst_dim)
    THROW 50302, N'目标维度已有测评配置数据，中止复制', 1;

CREATE TABLE #map_cat (old_id BIGINT PRIMARY KEY, new_id BIGINT);
CREATE TABLE #map_elem (old_id BIGINT PRIMARY KEY, new_id BIGINT);

DECLARE @old BIGINT, @new BIGINT;

-- ============ 1. 能力类别 ============
DECLARE c1 CURSOR LOCAL FAST_FORWARD FOR
    SELECT id FROM uf_ability_category
    WHERE dimension_id = @src_dim AND deleted = 0
    ORDER BY sort, id;
OPEN c1; FETCH NEXT FROM c1 INTO @old;
WHILE @@FETCH_STATUS = 0
BEGIN
    INSERT INTO uf_ability_category
        (category_name, description, status, sort, create_by, create_time, update_by, update_time, deleted, dimension_id)
    SELECT category_name, description, status, sort, create_by, GETDATE(), update_by, GETDATE(), 0, @dst_dim
    FROM uf_ability_category WHERE id = @old;
    SET @new = SCOPE_IDENTITY();
    INSERT INTO #map_cat VALUES (@old, @new);
    FETCH NEXT FROM c1 INTO @old;
END
CLOSE c1; DEALLOCATE c1;

-- ============ 2. 能力要素（重挂新类别，跳过逻辑删除行与已删类别下的孤儿行） ============
DECLARE c2 CURSOR LOCAL FAST_FORWARD FOR
    SELECT e.id FROM uf_ability_element e
    JOIN #map_cat m ON m.old_id = e.category_id
    WHERE e.dimension_id = @src_dim AND e.deleted = 0
    ORDER BY e.category_id, e.sort, e.id;
OPEN c2; FETCH NEXT FROM c2 INTO @old;
WHILE @@FETCH_STATUS = 0
BEGIN
    INSERT INTO uf_ability_element
        (element_name, element_code, category_id, description, status, sort,
         create_by, create_time, update_by, update_time, deleted, dimension_id, allow_client_assess, assess_in_flow)
    SELECT e.element_name, e.element_code, m.new_id, e.description, e.status, e.sort,
           e.create_by, GETDATE(), e.update_by, GETDATE(), 0, @dst_dim, e.allow_client_assess,
           COALESCE(e.assess_in_flow, 1)
    FROM uf_ability_element e
    JOIN #map_cat m ON m.old_id = e.category_id
    WHERE e.id = @old;
    SET @new = SCOPE_IDENTITY();
    INSERT INTO #map_elem VALUES (@old, @new);
    FETCH NEXT FROM c2 INTO @old;
END
CLOSE c2; DEALLOCATE c2;

-- ============ 3. 要素等级（重挂新要素） ============
DECLARE c3 CURSOR LOCAL FAST_FORWARD FOR
    SELECT l.id FROM uf_ability_element_level l
    JOIN #map_elem me ON me.old_id = l.element_id
    ORDER BY l.element_id, l.[level];
OPEN c3; FETCH NEXT FROM c3 INTO @old;
WHILE @@FETCH_STATUS = 0
BEGIN
    INSERT INTO uf_ability_element_level
        (element_id, [level], level_name, level_requirement, score, create_time, update_time, dimension_id)
    SELECT me.new_id, l.[level], l.level_name, l.level_requirement, l.score, GETDATE(), GETDATE(), @dst_dim
    FROM uf_ability_element_level l
    JOIN #map_elem me ON me.old_id = l.element_id
    WHERE l.id = @old;
    FETCH NEXT FROM c3 INTO @old;
END
CLOSE c3; DEALLOCATE c3;

-- ============ 4. 能力类型权重（重挂新类别） ============
DECLARE c4 CURSOR LOCAL FAST_FORWARD FOR
    SELECT w.id FROM uf_assess_weight_ability w
    JOIN #map_cat m ON m.old_id = w.category_id
    WHERE w.dimension_id = @src_dim
    ORDER BY w.category_id;
OPEN c4; FETCH NEXT FROM c4 INTO @old;
WHILE @@FETCH_STATUS = 0
BEGIN
    INSERT INTO uf_assess_weight_ability
        (company_id, category_id, weight, update_by, update_time, dimension_id)
    SELECT w.company_id, m.new_id, w.weight, w.update_by, GETDATE(), @dst_dim
    FROM uf_assess_weight_ability w
    JOIN #map_cat m ON m.old_id = w.category_id
    WHERE w.id = @old;
    FETCH NEXT FROM c4 INTO @old;
END
CLOSE c4; DEALLOCATE c4;

-- ============ 5. 测评对象权重 / 等级分值 / 等级标准（无外键，整表集合复制） ============
INSERT INTO uf_assess_weight_assessor (company_id, assessor_code, weight, update_by, update_time, dimension_id)
SELECT company_id, assessor_code, weight, update_by, GETDATE(), @dst_dim
FROM uf_assess_weight_assessor WHERE dimension_id = @src_dim;

INSERT INTO uf_assess_level_score (company_id, level_no, score, update_by, update_time, dimension_id)
SELECT company_id, level_no, score, update_by, GETDATE(), @dst_dim
FROM uf_assess_level_score WHERE dimension_id = @src_dim;

INSERT INTO uf_assess_grade_level (company_id, level_name, lower_bound, upper_bound, sort_order, update_by, update_time, dimension_id)
SELECT company_id, level_name, lower_bound, upper_bound, sort_order, update_by, GETDATE(), @dst_dim
FROM uf_assess_grade_level WHERE dimension_id = @src_dim;

-- ============ 6. 年度（目标维度已存在的 (company,year) 自动跳过） ============
INSERT INTO uf_assess_period (company_id, [year], status, create_time, update_time, dimension_id)
SELECT p.company_id, p.[year], p.status, GETDATE(), GETDATE(), @dst_dim
FROM uf_assess_period p
WHERE p.dimension_id = @src_dim
  AND NOT EXISTS (SELECT 1 FROM uf_assess_period t
                  WHERE t.dimension_id = @dst_dim AND t.company_id = p.company_id AND t.[year] = p.[year]);

-- ============ 结果自检 ============
DECLARE @msg NVARCHAR(400) = CONCAT(
    N'复制完成 维度', @src_dim, N' -> ', @dst_dim,
    N' | 类别:', (SELECT COUNT(*) FROM uf_ability_category WHERE dimension_id = @dst_dim AND deleted = 0),
    N' 要素:', (SELECT COUNT(*) FROM uf_ability_element WHERE dimension_id = @dst_dim AND deleted = 0),
    N' 等级:', (SELECT COUNT(*) FROM uf_ability_element_level WHERE dimension_id = @dst_dim),
    N' 能力权重:', (SELECT COUNT(*) FROM uf_assess_weight_ability WHERE dimension_id = @dst_dim),
    N' 对象权重:', (SELECT COUNT(*) FROM uf_assess_weight_assessor WHERE dimension_id = @dst_dim),
    N' 等级分值:', (SELECT COUNT(*) FROM uf_assess_level_score WHERE dimension_id = @dst_dim),
    N' 等级标准:', (SELECT COUNT(*) FROM uf_assess_grade_level WHERE dimension_id = @dst_dim),
    N' 年度:', (SELECT COUNT(*) FROM uf_assess_period WHERE dimension_id = @dst_dim));
PRINT @msg;

DROP TABLE #map_cat;
DROP TABLE #map_elem;
