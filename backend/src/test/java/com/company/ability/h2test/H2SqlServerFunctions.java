package com.company.ability.h2test;

import java.sql.Timestamp;

/**
 * H2 测试库的 SQL Server 函数替身。
 * 生产代码（AssessSheetSyncService 等）的原生 SQL 使用 SYSDATETIME()，H2 2.x 不提供该函数；
 * schema-test.sql 末尾通过 CREATE ALIAS 将其映射到这里，仅供测试使用，不影响生产行为。
 */
public final class H2SqlServerFunctions {

    private H2SqlServerFunctions() {
    }

    /** SYSDATETIME() 替身：当前时间戳 */
    public static Timestamp sysdatetime() {
        return new Timestamp(System.currentTimeMillis());
    }
}
