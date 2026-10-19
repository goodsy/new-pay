package com.paycore.simulator.van.application.required

/** 같은 namespace·같은 증거·같은 예약이 동시에 기록돼 UNIQUE에 걸렸다(또는 예약 확정 경합) — 호출자는 새 TX에서 다시 판정한다. */
class DuplicateVanRecord : RuntimeException("duplicate van record", null, false, false)
