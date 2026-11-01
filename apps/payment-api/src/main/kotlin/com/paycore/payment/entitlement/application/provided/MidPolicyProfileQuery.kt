package com.paycore.payment.entitlement.application.provided

/**
 * MID의 정책 Assignment 내용 상태 조회(DEMO-SEED-001 §4.5 — `demo-credential` 사전 조건). **호출자의 PAYMENT 로컬 TX에 참여한다**(MANDATORY). 로컬 권리
 * 투영만 읽는다(MID 소속 비잠금 → head `FOR SHARE` → 최신 revision 내용, 쓰기·외부 호출·캐시 없음). 결과는 판정 재료만 — "Demo 가맹점인가"의 판정은 호출자.
 */
interface MidPolicyProfileQuery {
    /**
     * MID가 최신 Snapshot에 없으면(소속 없음·head 비었음·막힘·미지원 schema·내용 없음) null.
     * @throws EntitlementEvaluationFailure 저장소 일시 실패(판정 결과가 아니다 — 호출자 TX는 rollback).
     */
    fun find(mid: String): MidPolicyProfile?
}

/** 최신 Snapshot의 그 MID 정책 Assignment 내용 상태 목록(순서 무관). 비어 있을 수 있다(v1 Snapshot·승인 조건 없음). */
data class MidPolicyProfile(val mid: String, val revision: Long, val policyContentStatuses: List<MidPolicyContentStatus>)

/** 정책 버전 내용 상태(D-MS2-003). UNDEFINED = 내용 미정, DEMO = 내용 없는 Demo 버전(운영값 아님). */
enum class MidPolicyContentStatus { UNDEFINED, DEMO }
