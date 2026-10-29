package com.paycore.archfixture.operatorcancel

/** ArchUnit 위반 fixture(checkpoint L-4): 서명을 문자열 동등 비교(Kotlin ==)로 확인한다 — 시간 차이로 서명이 새어 나갈 수 있다. */
object StringCompareSignature {
    fun verify(expected: String, presented: String): Boolean = expected == presented
}
