package com.paycore.testsupport

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * TEST-PERF-001: 시험 MySQL 컨테이너의 기동 단축 설정은 **충돌(crash) 복구에만 관계된 설정과 시간대 표 적재 생략**뿐이다. 트랜잭션·잠금·격리·
 * 제약 의미는 그대로다. binlog는 끄지 않는다(binlog가 켜져 있어야 운영처럼 함수/procedure 생성 제약 등이 드러난다). Docker 없이 설정만 본다.
 */
class MySqlTestContainerConfigSpec : FunSpec({

    test("every test MySQL container uses the single repository image with only the crash-durability and tz-table start options") {
        val container = MySqlTestContainerConfig.newTestMySqlContainer()
        container.dockerImageName shouldBe "mysql:8.4.11"
        container.envMap["MYSQL_INITDB_SKIP_TZINFO"] shouldBe "1"
        container.commandParts.toList() shouldContainExactlyInAnyOrder listOf(
            "--innodb-flush-log-at-trx-commit=2", "--sync-binlog=0", "--innodb-doublewrite=OFF",
        )
        container.commandParts.toList() shouldNotContain "--skip-log-bin"
    }

    test("each call builds a new, not started container (no reuse across specs or runs)") {
        val a = MySqlTestContainerConfig.newTestMySqlContainer()
        val b = MySqlTestContainerConfig.newTestMySqlContainer()
        (a === b) shouldBe false
        a.containerId shouldBe null
    }
})
