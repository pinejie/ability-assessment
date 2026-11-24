package com.company.ability.service;

import com.company.ability.dto.PaImportDraft;
import com.company.ability.dto.PaImportQuestion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 待确认项计算器：把「装配好的草稿」扫一遍，算出所有必须问人的事。
 *
 * <p><b>纯函数</b>（不查库、不调模型、不看线程上下文），所以能密集测。
 * 唯一需要外部信息的是「同部门同名岗位已存在」——那是 {@code PaImportValidator} 的活，
 * 这里只算「凭这份草稿本身就能判定的问题」。</p>
 *
 * <p>设计原则：<b>宁可多问一句，不要替用户猜。</b>权重合计 98% 这种事，
 * 「自动按比例摊平到 100%」看起来贴心，实际上是把客户的数据改了却不说。
 * 所以这里一律产出问题交给人裁决；用户答「就这样」也要留痕。</p>
 */
@Service
public class PaImportQuestionBuilder {

    private static final Logger log = LoggerFactory.getLogger(PaImportQuestionBuilder.class);

    /** 评级主体至少选一个（与 PaModelServiceImpl.validateItemFields 同口径） */
    private static final BigDecimal WEIGHT_SUM = new BigDecimal("100");
    /** 权重合计容差：允许 99.99 / 100.01 这种浮点误差 */
    private static final BigDecimal WEIGHT_TOLERANCE = new BigDecimal("0.01");
    /** 单个能力项至少 2 档（与 PaModelServiceImpl.MIN_LEVELS 同口径） */
    private static final int MIN_LEVELS = 2;

