package com.inspien.eai.bootstrap.crypto;

import com.inspien.eai.bootstrap.dto.ConnBlock;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BOOT-000 접속정보 복호화.
 *
 * <pre>
 *   key    = SHA-1( PHONE_NUMBER.getBytes("UTF-8") )[0..15]   // 앞 16바이트 = AES-128
 *   cipher = AES/ECB/PKCS5Padding
 *   plain  = cipher.doFinal( Base64.decode(field) )
 * </pre>
 *
 * <p><b>주의 1.</b> 키 seed 는 요청에 실어 보낸 전화번호 문자열과 <b>완전히 동일</b>해야 한다.
 * 하이픈 유무, 앞뒤 공백, 전각 하이픈 하나로 전 필드 복호화가 실패한다.
 *
 * <p><b>주의 2.</b> {@code SAMPLE_DATA} 는 암호화 대상이 아니다.
 * Base64 디코드 후 <b>EUC-KR</b> 로 문자열화해야 하며, UTF-8 로 읽으면 한글이 전부 깨지고
 * 그 상태가 FTP 영수증 파일까지 그대로 전파된다.
 *
 * <p><b>주의 3.</b> 암호화 단위는 접속정보 블록 전체가 아니라 <b>블록 안의 개별 필드</b>다.
 * (실측: 각 값이 Base64 24자=16바이트 또는 64자=48바이트, 즉 AES 블록 배수)
 */
@Component
public class CredentialDecryptor {

    private static final String TRANSFORMATION = "AES/ECB/PKCS5Padding";
    private static final String ALGORITHM = "AES";
    private static final int AES_128_KEY_BYTES = 16;

    /** 과제 원본 데이터의 인코딩. 이 상수를 UTF-8 로 바꾸는 순간 한글이 깨진다. */
    public static final Charset SAMPLE_DATA_CHARSET = Charset.forName("EUC-KR");

    /** 줄바꿈이 섞여 오더라도 견디도록 MIME 디코더를 쓴다. */
    private static final Base64.Decoder BASE64 = Base64.getMimeDecoder();

    /**
     * 전화번호 문자열로부터 AES-128 키를 유도한다.
     */
    public SecretKey deriveKey(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw new IllegalArgumentException("복호화 키 seed(전화번호)가 비어 있습니다.");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(phoneNumber.getBytes(StandardCharsets.UTF_8));
            byte[] keyBytes = Arrays.copyOf(digest, AES_128_KEY_BYTES);
            return new SecretKeySpec(keyBytes, ALGORITHM);
        } catch (Exception e) {
            throw new IllegalStateException("복호화 키 유도에 실패했습니다.", e);
        }
    }

    /**
     * 접속정보 블록의 모든 값을 <b>필드 단위로</b> 복호화한다. 키 이름과 순서는 그대로 보존한다.
     *
     * @return 같은 구조·같은 키를 갖되 값만 평문으로 바뀐 블록
     */
    public ConnBlock decryptBlock(ConnBlock block, SecretKey key) {
        Map<String, String> decrypted = new LinkedHashMap<>();
        block.fields().forEach((fieldName, cipherText) ->
                decrypted.put(fieldName, decrypt(cipherText, key, block.blockName() + "." + fieldName)));
        return new ConnBlock(block.blockName(), decrypted);
    }

    /**
     * Base64 로 인코딩된 AES-128 암호문을 복호화한다.
     *
     * @param fieldName 실패 메시지에만 사용. 값 자체는 절대 로그·예외에 싣지 않는다.
     */
    public String decrypt(String base64Cipher, SecretKey key, String fieldName) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key);
            byte[] plain = cipher.doFinal(BASE64.decode(base64Cipher));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "[" + fieldName + "] 복호화에 실패했습니다. "
                            + "요청에 사용한 전화번호와 복호화 키 seed 가 완전히 동일한지 확인하세요 "
                            + "(형식: 010-1234-5678, 하이픈 포함, 공백 없음). 원인: " + e.getClass().getSimpleName(), e);
        }
    }

    /**
     * {@code SAMPLE_DATA} 디코딩. 암호화가 아니라 Base64(EUC-KR) 이다.
     *
     * @return 디코딩된 원본 바이트. 문자열 변환은 호출 측에서 {@link #SAMPLE_DATA_CHARSET} 로 수행한다.
     */
    public byte[] decodeSampleData(String base64) {
        try {
            return BASE64.decode(base64);
        } catch (Exception e) {
            throw new IllegalStateException("[SAMPLE_DATA] Base64 디코딩에 실패했습니다.", e);
        }
    }
}
