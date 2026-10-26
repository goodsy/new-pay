package com.paycore.admin.account.application.required

/** 저장 값과 그 행의 JPA version(기술 값 — 업무 security_version과 다름). 갱신 Port는 기대 version을 받아 오래된 객체로 최신 행을 덮지 않는다. */
data class Versioned<T>(val value: T, val version: Long)
