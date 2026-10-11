package com.paycore.master.merchant.application

import com.navercorp.fixturemonkey.FixtureMonkey
import com.navercorp.fixturemonkey.kotlin.KotlinPlugin
import com.navercorp.fixturemonkey.kotlin.giveMe
import com.paycore.master.merchant.domain.DisplayName

/**
 * 대량 Merchant 구조 입력의 프로젝트 Fixture(Test Fixture Policy 5·6·8 — Fixture Monkey는 이 객체 안에만 있고 시험 본문은 업무 이름만 부른다).
 * Fixture Monkey가 만든 원시 seed를 그대로 쓰지 않고, 표시 이름은 **공식 생성 경로**([DisplayName] 생성자)를 통과하는 값으로 다듬고, Site 수는
 * 1..[MAX_SITES]로 묶는다(정상 Fixture는 Domain Invariant를 만족). seed 고정으로 재현 가능하다. 핵심 업무 규칙 시험은 명시 Fixture를 쓴다.
 */
object RandomOnboardingFixture {

    /** Fixture Monkey가 채우는 원시 seed(업무 의미 없음). */
    data class RawMerchantSeed(val nameSeed: String, val siteCountSeed: Int)

    data class MerchantShape(val displayName: DisplayName, val siteCount: Int)

    fun validMerchants(count: Int, seed: Long): List<MerchantShape> {
        val monkey = FixtureMonkey.builder().plugin(KotlinPlugin()).seed(seed).build()
        return monkey.giveMe<RawMerchantSeed>(count).map { raw ->
            MerchantShape(DisplayName(displayName(raw.nameSeed)), Math.floorMod(raw.siteCountSeed, MAX_SITES) + 1)
        }
    }

    /** 출력 가능한 code point만, 최대 100개, 앞뒤 공백 없음. 남는 것이 없으면 고정 문구. */
    private fun displayName(seed: String): String {
        val cleaned = buildString {
            var count = 0
            val points = seed.codePoints().iterator()
            while (points.hasNext() && count < MAX_NAME) {
                val cp = points.nextInt()
                if (Character.isISOControl(cp) || cp in 0xD800..0xDFFF || !Character.isDefined(cp) || Character.isWhitespace(cp)) continue
                appendCodePoint(cp)
                count++
            }
        }
        return cleaned.ifEmpty { "Demo Merchant" }
    }

    private const val MAX_SITES = 3
    private const val MAX_NAME = 100
}
