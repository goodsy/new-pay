package com.paycore.testsupport.containers

import com.paycore.testsupport.MySqlTestContainerConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.testcontainers.utility.DockerImageName

/**
 * TEST-PERF-002: 초기화 이미지 사본의 서버 설정은 TEST-PERF-001 시험 서버 옵션과 같고 datadir 위치만 다르다(commit이 volume을 담지 않으므로).
 * binlog는 계속 켜져 있다. Docker 없이 설정만 본다 — 실제 사본 격리·초기화 결과는 internal-admin `InitializedMySqlSnapshotIsolationSpec`.
 */
class InitializedMySqlSnapshotSpec : FunSpec({

    test("server options are exactly the TEST-PERF-001 test server options plus a datadir outside the image volume") {
        val container = InitializedMySqlSnapshot.withServerOptions(MySqlTestContainerConfig.newTestMySqlContainer())
        container.dockerImageName shouldBe "mysql:8.4.11"
        container.commandParts.toList() shouldContainExactlyInAnyOrder MySqlTestContainerConfig.TEST_SERVER_OPTIONS + "--datadir=/var/lib/paycore-mysql"
        container.commandParts.toList() shouldNotContain "--skip-log-bin"
        container.envMap["MYSQL_INITDB_SKIP_TZINFO"] shouldBe "1"
        container.containerId shouldBe null
    }

    test("a container on a snapshot image keeps the same environment and start options as on the repository image") {
        val snapshotImage = DockerImageName.parse("${InitializedMySqlSnapshot.REPOSITORY}:8.4.11-probe").asCompatibleSubstituteFor("mysql")
        val onRepositoryImage = MySqlTestContainerConfig.newTestMySqlContainer()
        val onSnapshot = MySqlTestContainerConfig.newTestMySqlContainer(snapshotImage)
        onSnapshot.envMap shouldBe onRepositoryImage.envMap
        onSnapshot.commandParts.toList() shouldBe onRepositoryImage.commandParts.toList()
    }

    test("a closed snapshot hands out no more containers") {
        val snapshot = InitializedMySqlSnapshot("closed") { error("must not configure after close") }
        snapshot.close()
        shouldThrow<IllegalStateException> { snapshot.newContainer() }
    }
})
