package com.company.ability.service;

import com.company.ability.dto.PaImportDraft;
import com.company.ability.dto.PaImportQuestion;
import com.company.ability.entity.PaItem;
import com.company.ability.entity.PaPost;
import com.company.ability.mapper.PaItemMapper;
import com.company.ability.mapper.PaPostMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 校验器：代码侧的硬校验，也是模型判错时的最后一道墙。
 *
 * <p><b>为什么模型判了还要代码再判一遍</b>：用户答非所问、模型理解偏了、网关返回了半截 JSON ——
 * 这三种情况都会让「模型认为没问题」变成「实际有问题」。这里不依赖任何模型输出，
 * 只按业务规则检查最终要入库的数据。过不了就是过不了，模型说了也不算。</p>
 *
 * <p>本类需要查库的地方只有一处：<b>同部门同名岗位是否已存在</b>（那决定走新建还是覆盖）。</p>
 */
@Service
public class PaImportValidator {

    private static final Logger log = LoggerFactory.getLogger(PaImportValidator.class);

    private static final int MAX_POST_NAME = 100;
    private static final int MAX_ITEM_NAME = 100;
    private static final int MAX_ITEM_CODE = 50;
    private static final int MAX_CATEGORY_NAME = 100;
    private static final int MAX_LEVEL_NAME = 50;

    private final PaPostMapper postMapper;
    private final PaItemMapper itemMapper;

    public PaImportValidator(PaPostMapper postMapper, PaItemMapper itemMapper) {
        this.postMapper = postMapper;
        this.itemMapper = itemMapper;
    }

    /**
     * 补齐岗位级信息并检查命名冲突。
     *
     * @param draft    草稿（会被就地补上 departmentId / departmentName）
     * @param deptId   用户确认的部门 id；<b>null = 全公司通用岗位</b>（见 sql/63）
     * @param deptName 部门名快照，通用岗位为 null
     * @return 待确认项（命名冲突、长度超限等）；空 = 通过
     */
    public List<PaImportQuestion> checkAndFillDepartment(PaImportDraft draft, Long deptId, String deptName) {
        for (PaImportDraft.PostDraft post : draft.getPosts()) {
            post.setDepartmentId(deptId);
            post.setDepartmentName(deptId == null ? null : deptName);
        }
        List<PaImportQuestion> out = new ArrayList<>();
        for (PaImportDraft.PostDraft post : draft.getPosts()) {
            out.addAll(checkPost(post));
        }
        return out;
    }

    /** 单个岗位的字段校验（不含部门） */
    public List<PaImportQuestion> checkPost(PaImportDraft.PostDraft post) {
        List<PaImportQuestion> out = new ArrayList<>();

        if (post.getPostName() == null || post.getPostName().isBlank()) {
            out.add(blocking(PaImportQuestion.MISSING, PaImportQuestionBuilder.slotPostName(post),
                    post.getSheetName(), "岗位名是空的"));
        } else if (post.getPostName().length() > MAX_POST_NAME) {
            // 库列是 NVARCHAR(100)；中文按字符计，超了 SQL Server 直接报错
            out.add(blocking(PaImportQuestion.CONFLICT, PaImportQuestionBuilder.slotPostName(post),
                    post.getSheetName(), "岗位名超过 " + MAX_POST_NAME + " 字：" + post.getPostName()));
        }

        for (PaImportDraft.ItemDraft item : post.getItems()) {
            String label = (item.getItemCode() == null ? "" : item.getItemCode() + " ") +
                    (item.getItemName() == null ? "" : item.getItemName());
            if (item.getItemName() == null || item.getItemName().isBlank()) {
                out.add(blocking(PaImportQuestion.MISSING,
                        PaImportQuestionBuilder.slotLevels(post, item), post.getSheetName(),
                        "有一个能力项没读出名称（表里第 " + item.getSourceRow() + " 行）"));
            } else if (item.getItemName().length() > MAX_ITEM_NAME) {
                out.add(blocking(PaImportQuestion.CONFLICT,
                        PaImportQuestionBuilder.slotLevels(post, item), post.getSheetName(),
                        "能力项名称超过 " + MAX_ITEM_NAME + " 字：" + label));
            }
            if (item.getItemCode() != null && item.getItemCode().length() > MAX_ITEM_CODE) {
                out.add(blocking(PaImportQuestion.CONFLICT,
                        PaImportQuestionBuilder.slotLevels(post, item), post.getSheetName(),
                        "能力项编码超过 " + MAX_ITEM_CODE + " 字：" + label));
            }
            for (PaImportDraft.LevelDraft lv : item.getLevels()) {
                if (lv.getLevelName() != null && lv.getLevelName().length() > MAX_LEVEL_NAME) {
                    out.add(blocking(PaImportQuestion.CONFLICT,
                            PaImportQuestionBuilder.slotLevels(post, item), post.getSheetName(),
                            "「" + label + "」的等级名超过 " + MAX_LEVEL_NAME + " 字：" + lv.getLevelName()));
                }
            }
        }

        // 编码在岗位内允许重复（sql/65）：能力的唯一标识是「部门 + 岗位 + 能力项」，
        // 编码只是给人看的参考号。模板里同一岗位的编码常分两段用
        // （专业能力 A01…G01，职业素养起又从 E01 来），重复是正常写法。
        // 早先这里把编码重复当阻断，等于要求业务改模板 —— 那是本末倒置。
        for (PaImportDraft.CategoryDraft c : post.getCategories()) {
            if (c.getCategoryName() != null && c.getCategoryName().length() > MAX_CATEGORY_NAME) {
                out.add(blocking(PaImportQuestion.CONFLICT, PaImportQuestionBuilder.slotBands(post),
                        post.getSheetName(), "能力类别名超过 " + MAX_CATEGORY_NAME + " 字：" + c.getCategoryName()));
            }
        }
        return out;
    }

