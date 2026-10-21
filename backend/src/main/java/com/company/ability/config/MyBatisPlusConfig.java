package com.company.ability.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.company.ability.context.DimensionContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * MyBatis-Plus 配置
 */
@Configuration
public class MyBatisPlusConfig {

    /**
     * 参与维度隔离的表（均已有 dimension_id 列，见 sql/15）。
     * 泛微钩子直接写入的子表（sheet_row/rating/log/comment）、维度表本身、
     * 平台表（Hrm*）与已废弃旧表不在清单内，不受租户拦截影响。
     */
    private static final Set<String> TENANT_TABLES = Set.of(
            "uf_ability_category",
            "uf_ability_element",
            "uf_ability_element_level",
            "uf_org_ability_req",
            "uf_position_ability_req_new",
            "uf_position_ability_req_item",
            "uf_user_ability_req_new",
            "uf_user_ability_req_item",
            "uf_assess_weight_ability",
            "uf_assess_weight_assessor",
            "uf_assess_grade_level",
            "uf_assess_level_score",
            "uf_assess_period",
            "uf_assess_subject",
            "uf_assess_subject_item",
            "uf_assess_subject_client"
    );

    /**
     * 分页插件 + 维度（类租户）拦截器
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 多租户插件必须在分页插件之前添加
        TenantLineInnerInterceptor tenantInterceptor = new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                return new LongValue(DimensionContext.get());
            }

            @Override
            public String getTenantIdColumn() {
                return "dimension_id";
            }

            @Override
            public boolean ignoreTable(String tableName) {
                // 未指定维度（如泛微钩子调用）时不做隔离
                if (DimensionContext.get() == null) {
                    return true;
                }
                return !TENANT_TABLES.contains(normalize(tableName));
            }
        });
        interceptor.addInnerInterceptor(tenantInterceptor);

        PaginationInnerInterceptor paginationInterceptor = new PaginationInnerInterceptor(DbType.SQL_SERVER);
        // 设置最大单页限制数量，默认 500 条，-1 不受限制
        paginationInterceptor.setMaxLimit(500L);
        interceptor.addInnerInterceptor(paginationInterceptor);
        return interceptor;
    }

    /** 去掉 schema 前缀与中括号，统一小写比对 */
    private static String normalize(String tableName) {
        String name = tableName.toLowerCase().replace("[", "").replace("]", "");
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }
}
