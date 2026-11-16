package com.company.ability.controller;

import com.company.ability.context.DimensionContext;
import com.company.ability.dto.PageResult;
import com.company.ability.dto.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 人员搜索的年度过滤。
 *
 * 这个接口有两个调用方：发起页的「被测人」必须只看已配置能力要求的人（否则选谁都是白选一次再报错），
 * 「客户评价人」必须能看到全量在岗人员。所以 requireYear 只能是**可选**参数，
 * 一旦改成必填或默认值，客户评价人那边就搜不到人了。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EcologyDataControllerTest {

    private static final long DIM = 3L;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private EcologyDataController controller;

    @BeforeEach
    void setUp() {
        controller = new EcologyDataController();
        ReflectionTestUtils.setField(controller, "jdbcTemplate", jdbcTemplate);
        DimensionContext.set(DIM);

        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        DimensionContext.clear();
    }

    @Test
    @DisplayName("不传 requireYear 时 SQL 不含能力要求过滤（客户评价人仍能搜全量）")
    void searchWithoutRequireYearKeepsFullPool() {
        controller.pageSearchResources("王", 1, 30, null);

        String baseSql = captureBaseSql();
        assertThat(baseSql).doesNotContain("uf_user_ability_req_new");
    }

    @Test
    @DisplayName("传 requireYear 时按 年度 + 当前维度 过滤，且 base/count 两处条件一致")
    void searchWithRequireYearFiltersByYearAndDimension() {
        controller.pageSearchResources("王", 1, 30, 2026);

        ArgumentCaptor<String> sqls = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).queryForList(sqls.capture(), any(Object[].class));
        verify(jdbcTemplate, atLeastOnce()).queryForObject(sqls.capture(), eq(Long.class), any(Object[].class));

        List<String> withRequirement = sqls.getAllValues().stream()
                .filter(s -> s.contains("uf_user_ability_req_new"))
                .toList();
        // base 与 count 各一条，且两条都必须带同样的 EXISTS 片段
        assertThat(withRequirement).hasSize(2);
        assertThat(withRequirement).allSatisfy(sql -> {
            assertThat(sql).contains("q.assess_year = ?");
            assertThat(sql).contains("q.dimension_id = ?");
            assertThat(sql).contains("q.resource_id = r.id");
        });
    }

    @Test
    @DisplayName("requireYear 的参数顺序必须是 keyword, keyword, year, dimension（其后才是分页的 offset/size）")
    void requireYearBindsYearThenDimension() {
        controller.pageSearchResources("王", 1, 30, 2026);

        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, atLeastOnce()).queryForList(anyString(), params.capture());

        Object[] captured = params.getValue();
        // JdbcPageHelper 在业务参数之后追加 offset 与 pageSize
        assertThat(captured).hasSize(6);
        assertThat(captured[0]).isEqualTo("%王%");
        assertThat(captured[1]).isEqualTo("%王%");
        assertThat(captured[2]).isEqualTo(2026);
        assertThat(captured[3]).isEqualTo(DIM);
    }

    @Test
    @DisplayName("未指定维度头时回落到默认维度 1，保证与数据库列默认值一致")
    void requireYearFallsBackToDefaultDimension() {
        DimensionContext.clear();

        controller.pageSearchResources("王", 1, 30, 2026);

        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, atLeastOnce()).queryForList(anyString(), params.capture());
        assertThat(params.getValue()[3]).isEqualTo(DimensionContext.DEFAULT_DIMENSION_ID);
    }

    @Test
    @DisplayName("count 语句必须带 HrmResource 别名 r，否则过滤后总数与列表对不上")
    void countSqlUsesAlias() {
        controller.pageSearchResources("王", 1, 30, 2026);

        ArgumentCaptor<String> sqls = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).queryForObject(sqls.capture(), eq(Long.class), any(Object[].class));

        String countSql = sqls.getValue();
        assertThat(countSql).startsWith("SELECT COUNT(*) FROM HrmResource r ");
        assertThat(countSql).contains("r.lastname LIKE ?");
        assertThat(countSql).contains("r.ecology_pinyin_search LIKE ?");
    }

    @Test
    @DisplayName("SQL 异常时返回空页而不是 500（搜索框不该把整页打挂）")
    void searchFailureReturnsEmptyPage() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenThrow(new RuntimeException("boom"));

        Result<PageResult<Map<String, Object>>> r = controller.pageSearchResources("王", 2, 10, 2026);

        assertThat(r.getData().getList()).isEmpty();
        assertThat(r.getData().getTotal()).isZero();
        assertThat(r.getData().getPageNum()).isEqualTo(2);
    }

    /** 从 queryForList 里捞出带 ORDER BY 的那条（base SQL） */
    private String captureBaseSql() {
        ArgumentCaptor<String> sqls = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).queryForList(sqls.capture(), any(Object[].class));
        return sqls.getAllValues().stream()
                .filter(s -> s.contains("ORDER BY r.lastname"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有抓到 base SQL"));
    }
}
