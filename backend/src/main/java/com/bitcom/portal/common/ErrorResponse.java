package com.bitcom.portal.common;

/** 프론트(lib/client.ts)가 기대하는 오류 본문 */
public record ErrorResponse(String error, String message) {}
