package com.paycore.admin.merchantops

import com.paycore.admin.merchantops.adapter.web.ContractManagerReferences
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.mock.web.MockHttpSession
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * MERCHANT-FLOW-001 FLOW-2d 계약 담당자 불투명 참조(D-GOV-024 Q-FLOW-40 — 세션에 묶인 무작위 참조, [구현 관례] 형식·상한·만료):
 * - 목록 응답마다 새 참조(`mgr_` + 무작위 16바이트 base64url 22자)를 발급하고 세션 속성 하나(**문자열** — JSON: 판·만료 시각·참조 → 계정 키)에 대응표를 둔다(세션 문자열만 관례).
 * - 새 목록은 대응표를 **바꾼다**(옛 참조 무효). 만료(발급 뒤 30분)·모르는 참조·형식 위반·손상된 속성·세션 없음·다른 세션 → null(fail-closed — 호출자 400 NOT_ALLOWED).
 * - 상한 101개(목록 100 + 본인)를 넘기면 결함(IAE). 참조·속성에는 로그인 이름이 없다(계정 키는 서버 세션에만 — 응답·DOM에 없다).
 */
class ContractManagerReferencesSpec : BehaviorSpec({

    var now = Instant.parse("2026-11-28T01:00:00Z")
    val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = now
    }
    val refs = ContractManagerReferences(clock)
    val a = "adm_01JC00000000000000000000V1"
    val b = "adm_01JC00000000000000000000V2"

    Given("issue and resolve") {
        Then("one distinct mgr_ reference per account, resolvable in the same session; the session attribute is a JSON string without login names") {
            now = Instant.parse("2026-11-28T01:00:00Z")
            val session = MockHttpSession()
            val issued = refs.issue(session, listOf(a, b))
            issued shouldHaveSize 2
            issued.forEach { it shouldMatch Regex("^mgr_[A-Za-z0-9_-]{22}$"); it shouldNotContain "adm_" }
            (issued.toSet().size) shouldBe 2
            refs.resolve(session, issued[0]) shouldBe a
            refs.resolve(session, issued[1]) shouldBe b
            val attribute = session.getAttribute(ContractManagerReferences.ATTRIBUTE)
            attribute.shouldBeInstanceOf<String>()
            attribute shouldNotContain "login"
        }

        Then("fail-closed: unknown / malformed reference, another session, no session, expiry (30 min), a corrupted attribute → null") {
            now = Instant.parse("2026-11-28T01:00:00Z")
            val session = MockHttpSession()
            val ref = refs.issue(session, listOf(a)).single()
            refs.resolve(session, "mgr_AAAAAAAAAAAAAAAAAAAAAA").shouldBeNull()
            refs.resolve(session, a).shouldBeNull()
            refs.resolve(session, "").shouldBeNull()
            refs.resolve(MockHttpSession(), ref).shouldBeNull()
            refs.resolve(null, ref).shouldBeNull()
            now = now.plus(Duration.ofMinutes(30)).minusMillis(1)
            refs.resolve(session, ref) shouldBe a
            now = now.plusMillis(1)
            refs.resolve(session, ref).shouldBeNull()
            val broken = MockHttpSession().also { it.setAttribute(ContractManagerReferences.ATTRIBUTE, "{not json") }
            refs.resolve(broken, ref).shouldBeNull()
        }

        Then("a new list replaces the table (old references no longer resolve); more than 101 accounts is a defect") {
            now = Instant.parse("2026-11-28T01:00:00Z")
            val session = MockHttpSession()
            val old = refs.issue(session, listOf(a)).single()
            val fresh = refs.issue(session, listOf(a)).single()
            (old == fresh) shouldBe false
            refs.resolve(session, old).shouldBeNull()
            refs.resolve(session, fresh) shouldBe a
            shouldThrow<IllegalArgumentException> { refs.issue(session, (1..102).map { "adm_01JC" + it.toString().padStart(22, '0') }) }
        }
    }
})
