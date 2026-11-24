package com.company.ability.service;

import com.company.ability.client.PaImportAiClient;
import com.company.ability.dto.PaImportQuestion;
import com.company.ability.dto.PaImportRound;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 对话的两端：把问题问成人话、把回答抽成值。
 *
 * <p><b>兜底原则：模型不可用时整条链路仍然能走完。</b>
 * 问题清单本来就是代码算的（{@link PaImportQuestionBuilder}），润色失败就用
 * {@code topic} 原文 —— 那些话虽然生硬（「权重合计是 98%，不是 100%」），但意思准确、能回答。
 * 抽答案失败就退化成「整段文本当作 {@code freeform} 回答」，让用户去手工映射。
 * 模型是加速器，不是必需品。</p>
 */
@Slf4j
@Service
public class PaImportDialogAi {

    /** 抽答案时最多带多少条历史：全带会把提示词撑爆，而最近的几轮才是相关的 */
    private static final int HISTORY_LIMIT = 6;

    private static final String ASK_SYSTEM = """
            你是企业人力资源系统里的「能力模型导入助手」。代码已经算出了一批
            **必须由用户确认**的问题，你的任务只有一个：把这些问题改写成自然、简短、
            面向人力资源同事的中文问句。

            ## 硬性约束

            1. **不许新增问题，不许合并问题，不许删问题**。给几条就还几条，no 原样保留。
            2. **不许替用户回答**。suggestion 只在你确实能从候选项里挑出一个明显默认值时给，
               否则留空。宁可空着，也不要猜一个部门名。
            3. 问句要具体：说清是哪个岗位、哪个能力项、现在是什么情况。
               例：「「技术人员」的 8 个能力项权重合计是 98%，你希望怎么处理？」
            4. 一次把所有问题都问出来（用户不喜欢一问一答挤牙膏），所以每条都要能独立读懂。
            5. **部门问题只有一个，且只能要一个部门**：系统一次导入只给所有岗位指定同一个部门。
               不要写成「这些岗位分别属于哪个部门」「请为每个岗位指定部门」，
               那样用户会以为能分别指定。要写成「这些岗位都归到哪个部门」。
            6. 只输出 JSON，不要解释、不要 markdown 围栏。

            ## 输出格式

            {"intro":"<一句话开场，说明这次要确认几件事>",
             "questions":[{"no":1,"question":"<改写后的问句>","suggestion":"<可空>"}]}
            """;

    private static final String EXTRACT_SYSTEM = """
            你是企业人力资源系统里的「能力模型导入助手」。用户刚刚回答了导入过程中
            提出的一批问题，你要把他的自然语言回答抽成结构化取值。

            ## 硬性约束

            1. **只允许填下面「可填槽位」里出现过的 slot**。清单外的 slot 一律不要输出，
               哪怕你觉得某个值应该属于另一个概念 —— 代码只认这些槽位。
            2. **不推测，不补全**。用户没提到的 slot 就不要出现在 values 里，
               并把它写进 unanswered。用户说得含糊（「差不多吧」）也算没答。
            3. **识别越界**：如果用户的回答跟本次导入确认无关（问部门却回答「把权重改成
               20% 吧」、说别的系统、发牢骚、问今天天气），把 outOfScope 填成一句
               友好的说明，values 留空。这时整段回答都算没答。
            4. 用户明确表达「就这样 / 不用改 / 按你说的来」时，按对应 slot 的候选项里
               那个「保持原样」的取值填。
            5. values 的值必须是**可以直接写库的干净值**：
               - 部门：填用户说的部门名原文（代码会去组织树里唯一匹配）
               - 权重类：填数字或 "keep"（保持原样）
               - 评级主体：填 "直接上级" / "360 测评人" / "两者都要"
               - 是/否：填 "是" / "否"
            6. **`.levels` 结尾的槽位（等级档位）永远不要填进 values**。档位的名称、描述、
               分值只能来自用户上传的表格，对话里给不出来。这类问题即使热心地「答应一声」
               也解决不了，代码会拒收；请把它留在 unanswered 里。
            7. 只输出 JSON，不要解释、不要 markdown 围栏。

            ## 输出格式

            {"values":{"<slot>":"<值>"},"unanswered":["<slot>"],
             "outOfScope":"<可空，越界说明>","reply":"<一句话回应用户>",
             "followUps":["<可空，需要追问的点>"]}
            """;