    /**
     * 扫草稿，产出全部待确认项。
     *
     * @param draft       装配后的草稿
     * @param resolved    已经由用户确认过的值（slot → 值）。已解决的不再重复问
     */
    public List<PaImportQuestion> build(PaImportDraft draft, Map<String, String> resolved) {
        List<PaImportQuestion> out = new ArrayList<>();
        Map<String, String> done = resolved == null ? Map.of() : resolved;

        // ① 装配阶段的对账问题：模型改写原文 / 指错格子
        Map<String, PaImportDraft.Reconcile> seen = new LinkedHashMap<>();
        for (PaImportDraft.Reconcile r : draft.getReconciles()) {
            String key = r.getSheetName() + "|" + r.getCell() + "|" + r.getField();
            seen.putIfAbsent(key, r);
        }
        for (PaImportDraft.Reconcile r : seen.values()) {
            PaImportQuestion q = q(PaImportQuestion.MISMATCH,
                    "reconcile." + r.getSheetName() + "." + r.getField() + "." + r.getCell(),
                    r.getSheetName(), r.describe(), true);
            q.getContext().put("cell", r.getCell());
            q.getContext().put("claimed", r.getClaimed());
            q.getContext().put("actual", r.getActual());
            q.getContext().put("kind", r.getKind());
            if (r.getActual() != null) {
                q.setSuggestion(r.getActual());
                q.getCandidates().add(r.getActual());
                q.getCandidates().add(r.getClaimed());
            }
            out.add(q);
        }

        // ② 岗位还没有部门 id → 必问（这是导入的落点，猜不出来）
        // 注意边界：判定只看「有没有 id」，不看 departmentName。
        // 文件里读到的部门名只是一段文字（没和 HrmDepartment 对上过），
        // 拿它当「已确定」会让写库阶段拿着文字去建岗位。见 PaImportService.resolveFileDepartments。
        for (PaImportDraft.PostDraft post : draft.getPosts()) {
            if (post.getDepartmentId() == null) {
                if (!done.containsKey(slotDept())) {
                    // 表里确实写了部门名、只是没对上组织树（重名或不存在）时，不能说「文件里没有写」——
                    // 用户会以为自己写的没被读到，回头改表格。
                    boolean namedInFile = post.getDepartmentName() != null
                            && !post.getDepartmentName().isBlank();
                    String head = namedInFile
                            ? "文件里写的部门「" + post.getDepartmentName()
                                    + "」在组织树里没有唯一匹配（可能重名或已不存在），请直接指定一个部门"
                            : "文件里没有写这些岗位属于哪个部门，需要你指定一个部门";
                    PaImportQuestion q = q(PaImportQuestion.MISSING, slotDept(), null,
                            head + "（部门 + 岗位是能力模型的唯一锚点；"
                                    + "一次导入只指定一个部门，要分到不同部门的岗位请分次导入）。"
                                    + "如果这些岗位全公司通用（会计、行政专员这类每个部门都有的），"
                                    + "选「全公司通用」即可，不用挑部门", true);
                    q.getContext().put("posts", postNames(draft));
                    // 刻意不把「全公司通用」放进 candidates：
                    // candidates 是给模型挑 suggestion 用的白名单（PaImportDialogAi.isAmongCandidates），
                    // 放进去后模型会把「全公司通用」当建议返回，前端再预填进 answers['dept']，
                    // 而 dept 在页面上是专门的部门选择器、没有输入框 —— 用户看不到这个预填值、
                    // 选了部门提交时它一起上去，反把部门顶成通用（2026-11-23 线上实测）。
                    // 通用只走前端的勾选框 + 后端的 genericDepartment 字段，不经 candidates。
                    q.getContext().put("genericOption", true);
                    out.add(q);
                }
            }
            if (post.getPostName() == null || post.getPostName().isBlank()) {
                if (!done.containsKey(slotPostName(post))) {
                    PaImportQuestion q = q(PaImportQuestion.MISSING, slotPostName(post), post.getSheetName(),
                            "sheet「" + post.getSheetName() + "」没读出岗位名", true);
                    q.getCandidates().add(post.getSheetName());
                    out.add(q);
                }
            }

            // ③ 权重：合计必须 100%
            BigDecimal sum = BigDecimal.ZERO;
            int weightCount = 0;
            Set<String> missingWeight = new LinkedHashSet<>();
            for (PaImportDraft.ItemDraft item : post.getItems()) {
                if (item.getWeight() == null) {
                    missingWeight.add(label(item));
                } else {
                    sum = sum.add(item.getWeight());
                    weightCount++;
                }
            }
            if (!missingWeight.isEmpty()) {
                if (!done.containsKey(slotWeight(post))) {
                    PaImportQuestion q = q(PaImportQuestion.MISSING, slotWeight(post), post.getSheetName(),
                            "这些能力项没读出权重：" + String.join("、", missingWeight), true);
                    q.getCandidates().add("按已读到的权重等比摊满 100%");
                    q.getCandidates().add("我手工补");
                    out.add(q);
                }
            } else if (weightCount > 0 && sum.subtract(WEIGHT_SUM).abs().compareTo(WEIGHT_TOLERANCE) > 0) {
                if (!done.containsKey(slotWeight(post))) {
                    PaImportQuestion q = q(PaImportQuestion.CONFLICT, slotWeight(post), post.getSheetName(),
                            "权重合计是 " + sum.stripTrailingZeros().toPlainString() + "%，不是 100%", true);
                    q.getContext().put("sum", sum);
                    q.getContext().put("diff", WEIGHT_SUM.subtract(sum));
                    q.getCandidates().add("就按 " + sum.stripTrailingZeros().toPlainString() + "% 导入（系统按 Σ权重 归一，不要求满分）");
                    q.getCandidates().add("按比例摊平到 100%");
                    q.getCandidates().add("我手工核定");
                    out.add(q);
                }
            }

            // ④ 每个能力项：评级主体 + 等级
            for (PaImportDraft.ItemDraft item : post.getItems()) {
                String slotRater = slotRater(post, item);
                if ((item.getRaterLeader() < 0 || item.getRater360() < 0) && !done.containsKey(slotRater)) {
                    PaImportQuestion q = q(PaImportQuestion.UNKNOWN_VOCAB, slotRater, post.getSheetName(),
                            "「" + label(item) + "」的评级主体认不出来（系统只认「直接上级」「360 测评人」或两者）", true);
                    q.getCandidates().addAll(List.of("直接上级", "360 测评人", "两者都要"));
                    out.add(q);
                }
                String slotLevel = slotLevels(post, item);
                // 等级类问题<b>不看 resolved</b>：它问的是「文件里读出几档」，答案只能靠草稿里的等级变多，
                // 不能靠人答一句「就这样吧」。曾经这里也是 done.containsKey 一票放行，结果
                // 任意一句回答就把阻断项销掉，1 档能力项一路写进库 —— 评价人填表时该项只有唯一选项，
                // 这份评价对区分度没有任何贡献。草稿没变，问题就必须还在。
                if (item.getLevels().size() < MIN_LEVELS) {
                    out.add(q(PaImportQuestion.CONFLICT, slotLevel, post.getSheetName(),
                            "「" + label(item) + "」只读出 " + item.getLevels().size()
                                    + " 个等级，至少要有 " + MIN_LEVELS + " 个才有区分度。"
                                    + "这个问题在对话里改不了（档位的名称、描述、分值只能来自你的文件），"
                                    + "请回表格把该能力项补到 " + MIN_LEVELS + " 档以上再重新上传", true));
                }
                // 等级分值重复：同一能力项里两档同分，评价人选谁都一样，等于少一档。
                // 与上面同理，resolved 销不掉它 —— 除非文件里的分值真的改了。
                Set<BigDecimal> scores = new LinkedHashSet<>();
                boolean dupScore = false;
                for (PaImportDraft.LevelDraft lv : item.getLevels()) {
                    if (lv.getScore() != null && !scores.add(lv.getScore())) {
                        dupScore = true;
                    }
                }
                if (dupScore) {
                    out.add(q(PaImportQuestion.CONFLICT, slotLevel, post.getSheetName(),
                            "「" + label(item) + "」有重复的等级分值，请回表格改正后重新上传", true));
                }
            }

            // ⑤ 区间：必须首尾相接、最高档开口（与 PaModelServiceImpl.validateBands 同口径）
            out.addAll(checkBands(post, done));
        }

        renumber(out);
        log.info("待确认项计算完成：{} 条（阻断 {} 条）", out.size(), out.stream().filter(PaImportQuestion::isBlocking).count());
        return out;
    }

