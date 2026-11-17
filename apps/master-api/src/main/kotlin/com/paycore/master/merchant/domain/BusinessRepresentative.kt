package com.paycore.master.merchant.domain

/** 대표자(현재 관계 — 과거 행은 Aggregate에 싣지 않는다). 유형: SOLE(단독)·JOINT(공동)·EACH(각자). 같은 이름·유형 두 행 허용(m-5 — 행은 순서로 구별). */
data class BusinessRepresentative(val name: PersonName, val type: RepresentativeType)

/** 대표자 유형(영속 값 `business_representative.representative_type`). */
enum class RepresentativeType { SOLE, JOINT, EACH }