package com.paycore.simulator.acquirer.application.required

/** UNIQUE 충돌(MySQL 1062) — 같은 파일의 동시 수신 또는 같은 거래의 동시 ACCEPTED. 호출자는 TX를 버리고 다음 회차에 다시 판정한다. */
class DuplicateAcquirerRecord : RuntimeException("a concurrent acquirer record was written first")
