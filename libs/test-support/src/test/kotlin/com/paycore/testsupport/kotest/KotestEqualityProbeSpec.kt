package com.paycore.testsupport.kotest

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * TEST-ASSERT-001: Kotest 6.2.5 동등 비교 실측 고정(meta 시험). 각 행은 "실제로는 **다른** 값인데 이 단언이 통과하는가"를 기록한다.
 * `passes = true`이면 그 꼴은 **공허(vacuous)** — 기대값을 다른 하위형으로 바꿔도 시험이 잡지 못한다.
 *
 * 원인(kotest-assertions-core 6.2.5 `io.kotest.assertions.eq.DataClassEq`, 바이트코드 확인 + 아래 실측):
 * 양쪽이 data class이면 `shouldBe`(및 eq를 쓰는 `shouldContain`·`shouldContainExactly`·`shouldNotBe`)는 `equals`가 false일 때
 * 필드별 차이를 계산하고, **차이 0개이면 성공**으로 본다. 다른 중첩 필드 값이 둘 다 data class(`data object` 포함)이고 한쪽이라도
 * 속성이 있으면 그 값을 재귀 비교하는데, 재귀는 한쪽 값의 클래스의 주 생성자 속성만 나열한다. 실측상 **기대값 쪽 중첩 값이
 * `data object`(속성 0개)**이면 나열할 속성이 없어 차이 0개 → "같다"로 판정된다(깊이 1·2·3, Pair·Triple·사용자 data class,
 * data class 안 List 필드의 원소, List·Map 원소인 data class 모두). 최상위 값끼리는 "차이 0개 + 속성 0개"를 실패로 막는 보호가 있어 엄격하다.
 *
 * 금지 꼴: eq 기반 단언의 **기대값 안 깊이 1 이상**에 data object가 있고, 같은 자리의 실제값이 속성 있는 data class일 수 있는 비교.
 * 대체: `shouldBeEqual`(equals 기반 — 아래에서 엄격 실측) 또는 하위형·필드를 직접 단언.
 * 재발 방지: 공유 `io.kotest.provided.ProjectConfig`가 `kotest.assertions.show-data-class-diffs=false`로 data class 필드별 경로를 끈다(아래 guard 두 시험 — 이 spec은 ProjectConfig를 직접 만들지 않고 엔진이 로드한 결과만 잰다).
 * 이 spec의 "Kotest 기본 동작" 실측은 [kotestDefault]로 그 속성을 잠시 기본값(true)으로 되돌려 잰다.
 *
 * Kotest 버전을 올려 동작이 바뀌면 이 시험이 실패한다 — 그때 이 표와 docs/repository/TEST-ASSERT-001-KOTEST-EQUALITY.md를 함께 고친다.
 */
