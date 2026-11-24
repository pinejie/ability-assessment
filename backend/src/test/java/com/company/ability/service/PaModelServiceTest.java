package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import com.company.ability.dto.PaBandSaveDTO;
import com.company.ability.dto.PaCategoryCreateDTO;
import com.company.ability.dto.PaCategoryUpdateDTO;
import com.company.ability.dto.PaItemCreateDTO;
import com.company.ability.dto.PaItemLevelDTO;
import com.company.ability.dto.PaItemUpdateDTO;
import com.company.ability.entity.PaRating;
import com.company.ability.entity.PaSheetItem;
import com.company.ability.exception.BusinessException;
import com.company.ability.mapper.PaRatingMapper;
import com.company.ability.mapper.PaSheetItemMapper;
import com.company.ability.vo.PaItemVO;
import com.company.ability.vo.PaModelVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 岗位能力模型服务测试（方案 17 §4.2~4.5）。
 *
 * <p>覆盖三类规则：</p>
 * <ul>
 *   <li>结构：类别名岗位内唯一、能力项编码<b>可重复</b>（sql/65）、对象必须属于该岗位</li>
 *   <li>等级：至少 2 档、排序号唯一、分值唯一（4 与 4.00 算同分）、被评级引用后禁删</li>
 *   <li>区间：首尾开口、相邻严密相接（重叠与断档都拒）</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("岗位能力模型服务测试")
class PaModelServiceTest {

    private static final long DIM = 1L;
    private static final long DEPT_HR = 601L;

    @Autowired
    private PaModelService modelService;

    @Autowired
    private PaPostService postService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PaRatingMapper ratingMapper;

    @Autowired
    private PaSheetItemMapper sheetItemMapper;

    private Long postId;

    @BeforeEach
    void setUp() {
        for (String t : List.of("uf_pa_rating", "uf_pa_sheet_item", "uf_pa_sheet",
                "uf_pa_band", "uf_pa_item_level", "uf_pa_item", "uf_pa_category", "uf_pa_post")) {
            jdbc.update("DELETE FROM " + t);
        }
        jdbc.update("DELETE FROM HrmDepartment");
        jdbc.update("INSERT INTO HrmDepartment (id, departmentname, departmentmark, canceled) VALUES (?, ?, ?, '0')",
                DEPT_HR, "人力资源部", "人力资源部");
        DimensionContext.set(DIM);

        com.company.ability.dto.PaPostCreateDTO post = new com.company.ability.dto.PaPostCreateDTO();
        post.setDepartmentId(DEPT_HR);
        post.setPostName("人事专员");
        postId = postService.createPost(post);
    }

    @AfterEach
    void tearDown() {
        DimensionContext.clear();
    }

    // ================================================================ 类别

    @Test
    @DisplayName("新增类别：岗位内同名被拒，跨岗位允许")
    void categoryNameUniqueWithinPost() {
        Long catId = modelService.createCategory(postId, category("领导力"));
        assertNotNull(catId);

        BusinessException dup = assertThrows(BusinessException.class,
                () -> modelService.createCategory(postId, category("领导力")));
        assertTrue(dup.getMessage().contains("已存在"));

        // 另一个岗位下同名类别可以建
        com.company.ability.dto.PaPostCreateDTO other = new com.company.ability.dto.PaPostCreateDTO();
        other.setDepartmentId(DEPT_HR);
        other.setPostName("质检专员");
        Long otherPost = postService.createPost(other);
        assertNotNull(modelService.createCategory(otherPost, category("领导力")));
    }

    @Test
    @DisplayName("删除类别：类别下有能项时拒绝，移走后可删")
    void deleteCategoryRejectedWhenHasItems() {
        Long catId = modelService.createCategory(postId, category("管理力"));
        modelService.createItem(postId, item(catId, "L1", "知人善用", "8", levels("入门", 1, "精进", 4)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> modelService.deleteCategory(postId, catId));
        assertTrue(ex.getMessage().contains("1 个能力项"));
        assertTrue(ex.getMessage().contains("停用"));

        PaModelVO model = modelService.getModel(postId);
        Long itemId = model.getItems().get(0).getId();
        modelService.deleteItem(postId, itemId);
        assertDoesNotThrow(() -> modelService.deleteCategory(postId, catId));
    }

    @Test
    @DisplayName("更新类别：改名撞到同岗位其他类别被拒")
    void updateCategoryRenamesWithUniqueness() {
        Long a = modelService.createCategory(postId, category("领导力"));
        modelService.createCategory(postId, category("管理力"));

        PaCategoryUpdateDTO dto = new PaCategoryUpdateDTO();
        dto.setId(a);
        dto.setCategoryName("管理力");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> modelService.updateCategory(postId, dto));
        assertTrue(ex.getMessage().contains("管理力"));

        dto.setCategoryName("管理力2");
        assertDoesNotThrow(() -> modelService.updateCategory(postId, dto));
        assertEquals("管理力2", modelService.getModel(postId).getCategories().stream()
                .filter(c -> c.getId().equals(a)).findFirst().orElseThrow().getCategoryName());
    }