    // ------------------------------------------------------------------ 区间

    private List<PaImportQuestion> checkBands(PaImportDraft.PostDraft post, Map<String, String> done) {
        List<PaImportQuestion> out = new ArrayList<>();
        String slot = slotBands(post);
        if (done.containsKey(slot)) {
            return out;
        }
        List<PaImportDraft.BandDraft> bands = post.getBands();
        if (bands.isEmpty()) {
            out.add(q(PaImportQuestion.CONFLICT, slot, post.getSheetName(),
                    "没有算出分值区间，无法判定等级", true));
            return out;
        }
        // 最高一档上界必须留空（= +∞），否则高于它的得分命中不到任何档
        PaImportDraft.BandDraft last = bands.get(bands.size() - 1);
        if (last.getUpperBound() != null) {
            out.add(q(PaImportQuestion.CONFLICT, slot, post.getSheetName(),
                    "最高一档「" + last.getBandName() + "」的上界不是开口（应为 +∞），"
                            + "高于 " + last.getUpperBound().toPlainString() + " 的得分会命中不到任何档", true));
        }
        // 相邻两段必须首尾相接，不重叠也不留缝
        for (int i = 0; i < bands.size() - 1; i++) {
            PaImportDraft.BandDraft cur = bands.get(i);
            PaImportDraft.BandDraft nxt = bands.get(i + 1);
            if (cur.getUpperBound() == null) {
                out.add(q(PaImportQuestion.CONFLICT, slot, post.getSheetName(),
                        "区间「" + cur.getBandName() + "」不是最高一档，上界不该开口", true));
                break;
            }
            if (nxt.getLowerBound() == null) {
                out.add(q(PaImportQuestion.CONFLICT, slot, post.getSheetName(),
                        "区间「" + nxt.getBandName() + "」不是最低一档，下界不该开口", true));
                break;
            }
            if (cur.getUpperBound().compareTo(nxt.getLowerBound()) != 0) {
                out.add(q(PaImportQuestion.CONFLICT, slot, post.getSheetName(),
                        "区间「" + cur.getBandName() + "」的上界 " + cur.getUpperBound().toPlainString()
                                + " 和「" + nxt.getBandName() + "」的下界 " + nxt.getLowerBound().toPlainString()
                                + " 对不上（会出现得分命中不到或命中两档）", true));
                break;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 工具

    private PaImportQuestion q(String type, String slot, String sheetName, String topic, boolean blocking) {
        PaImportQuestion question = new PaImportQuestion();
        question.setType(type);
        question.setSlot(slot);
        question.setSheetName(sheetName);
        question.setTopic(topic);
        question.setQuestion(topic);
        question.setBlocking(blocking);
        return question;
    }

    private void renumber(List<PaImportQuestion> list) {
        // 同一 slot 只留一条：问两遍同一件事，人只会烦
        Map<String, PaImportQuestion> unique = new LinkedHashMap<>();
        for (PaImportQuestion q : list) {
            unique.putIfAbsent(q.getSlot() + "|" + q.getType() + "|" + q.getTopic(), q);
        }
        int no = 1;
        for (PaImportQuestion q : unique.values()) {
            q.setNo(no++);
        }
        list.clear();
        list.addAll(unique.values());
    }

    private static String label(PaImportDraft.ItemDraft item) {
        if (item.getItemCode() != null && !item.getItemCode().isBlank()) {
            return item.getItemCode() + " " + (item.getItemName() == null ? "" : item.getItemName());
        }
        return item.getItemName() == null ? ("第 " + item.getSort() + " 项") : item.getItemName();
    }

    private static List<String> postNames(PaImportDraft draft) {
        List<String> names = new ArrayList<>();
        for (PaImportDraft.PostDraft p : draft.getPosts()) {
            names.add(p.getPostName() == null ? p.getSheetName() : p.getPostName());
        }
        return names;
    }

    // 槽位命名：统一在这里生成，避免两边拼错
    public static String slotDept() {
        return "dept";
    }

    /**
     * 「全公司通用」这个答案的统一写法。
     *
     * <p>前后端各需要它一次：前端渲染成勾选项、后端把用户打字的回答转成通用意图。
     * 三处各写一个字符串的话，改文案时必然漏一处，症状是「我明明勾了通用，导出来还是有部门」。</p>
     */
    public static final String GENERIC_DEPARTMENT = "全公司通用";

    /** 用户这个回答是不是「全公司通用」（容忍前后缀：全公司通用岗位 / 通用 / 不绑定部门） */
    public static boolean isGenericDepartment(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim();
        return v.equals(GENERIC_DEPARTMENT) || v.equals("通用") || v.contains("全公司通用")
                || v.contains("不绑定部门") || v.contains("无部门");
    }

    public static String slotPostName(PaImportDraft.PostDraft post) {
        return "post." + nz(post.getSheetName()) + ".postName";
    }

    public static String slotWeight(PaImportDraft.PostDraft post) {
        return "post." + nz(post.getSheetName()) + ".weight";
    }

    /**
     * 能力项在这个岗位里的序号（从 1 起）。
     *
     * <p>槽位名里必须带上它：编码允许重复（sql/65），同一岗位里两个都叫 E01 的能力项
     * 如果只按编码拼槽位，就会共用一个答案槽 —— 前端 {@code answers[slot]} 是同一个键，
     * 用户答了第一问，第二问跟着变，两项都被记成同一个值，而人根本没法分别回答。</p>
     */
    private static int itemOrdinal(PaImportDraft.PostDraft post, PaImportDraft.ItemDraft item) {
        int i = post == null || post.getItems() == null ? -1 : post.getItems().indexOf(item);
        return i < 0 ? 0 : i + 1;
    }

    public static String slotRater(PaImportDraft.PostDraft post, PaImportDraft.ItemDraft item) {
        return "post." + nz(post.getSheetName()) + ".item." + itemOrdinal(post, item)
                + "." + nz(item.getItemCode()) + ".rater";
    }

    public static String slotLevels(PaImportDraft.PostDraft post, PaImportDraft.ItemDraft item) {
        return "post." + nz(post.getSheetName()) + ".item." + itemOrdinal(post, item)
                + "." + nz(item.getItemCode()) + ".levels";
    }

    public static String slotBands(PaImportDraft.PostDraft post) {
        return "post." + nz(post.getSheetName()) + ".bands";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 便于测试：空的已解决表 */
    public static Map<String, String> none() {
        return new HashMap<>();
    }
}
