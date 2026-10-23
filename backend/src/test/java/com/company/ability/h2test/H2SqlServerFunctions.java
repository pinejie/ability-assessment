package com.company.ability.h2test;

import java.sql.Timestamp;

/**
 * H2 测试库的 SQL Server 函数替身。
 * 生产代码（AssessSheetSyncService / AssessReportService 等）的原生 SQL 使用 SYSDATETIME()、ISNULL()，
 * H2 2.x 不提供这些函数；schema-test.sql 末尾通过 CREATE ALIAS 将其映射到这里，仅供测试使用，不影响生产行为。
 */
public final class H2SqlServerFunctions {

    private H2SqlServerFunctions() {
    }

    /** SYSDATETIME() 替身：当前时间戳 */
    public static Timestamp sysdatetime() {
        return new Timestamp(System.currentTimeMillis());
    }

    /**
     * ISNULL(a, b) 替身：a 为空时返回 b。仅覆盖数值列（dimension_id 等，返回 Long 保证 JDBC 类型推断）。
     * 注意：生产 EcologyDataController 对 Hrm* 的 canceled CHAR(1) 列也用 ISNULL(canceled,0)——
     * 若新增测试触达这类查询会在 H2 报类型转换错，届时再按列类型扩展本别名，不改生产 SQL。
     */
    public static Long isNull(Long a, long b) {
        return a != null ? a : b;
    }
}
