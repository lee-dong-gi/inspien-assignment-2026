/**
 * EAI 연계 엔진 코어.
 *
 * <p>이 패키지에는 <b>주문도 운송도 등장하지 않는다.</b> 그것이 의도다.
 * 연계 엔진은 흐르는 데이터의 의미를 소유하지 않는다. 받아서, 검증하고, 변환해서, 전달할 뿐이다.
 * 도메인 지식은 전부 시나리오별 구현 패키지에 있다.
 *
 * <h2>구성</h2>
 * <pre>
 *   message   CanonicalMessage / MessageHeader / ProcessResult   표준 메시지와 실행 결과
 *   sender    Sender                                             송신 시스템 어댑터
 *   validator Validator / ValidationResult                       적재 가능 여부 판정
 *   mapper    Mapper                                             소스 → 타깃 구조 변환
 *   receiver  Receiver / Delivery / DeliveryCoordinator          수신 시스템 어댑터와 확정 조율
 *   flow      IntegrationFlow                                    파이프라인 조립
 *   log       InterfaceLogger / Step                             운영 관점 실행 이력
 *   exception EaiErrorCode / Retryable · NonRetryableException   예외 분류 체계
 * </pre>
 *
 * <h2>설계 근거</h2>
 * <ol>
 *   <li><b>가운데에 중립 표현을 둔다.</b> 송신 형식도 수신 형식도 아닌 표준 메시지를 경유하면
 *       변환 조합이 N×M 에서 N+M 으로 줄고, 양쪽 시스템은 서로를 모른 채로 남는다.</li>
 *   <li><b>실시간과 배치가 같은 파이프라인을 쓴다.</b> 두 시나리오의 차이는 Sender 구현체
 *       하나로 흡수된다. 기능을 두 벌 만드는 대신 구조를 한 벌 만든다.</li>
 *   <li><b>준비와 확정을 분리한다.</b> FTP 는 DB 트랜잭션에 참여할 수 없다. 2PC 대신
 *       보상 트랜잭션을 {@link com.inspien.eai.engine.receiver.Delivery} 타입으로 고정했다.</li>
 *   <li><b>부분 성공을 1급 상태로 둔다.</b> 조용히 일부만 처리하고 성공이라 답하는 것이
 *       실패보다 위험하다. {@code PARTIAL} 은 예외적 상태가 아니라 정상 결과의 하나다.</li>
 * </ol>
 *
 * @see <a href="../../../../../../docs/interface-spec.md">인터페이스 정의서</a>
 */
package com.inspien.eai.engine;
