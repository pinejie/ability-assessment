-- 测试数据库 Schema

-- 泛微分部表（测试用，分部子树匹配走上级链）
CREATE TABLE IF NOT EXISTS HrmSubCompany (
    id BIGINT PRIMARY KEY,
    subcompanyname VARCHAR(100) NOT NULL,
    supsubcomid BIGINT,
    canceled CHAR(1) DEFAULT '0'
);

-- 泛微部门表（测试用）
-- supdepid / subcompanyid1 与生产同名：部门树按上下级拼、维度授权按所属分部筛部门
CREATE TABLE IF NOT EXISTS HrmDepartment (
    id BIGINT PRIMARY KEY,
    departmentname VARCHAR(100) NOT NULL,
    departmentmark VARCHAR(100),
    supdepid BIGINT,
    subcompanyid1 BIGINT,
    canceled CHAR(1) DEFAULT '0'
);

-- 泛微岗位表（测试用）
CREATE TABLE IF NOT EXISTS HrmJobTitles (
    id BIGINT PRIMARY KEY,
    jobtitlemark VARCHAR(100) NOT NULL,
    jobtitlename VARCHAR(100),
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
    subcompanyid1 BIGINT,
    workcode VARCHAR(50),
    ecology_pinyin_search VARCHAR(200),
    birthday DATE
);

-- 维度（类租户）主数据（测试用，与 sql/14 + sql/57 对齐）
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

-- 维度授权表（测试用，与 sql/58 对齐）：分部 + 人员都在这张表；
-- 无授权行 = 仅系统管理员可用。auth_type=2 的人员同时是维度管理员（360 姓名可见）
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

-- ========== 年度（生产 sql/09、sql/14；P3 起旧测评页表已删除，仅年度表保留） ==========

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

-- 测评任务与评价人（sql/37、sql/38）。
-- 2026-11-21 起 P2 的新链路（PaTask* / PaFill*）也读写这两张表，故补齐它用到的生产列
-- （快照计数、撤回链、催办轮次、提交时间）。列长度与 sql/37、sql/38、sql/47 保持一致。
CREATE TABLE IF NOT EXISTS uf_assess_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL DEFAULT 1,
    dimension_id BIGINT NOT NULL DEFAULT 1,
    period_id BIGINT NOT NULL DEFAULT 1,
    sheet_id BIGINT,
    resource_id BIGINT NOT NULL DEFAULT 0,
    department_id BIGINT,
    leader_resource_id BIGINT,
    status TINYINT NOT NULL DEFAULT 0,
    rating_count INT NOT NULL DEFAULT 0,
    client_count INT NOT NULL DEFAULT 0,
    remark NVARCHAR(1000),
    create_by BIGINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    void_by BIGINT,
    void_time TIMESTAMP,
    void_reason NVARCHAR(400),
    void_notice_json NVARCHAR(MAX),
    replaced_by_task_id BIGINT
);

CREATE TABLE IF NOT EXISTS uf_assess_task_rater (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    rater_resource_id BIGINT NOT NULL,
    rater_type VARCHAR(10) NOT NULL,
    status TINYINT NOT NULL DEFAULT 0,
    comment NVARCHAR(1000),
    draft_json NVARCHAR(MAX),
    draft_time TIMESTAMP,
    draft_version INT NOT NULL DEFAULT 0,
    submit_time TIMESTAMP,
    remind_count INT NOT NULL DEFAULT 0,
    last_remind_time TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    access_token VARCHAR(64) NOT NULL DEFAULT ''
);

-- 360 测评人按能力项指派（sql/60 的 H2 版）。
-- 生产上的唯一索引 UX_rater_item (rater_id, item_id) 这里有 H2 语法限制，
-- 用例不靠数据库挡重复：写入侧本来就先去重，重复唯一性不是被测逻辑。
CREATE TABLE IF NOT EXISTS uf_assess_task_rater_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    rater_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- 通用消息队列表（与业务解耦；测评只往里插行，泛微 PushMsgJob 轮询发出）
-- 发起 / 催办 / 撤回通知都写这张表，所以发起链路测试必须有它。
CREATE TABLE IF NOT EXISTS uf_push_msg (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    receiver_id BIGINT NOT NULL,
    msg_type INT NOT NULL,
    title NVARCHAR(200) NOT NULL,
    content NVARCHAR(1000),
    pc_url VARCHAR(500),
    mobile_url VARCHAR(500),
    status TINYINT NOT NULL DEFAULT 0,
    channel VARCHAR(32) NOT NULL DEFAULT 'weaver',
    last_error NVARCHAR(500),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    sent_time TIMESTAMP
);

