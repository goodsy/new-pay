package com.paycore.testsupport.kotest

/**
 * TEST-ASSERT-001: `equals`만으로 판정하는 동등 단언(null 허용). Kotest `shouldBeEqual`은 non-null 값만 받으므로, 양쪽이 nullable인
 * 비교(예: 복원 전후의 `transaction`, 조회 view)에 쓴다. Kotest `shouldBe`의 data class 필드별 경로(기대값 안 중첩 `data object`를
 * "같다"로 보는 공허 통과 — docs/repository/TEST-ASSERT-001-KOTEST-EQUALITY.md)를 타지 않으므로 공유 ProjectConfig 설정과 독립적으로 엄격하다.
 * 같으면 실제값을 돌려준다.
 */
infix fun <T> T?.shouldBeEqualNullable(expected: T?): T? {
    if (this != expected) throw AssertionError("expected:<$expected> but was:<$this>")
    return this
}
