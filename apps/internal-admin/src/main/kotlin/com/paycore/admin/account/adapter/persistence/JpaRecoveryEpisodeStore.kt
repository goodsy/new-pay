package com.paycore.admin.account.adapter.persistence

import com.paycore.admin.account.adapter.persistence.PersistenceFailures.translate
import com.paycore.admin.account.application.required.RecoveryEpisode
import com.paycore.admin.account.application.required.RecoveryEpisodeStore
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * [RecoveryEpisodeStore] — native SQL(행 4열·잠금 읽기·INSERT·`closed_at` UPDATE만이라 Entity 없이 둔다: runtime 권한 SELECT·INSERT + 열 UPDATE와 같은 모양).
 * 열린 회차는 생성 열 `open_marker = 1`(UNIQUE — 최대 1행). 시각은 `DATE_FORMAT` 문자열(UTC)로 읽고 바인딩은 `Instant`(연결 time_zone UTC).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaRecoveryEpisodeStore(private val entityManager: EntityManager) : RecoveryEpisodeStore {

    override fun lockOpen(): Long? = lockOpenEpisode()?.episodeNo

    override fun lockOpenEpisode(): RecoveryEpisode? = translate(ENTITY) { select("WHERE open_marker = 1 FOR UPDATE") }

    /**
     * 비잠금 읽기는 생성 열 UNIQUE 보조 인덱스가 아니라 `closed_at IS NULL`(클러스터 행)로 한다 — 동시 실행이 E1을 닫고 같은 키(1)로 E2를 INSERT한 미커밋 상태에서
     * 보조 인덱스 레코드가 E2를 가리키면 일관 읽기가 E1의 이전 버전을 찾지 못해 "열린 회차 없음"으로 보이기 때문이다(S6 m-1 실측). 열린 회차는 최대 1행.
     */
    // 변이 F-m1-index(이 읽기를 `open_marker = 1`로 되돌림)는 결과 수준 동치다(S8 재실행 — 보조 인덱스 경로면 죽은 회차를 놓쳐 INSERT 1062로, 이 경로면 닫기 0행으로 — 둘 다 LOST·같은 감사).
    // 그래도 이 경로를 유지하는 이유: 열린 회차가 실제로 있는데 "없음"으로 읽는 것은 읽기 오류이고, 1062 경로는 UNIQUE 제약 하나에 정확성을 모두 맡긴다.
    override fun findOpen(): RecoveryEpisode? = translate(ENTITY) { select("WHERE closed_at IS NULL ORDER BY episode_no DESC LIMIT 1") }

    override fun nextEpisodeNo(): Long = translate(ENTITY) {
        (entityManager.createNativeQuery("SELECT COALESCE(MAX(episode_no), 0) + 1 FROM admin_recovery_episode").singleResult as Number).toLong()
    }

    override fun open(episode: RecoveryEpisode): Unit = translate(ENTITY) {
        require(episode.closedAt == null) { "a new episode is open" }
        entityManager.createNativeQuery("INSERT INTO admin_recovery_episode (episode_no, opened_at, expires_at) VALUES (?1, ?2, ?3)")
            .setParameter(1, episode.episodeNo).setParameter(2, episode.openedAt).setParameter(3, episode.expiresAt).executeUpdate()
    }

    override fun close(episodeNo: Long, now: Instant): Unit = translate(ENTITY) {
        val changed = entityManager.createNativeQuery("UPDATE admin_recovery_episode SET closed_at = ?1 WHERE episode_no = ?2 AND closed_at IS NULL")
            .setParameter(1, now).setParameter(2, episodeNo).executeUpdate()
        check(changed == 1) { "the episode to close must be open under its row lock" }
    }

    override fun closeIfOpen(episodeNo: Long, now: Instant): Boolean = translate(ENTITY) {
        entityManager.createNativeQuery("UPDATE admin_recovery_episode SET closed_at = ?1 WHERE episode_no = ?2 AND closed_at IS NULL")
            .setParameter(1, now).setParameter(2, episodeNo).executeUpdate() == 1
    }

    private fun select(where: String): RecoveryEpisode? {
        val row = entityManager.createNativeQuery(
            "SELECT episode_no, DATE_FORMAT(opened_at, '%Y-%m-%dT%H:%i:%s.%fZ'), DATE_FORMAT(expires_at, '%Y-%m-%dT%H:%i:%s.%fZ'), " +
                "DATE_FORMAT(closed_at, '%Y-%m-%dT%H:%i:%s.%fZ') FROM admin_recovery_episode $where",
        ).resultList.firstOrNull() as Array<*>? ?: return null
        return RecoveryEpisode((row[0] as Number).toLong(), Instant.parse(row[1] as String), Instant.parse(row[2] as String), (row[3] as String?)?.let(Instant::parse))
    }

    private companion object {
        const val ENTITY = "admin_recovery_episode"
    }
}