-- 维度集成配置（与 sql/66 对齐）：每个维度接哪套登录、消息往哪推
CREATE TABLE IF NOT EXISTS uf_assess_dimension_integration (
    dimension_id BIGINT PRIMARY KEY,
    sso_enabled BIT NOT NULL DEFAULT 1,
    sso_provider_type VARCHAR(32) NOT NULL,
    sso_client_id VARCHAR(128) NOT NULL,
    sso_client_secret VARCHAR(512) NOT NULL,
    sso_base_url VARCHAR(500),
    sso_authorize_url VARCHAR(500),
    sso_token_url VARCHAR(500),
    sso_profile_url VARCHAR(500),
    sso_userinfo_field VARCHAR(64),
    -- 第三方提供的参数（sql/70）：NULL = 没配过，取协议默认
    sso_callback_param VARCHAR(32),
    sso_token_method VARCHAR(8),
    sso_profile_method VARCHAR(8),
    sso_access_token_field VARCHAR(64),
    sso_match_field VARCHAR(32) NOT NULL,
    sso_redirect_path VARCHAR(200),
    push_enabled BIT NOT NULL DEFAULT 1,
    push_channel VARCHAR(32) NOT NULL,
    push_agent_id VARCHAR(64),
    push_secret VARCHAR(512),
    push_extra_json VARCHAR(4000),
    update_by BIGINT,
    update_time TIMESTAMP
);


-- ============================================================
-- 岗位能力模型（sql/50-pa-schema.sql 的 H2 版）
-- ------------------------------------------------------------
-- 与生产结构的差异只有两处，都是 H2 方言所限，不影响被测逻辑：
--   1) 筛选索引（WHERE deleted = 0）H2 不支持 → 这里建普通表，唯一性由服务层保证
--   2) NVARCHAR(MAX) → VARCHAR（H2 无 MAX 长度）
-- P3 起旧模型表（uf_ability_* / uf_position_* / uf_user_* / uf_assess_sheet* 等
-- 23 张）已从本文件删除 —— 生产侧同批 DROP 见 sql/51。
-- ============================================================
CREATE TABLE IF NOT EXISTS uf_pa_post (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dimension_id BIGINT NOT NULL,
    -- NULL = 全公司通用岗位（sql/63）
    department_id BIGINT,
    department_name VARCHAR(100),
    post_code VARCHAR(50),
    post_name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    status INT DEFAULT 1 NOT NULL,
    sort INT DEFAULT 0 NOT NULL,
    create_by BIGINT,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by BIGINT,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted INT DEFAULT 0 NOT NULL
);

CREATE TABLE IF NOT EXISTS uf_pa_category (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dimension_id BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    category_name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    sort INT DEFAULT 0 NOT NULL,
    status INT DEFAULT 1 NOT NULL,
    create_by BIGINT,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by BIGINT,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted INT DEFAULT 0 NOT NULL
);

CREATE TABLE IF NOT EXISTS uf_pa_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dimension_id BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    item_code VARCHAR(50),
    item_name VARCHAR(100) NOT NULL,
    definition VARCHAR(4000),
    weight DECIMAL(9,4) DEFAULT 0 NOT NULL,
    rater_leader INT DEFAULT 1 NOT NULL,
    rater_360 INT DEFAULT 0 NOT NULL,
    rater_text VARCHAR(100),
    sort INT DEFAULT 0 NOT NULL,
    status INT DEFAULT 1 NOT NULL,
    create_by BIGINT,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by BIGINT,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted INT DEFAULT 0 NOT NULL,
    CONSTRAINT CK_pa_item_rater CHECK (rater_leader = 1 OR rater_360 = 1)
);

