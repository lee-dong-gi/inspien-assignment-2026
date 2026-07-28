package com.inspien.eai.bootstrap.dto;

/**
 * BOOT-000 응답에서 추출한 원본(가공 전) 구조.
 *
 * <p>이 시점의 접속정보 값은 아직 <b>필드 단위 AES-128 암호문</b> 이다.
 * 복호화 책임은 {@code CredentialDecryptor} 에 있으며, 이 레코드는 "수신한 그대로"만 담는다 —
 * EAI 관점에서 Sender 단계의 산출물에 해당한다.
 *
 * <p><b>실측 구조</b> (2026 과제 응답 기준)
 * <pre>
 * {
 *   "APPLICANT_KEY"   : 평문 8자,
 *   "ORDER_TB_CONN"   : { URL, ID, PASSWORD, TABLE },        // 값마다 개별 암호화
 *   "SHIPMENT_TB_CONN": { URL, ID, PASSWORD, TABLE },
 *   "FTP_CONN"        : { URL, PORT, ID, PASSWORD, PATH },
 *   "SAMPLE_DATA"     : Base64(EUC-KR XML)                    // 암호화 대상 아님
 * }
 * </pre>
 *
 * @param applicantKey   평문. 두 인터페이스 전 행에 고정 삽입될 지원자 키
 * @param orderTbConn    주문 DB 접속정보 블록
 * @param shipmentTbConn 운송 DB 접속정보 블록
 * @param ftpConn        FTP 접속정보 블록
 * @param sampleData     Base64 (EUC-KR XML). <b>암호화 대상이 아니다</b>
 */
public record BootstrapPayload(
        String applicantKey,
        ConnBlock orderTbConn,
        ConnBlock shipmentTbConn,
        ConnBlock ftpConn,
        String sampleData
) {
}
