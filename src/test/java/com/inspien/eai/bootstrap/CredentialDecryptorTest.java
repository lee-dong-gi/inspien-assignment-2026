package com.inspien.eai.bootstrap;

import com.inspien.eai.bootstrap.crypto.CredentialDecryptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 복호화 규격 검증.
 *
 * <p>실제 접속정보 없이도 규격 자체를 검증할 수 있도록,
 * 같은 규격으로 <b>암호화한 값을 되돌리는</b> 왕복 테스트로 구성했다.
 * 과제 API 호출 전에 이 테스트가 통과해야 "복호화 로직은 맞다"는 전제를 확보할 수 있다.
 */
class CredentialDecryptorTest {

    private static final String SAMPLE_PHONE = "010-1234-5678";

    private final CredentialDecryptor decryptor = new CredentialDecryptor();

    @Test
    @DisplayName("전화번호 SHA-1 앞 16바이트로 AES-128 키가 유도된다")
    void deriveKey_produces128BitKey() {
        SecretKey key = decryptor.deriveKey(SAMPLE_PHONE);

        assertThat(key.getAlgorithm()).isEqualTo("AES");
        assertThat(key.getEncoded()).hasSize(16);
    }

    @Test
    @DisplayName("전화번호가 한 글자만 달라도 완전히 다른 키가 나온다 — 형식 실수 = 전면 실패")
    void deriveKey_isSensitiveToExactString() {
        byte[] correct = decryptor.deriveKey(SAMPLE_PHONE).getEncoded();
        byte[] withoutHyphen = decryptor.deriveKey("01012345678").getEncoded();
        byte[] withSpace = decryptor.deriveKey(SAMPLE_PHONE + " ").getEncoded();

        assertThat(correct).isNotEqualTo(withoutHyphen);
        assertThat(correct).isNotEqualTo(withSpace);
    }

    @Test
    @DisplayName("AES/ECB/PKCS5Padding 왕복 — 암호화한 접속정보가 원문으로 복원된다")
    void decrypt_roundTrip() throws Exception {
        String plain = "jdbc:mysql://host:3306/db?user=u&password=p";
        SecretKey key = decryptor.deriveKey(SAMPLE_PHONE);

        String encrypted = encrypt(plain, key);
        String decrypted = decryptor.decrypt(encrypted, key, "TEST_CONN");

        assertThat(decrypted).isEqualTo(plain);
    }

    @Test
    @DisplayName("잘못된 키로 복호화하면 원인이 드러나는 예외가 난다")
    void decrypt_withWrongKey_throwsMeaningfulException() throws Exception {
        SecretKey correctKey = decryptor.deriveKey(SAMPLE_PHONE);
        SecretKey wrongKey = decryptor.deriveKey("010-0000-0000");
        String encrypted = encrypt("some-connection-string", correctKey);

        assertThatThrownBy(() -> decryptor.decrypt(encrypted, wrongKey, "ORDER_TB_CONN"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ORDER_TB_CONN")
                .hasMessageContaining("전화번호");
    }

    @Test
    @DisplayName("SAMPLE_DATA 는 EUC-KR 로 읽어야 한글이 살아난다 — UTF-8 로 읽으면 깨진다")
    void decodeSampleData_mustBeReadAsEucKr() {
        String original = "<HEADER><NAME>홍길동</NAME><ADDRESS>서울특별시 금천구</ADDRESS></HEADER>";
        String base64 = Base64.getEncoder()
                .encodeToString(original.getBytes(Charset.forName("EUC-KR")));

        byte[] decoded = decryptor.decodeSampleData(base64);

        assertThat(new String(decoded, CredentialDecryptor.SAMPLE_DATA_CHARSET))
                .isEqualTo(original);

        // 같은 바이트를 UTF-8 로 읽으면 한글이 보존되지 않는다 — 대표 함정의 회귀 방지
        assertThat(new String(decoded, StandardCharsets.UTF_8))
                .isNotEqualTo(original);
    }

    @Test
    @DisplayName("Base64 에 줄바꿈이 섞여도 디코딩된다")
    void decodeSampleData_toleratesLineBreaks() {
        String original = "<ITEM><ITEM_NAME>청바지</ITEM_NAME></ITEM>";
        String base64 = Base64.getMimeEncoder(16, "\r\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(original.getBytes(Charset.forName("EUC-KR")));

        byte[] decoded = decryptor.decodeSampleData(base64);

        assertThat(new String(decoded, CredentialDecryptor.SAMPLE_DATA_CHARSET)).isEqualTo(original);
    }

    // 테스트 전용 — 운영 코드에는 암호화 기능이 필요 없다(수신만 한다).
    private String encrypt(String plain, SecretKey key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
    }
}
