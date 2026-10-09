-- 测试数据库 Schema

-- 能力类别表
CREATE TABLE IF NOT EXISTS uf_ability_category (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    category_name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    status INT DEFAULT 1,
    sort INT DEFAULT 0,
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

-- 岗位能力要求表
CREATE TABLE IF NOT EXISTS uf_position_ability_req (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_title_id BIGINT NOT NULL,
    element_id BIGINT NOT NULL,
    description VARCHAR(500),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
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

-- 泛微人员表（测试用）
CREATE TABLE IF NOT EXISTS HrmResource (
    id BIGINT PRIMARY KEY,
    lastname VARCHAR(100) NOT NULL,
    status INT DEFAULT 0
);