    @Test
    @DisplayName("类别不属于该岗位时读写都被拒")
    void categoryFromAnotherPostRejected() {
        com.company.ability.dto.PaPostCreateDTO other = new com.company.ability.dto.PaPostCreateDTO();
        other.setDepartmentId(DEPT_HR);
        other.setPostName("质检专员");
        Long otherPost = postService.createPost(other);
        Long otherCat = modelService.createCategory(otherPost, category("质检专业"));

        // 拿别的岗位的类别往自己岗位建能力项
        PaItemCreateDTO dto = item(otherCat, "Q1", "抽检", "10", levels("入门", 1, "能手", 4));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> modelService.createItem(postId, dto));
        assertTrue(ex.getMessage().contains("不属于该岗位"));

        // 删别的岗位的类别
        assertThrows(BusinessException.class, () -> modelService.deleteCategory(postId, otherCat));
    }

    // ================================================================ 能力项

    @Test
    @DisplayName("模型读取：权重合计只算启用项，项数与未配好等级数正确")
    void modelAggregatesOnlyEnabledItems() {
        Long cat = modelService.createCategory(postId, category("领导力"));
        modelService.createItem(postId, item(cat, "L1", "知人善用", "30", levels("入门", 1, "精进", 4)));
        modelService.createItem(postId, item(cat, "L2", "培育带教", "20.5", levels("入门", 1, "能手", 4, "专家", 5)));

        PaItemCreateDTO disabled = item(cat, "L3", "停用项", "50", levels("入门", 1, "能手", 4));
        disabled.setStatus(0);
        modelService.createItem(postId, disabled);

        PaModelVO model = modelService.getModel(postId);
        assertEquals(2, model.getItemCount());
        assertEquals(0, new BigDecimal("50.5").compareTo(model.getWeightTotal()));
        assertEquals(0, model.getUnconfiguredItemCount());
        // 左树角标只数启用项
        assertEquals(2, model.getCategories().get(0).getItemCount());
        assertEquals(3, model.getItems().size());
    }

    @Test
    @DisplayName("能力项：编码可重复，留空也多条共存")
    void itemCodeCanRepeatWithinPost() {
        Long cat = modelService.createCategory(postId, category("领导力"));
        modelService.createItem(postId, item(cat, "E01", "服务意识", "10", levels("入门", 1, "精进", 4)));

        // 同一岗位再来一个 E01：模板里两段编号各自从 E01 起，这是正常写法，不是错误
        assertDoesNotThrow(
                () -> modelService.createItem(postId, item(cat, "E01", "抗压能力", "10", levels("入门", 1, "精进", 4))),
                "编码只是参考号，重复不该被拦");

        // 编码留空的同样可以有多条
        assertDoesNotThrow(() -> modelService.createItem(postId, item(cat, null, "培育带教", "10", levels("入门", 1, "精进", 4))));
        assertDoesNotThrow(() -> modelService.createItem(postId, item(cat, null, "团队激励", "10", levels("入门", 1, "精进", 4))));
    }

    @Test
    @DisplayName("能力项：修改时也能改成与同岗位其它项相同的编码")
    void updateItemAllowsDuplicateCode() {
        Long cat = modelService.createCategory(postId, category("领导力"));
        modelService.createItem(postId, item(cat, "E01", "服务意识", "10", levels("入门", 1, "精进", 4)));
        Long second = modelService.createItem(postId,
                item(cat, "E02", "抗压能力", "10", levels("入门", 1, "精进", 4)));

        PaItemUpdateDTO dto = new PaItemUpdateDTO();
        dto.setId(second);
        dto.setItemCode("E01");
        assertDoesNotThrow(() -> modelService.updateItem(postId, dto));
    }

    @Test
    @DisplayName("能力项：评级主体两个都不选被拒（与库层 CHECK 同口径）")
    void itemRejectsEmptyRater() {
        Long cat = modelService.createCategory(postId, category("领导力"));
        PaItemCreateDTO dto = item(cat, "L1", "知人善用", "10", levels("入门", 1, "精进", 4));
        dto.setRaterLeader(0);
        dto.setRater360(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> modelService.createItem(postId, dto));
        assertTrue(ex.getMessage().contains("至少选一个"));
    }

    @Test
    @DisplayName("能力项：权重为负被拒")
    void itemRejectsNegativeWeight() {
        Long cat = modelService.createCategory(postId, category("领导力"));
        PaItemCreateDTO dto = item(cat, "L1", "知人善用", "-1", levels("入门", 1, "精进", 4));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> modelService.createItem(postId, dto));
        assertTrue(ex.getMessage().contains("负数"));
    }

    @Test
    @DisplayName("等级：少于 2 档被拒")
    void levelsRequireAtLeastTwo() {
        Long cat = modelService.createCategory(postId, category("领导力"));
        PaItemCreateDTO dto = item(cat, "L1", "知人善用", "10", List.of(level("入门", 1, 0)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> modelService.createItem(postId, dto));
        assertTrue(ex.getMessage().contains("至少配置 2 个等级"));
    }

    @Test
    @DisplayName("等级：排序号重复被拒")
    void levelsRejectDuplicateSort() {
        Long cat = modelService.createCategory(postId, category("领导力"));
        List<PaItemLevelDTO> levels = new ArrayList<>();
        levels.add(level("入门", 1, 0));
        levels.add(level("精进", 4, 0));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> modelService.createItem(postId, item(cat, "L1", "知人善用", "10", levels)));
        assertTrue(ex.getMessage().contains("排序号"));
    }

    @Test
    @DisplayName("等级：分值重复被拒（4 与 4.00 视为同分）")
    void levelsRejectDuplicateScore() {
        Long cat = modelService.createCategory(postId, category("领导力"));
        List<PaItemLevelDTO> levels = new ArrayList<>();
        levels.add(level("入门", 1, 0));
        levels.add(level("精进", new BigDecimal("4.00"), 1));
        levels.add(level("能手", new BigDecimal("4"), 2));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> modelService.createItem(postId, item(cat, "L1", "知人善用", "10", levels)));
        assertTrue(ex.getMessage().contains("分值"), ex.getMessage());
        assertTrue(ex.getMessage().contains("重复"), ex.getMessage());
    }

    @Test
    @DisplayName("等级整批覆盖：新增+更新+删除一次完成")
    void levelsOverwriteInOneCall() {
        Long cat = modelService.createCategory(postId, category("领导力"));
        Long itemId = modelService.createItem(postId,
                item(cat, "L1", "知人善用", "10", levels("入门", 1, "精进", 4)));

        List<PaItemLevelDTO> current = levelDtosOf(itemId);
        PaItemLevelDTO keep = current.get(0);
        keep.setLevelDesc("能独立完成日常事务");
        PaItemLevelDTO add = level("能手", 5, 2);

        PaItemUpdateDTO dto = new PaItemUpdateDTO();
        dto.setId(itemId);
        // 只留第一档 + 新增一档 → 第二档（精进 4 分）被删
        dto.setLevels(List.of(keep, add));
        modelService.updateItem(postId, dto);

        List<PaItemLevelDTO> after = levelDtosOf(itemId);
        assertEquals(2, after.size());
        assertTrue(after.stream().anyMatch(l -> "入门".equals(l.getLevelName())
                && "能独立完成日常事务".equals(l.getLevelDesc())));
        assertTrue(after.stream().anyMatch(l -> "能手".equals(l.getLevelName())));
        assertTrue(after.stream().noneMatch(l -> "精进".equals(l.getLevelName())));
    }

    @Test
    @DisplayName("等级整批覆盖：删到 1 档被拒（否则填表页一项都点不出来）")
    void levelsCannotShrinkBelowTwo() {
        Long cat = modelService.createCategory(postId, category("领导力"));
        Long itemId = modelService.createItem(postId,
                item(cat, "L1", "知人善用", "10", levels("入门", 1, "精进", 4, "能手", 5)));

        PaItemUpdateDTO toOne = new PaItemUpdateDTO();
        toOne.setId(itemId);
        toOne.setLevels(List.of(levelDtosOf(itemId).get(0)));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> modelService.updateItem(postId, toOne), "1 档没有区分度，且填表时选不出档位");
        assertTrue(ex.getMessage().contains("至少配置 2 个等级"), ex.getMessage());
        assertEquals(3, levelDtosOf(itemId).size(), "被拒后原有等级不变");

        PaItemUpdateDTO toZero = new PaItemUpdateDTO();
        toZero.setId(itemId);
        toZero.setLevels(List.of());
        assertThrows(BusinessException.class, () -> modelService.updateItem(postId, toZero),
                "清空等级同样要拦");
        assertEquals(3, levelDtosOf(itemId).size());
    }

    @Test
    @DisplayName("等级整批覆盖：levels 传 null 时等级保持不变")
    void levelsNullMeansUntouched() {        Long cat = modelService.createCategory(postId, category("领导力"));
        Long itemId = modelService.createItem(postId,
                item(cat, "L1", "知人善用", "10", levels("入门", 1, "精进", 4)));

        PaItemUpdateDTO dto = new PaItemUpdateDTO();
        dto.setId(itemId);
        dto.setWeight(new BigDecimal("15"));
        modelService.updateItem(postId, dto);

        PaItemVO after = modelService.getModel(postId).getItems().get(0);
        assertEquals(0, new BigDecimal("15").compareTo(after.getWeight()));
        assertEquals(2, after.getLevels().size());
    }

    @Test
    @DisplayName("评价主体描述：新建落库、传 null 不改、传空串清空")
    void raterTextLifecycle() {
        Long cat = modelService.createCategory(postId, category("领导力"));
        PaItemCreateDTO create = item(cat, "L1", "知人善用", "10", levels("入门", 1, "精进", 4));
        create.setRaterText("  内部客户360＋直接上级  ");
        Long itemId = modelService.createItem(postId, create);

        // 前后空格不能留：页面上会显示成一个看不出来的空白缩进
        assertEquals("内部客户360＋直接上级",
                modelService.getModel(postId).getItems().get(0).getRaterText());

        PaItemUpdateDTO keep = new PaItemUpdateDTO();
        keep.setId(itemId);
        keep.setWeight(new BigDecimal("12"));
        modelService.updateItem(postId, keep);
        assertEquals("内部客户360＋直接上级", modelService.getModel(postId).getItems().get(0).getRaterText(),
                "传 null 表示不动，改别的字段不能把描述冲掉");

        PaItemUpdateDTO clear = new PaItemUpdateDTO();
        clear.setId(itemId);
        clear.setRaterText("");
        modelService.updateItem(postId, clear);
        assertNull(modelService.getModel(postId).getItems().get(0).getRaterText(),
                "传空串表示清空：用户把原文删了就该消失");
    }

    @Test
    @DisplayName("等级被评级引用后禁止删除（整批拒绝，不部分保存）")
    void levelsInUseCannotBeDeleted() {
        Long cat = modelService.createCategory(postId, category("领导力"));
        // 造 3 档：删掉被引用的那档后仍有 2 档，才不会先撞上「至少 2 档」的护栏，
        // 确保这里测的是「被引用不能删」而不是别的规则。
        Long itemId = modelService.createItem(postId,
                item(cat, "L1", "知人善用", "10", levels("入门", 1, "精进", 4, "能手", 5)));
        List<PaItemLevelDTO> levels = levelDtosOf(itemId);
        PaItemLevelDTO rated = levels.get(1);

        PaRating rating = new PaRating();
        rating.setSheetId(9_000_001L);
        rating.setItemId(itemId);
        rating.setRaterType(PaRating.TYPE_LEADER);
        rating.setRaterResourceId(77L);
        rating.setLevelId(rated.getId());
        rating.setLevelName(rated.getLevelName());
        rating.setScore(rated.getScore());
        ratingMapper.insert(rating);

        PaItemUpdateDTO dto = new PaItemUpdateDTO();
        dto.setId(itemId);
        dto.setLevels(List.of(levels.get(0), levels.get(2)));   // 想删掉被引用的那档
        BusinessException ex = assertThrows(BusinessException.class,
                () -> modelService.updateItem(postId, dto));
        assertTrue(ex.getMessage().contains("评级"), ex.getMessage());

        // 整批拒绝：另两档也原样保留
        assertEquals(3, levelDtosOf(itemId).size());
    }

    @Test
    @DisplayName("能力项被测评页引用后禁止删除")
    void itemInUseCannotBeDeleted() {
        Long cat = modelService.createCategory(postId, category("领导力"));
        Long itemId = modelService.createItem(postId,
                item(cat, "L1", "知人善用", "10", levels("入门", 1, "精进", 4)));

        PaSheetItem si = new PaSheetItem();
        si.setSheetId(9_000_002L);
        si.setItemId(itemId);
        si.setItemName("知人善用");
        sheetItemMapper.insert(si);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> modelService.deleteItem(postId, itemId));
        assertTrue(ex.getMessage().contains("不能删除"));
        assertTrue(ex.getMessage().contains("停用"));
    }

    // ================================================================ 分值区间

    @Test
    @DisplayName("区间保存：首尾开口且严密相接时通过，读取按排序返回")
    void saveBandsSucceeds() {
        modelService.saveBands(postId, List.of(
                band("新手", null, "3", 0),
                band("精进/能手", "3", "4", 1),
                band("高手", "4", null, 2)));

        List<com.company.ability.vo.PaBandVO> bands = modelService.listBands(postId);
        assertEquals(3, bands.size());
        assertNull(bands.get(0).getLowerBound());
        assertNull(bands.get(2).getUpperBound());
        assertEquals("精进/能手", bands.get(1).getBandName());
    }

    @Test
    @DisplayName("区间保存：最低档下界可以写具体分值（业务表就是 [1,2) 这样从 1 分起）")
    void saveBandsAllowsClosedBottom() {
        modelService.saveBands(postId, List.of(
                band("新手", "1", "2", 0),
                band("熟手", "2", "3", 1),
                band("能手", "3", "4", 2),
                band("高手", "4", "5", 3),
                band("专家", "5", null, 4)));

        List<com.company.ability.vo.PaBandVO> bands = modelService.listBands(postId);
        assertEquals(5, bands.size());
        assertEquals(0, new BigDecimal("1").compareTo(bands.get(0).getLowerBound()), "首档下界照存 1");
    }

    @Test
    @DisplayName("区间保存：中间档上界留空被拒（留空表示 +∞，后面还有档就永远命中不到）")
    void saveBandsRejectsEmptyMiddleUpper() {
        BusinessException ex = assertThrows(BusinessException.class, () -> modelService.saveBands(postId, List.of(
                band("新手", null, null, 0),
                band("高手", "3", "5", 1),
                band("专家", "5", null, 2))));
        assertTrue(ex.getMessage().contains("上界不能留空"), ex.getMessage());
    }

    @Test
    @DisplayName("区间保存：中间档下界留空被拒（留空表示 −∞，前面还有档就永远命中不到）")
    void saveBandsRejectsEmptyMiddleLower() {
        BusinessException ex = assertThrows(BusinessException.class, () -> modelService.saveBands(postId, List.of(
                band("新手", null, "3", 0),
                band("中间", null, "5", 1),
                band("高手", "5", null, 2))));
        assertTrue(ex.getMessage().contains("下界不能留空"), ex.getMessage());
    }

    @Test
    @DisplayName("区间保存：最高档上界不为空被拒（高分命中不到区间）")
    void saveBandsRejectsClosedTop() {
        BusinessException ex = assertThrows(BusinessException.class, () -> modelService.saveBands(postId, List.of(
                band("新手", null, "3", 0),
                band("高手", "3", "5", 1))));
        assertTrue(ex.getMessage().contains("留空"), ex.getMessage());
    }

    @Test
    @DisplayName("区间保存：重叠被拒")
    void saveBandsRejectsOverlap() {
        BusinessException ex = assertThrows(BusinessException.class, () -> modelService.saveBands(postId, List.of(
                band("新手", null, "3", 0),
                band("精进", "2", "4", 1),
                band("高手", "4", null, 2))));
        assertTrue(ex.getMessage().contains("重叠"), ex.getMessage());
    }

    @Test
    @DisplayName("区间保存：断档被拒")
    void saveBandsRejectsGap() {
        BusinessException ex = assertThrows(BusinessException.class, () -> modelService.saveBands(postId, List.of(
                band("新手", null, "3", 0),
                band("高手", "3.5", null, 1))));
        assertTrue(ex.getMessage().contains("断档"), ex.getMessage());
    }

    @Test
    @DisplayName("区间保存：下界不小于上界被拒")
    void saveBandsRejectsInvertedRange() {
        BusinessException ex = assertThrows(BusinessException.class, () -> modelService.saveBands(postId, List.of(
                band("新手", null, "3", 0),
                band("反向", "4", "4", 1),
                band("高手", "4", null, 2))));
        assertTrue(ex.getMessage().contains("下界必须小于上界"), ex.getMessage());
    }

    @Test
    @DisplayName("区间保存：整批替换，旧区间不残留")
    void saveBandsReplacesAll() {
        modelService.saveBands(postId, List.of(
                band("新手", null, "3", 0),
                band("高手", "3", null, 1)));
        modelService.saveBands(postId, List.of(
                band("合格", null, "2.5", 0),
                band("优秀", "2.5", null, 1)));

        List<com.company.ability.vo.PaBandVO> bands = modelService.listBands(postId);
        assertEquals(2, bands.size());
        assertTrue(bands.stream().noneMatch(b -> "新手".equals(b.getBandName())));
    }

    @Test
    @DisplayName("区间保存：空列表表示清空，允许")
    void saveBandsAllowsEmpty() {
        modelService.saveBands(postId, List.of(
                band("新手", null, "3", 0),
                band("高手", "3", null, 1)));
        assertDoesNotThrow(() -> modelService.saveBands(postId, List.of()));
        assertTrue(modelService.listBands(postId).isEmpty());
    }

    @Test
    @DisplayName("岗位不存在时读写都抛业务异常")
    void missingPostRejected() {
        long missing = 987_654L;
        assertThrows(BusinessException.class, () -> modelService.getModel(missing));
        assertThrows(BusinessException.class, () -> modelService.createCategory(missing, category("领导力")));
        assertThrows(BusinessException.class, () -> modelService.saveBands(missing, List.of()));
    }

    // ================================================================ 构造工具

    private PaCategoryCreateDTO category(String name) {
        PaCategoryCreateDTO dto = new PaCategoryCreateDTO();
        dto.setCategoryName(name);
        return dto;
    }

    private PaItemCreateDTO item(Long categoryId, String code, String name, String weight,
                                 List<PaItemLevelDTO> levels) {
        PaItemCreateDTO dto = new PaItemCreateDTO();
        dto.setCategoryId(categoryId);
        dto.setItemCode(code);
        dto.setItemName(name);
        dto.setWeight(new BigDecimal(weight));
        dto.setSort(0);
        dto.setLevels(new ArrayList<>(levels));
        return dto;
    }

    /** 交替传 (名称, 分值) 构造等级，排序号按传入顺序 0,1,2… */
    private List<PaItemLevelDTO> levels(Object... nameScorePairs) {
        List<PaItemLevelDTO> list = new ArrayList<>();
        for (int i = 0; i < nameScorePairs.length; i += 2) {
            String name = (String) nameScorePairs[i];
            Object score = nameScorePairs[i + 1];
            BigDecimal value = score instanceof BigDecimal b ? b : new BigDecimal(score.toString());
            list.add(level(name, value, i / 2));
        }
        return list;
    }

    private PaItemLevelDTO level(String name, Number score, int sort) {
        return level(name, new BigDecimal(score.toString()), sort);
    }

    private PaItemLevelDTO level(String name, BigDecimal score, int sort) {
        PaItemLevelDTO dto = new PaItemLevelDTO();
        dto.setLevelName(name);
        dto.setScore(score);
        dto.setSort(sort);
        return dto;
    }

    private List<PaItemLevelDTO> levelDtosOf(Long itemId) {
        return modelService.getModel(postId).getItems().stream()
                .filter(i -> i.getId().equals(itemId)).findFirst().orElseThrow()
                .getLevels().stream().map(l -> {
                    PaItemLevelDTO dto = new PaItemLevelDTO();
                    dto.setId(l.getId());
                    dto.setLevelName(l.getLevelName());
                    dto.setScore(l.getScore());
                    dto.setLevelDesc(l.getLevelDesc());
                    dto.setSort(l.getSort());
                    return dto;
                }).toList();
    }

    private PaBandSaveDTO band(String name, String lower, String upper, int sort) {
        PaBandSaveDTO dto = new PaBandSaveDTO();
        dto.setBandName(name);
        dto.setLowerBound(lower == null ? null : new BigDecimal(lower));
        dto.setUpperBound(upper == null ? null : new BigDecimal(upper));
        dto.setSort(sort);
        return dto;
    }
}
