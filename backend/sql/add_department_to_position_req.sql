-- 为岗位能力要求表添加部门ID字段
ALTER TABLE uf_position_ability_req ADD departmentId INT NULL;

-- 添加等级和分数字段（与人员能力要求一致）
ALTER TABLE uf_position_ability_req ADD levelId INT NULL;
ALTER TABLE uf_position_ability_req ADD score DECIMAL(10,2) NULL;

-- 添加索引
CREATE INDEX idx_position_req_department ON uf_position_ability_req(departmentId);
CREATE INDEX idx_position_req_job_title ON uf_position_ability_req(jobTitleId);
