package com.paycore.master.merchant.domain

/**
 * 사업자 생성·전이 결과(명세 §4.4): 새 Root 상태([business] — 수정이면 version +1), 저장할 정규 profile([profile] — [BusinessProfile.canonical]),
 * 바뀐 항목([change] — 이력 `changes`의 원천, 마스킹 값만). Application이 이것으로 저장(diff)·이력·Outbox를 같은 TX에 쓴다.
 */
data class BusinessProfileEdit(val business: Business, val profile: BusinessProfile, val change: BusinessProfileChange)