    private final PaImportAiClient ai;
    private final ObjectMapper json;

    public PaImportDialogAi(PaImportAiClient ai, ObjectMapper json) {
        this.ai = ai;
        this.json = json;
    }

    /**
     * 把问题改写成人话。
     *
     * @return 改写结果（与入参同序同数量）；模型不可用或失败时原样返回
     */
    public Reworded reword(List<PaImportQuestion> questions, String postNames) {
        List<PaImportQuestion> list = questions == null ? List.of() : questions;
        if (list.isEmpty()) {
            return new Reworded("", list);
        }
        if (!ai.available()) {
            log.info("AI 网关未配置，问题直接用代码文案（共 {} 条）", list.size());
            return new Reworded("", list);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 本次导入涉及的岗位\n").append(postNames == null ? "（未识别）" : postNames).append("\n\n");
        sb.append("## 待改写的问题（共 ").append(list.size()).append(" 条）\n");
        for (PaImportQuestion q : list) {
            sb.append("\n### no=").append(q.getNo()).append("  type=").append(q.getType())
                    .append("  sheet=").append(q.getSheetName() == null ? "（整份文件）" : q.getSheetName())
                    .append("\n要点：").append(q.getTopic());
            if (q.getCandidates() != null && !q.getCandidates().isEmpty()) {
                sb.append("\n候选：").append(String.join(" ／ ", q.getCandidates()));
            }
            if (q.getContext() != null && !q.getContext().isEmpty()) {
                sb.append("\n上下文：").append(q.getContext());
            }
            sb.append("\n");
        }

        try {
            Map<String, Object> raw = ai.completeJson(ASK_SYSTEM, sb.toString(), Map.class);
            String intro = raw.get("intro") == null ? "" : raw.get("intro").toString();
            Map<Integer, Map<String, Object>> byNo = new LinkedHashMap<>();
            Object arr = raw.get("questions");
            if (arr instanceof List<?> items) {
                for (Object o : items) {
                    if (o instanceof Map<?, ?> m) {
                        Object no = m.get("no");
                        if (no instanceof Number n) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> mm = (Map<String, Object>) m;
                            byNo.put(n.intValue(), mm);
                        }
                    }
                }
            }
            // 逐条覆盖：模型少给一条就用原 topic 兜，绝不因为模型漏答而少问一个问题
            for (PaImportQuestion q : list) {
                Map<String, Object> m = byNo.get(q.getNo());
                if (m == null) {
                    continue;
                }
                Object text = m.get("question");
                if (text != null && !text.toString().isBlank()) {
                    q.setQuestion(text.toString().trim());
                }
                Object sug = m.get("suggestion");
                if (sug != null && !sug.toString().isBlank() && isAmongCandidates(sug.toString(), q.getCandidates())) {
                    q.setSuggestion(sug.toString().trim());
                }
            }
            log.info("问题改写完成：{} 条（模型返回 {} 条）", list.size(), byNo.size());
            return new Reworded(intro, list);
        } catch (Exception e) {
            // 措辞失败不影响功能：topic 本身就是完整可读的问题
            log.warn("问题改写失败，用代码文案兜底：{}", e.getMessage());
            return new Reworded("", list);
        }
    }

    /**
     * 抽答案。
     *
     * @param questions 这一轮问过的问题（限定可填 slot）
     * @param history   最近几轮对话（帮模型理解「就按那个来吧」指的是什么）
     * @param answer    用户原话或结构化回答
     */
    public PaImportRound extract(List<PaImportQuestion> questions, List<Map<String, String>> history,
                                 Map<String, String> structured, String freeText) {
        PaImportRound round = new PaImportRound();
        round.setAnswerText(freeText);

        Set<String> allowed = questions.stream()
                .map(PaImportQuestion::getSlot)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.toSet());

        // 前端按表单逐条给的值：可信度高（是用户点选的），但仍然要过 slot 白名单
        if (structured != null && !structured.isEmpty()) {
            structured.forEach((slot, value) -> {
                if (allowed.contains(slot) && value != null && !value.isBlank()) {
                    round.getValues().put(slot, value.trim());
                }
            });
        }

