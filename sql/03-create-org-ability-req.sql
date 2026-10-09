-- 组织能力要求表
CREATE TABLE uf_org_ability_req (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    company_id BIGINT NOT NULL,
    org_id BIGINT NOT NULL,
    element_id BIGINT NOT NULL,
    required_level INT NOT NULL,
    description NVARCHAR(500),
    create_by BIGINT,
    create_time DATETIME2 DEFAULT GETDATE(),
    update_by BIGINT,
    update_time DATETIME2 DEFAULT GETDATE(),
    deleted INT DEFAULT 0,
    CONSTRAINT fk_org_ability_element FOREIGN KEY (element_id) REFERENCES uf_ability_element(id)
);

-- 添加索引
CREATE INDEX idx_org_ability_company ON uf_org_ability_req(company_id);
CREATE INDEX idx_org_ability_org ON uf_org_ability_req(org_id);
CREATE INDEX idx_org_ability_element ON uf_org_ability_req(element_id);

-- 插入测试数据
INSERT INTO uf_org_ability_req (company_id, org_id, element_id, required_level, description) VALUES
(1, 100, 1, 2, '分公司总部要求：责任心达到中级水平'),
(1, 100, 2, 3, '分公司总部要求：团队合作达到高级水平'),
(1, 101, 1, 1, '研发部门要求：责任心达到初级水平');
