package com.company.ability.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一轮对话：模型把代码算出的待确认项问成人话，再把用户的回答抽回成 slot→值。
 *
 * <p>两次调用都<b>不决定「问什么」</b>：问题清单是 {@code PaImportQuestionBuilder} 用代码算的，
 * 模型只负责措辞；抽答案时也只认清单里已有的 slot，抽到清单外的 slot 一律丢弃 ——
 * 否则模型可以凭空造一个「你觉得该叫 XX 吧」的回答，把人没说过的话写进库。</p>
 */
@Data
public class PaImportRound {

    /**
     * 用户这一轮的回答原文（原样存，不加工）。
     *
     * <p>必须原样留：模型把「就按 98% 吧」抽成 {@code weight=98} 之后，
     * 出问题要能回答「我当时到底说了什么」。这也是 dialog 字段存在的意义。</p>
     */
    private String answerText;

    /** 模型从回答里抽出的值：slot → 值。只可能是本次提问清单里出现过的 slot */
    private Map<String, String> values = new LinkedHashMap<>();

    /** 模型判定「这段回答跟本次导入无关」时的说明（越界提示用） */
    private String outOfScope;

    /** 模型对这次回答的确认话术（「好的，按 98% 导入」） */
    private String reply;

    /** 这一轮仍然没答到的 slot（模型说「你还没说部门」） */
    private List<String> unanswered = new ArrayList<>();

    /** 抽不出来 / 有歧义时，模型追加的追问（会并进下一轮待确认项） */
    private List<String> followUps = new ArrayList<>();
}