        // 结构化值已经覆盖了所有可填 slot，或模型不可用，就不再调模型
        if (!round.getValues().isEmpty() && round.getValues().keySet().containsAll(allowed)) {
            return round;
        }
        if (freeText == null || freeText.isBlank() || !ai.available()) {
            return round;
        }

        String user = buildExtractPrompt(questions, history, freeText);
        try {
            PaImportRound parsed = ai.completeJson(EXTRACT_SYSTEM, user, PaImportRound.class);
            if (parsed == null) {
                return round;
            }
            // 白名单过滤：模型可能自作主张多填 slot，多出来的必须丢掉
            if (parsed.getValues() != null) {
                parsed.getValues().forEach((slot, value) -> {
                    if (allowed.contains(slot) && value != null && !value.isBlank()) {
                        round.getValues().putIfAbsent(slot, value.trim());
                    } else if (!allowed.contains(slot)) {
                        log.info("丢弃模型自造的槽位 {}={}", slot, value);
                    }
                });
            }
            round.setOutOfScope(parsed.getOutOfScope());
            round.setReply(parsed.getReply());
            round.setUnanswered(parsed.getUnanswered() == null ? List.of() : parsed.getUnanswered());
            round.setFollowUps(parsed.getFollowUps() == null ? List.of() : parsed.getFollowUps());

            // 越界回答必须整体作废：不能一边说「没听懂」一边写进去几个值
            if (round.getOutOfScope() != null && !round.getOutOfScope().isBlank()) {
                round.getValues().clear();
            }
            if (round.getReply() == null || round.getReply().isBlank()) {
                round.setReply(respond(round));
            }
            log.info("回答抽取完成：{} 个槽位，未答 {} 个{}", round.getValues().size(),
                    round.getUnanswered().size(),
                    round.getOutOfScope() == null ? "" : "，判定越界");
            return round;
        } catch (Exception e) {
            log.warn("回答抽取失败，按未识别处理：{}", e.getMessage());
            round.setReply("我没能从中解析出确认信息，请再用一句话说明，或直接在上面逐条选择。");
            return round;
        }
    }

    // ------------------------------------------------------------------ 内部

    private String buildExtractPrompt(List<PaImportQuestion> questions, List<Map<String, String>> history,
                                      String freeText) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 可填槽位（只允许这些 slot）\n");
        for (PaImportQuestion q : questions) {
            if (q.getSlot() == null || q.getSlot().isBlank()) {
                continue;
            }
            sb.append("- ").append(q.getSlot()).append("  ← ").append(q.getTopic());
            if (q.getCandidates() != null && !q.getCandidates().isEmpty()) {
                sb.append("（候选：").append(String.join(" ／ ", q.getCandidates())).append("）");
            }
            sb.append("\n");
        }

        if (history != null && !history.isEmpty()) {
            sb.append("\n## 最近几轮对话（帮你理解指代）\n");
            int from = Math.max(0, history.size() - HISTORY_LIMIT);
            for (Map<String, String> h : history.subList(from, history.size())) {
                sb.append(h.getOrDefault("role", "?")).append("：")
                        .append(h.getOrDefault("text", "")).append("\n");
            }
        }

        sb.append("\n## 用户这次的回答\n").append(freeText).append("\n");
        return sb.toString();
    }

    /** 兜底回应：至少让用户知道系统收到了什么 */
    private static String respond(PaImportRound round) {
        if (round.getValues().isEmpty()) {
            return "没有识别出可用于导入的信息，请再说明一次。";
        }
        return "已记下 " + round.getValues().size() + " 项确认信息。";
    }

    /**
     * 建议值必须在候选项里。
     *
     * <p>模型的建议会预填到输入框里，用户一路回车就提交了。如果它建议一个不存在的选项
     * （比如部门名猜错了），用户会以为自己选过。所以只接受候选项内的建议。</p>
     */
    private static boolean isAmongCandidates(String suggestion, List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return false;
        }
        String s = suggestion.trim();
        for (String c : candidates) {
            if (c != null && (c.contains(s) || s.contains(c))) {
                return true;
            }
        }
        return false;
    }

    /** 改写结果：开场白 + 改写后的问题（原 List 已就地被改） */
    public record Reworded(String intro, List<PaImportQuestion> questions) {
        public Reworded {
            questions = questions == null ? new ArrayList<>() : questions;
        }
    }
}
