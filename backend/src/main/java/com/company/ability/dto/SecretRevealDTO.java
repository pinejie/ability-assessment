package com.company.ability.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 「查看已保存密钥」的入参。
 *
 * <p>只允许点名两个字段 —— 用白名单而不是让前端传任意列名，
 * 因为这里返回的是解密后的明文：一旦列名可控，就等于给了一个
 * 「读任意配置列」的接口，将来这张表再加别的敏感字段会被顺带读走。</p>
 */
@Data
public class SecretRevealDTO {

    /** 要查看的密钥字段：sso_client_secret 或 push_secret */
    @NotBlank(message = "字段名不能为空")
    @Pattern(regexp = "sso_client_secret|push_secret",
            message = "字段名只能是 sso_client_secret 或 push_secret")
    private String field;
}
