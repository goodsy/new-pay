package com.paycore.financial

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.mysql.MySQLContainer

/**
 * 운영 설정(main migration 위치만)으로 기동했을 때의 schema를 고정한다. Q-BATCH-META 답변(D-ACQ-007 — Batch metadata는 FINANCIAL DB 같은 schema)에 따라
 * BATCH_*는 main V1이 만들고, 결제 사실 Inbox·투영은 main V2가 만든다(FIN-FACT-RELAY-001). 이전 기대값(main 비어 있음·BATCH_* 없음)은 "답변 후 그 결정에 맞게 바꾼다"고
 * 적어 둔 그 결정으로 바뀌었다(완화 아님 — 표가 생겼다).
 * FIN-DBACCOUNT-001(D-FFR-002 — 사용자 결정 2026-11-05): runtime 표·열 단위 권한 migration 위치(db/runtime-privilege/financial, V3)가 더해졌다. 기대값 변경은
 * 완화가 아니다 — 위치·적용 버전이 하나씩 **늘었고** V1·V2와 표 집합 단언은 그대로다. 컨테이너는 실제 초기화 스크립트 계정(FinancialMySqlContainerConfig)이고
 * Flyway Bean은 migration 계정 연결로 history를 읽는다(runtime 계정은 flyway_schema_history 1142 — FinancialDatabaseAccountSpec).
 * ACQUIRING-001(사용자 결정 D-FFR-003·D-ACQ-002·003·008·012): V4(보류 해제 기록)·V5(매입 lane·봉인 파일·레코드·상계·제외)·V6(그 runtime 권한)이 더해졌다.
 * 기대값 변경은 완화가 아니다 — 적용 버전·표가 **늘었고** 기존 버전·표 단언은 그대로다.
 */
@SpringBootTest
@Import(FinancialMySqlContainerConfig::class)
class MainMigrationLocationSpec(
    private val flyway: Flyway,
    private val container: MySQLContainer,
) : FunSpec({

    // 표 목록은 root로 읽는다: runtime 계정의 information_schema는 권한 있는 표만 보여 줘, 권한 없는 표가 새어 들어와도 "없음"으로 보인다(재검토 MINOR-1).
    fun tables(pattern: String): Set<String> = FinancialDbAccounts.root(container).use { c ->
        c.column("SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name LIKE ?", pattern).toSet()
    }

    test("context starts with only the main FINANCIAL locations, applying V1 (batch metadata), V2 (payment fact inbox), V3 (runtime privileges), V4..V15 (acquiring), V16 (acquisition view index — ACQ-VIEW-001)") {
        flyway.configuration.locations.map { it.descriptor } shouldBe
            listOf("classpath:db/migration/financial", "classpath:db/runtime-privilege/financial")
        flyway.info().applied().map { it.version.version } shouldBe listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20")
        flyway.info().applied().all { it.state.isApplied && !it.state.isFailed } shouldBe true
    }

    test("D-ACQ-007: Spring Batch metadata tables exist in the FINANCIAL schema itself (main V1)") {
        tables("BATCH\\_%") shouldBe setOf(
            "BATCH_JOB_INSTANCE", "BATCH_JOB_EXECUTION", "BATCH_JOB_EXECUTION_PARAMS", "BATCH_STEP_EXECUTION", "BATCH_STEP_EXECUTION_CONTEXT",
            "BATCH_JOB_EXECUTION_CONTEXT", "BATCH_STEP_EXECUTION_SEQ", "BATCH_JOB_EXECUTION_SEQ", "BATCH_JOB_INSTANCE_SEQ",
        )
    }

    test("FIN-FACT-RELAY-001: payment fact inbox and projection tables exist (main V2) and no test-only table leaked into the main schema") {
        tables("payment\\_fact\\_%") shouldBe setOf(
            "payment_fact_inbox", "payment_fact_stream", "payment_fact_authorization", "payment_fact_cancellation", "payment_fact_hold",
            "payment_fact_hold_release",
        )
        tables("tech\\_smoke\\_%") shouldBe emptySet()
    }

    test("ACQUIRING-001: acquiring tables exist (main V5 — lane, sealed file, bytes, records, netting, exclusion; V7 — transmission attempt and outcome; V10 — responses, record results, cancel not required; V12 — re-acquisition approvals)") {
        tables("acquisition\\_%") shouldBe setOf(
            "acquisition_lane", "acquisition_file", "acquisition_file_content", "acquisition_file_record", "acquisition_netting", "acquisition_exclusion",
            "acquisition_transmission_attempt", "acquisition_transmission_outcome",
            // 2-F(V10): 응답 처리 기록·레코드 결과·취소 매입 불요, 2-G(V12): 재매입 승인 기록.
            "acquisition_response", "acquisition_record_result", "acquisition_cancel_not_required", "acquisition_reacquisition_approval",
            // rev 1.1(V14): 매입 확정 표지.
            "acquisition_accepted_record",
            // ACQ-ADMIN-001 B1(V17): 조작 결정 기록·재매입 불요 종결.
            "acquisition_ops_decision", "acquisition_reacquisition_not_required",
            // ACQ-ADMIN-001 B2(V19): 재매입 파일 전송 보류 근거.
            "acquisition_transmission_withhold",
        )
    }
})
