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
 * 人员搜索的 SQL 形状。
 *
 * <p>这个接口服务于发起页的「被测人」与「360 测评人」两处。被测人默认仍搜全量在岗人员，
 * 但可以传 {@code departmentIds} 收窄到「已配置岗位的部门」；360 测评人不传，保持全量
 * （跨部门评价本就是 360 的语义）。用例守的是「别把年度过滤偷偷加回来」、
 * 「部门白名单只在传了才生效」以及「base 与 count 条件必须一致」。</p>
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
    @DisplayName("不按年度/人员映射过滤（被测人与 360 测评人都能搜全量在岗人员）")
    void searchKeepsFullPool() {
        controller.pageSearchResources("王", 1, 30, null);

        String baseSql = captureBaseSql();
        assertThat(baseSql).doesNotContain("uf_pa_person_post");
        assertThat(baseSql).doesNotContain("uf_assess_period");
    }

    @Test
    @DisplayName("只绑定 keyword 两个参数，其后是分页的 offset/size")
    void bindsOnlyKeywordParams() {
        controller.pageSearchResources("王", 1, 30, null);

        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, atLeastOnce()).queryForList(anyString(), params.capture());

        // JdbcPageHelper 在业务参数之后追加 offset 与 pageSize
        assertThat(params.getValue()).hasSize(4);
        assertThat(params.getValue()[0]).isEqualTo("%王%");
        assertThat(params.getValue()[1]).isEqualTo("%王%");
    }

    // ================================================================ 部门白名单

    @Test
    @DisplayName("传了 departmentIds 才加部门条件；不传则完全不过滤（360 仍搜全量）")
    void departmentFilterOnlyWhenGiven() {
        controller.pageSearchResources("王", 1, 30, null);
        assertThat(captureBaseSql()).doesNotContain("r.departmentid IN");

        controller.pageSearchResources("王", 1, 30, List.of());
        assertThat(captureBaseSql()).doesNotContain("r.departmentid IN");
    }

    @Test
    @DisplayName("部门白名单同时进 base 与 count，且参数排在 LIKE 之前")
    void departmentFilterInBothSqlAndParamsFirst() {
        controller.pageSearchResources("王", 1, 30, List.of(57L));

        String baseSql = captureBaseSql();
        assertThat(baseSql).contains("r.departmentid IN (?)");
        assertThat(captureCountSql()).contains("r.departmentid IN (?)");

        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, atLeastOnce()).queryForList(anyString(), params.capture());
        // 57 在前，两个 %王% 在后，最后是 offset/size
        assertThat(params.getValue()).hasSize(5);
        assertThat(params.getValue()[0]).isEqualTo(57L);
        assertThat(params.getValue()[1]).isEqualTo("%王%");
        assertThat(params.getValue()[2]).isEqualTo("%王%");
    }

    @Test
    @DisplayName("多个部门展开成多个占位符，不拼字符串（否则 57 会匹配到 157）")
    void multipleDepartmentsUsePlaceholders() {
        controller.pageSearchResources("王", 1, 30, List.of(57L, 88L));

        assertThat(captureBaseSql()).contains("r.departmentid IN (?, ?)");
    }

    @Test
    @DisplayName("全是不合法部门 ID 时返回空页，不能退化成「不过滤」")
    void illegalDepartmentIdsYieldEmptyPage() {
        Result<PageResult<Map<String, Object>>> r = controller.pageSearchResources("王", 1, 30, List.of(0L, -1L));

        assertThat(r.getData().getList()).isEmpty();
        assertThat(r.getData().getTotal()).isZero();
        // 闸门被拆掉的表现是「照样查了库」，这里必须一条 SQL 都没发
        verify(jdbcTemplate, org.mockito.Mockito.never())
                .queryForList(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("count 语句必须带 HrmResource 别名 r，否则总数与列表对不上")
    void countSqlUsesAlias() {
        controller.pageSearchResources("王", 1, 30, null);

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

        Result<PageResult<Map<String, Object>>> r = controller.pageSearchResources("王", 2, 10, null);

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

    /** count SQL 不带 ORDER BY，靠前缀区分 */
    private String captureCountSql() {
        ArgumentCaptor<String> sqls = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).queryForObject(sqls.capture(), eq(Long.class), any(Object[].class));
        return sqls.getAllValues().stream()
                .filter(s -> s.startsWith("SELECT COUNT(*) FROM HrmResource r "))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有抓到 count SQL"));
    }
}
