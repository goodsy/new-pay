package com.paycore.admin.account.application.required

import com.paycore.admin.account.domain.AdminActorId
import com.paycore.admin.account.domain.EnrollmentCode
import com.paycore.admin.account.domain.EnrollmentCodeHash
import java.time.Instant

/**
 * 1회용 등록 코드 기록(§14 Store ③ — 프로토콜 기록, §4.3.6). 원문 없이 SHA-256만. runtime 열 단위 UPDATE는 `used_at`·`revoked_at`만.
 * - [consume]: 원자 소비 `UPDATE … SET used_at = ? WHERE code_hash = ? AND used_at IS NULL AND revoked_at IS NULL AND expires_at > ?` — 영향 1이면 true.
 *   같은 코드로 동시에 완료해도 한 쪽만 true(EnrollmentCodeRaceSpec).
 * - [revokeUnused]: 대상의 미사용·미폐기 코드를 모두 폐기(새 발급·정지 TX — 대상당 활성 1개, rev 0.3 H-B)하고 폐기한 코드의 **발급 근거**(비밀 아님 —
 *   `enc_`·`apr_`·`episode:` 형식)를 발급 순으로 돌려준다(감사 detail — S3 리뷰 m-2). 대상 계정 행 잠금 아래에서만 부른다(같은 대상의 코드 발급도 그 잠금 아래).
 */
interface EnrollmentCodeStore {
    fun insert(code: EnrollmentCode)

    fun find(hash: EnrollmentCodeHash): EnrollmentCode?

    fun consume(hash: EnrollmentCodeHash, now: Instant): Boolean

    fun revokeUnused(target: AdminActorId, now: Instant): List<String>

    /** 회차 [episodeNo]의 아직 쓸 수 있는 코드 수(미사용·미폐기·만료 전) — 회차 종료 판정(회차 행 잠금 아래, rev 0.3 m-B). */
    fun countUsableForEpisode(episodeNo: Long, now: Instant): Int
}
