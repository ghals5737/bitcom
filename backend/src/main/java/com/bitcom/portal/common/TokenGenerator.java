package com.bitcom.portal.common;

/** 규칙 3: 랜덤은 Service 안에서 만들지 않고 주입받는다. 테스트에서는 고정값 구현으로 교체. */
public interface TokenGenerator {
    /** 세션 ID: 32바이트 hex (64자) */
    String sessionId();

    /** 임시 비밀번호: 12자, 숫자·특수문자 포함 보장 */
    String temporaryPassword();
}
