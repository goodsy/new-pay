package com.paycore.testsupport.containers

import com.paycore.testsupport.MySqlTestContainerConfig
import org.testcontainers.DockerClientFactory
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID

/**
 * 초기화를 마친 시험 MySQL 서버의 **이미지 사본**에서 새 컨테이너를 만드는 공급기(TEST-PERF-002, docs/repository/TECH-BASELINE.md §7.4).
 *
 * 왜: 시험 MySQL 컨테이너 기동 시간의 대부분은 매번 같은 결과를 내는 초기화(`mysqld --initialize` → 모듈 초기화 스크립트(계정·role·procedure) →
 * 임시 서버 정지 → 재시작)다. Spring 컨텍스트마다 새 컨테이너를 띄우는 모듈(internal-admin 약 30개)에서 이 초기화가 시험 시간의 큰 몫이다.
 *
 * 어떻게(시험 JVM 하나에 한 번):
 * 1. 모듈 설정 그대로([configure] — 같은 저장소 이미지·초기화 스크립트·환경변수·OS 시간대) **템플릿** 컨테이너를 정상 초기화·기동한다.
 *    datadir은 이미지의 VOLUME(`/var/lib/mysql`) 밖([DATADIR])에 둔다 — `docker commit`은 volume 내용을 담지 않기 때문이다.
 * 2. 템플릿 mysqld를 정상 종료(SIGTERM, exit 0 확인)하고 `docker commit`으로 이미지를 만든 뒤 템플릿 컨테이너는 지운다.
 * 3. [newContainer]는 매번 그 이미지로 **새** 컨테이너(같은 [configure]·같은 서버 옵션)를 만든다. 이미 초기화된 datadir이 있으므로 공식 이미지
 *    entrypoint는 초기화를 건너뛰고 mysqld만 띄운다.
 *
 * 바뀌지 않는 것: 컨테이너는 여전히 컨텍스트(받는 쪽)마다 새 것이고 서로 공유·재사용하지 않는다(사본의 쓰기는 각 컨테이너 자기 layer에만 남는다).
 * 각 컨테이너의 시작 상태는 "같은 이미지·같은 초기화 스크립트·같은 환경변수로 막 초기화된 서버"와 같다(같은 JVM에서 실제로 한 번 실행한 결과).
 * Flyway·Spring 기동·시험 데이터는 이전처럼 컨테이너마다 처음부터다. 초기화 스크립트 파일·환경변수도 사본 컨테이너에 그대로 있어 스크립트 재실행
 * 시험(멱등성)은 같은 조건에서 돈다. 이미지는 시험 세션 label을 달아 JVM 종료 때 지우고(Ryuk도 정리), 실행 사이에 재사용하지 않는다(`withReuse` 없음).
 * 초기화 스크립트 자체가 실패하면 템플릿 기동이 실패하고 그 실패가 받는 쪽에 그대로 전달된다.
 *
 * 적용하지 않는 곳(D-TEST-PERF-002 "매번 초기화 시험 유지"): 설정을 바꾼 컨테이너(예: ADMIN 변수 없이 초기화), 초기화 스크립트를 빈 MySQL에서 실제로
 * 실행한 결과 자체를 보는 계정·권한 시험(internal-admin `@AdminFreshInitDbTest` — AdminDatabaseAccountSpec)은 새 초기화를 쓴다.
 * 지금 사본을 쓰는 모듈은 internal-admin뿐이다. payment-api·institution-simulator는 측정상 순손해(사본 생성 약 45~50초 > 재사용 이득)라
 * 적용하지 않았고, master-api·financial-batch와 함께 모든 컨테이너가 매번 새 초기화다(TECH-BASELINE §7.4).
 * 사본끼리 같은 것: MySQL server_uuid(auto.cnf)·binlog 시작 위치 — 시험은 복제를 쓰지 않으므로 의미가 없다.
 */
