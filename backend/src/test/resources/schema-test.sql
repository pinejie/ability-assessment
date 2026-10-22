-- 测试数据库 Schema

-- 能力类别表
CREATE TABLE IF NOT EXISTS uf_ability_category (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    category_name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    status INT DEFAULT 1,
    sort INT DEFAULT 0,
    dimension_id BIGINT DEFAULT 1,
    create_by BIGINT,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_by BIGINT,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted INT DEFAULT 0
);

-- 能力要素表
CREATE TABLE IF NOT EXISTS uf_ability_element (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    element_name VARCHAR(100) NOT NULL,
    element_code VARCHAR(50),
    category_id BIGINT NOT NULL,
    description VARCHAR(500),
    allow_client_assess INT DEFAULT 0,
    assess_in_flow INT DEFAULT 1,
    status INT DEFAULT 1,
    sort INT DEFAULT 0,
    create_by BIGINT,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_by BIGINT,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted INT DEFAULT 0
);

-- 能力要素等级配置表
CREATE TABLE IF NOT EXISTS uf_ability_element_level (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    element_id BIGINT NOT NULL,
    level INT NOT NULL,
    level_name VARCHAR(100) NOT NULL,
    level_requirement VARCHAR(500),
    score DECIMAL(10,2) NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 部门能力要求表
CREATE TABLE IF NOT EXISTS uf_org_ability_req (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    department_id BIGINT NOT NULL,
    element_id BIGINT NOT NULL,
    description VARCHAR(500),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 岗位能力要求表（旧版，保留兼容）
CREATE TABLE IF NOT EXISTS uf_position_ability_req (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_title_id BIGINT NOT NULL,
    element_id BIGINT NOT NULL,
    description VARCHAR(500),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 岗位能力要求主表（新版，实体 PositionAbilityReq）
CREATE TABLE IF NOT EXISTS uf_position_ability_req_new (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    department_id BIGINT,
    job_title_id BIGINT NOT NULL,
    description VARCHAR(500),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 岗位能力要求明细（新版，实体 PositionAbilityReqItem）
CREATE TABLE IF NOT EXISTS uf_position_ability_req_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    req_id BIGINT NOT NULL,
    category_id BIGINT,
    element_id BIGINT NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 人员能力要求表
CREATE TABLE IF NOT EXISTS uf_user_ability_req (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    resource_id BIGINT NOT NULL,
    element_id BIGINT NOT NULL,
    level_id BIGINT NOT NULL,
    score DECIMAL(10,2),
    description VARCHAR(500),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 评分权重配置表
CREATE TABLE IF NOT EXISTS uf_score_weight (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    weight_name VARCHAR(100) NOT NULL,
    category_weight DECIMAL(5,2) NOT NULL,
    element_weight DECIMAL(5,2) NOT NULL,
    description VARCHAR(500),
    create_by BIGINT,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_by BIGINT,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted INT DEFAULT 0
);

-- 泛微分部表（测试用，分部子树匹配走上级链）
CREATE TABLE IF NOT EXISTS HrmSubCompany (
    id BIGINT PRIMARY KEY,
    subcompanyname VARCHAR(100) NOT NULL,
    supsubcomid BIGINT,
    canceled CHAR(1) DEFAULT '0'
);

-- 泛微部门表（测试用）
CREATE TABLE IF NOT EXISTS HrmDepartment (
    id BIGINT PRIMARY KEY,
    departmentname VARCHAR(100) NOT NULL,
    canceled CHAR(1) DEFAULT '0'
);

-- 泛微岗位表（测试用）
CREATE TABLE IF NOT EXISTS HrmJobTitles (
    id BIGINT PRIMARY KEY,
    jobtitlemark VARCHAR(100) NOT NULL,
    canceled CHAR(1) DEFAULT '0'
);

-- 泛微人员表（测试用；loginid/status=1 在职口径与生产一致）
CREATE TABLE IF NOT EXISTS HrmResource (
    id BIGINT PRIMARY KEY,
    lastname VARCHAR(100) NOT NULL,
    status INT DEFAULT 0,
    loginid VARCHAR(100),
    departmentid BIGINT,
    jobtitle BIGINT,
    managerid BIGINT,
    subcompanyid1 BIGINT
);

-- 维度（类租户）主数据（测试用，与 sql/14 对齐）
CREATE TABLE IF NOT EXISTS uf_assess_dimension (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dimension_name VARCHAR(100) NOT NULL,
    dimension_code VARCHAR(50),
    description VARCHAR(500),
    status INT DEFAULT 1,
    sort INT DEFAULT 0,
    create_by BIGINT,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_by BIGINT,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted INT DEFAULT 0
);

-- 维度授权表（测试用；无授权行 = 所有人可用）
CREATE TABLE IF NOT EXISTS uf_assess_dimension_auth (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dimension_id BIGINT NOT NULL,
    auth_type INT NOT NULL,
    auth_id BIGINT NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_dimension_auth UNIQUE (dimension_id, auth_type, auth_id)
);

-- 系统管理员表（测试用，与 sql/21 对齐；物理删除小配置表）
CREATE TABLE IF NOT EXISTS uf_assess_admin (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    loginid VARCHAR(100) NOT NULL UNIQUE,
    lastname VARCHAR(90),
    remark VARCHAR(200),
    create_by BIGINT,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ========== 测评页面同步（AssessSheetSyncService 集成测试用，字段对齐 sql/09~17） ==========

CREATE TABLE IF NOT EXISTS uf_assess_period (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    period_name VARCHAR(100),
    year INT NOT NULL,
    status INT DEFAULT 0,
    dimension_id BIGINT DEFAULT 1,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS uf_assess_sheet (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    period_id BIGINT NOT NULL,
    resource_id BIGINT NOT NULL,
    department_id BIGINT,
    job_title_id BIGINT,
    leader_resource_id BIGINT,
    status INT DEFAULT 0,
    need_recalc INT DEFAULT 0,
    req_changed INT DEFAULT 0,
    client_count INT DEFAULT 0,
    dimension_id BIGINT DEFAULT 1,
    standard_score DECIMAL(10,4),
    leader_score DECIMAL(10,4),
    client_score DECIMAL(10,4),
    self_score DECIMAL(10,4),
    actual_score DECIMAL(10,4),
    score_rate DECIMAL(10,4),
    grade_name VARCHAR(20),
    sync_time TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sheet_period_resource UNIQUE (period_id, resource_id)
);

CREATE TABLE IF NOT EXISTS uf_assess_sheet_row (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id BIGINT NOT NULL,
    category_id BIGINT,
    category_name VARCHAR(100),
    element_id BIGINT NOT NULL,
    element_name VARCHAR(100),
    required_level_no INT,
    sort_no INT,
    active INT DEFAULT 1,
    dimension_id BIGINT DEFAULT 1,
    category_weight DECIMAL(10,4),
    standard_score DECIMAL(10,4),
    leader_level_no INT,
    leader_score DECIMAL(10,4),
    self_level_no INT,
    self_score DECIMAL(10,4),
    client_avg_score DECIMAL(10,4)
);

CREATE TABLE IF NOT EXISTS uf_assess_sheet_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id BIGINT NOT NULL,
    action VARCHAR(20),
    operator_resource_id BIGINT,
    detail_json VARCHAR(1000),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    dimension_id BIGINT DEFAULT 1
);

-- 人员能力要求（新版主表 + 明细）
CREATE TABLE IF NOT EXISTS uf_user_ability_req_new (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT,
    resource_id BIGINT NOT NULL,
    assess_year INT NOT NULL,
    dimension_id BIGINT DEFAULT 1,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS uf_user_ability_req_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    req_id BIGINT NOT NULL,
    element_id BIGINT NOT NULL,
    category_id BIGINT,
    level_id BIGINT,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 测评配置：类别权重 / 等级分值（Java 侧按 BigDecimal 读取，列必须是 DECIMAL）
CREATE TABLE IF NOT EXISTS uf_assess_weight_ability (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    dimension_id BIGINT DEFAULT 1,
    category_id BIGINT NOT NULL,
    weight DECIMAL(10,4) NOT NULL
);

CREATE TABLE IF NOT EXISTS uf_assess_level_score (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    dimension_id BIGINT DEFAULT 1,
    level_no INT NOT NULL,
    score DECIMAL(10,4) NOT NULL,
    CONSTRAINT uk_level_score UNIQUE (company_id, dimension_id, level_no)
);

-- SQL Server 函数替身：生产同步 SQL 使用 SYSDATETIME()，H2 无此函数（见 H2SqlServerFunctions）
CREATE ALIAS IF NOT EXISTS SYSDATETIME FOR "com.company.ability.h2test.H2SqlServerFunctions.sysdatetime";