    /**
     * 找出同部门同名的既有岗位。
     *
     * <p>唯一口径是 {@code (dimension_id, department_id, post_name)}（见 sql/50 的 UX_pa_post_name
     * 与 PaPostServiceImpl.assertNameUnique）—— <b>不同部门可以同名</b>，那不是冲突。</p>
     *
     * <p>{@code departmentId == null} 表示全公司通用岗位，要按 {@code IS NULL} 查
     * （sql/63：NULL 部门在唯一索引里同样成立，全公司只允许一条同名通用岗位）。
     * 早先这里把 null 当「查不了」直接返回，导入通用岗位时永远查不到存量 ——
     * 于是每次都判成新建，写库那步撞唯一索引。</p>
     *
     * @return 已存在的岗位；没有返回 null
     */
    public PaPost findExistingPost(Long departmentId, String postName) {
        if (postName == null || postName.isBlank()) {
            return null;
        }
        LambdaQueryWrapper<PaPost> wrapper = new LambdaQueryWrapper<PaPost>()
                .eq(PaPost::getPostName, postName.trim())
                .eq(PaPost::getDeleted, 0);
        // 注意 eq(column, null) 在 MyBatis-Plus 生成的是 department_id = NULL（永远不成立），
        // 必须单独走 isNull
        if (departmentId == null) {
            wrapper.isNull(PaPost::getDepartmentId);
        } else {
            wrapper.eq(PaPost::getDepartmentId, departmentId);
        }
        List<PaPost> list = postMapper.selectList(wrapper
                // SQL Server 的 OFFSET 必须跟 ORDER BY，缺了直接报「OFFSET 附近有语法错误」。
                // H2 容忍，所以单测发现不了 —— 线上第一次点导入才会炸。
                .orderByAsc(PaPost::getId)
                .last("OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY"));
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 比对既有模型与新草稿，得出「跳过的 / 覆盖的 / 新增的 / 移除的」。
     *
     * <p>一致判定按能力项逐个字段比（编码、名称、定义、权重、评级主体、排序 + 等级逐档
     * 名称/分值/描述全等）。差一个字符就算有差异 —— 宁可多写一次，
     * 也不要因为「差不多」让客户以为导入了新版本但库里还是旧的。</p>
     *
     * <p>匹配走 {@link PaImportItemMatcher}，与写库那步共用同一套规则（编码可重复，见 sql/65）。</p>
     */
    public Diff diff(PaPost existing, PaImportDraft.PostDraft draft) {
        Diff diff = new Diff();
        List<PaItem> oldItems = itemMapper.selectList(new LambdaQueryWrapper<PaItem>()
                .eq(PaItem::getPostId, existing.getId())
                .eq(PaItem::getDeleted, 0));

        PaImportItemMatcher matcher = new PaImportItemMatcher(oldItems);
        for (PaImportDraft.ItemDraft item : draft.getItems()) {
            PaItem old = matcher.match(item);
            if (old == null) {
                diff.added.add(item.getItemName());
            } else if (sameItem(old, item)) {
                diff.unchanged.add(item.getItemName());
            } else {
                diff.changed.add(item.getItemName());
            }
        }
        // 剩下的 = 旧模型有、新模板没有
        for (PaItem old : matcher.remaining()) {
            diff.removed.add(old.getItemName());
            diff.removedIds.add(old.getId());
        }
        diff.exists = true;
        diff.postId = existing.getId();
        diff.postName = existing.getPostName();
        log.info("模型比对 postId={}：一致 {} / 有差异 {} / 新增 {} / 移除 {}",
                existing.getId(), diff.unchanged.size(), diff.changed.size(),
                diff.added.size(), diff.removed.size());
        return diff;
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    /** 能力项是否一致（不含等级；等级由调用方再比） */
    private boolean sameItem(PaItem old, PaImportDraft.ItemDraft now) {
        return nz(old.getItemCode()).equals(nz(now.getItemCode()))
                && nz(old.getItemName()).equals(nz(now.getItemName()))
                && nz(old.getDefinition()).equals(nz(now.getDefinition()))
                && old.getWeight() != null && now.getWeight() != null
                && old.getWeight().compareTo(now.getWeight()) == 0
                && intOf(old.getRaterLeader()) == now.getRaterLeader()
                && intOf(old.getRater360()) == now.getRater360()
                // 原文也比：只改了「内部客户360」的写法而布尔没变，
                // 落地时确实会更新这一行，预览说「一致」会让人以为不会动
                && nz(old.getRaterText()).equals(nz(now.getRaterText()));
    }

    private static int intOf(Integer v) {
        return v == null ? 0 : v;
    }

    private static PaImportQuestion blocking(String type, String slot, String sheetName, String topic) {
        PaImportQuestion q = new PaImportQuestion();
        q.setType(type);
        q.setSlot(slot);
        q.setSheetName(sheetName);
        q.setTopic(topic);
        q.setQuestion(topic);
        q.setBlocking(true);
        return q;
    }

    /** 一个岗位的比对结果 */
    public static class Diff {
        public boolean exists;
        public Long postId;
        public String postName;
        public List<String> unchanged = new ArrayList<>();
        public List<String> changed = new ArrayList<>();
        public List<String> added = new ArrayList<>();
        public List<String> removed = new ArrayList<>();
        /** 被移除能力项的 id（决定「删」还是「停用」要看它们有没有被测评引用） */
        public List<Long> removedIds = new ArrayList<>();

        public boolean hasDifference() {
            return !changed.isEmpty() || !added.isEmpty() || !removed.isEmpty();
        }
    }
}