class KotestEqualityProbeSpec : FunSpec({

    fun passes(assertion: () -> Unit): Boolean = runCatching(assertion).isSuccess

    /** Kotest 기본값(show-data-class-diffs=true)에서 [assertion]이 통과하는지 잰다. 속성은 끝나면 원래 값으로 되돌린다. */
    fun kotestDefault(assertion: () -> Unit): Boolean {
        val previous = System.getProperty(SHOW_DATA_CLASS_DIFFS)
        System.setProperty(SHOW_DATA_CLASS_DIFFS, "true")
        try {
            return passes(assertion)
        } finally {
            if (previous == null) System.clearProperty(SHOW_DATA_CLASS_DIFFS) else System.setProperty(SHOW_DATA_CLASS_DIFFS, previous)
        }
    }

    val refusedX: Outcome = Outcome.Refused("X")
    val rejectedX: Outcome = Outcome.Rejected("X")
    val lost: Outcome = Outcome.Lost
    val expired: Outcome = Outcome.Expired

    // ---- Kotest 기본 동작: 공허(VACUOUS) — 기대값 쪽 중첩 값이 data object ----

    test("VACUOUS measured: Pair whose expected second is a data object — Pair(1, Refused(X)) shouldBe Pair(1, Lost) passes") {
        kotestDefault { (1 to refusedX) shouldBe (1 to lost) } shouldBe true
    }

    test("VACUOUS measured: user data class wrapping a data object — W(Refused(X)) shouldBe W(Lost) passes (CreatePaymentEligibilitySpec form)") {
        kotestDefault { Wrapper(refusedX) shouldBe Wrapper(lost) } shouldBe true
    }

    test("VACUOUS measured: Triple whose expected field is a data object passes") {
        kotestDefault { Triple(1, refusedX, "s") shouldBe Triple(1, lost, "s") } shouldBe true
    }

    test("VACUOUS measured: two-field data class whose other field is equal passes (Two(Refused(X), 1) shouldBe Two(Lost, 1))") {
        kotestDefault { Two(refusedX, 1) shouldBe Two(lost, 1) } shouldBe true
    }

    test("VACUOUS measured: nested depth 2 and 3 with the data object on the expected side pass") {
        kotestDefault { Outer(Wrapper(refusedX)) shouldBe Outer(Wrapper(lost)) } shouldBe true
        kotestDefault { Outer2(Outer(Wrapper(refusedX))) shouldBe Outer2(Outer(Wrapper(lost))) } shouldBe true
    }

    test("VACUOUS measured: List of Pairs — listOf(1 to Refused(X)) shouldBe listOf(1 to Lost) passes") {
        kotestDefault { listOf(1 to refusedX) shouldBe listOf(1 to lost) } shouldBe true
    }

    test("VACUOUS measured: Map value that is a user data class wrapping a data object passes") {
        kotestDefault { mapOf("k" to Wrapper(refusedX)) shouldBe mapOf("k" to Wrapper(lost)) } shouldBe true
    }

    test("VACUOUS measured: data class with a List field of wrappers passes (Holder(listOf(W(Refused(X)))) shouldBe Holder(listOf(W(Lost))))") {
        kotestDefault { Holder(listOf(Wrapper(refusedX))) shouldBe Holder(listOf(Wrapper(lost))) } shouldBe true
    }

    test("VACUOUS measured: shouldContain and shouldContainExactly on Pairs use the same eq and pass") {
        kotestDefault { listOf(1 to refusedX) shouldContain (1 to lost) } shouldBe true
        kotestDefault { listOf(1 to refusedX) shouldContainExactly listOf(1 to lost) } shouldBe true
    }

    // ---- Kotest 기본 동작: 엄격(STRICT) ----

    test("STRICT measured: data object on the actual side (expected side has the data class) fails — depth 1, 2, 3 and List of Pairs") {
        kotestDefault { Wrapper(lost) shouldBe Wrapper(refusedX) } shouldBe false
        kotestDefault { (1 to lost) shouldBe (1 to refusedX) } shouldBe false
        kotestDefault { Outer(Wrapper(lost)) shouldBe Outer(Wrapper(refusedX)) } shouldBe false
        kotestDefault { Outer2(Outer(Wrapper(lost))) shouldBe Outer2(Outer(Wrapper(refusedX))) } shouldBe false
        kotestDefault { listOf(1 to lost) shouldBe listOf(1 to refusedX) } shouldBe false
    }

    test("STRICT measured: same property name and value on different sealed subtypes — W(Refused(X)) vs W(Rejected(X)) and Pair — fails") {
        kotestDefault { Wrapper(refusedX) shouldBe Wrapper(rejectedX) } shouldBe false
        kotestDefault { (1 to refusedX) shouldBe (1 to rejectedX) } shouldBe false
    }

    test("STRICT measured: two different data objects inside a data class — W(Lost) shouldBe W(Expired) — fails") {
        kotestDefault { Wrapper(lost) shouldBe Wrapper(expired) } shouldBe false
    }

    test("STRICT measured: another differing field in the same data class makes it fail (Two(Refused(X), 1) shouldBe Two(Lost, 2))") {
        kotestDefault { Two(refusedX, 1) shouldBe Two(lost, 2) } shouldBe false
    }

    test("STRICT measured: direct comparison of the sealed values fails, both directions") {
        kotestDefault { refusedX shouldBe lost } shouldBe false
        kotestDefault { lost shouldBe refusedX } shouldBe false
    }

    test("STRICT measured: List or Map holding the sealed values themselves (not wrapped) fails") {
        kotestDefault { listOf(refusedX) shouldBe listOf(lost) } shouldBe false
        kotestDefault { mapOf("k" to refusedX) shouldBe mapOf("k" to lost) } shouldBe false
        kotestDefault { Holder2(listOf(refusedX)) shouldBe Holder2(listOf(lost)) } shouldBe false
    }

    test("STRICT measured: enum field, plain (non-data) object field and null expected field fail") {
        kotestDefault { (1 to Kind.A) shouldBe (1 to Kind.B) } shouldBe false
        kotestDefault { PlainWrapper(Plain.Refused("X")) shouldBe PlainWrapper(Plain.Lost) } shouldBe false
        kotestDefault { NullableWrapper(refusedX) shouldBe NullableWrapper(null) } shouldBe false
    }

    test("STRICT measured: different top-level data classes fail") {
        kotestDefault { (Wrapper(refusedX) as Any) shouldBe OtherWrapper(lost) } shouldBe false
    }

    test("STRICT measured: shouldBeIn uses equals and fails, including the ApprovalRaceSpec Pair<Any, Any> form") {
        kotestDefault { (1 to refusedX) shouldBeIn listOf(1 to lost) } shouldBe false
        kotestDefault { ((refusedX as Any) to (0 as Any)) shouldBeIn listOf<Pair<Any, Any>>(lost to 0, expired to 1) } shouldBe false
    }

    test("STRICT measured: shouldBeEqual (equals-based) fails for every vacuous shape above and passes for equal values") {
        kotestDefault { (1 to refusedX) shouldBeEqual (1 to lost) } shouldBe false
        kotestDefault { Wrapper(refusedX) shouldBeEqual Wrapper(lost) } shouldBe false
        kotestDefault { Triple(1, refusedX, "s") shouldBeEqual Triple(1, lost, "s") } shouldBe false
        kotestDefault { Outer2(Outer(Wrapper(refusedX))) shouldBeEqual Outer2(Outer(Wrapper(lost))) } shouldBe false
        kotestDefault { listOf(1 to refusedX) shouldBeEqual listOf(1 to lost) } shouldBe false
        kotestDefault { mapOf("k" to Wrapper(refusedX)) shouldBeEqual mapOf("k" to Wrapper(lost)) } shouldBe false
        kotestDefault { Holder(listOf(Wrapper(refusedX))) shouldBeEqual Holder(listOf(Wrapper(lost))) } shouldBe false
        kotestDefault { Wrapper(refusedX) shouldBeEqual Wrapper(Outcome.Refused("X")) } shouldBe true
    }

    test("measured: shouldNotBe on a vacuous shape errs loudly (fails although the values differ) — not a silent pass") {
        kotestDefault { (1 to refusedX) shouldNotBe (1 to lost) } shouldBe false
    }

    // ---- 재발 방지: 공유 ProjectConfig ----

    test("guard: the ProjectConfig the engine loaded has turned the data class field-by-field path off (this spec does not construct it)") {
        System.getProperty(SHOW_DATA_CLASS_DIFFS) shouldBe "false"
    }

    test("guard: under the project setting every vacuous shape above fails with plain shouldBe / shouldContain") {
        passes { (1 to refusedX) shouldBe (1 to lost) } shouldBe false
        passes { Wrapper(refusedX) shouldBe Wrapper(lost) } shouldBe false
        passes { Triple(1, refusedX, "s") shouldBe Triple(1, lost, "s") } shouldBe false
        passes { Two(refusedX, 1) shouldBe Two(lost, 1) } shouldBe false
        passes { Outer2(Outer(Wrapper(refusedX))) shouldBe Outer2(Outer(Wrapper(lost))) } shouldBe false
        passes { listOf(1 to refusedX) shouldBe listOf(1 to lost) } shouldBe false
        passes { mapOf("k" to Wrapper(refusedX)) shouldBe mapOf("k" to Wrapper(lost)) } shouldBe false
        passes { Holder(listOf(Wrapper(refusedX))) shouldBe Holder(listOf(Wrapper(lost))) } shouldBe false
        passes { listOf(1 to refusedX) shouldContain (1 to lost) } shouldBe false
        passes { listOf(1 to refusedX) shouldContainExactly listOf(1 to lost) } shouldBe false
        // 같은 값은 여전히 통과한다.
        passes { Wrapper(refusedX) shouldBe Wrapper(Outcome.Refused("X")) } shouldBe true
    }

    // ---- 공유 helper shouldBeEqualNullable(equals, null 허용) — 가드와 무관하게 엄격 ----

    test("shouldBeEqualNullable is strict for the vacuous shapes even with Kotest defaults, and handles null") {
        kotestDefault { Wrapper(refusedX) shouldBeEqualNullable Wrapper(lost) } shouldBe false
        kotestDefault { (1 to refusedX) shouldBeEqualNullable (1 to lost) } shouldBe false
        kotestDefault { listOf(Wrapper(refusedX)) shouldBeEqualNullable listOf(Wrapper(lost)) } shouldBe false
        kotestDefault { (Wrapper(refusedX) as Wrapper?) shouldBeEqualNullable null } shouldBe false
        kotestDefault { (null as Wrapper?) shouldBeEqualNullable Wrapper(lost) } shouldBe false
        kotestDefault { (null as Wrapper?) shouldBeEqualNullable null } shouldBe true
        kotestDefault { Wrapper(refusedX) shouldBeEqualNullable Wrapper(Outcome.Refused("X")) } shouldBe true
    }
}) {
    companion object {
        const val SHOW_DATA_CLASS_DIFFS = "kotest.assertions.show-data-class-diffs"
    }

    sealed interface Outcome {
        data class Refused(val code: String) : Outcome
        data class Rejected(val code: String) : Outcome
        data object Lost : Outcome
        data object Expired : Outcome
    }

    sealed interface Plain {
        data class Refused(val code: String) : Plain
        object Lost : Plain
    }

    enum class Kind { A, B }

    data class Wrapper(val outcome: Outcome)
    data class OtherWrapper(val outcome: Outcome)
    data class PlainWrapper(val value: Plain)
    data class Outer(val wrapper: Wrapper)
    data class Outer2(val outer: Outer)
    data class Holder(val wrappers: List<Wrapper>)
    data class Holder2(val outcomes: List<Outcome>)
    data class NullableWrapper(val outcome: Outcome?)
    data class Two(val outcome: Outcome, val n: Int)
}
