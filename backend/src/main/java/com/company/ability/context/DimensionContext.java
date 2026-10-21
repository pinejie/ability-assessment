package com.company.ability.context;

/**
 * 当前请求的维度上下文（类租户）。
 * 由 DimensionContextFilter 从请求头 X-Dimension-Id 解析写入。
 * 为空表示调用方未指定维度：MyBatis 租户拦截器与原生 SQL 均跳过过滤（宽松模式，
 * 保证泛微钩子等未带维度头的既有集成不被打断），新增数据落库依赖列默认值（默认维度=1）。
 */
public final class DimensionContext {

    private static final ThreadLocal<Long> DIMENSION_ID = new ThreadLocal<>();

    private DimensionContext() {
    }

    public static void set(Long dimensionId) {
        DIMENSION_ID.set(dimensionId);
    }

    /** 当前维度 ID，可能为 null（未指定维度） */
    public static Long get() {
        return DIMENSION_ID.get();
    }

    /**
     * 原生 SQL 使用的有效维度：未指定时回落到默认维度（= 数据库列默认值 1），
     * 保证无维度头的调用与存量数据视图一致。
     */
    public static long getOrDefault() {
        Long id = DIMENSION_ID.get();
        return id != null ? id : DEFAULT_DIMENSION_ID;
    }

    /** 默认维度 ID（存量数据挂载点，见 sql/14） */
    public static final long DEFAULT_DIMENSION_ID = 1L;

    public static void clear() {
        DIMENSION_ID.remove();
    }
}
