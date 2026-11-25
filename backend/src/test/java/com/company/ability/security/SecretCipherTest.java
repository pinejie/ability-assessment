package com.company.ability.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集成配置密钥加解密单测
 */
class SecretCipherTest {

    /** 32 字节原文 */
    private static final String RAW_KEY = "0123456789abcdef0123456789abcdef";
    /** 同一个 32 字节，Base64 形式 */
    private static final String B64_KEY =
            Base64.getEncoder().encodeToString(RAW_KEY.getBytes(StandardCharsets.UTF_8));

    private final SecretCipher cipher = new SecretCipher(RAW_KEY);

    @Test
    @DisplayName("加解密往返：密文可还原原文")
    void roundTrip() {
        String plain = "yIe4JZbva-secret";
        String encrypted = cipher.encrypt(plain);

        assertTrue(encrypted.startsWith(SecretCipher.PREFIX), "密文应带前缀: " + encrypted);
        assertEquals(plain, cipher.decrypt(encrypted));
    }

    @Test
    @DisplayName("两次加密同一明文得到不同密文（IV 随机），但都能解回")
    void randomIv() {
        String a = cipher.encrypt("same");
        String b = cipher.encrypt("same");

        assertNotEquals(a, b, "IV 随机，密文不应相同");
        assertEquals("same", cipher.decrypt(a));
        assertEquals("same", cipher.decrypt(b));
    }

    @Test
    @DisplayName("重复加密已是密文的值时原样返回，不会套娃")
    void encryptIsIdempotentForCipherText() {
        String once = cipher.encrypt("x");

        assertEquals(once, cipher.encrypt(once), "已加密的值不应被再次加密");
    }

    @Test
    @DisplayName("解密时明文原样返回（兼容手工填库的调试数据）")
    void decryptPlainTextPassesThrough() {
        assertEquals("plain-value", cipher.decrypt("plain-value"));
    }

    @Test
    @DisplayName("null 输入返回 null，不抛异常")
    void nullSafe() {
        assertNull(cipher.encrypt(null));
        assertNull(cipher.decrypt(null));
    }

    @Test
    @DisplayName("密文被篡改时解密失败（GCM 完整性校验）")
    void tamperedCipherTextFails() {
        String encrypted = cipher.encrypt("secret-value");
        // 改动 Base64 尾部一个字符：GCM 的 Tag 校验必然失败
        String body = encrypted.substring(SecretCipher.PREFIX.length());
        char last = body.charAt(body.length() - 1);
        String tampered = SecretCipher.PREFIX
                + body.substring(0, body.length() - 1)
                + (last == 'A' ? 'B' : 'A');

        assertThrows(IllegalStateException.class, () -> cipher.decrypt(tampered));
    }

    @Test
    @DisplayName("换成另一个主密钥后解密失败，且提示指向主密钥不一致")
    void wrongKeyFails() {
        String encrypted = cipher.encrypt("secret-value");
        SecretCipher other = new SecretCipher("ffffffffffffffffffffffffffffffff");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> other.decrypt(encrypted));
        assertTrue(e.getMessage().contains("INTEGRATION_MASTER_KEY"), e.getMessage());
    }

    @Test
    @DisplayName("Base64 形式的 32 字节主密钥与原文形式等价")
    void base64KeyEqualsRawKey() {
        SecretCipher fromB64 = new SecretCipher(B64_KEY);

        assertEquals("v", fromB64.decrypt(cipher.encrypt("v")),
                "两种写法应得到同一把密钥");
        assertEquals("v", cipher.decrypt(fromB64.encrypt("v")));
    }

    @Test
    @DisplayName("未配置主密钥：isConfigured=false，使用时抛明确异常")
    void noKeyConfigured() {
        SecretCipher none = new SecretCipher("");

        assertFalse(none.isConfigured());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> none.encrypt("x"));
        assertTrue(e.getMessage().contains("INTEGRATION_MASTER_KEY"), e.getMessage());
    }

    @Test
    @DisplayName("主密钥长度不对时启动即失败")
    void wrongKeyLengthFailsFast() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new SecretCipher("too-short"));
        assertTrue(e.getMessage().contains("32 字节"), e.getMessage());
    }

    @Test
    @DisplayName("isEncrypted 只认带前缀的值")
    void isEncryptedDetection() {
        assertTrue(SecretCipher.isEncrypted(cipher.encrypt("a")));
        assertFalse(SecretCipher.isEncrypted("plain"));
        assertFalse(SecretCipher.isEncrypted(null));
    }
}