CREATE TABLE IF NOT EXISTS uf_pa_item_level (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dimension_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    level_name VARCHAR(50) NOT NULL,
    score DECIMAL(6,2) NOT NULL,
    level_desc VARCHAR(4000),
    sort INT DEFAULT 0 NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS uf_pa_band (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dimension_id BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    band_name VARCHAR(50) NOT NULL,
    lower_bound DECIMAL(6,2),
    upper_bound DECIMAL(6,2),
    sort INT DEFAULT 0 NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS uf_pa_sheet (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    dimension_id BIGINT NOT NULL,
    period_id BIGINT NOT NULL,
    resource_id BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    department_id BIGINT,
    department_name VARCHAR(100),
    post_name VARCHAR(100),
    leader_resource_id BIGINT,
    status INT DEFAULT 0 NOT NULL,
    item_count INT DEFAULT 0 NOT NULL,
    self_score DECIMAL(10,4),
    leader_score DECIMAL(10,4),
    rater360_score DECIMAL(10,4),
    total_score DECIMAL(10,4),
    band_name VARCHAR(50),
    comment_text VARCHAR(4000),
    snapshot_json VARCHAR(4000),
    -- 封存（sql/64）：seal_status 0 未封存 / 1 已封存；sealed_json 是封存那一刻的整份报告
    seal_status INT DEFAULT 0 NOT NULL,
    seal_time TIMESTAMP,
    seal_by_loginid VARCHAR(50),
    seal_by_name VARCHAR(50),
    sealed_json CLOB,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS uf_pa_sheet_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dimension_id BIGINT NOT NULL,
    sheet_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    category_id BIGINT,
    category_name VARCHAR(100),
    item_code VARCHAR(50),
    item_name VARCHAR(100),
    definition VARCHAR(4000),
    sort_no INT DEFAULT 0 NOT NULL,
    weight DECIMAL(9,4),
    rater_leader INT DEFAULT 0 NOT NULL,
    rater_360 INT DEFAULT 0 NOT NULL,
    rater_text VARCHAR(100),
    leader_score DECIMAL(10,4),
    rater360_score DECIMAL(10,4),
    self_score DECIMAL(10,4),
    item_score DECIMAL(10,4),
    item_level_name VARCHAR(50),
    rater360_count INT DEFAULT 0 NOT NULL
);

CREATE TABLE IF NOT EXISTS uf_pa_rating (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dimension_id BIGINT NOT NULL,
    sheet_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    rater_type VARCHAR(10) NOT NULL,
    rater_resource_id BIGINT NOT NULL,
    level_id BIGINT NOT NULL,
    level_name VARCHAR(50),
    score DECIMAL(6,2) NOT NULL,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT CK_pa_rating_type CHECK (rater_type IN ('SELF', 'LEADER', 'RATER360'))
);

-- 能力结构导入会话（sql/55）。生产是 NVARCHAR(MAX)，H2 用 VARCHAR 不限长
CREATE TABLE IF NOT EXISTS uf_pa_import_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_key VARCHAR(64) NOT NULL,
    dimension_id BIGINT NOT NULL,
    resource_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    stage VARCHAR(100),
    file_name VARCHAR(255),
    file_size BIGINT,
    payload VARCHAR,
    mapping VARCHAR,
    overview VARCHAR,
    user_plan VARCHAR,
    pending VARCHAR,
    dialog VARCHAR,
    preview VARCHAR,
    result VARCHAR,
    snapshot VARCHAR,
    error VARCHAR(1000),
    round_no INT DEFAULT 0 NOT NULL,
    progress INT DEFAULT 0 NOT NULL,
    create_by BIGINT,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    expire_time TIMESTAMP NOT NULL
);

-- SQL Server 函数替身：生产 SQL 使用 SYSDATETIME()/ISNULL()，H2 无此函数（见 H2SqlServerFunctions）
CREATE ALIAS IF NOT EXISTS SYSDATETIME FOR "com.company.ability.h2test.H2SqlServerFunctions.sysdatetime";
CREATE ALIAS IF NOT EXISTS ISNULL FOR "com.company.ability.h2test.H2SqlServerFunctions.isNull";
