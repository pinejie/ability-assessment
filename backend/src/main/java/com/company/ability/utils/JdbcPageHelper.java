package com.company.ability.utils;

import com.company.ability.dto.PageRequest;
import com.company.ability.dto.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * JdbcTemplate 分页辅助工具（SQL Server）
 */
public class JdbcPageHelper {

    /**
     * 分页查询
     *
     * @param jdbcTemplate JdbcTemplate
     * @param baseSql      基础 SQL（不含 OFFSET/FETCH），必须包含 ORDER BY
     * @param countSql     计数 SQL（不含 ORDER BY）
     * @param params       SQL 参数
     * @param pageRequest  分页参数
     * @return 分页结果
     */
    public static PageResult<Map<String, Object>> page(
            JdbcTemplate jdbcTemplate,
            String baseSql,
            String countSql,
            List<Object> params,
            PageRequest pageRequest) {

        pageRequest.validate();

        // 查询总数
        Long total = jdbcTemplate.queryForObject(countSql, Long.class, params.toArray());
        if (total == null) {
            total = 0L;
        }

        // 分页查询
        int offset = (pageRequest.getPageNum() - 1) * pageRequest.getPageSize();
        String pageSql = baseSql + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";

        List<Object> pageParams = new java.util.ArrayList<>(params);
        pageParams.add(offset);
        pageParams.add(pageRequest.getPageSize());

        List<Map<String, Object>> list = jdbcTemplate.queryForList(pageSql, pageParams.toArray());

        return new PageResult<>(list, total, pageRequest.getPageNum(), pageRequest.getPageSize());
    }

    /**
     * 分页查询（无参数的简化版）
     */
    public static PageResult<Map<String, Object>> page(
            JdbcTemplate jdbcTemplate,
            String baseSql,
            String countSql,
            PageRequest pageRequest) {
        return page(jdbcTemplate, baseSql, countSql, List.of(), pageRequest);
    }
}
