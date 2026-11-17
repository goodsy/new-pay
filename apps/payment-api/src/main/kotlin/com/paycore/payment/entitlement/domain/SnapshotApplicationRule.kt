package com.paycore.payment.entitlement.domain

/**
 * 수신 판정: 기록할 outcome과 바뀐 head(바뀌지 않으면 null). [appliedContent]는 APPLIED일 때 revision별로 저장할 내용.
 * [merchantCodeChanged]: APPLIED가 이미 코드가 있던 head의 코드를 **다른 코드로 바꾸거나 없앴다**(MERCHANT-CODE-001 §6 — Snapshot은 MID가 있어야 발행되고 MID는 계약 승인 뒤에만
 * 있으며 그 뒤 코드는 불변이므로 정상 경로에서는 일어나지 않는다 → 정상 적용하되 경보 `merchant_code_changed_with_mid`). 코드가 처음 생긴 승격(v3 → v4)은 변경이 아니다.
 * 코드 소실(code → null, 예: MASTER가 v4 이전으로 되돌아가 더 높은 revision을 v3로 발행)도 같은 카운터로 센다(리뷰 MINOR-1): 가맹점 쪽에서는 이미 받은 코드가 응답·웹훅에서
 * 사라지는 것이라 "나간 코드의 변경"과 같은 불변식 위반이고, 지표는 label 없음 계약(§9 Observability)이라 사유 label을 새로 두지 않는다 — 구분은 ERROR 로그 문구로 한다.
 */
data class ApplicationDecision(
    val outcome: InboxOutcome,
    val newHead: EntitlementHead?,
    val appliedContent: EntitlementContent?,
    val merchantCodeChanged: Boolean = false,
)

/**
 * Snapshot 적용 규칙(명세 §3.5, 허용 §4.2 R2·R4). head 잠금 아래 한 PAYMENT 로컬 TX에서 판정한다. 전체 상태 Snapshot이라 중간 revision 누락은
 * 판정에 영향이 없다. **더 작은 revision(늦게 도착한 과거 허용)은 head를 바꾸지 않는다** — 차단을 되돌릴 수 없다.
 *
 * | 수신 | 결과 |
 * |---|---|
 * | 지원 버전, revision > 지금까지 본 최대 | APPLIED(내용 교체·lease 설정·막힘 해제) |
 * | 지원 버전, revision == 막은 revision(지원하지 않던 버전의 재처리) | APPLIED |
 * | 지원 버전, revision == head.revision(막힘 없음)·같은 hash | lease가 더 길면 LEASE_EXTENDED, 아니면 STALE |
 * | 지원 버전, revision == head.revision·다른 hash | INTEGRITY_CONFLICT(head를 막는다 — revision 증가 누락 결함) |
 * | 지원 버전, 그 밖(더 작은 revision 등) | STALE |
 * | 미지원 버전, revision > 지금까지 본 최대 | UNSUPPORTED + head를 VERSION_UNSUPPORTED로 막는다(더 새 권리를 못 읽으면서 옛 권리를 쓰지 않는다) |
 * | 미지원 버전, 그 밖 | UNSUPPORTED(기록만) |
 */
object SnapshotApplicationRule {
    fun decide(head: EntitlementHead, incoming: IncomingSnapshot): ApplicationDecision {
        require(head.merchantId == incoming.merchantId) { "head and snapshot must be of the same merchant" }
        if (!incoming.isSupported) {
            if (incoming.revision > head.highestSeen) {
                return ApplicationDecision(
                    InboxOutcome.UNSUPPORTED, head.copy(blocked = HeadBlock.VERSION_UNSUPPORTED, blockedRevision = incoming.revision), null,
                )
            }
            return ApplicationDecision(InboxOutcome.UNSUPPORTED, null, null)
        }
        val content = checkNotNull(incoming.content)
        val lease = checkNotNull(incoming.lease)
        val reprocessesBlockedVersion = head.blocked == HeadBlock.VERSION_UNSUPPORTED && incoming.revision == head.blockedRevision
        return when {
            incoming.revision > head.highestSeen || reprocessesBlockedVersion -> ApplicationDecision(
                InboxOutcome.APPLIED,
                EntitlementHead(
                    head.merchantId, incoming.revision, content.contentHash, incoming.schemaVersion, lease, incoming.eventId, null, null, content.merchantCode,
                ),
                content,
                merchantCodeChanged = head.merchantCode != null && head.merchantCode != content.merchantCode,
            )
            incoming.revision == head.revision && head.blocked == null -> when {
                content.contentHash != head.contentHash -> ApplicationDecision(
                    InboxOutcome.INTEGRITY_CONFLICT, head.copy(blocked = HeadBlock.INTEGRITY_CONFLICT, blockedRevision = incoming.revision), null,
                )
                lease.leaseUntil.isAfter(checkNotNull(head.lease).leaseUntil) ->
                    ApplicationDecision(InboxOutcome.LEASE_EXTENDED, head.copy(lease = lease, sourceEventId = incoming.eventId), null)
                else -> ApplicationDecision(InboxOutcome.STALE, null, null)
            }
            else -> ApplicationDecision(InboxOutcome.STALE, null, null)
        }
    }
}