class InitializedMySqlSnapshot(
    private val name: String,
    /** 주어진 이미지로 모듈 기본 설정(초기화 스크립트·환경변수·OS 시간대)의 **시작하지 않은** 컨테이너를 만든다. */
    private val configure: (DockerImageName) -> MySQLContainer,
) : AutoCloseable {

    private var image: DockerImageName? = null
    private var closed = false

    /** 초기화된 이미지 사본으로 만든 **새**, 시작하지 않은 컨테이너. 첫 호출에서 템플릿을 초기화해 이미지를 만든다(시간이 더 걸린다). */
    @Synchronized
    fun newContainer(): MySQLContainer {
        check(!closed) { "InitializedMySqlSnapshot($name) is closed" }
        val snapshot = image ?: build().also { image = it }
        return withServerOptions(configure(snapshot))
    }

    private fun build(): DockerImageName {
        val tag = "${MySqlTestContainerConfig.MYSQL_IMAGE.substringAfter(':')}-$name-${UUID.randomUUID().toString().take(8)}"
        val snapshot = DockerImageName.parse("$REPOSITORY:$tag").asCompatibleSubstituteFor("mysql")
        val buildStart = System.nanoTime()
        val template = withServerOptions(configure(DockerImageName.parse(MySqlTestContainerConfig.MYSQL_IMAGE)))
        try {
            template.start()
            val client = DockerClientFactory.instance().client()
            client.stopContainerCmd(template.containerId).withTimeout(STOP_TIMEOUT_SECONDS).exec()
            val exitCode = client.inspectContainerCmd(template.containerId).exec().state.exitCodeLong
            check(exitCode == 0L) { "template mysqld did not shut down cleanly (exit $exitCode) — snapshot not taken" }
            client.commitCmd(template.containerId).withRepository(REPOSITORY).withTag(tag)
                .withLabels(DockerClientFactory.DEFAULT_LABELS + (DockerClientFactory.TESTCONTAINERS_SESSION_ID_LABEL to DockerClientFactory.SESSION_ID))
                .exec()
        } finally {
            runCatching { template.stop() }
        }
        println("[InitializedMySqlSnapshot:$name] initialized ${MySqlTestContainerConfig.MYSQL_IMAGE} once and committed it as $snapshot in ${(System.nanoTime() - buildStart) / 1_000_000}ms")
        return snapshot
    }

    /**
     * 이미지를 지운다. 사본 컨테이너가 아직 실행 중이면(같은 시각 Spring 컨텍스트 종료가 컨테이너를 멈추는 중) 지울 수 없으므로 잠시 다시 시도한다.
     * 그래도 남으면 시험 세션 label로 Ryuk가 JVM 종료 뒤 지운다(빌드 뒤 남은 이미지가 없는지는 TECH-BASELINE §7.4 측정 절차가 확인한다).
     */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        val snapshot = image ?: return
        val client = DockerClientFactory.instance().client()
        repeat(REMOVE_ATTEMPTS) { attempt ->
            val removed = runCatching { client.removeImageCmd(snapshot.asCanonicalNameString()).withForce(true).exec() }
            if (removed.isSuccess) return
            if (attempt == REMOVE_ATTEMPTS - 1) {
                println("[InitializedMySqlSnapshot:$name] could not remove $snapshot (${removed.exceptionOrNull()?.message}) — left to Ryuk (session label)")
            } else {
                Thread.sleep(REMOVE_RETRY_MILLIS)
            }
        }
    }

    /**
     * 사본 이미지로 만든 새 컨테이너를 한 개 앞서 시작해 두는 공급기([PrefetchedStarts] — TEST-PERF-001). JVM 종료 때 한 hook 안에서
     * 아직 아무에게도 주지 않은 예비 컨테이너를 먼저 멈추고 그 뒤 이미지를 지운다(종료 hook끼리는 순서가 없으므로 하나로 묶는다).
     */
    fun prefetchedStartsClosingAtExit(): PrefetchedStarts<MySQLContainer> {
        val starts = PrefetchedStarts(
            name = "$name-mysql-snapshot", create = ::newContainer, start = { it.start() }, stop = { it.stop() },
            describe = { "${it.dockerImageName} ${it.containerId}" },
        )
        Runtime.getRuntime().addShutdownHook(
            Thread({ runCatching { starts.close() }; close() }, "initialized-mysql-snapshot-$name-close"),
        )
        return starts
    }

    companion object {
        const val REPOSITORY = "paycore-test-mysql-snapshot"

        /** 이미지 VOLUME(`/var/lib/mysql`) 밖의 datadir — commit이 초기화 결과를 담도록. 템플릿과 사본이 같은 값을 쓴다. */
        const val DATADIR = "/var/lib/paycore-mysql"

        private const val STOP_TIMEOUT_SECONDS = 120
        private const val REMOVE_ATTEMPTS = 20
        private const val REMOVE_RETRY_MILLIS = 1_000L

        /** 서버 옵션: [MySqlTestContainerConfig.TEST_SERVER_OPTIONS] + datadir 위치. 그 밖의 서버 설정은 같다. */
        fun withServerOptions(container: MySQLContainer): MySQLContainer =
            container.withCommand(*(MySqlTestContainerConfig.TEST_SERVER_OPTIONS + "--datadir=$DATADIR").toTypedArray())
    }
}
