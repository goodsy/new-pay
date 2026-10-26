package com.paycore.admin.account.application

import com.paycore.admin.account.application.provided.AdminRetryLaterException
import com.paycore.admin.account.application.required.PersistenceFailure

/**
 * Use Case 경계의 저장 경합 변환(§4.13): 1213·1205·version 충돌이 재시도 뒤에도 남으면 [AdminRetryLaterException](웹 409 `RETRY_LATER`).
 * 전체 rollback이 끝난 뒤에만 던진다 — 성공·결과 불명확으로 매핑하지 않는다. 그 밖의 저장 실패는 그대로(500).
 */
internal inline fun <T> retryLaterOnConflict(block: () -> T): T = try {
    block()
} catch (e: PersistenceFailure.Deadlock) {
    throw AdminRetryLaterException(e)
} catch (e: PersistenceFailure.LockTimeout) {
    throw AdminRetryLaterException(e)
} catch (e: PersistenceFailure.OptimisticConflict) {
    throw AdminRetryLaterException(e)
}
