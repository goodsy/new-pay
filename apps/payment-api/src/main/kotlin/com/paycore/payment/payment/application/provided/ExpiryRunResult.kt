package com.paycore.payment.payment.application.provided

/**
 * 한 회차 결과(지표 `paycore.payment.checkout_expiry{result}`): 이번 회차가 만료한 수, 잠금 재판정에서 변경 없음, 그 건만 rollback된 저장소 일시 실패(교착 재시도 소진·잠금
 * timeout·연결·commit 불명확 — 다음 회차에 수렴), 그 건만 rollback된 그 밖의 예외([defects] — 결함 후보, ERROR 로그·경보 대상이며 다음 회차에도 반복될 수 있다, 검토 #4 n-3).
 */
data class ExpiryRunResult(val expired: Int, val noChange: Int, val failed: Int, val defects: Int)
