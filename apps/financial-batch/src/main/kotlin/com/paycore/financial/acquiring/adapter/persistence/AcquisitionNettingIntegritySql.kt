package com.paycore.financial.acquiring.adapter.persistence

/**
 * 상계 무결성 검사 SQL 조각(대사 입력 무결성 — 0이어야 한다). [JpaAcquisitionMembershipStore](파일별 — 봉인·재매입 실행 TX 끝 검사)와
 * [JpaAcquisitionBacklogReader](전체 — 지표)가 같은 정의를 쓴다.
 *
 * - 세대 0(정기 봉인) SALE 레코드: 상계액 = 그 레코드를 가리키는 `acquisition_netting` 합(기존 정의 — ACQUIRING-001 §16 NIT). 재매입 레코드(세대 ≥ 1)는 상계 행이
 *   가리키지 않으므로 이 검사 대상이 아니다(ACQ-ADMIN-001 rev 0.3 리뷰 MJ-1 — 세대 구분 없이 세면 재매입 레코드마다 영구 +1).
 * - 세대 ≥ 1(재매입) SALE 레코드 r: 상계액 = 원승인액 − 순액(I-13) = 실행 시점까지 **판정이 끝난** 그 원승인의 취소 합(실행은 판정 안 된 취소가 있으면 거절 — I-12).
 *   판정 기록의 합과 같아야 한다(ACQ-ADMIN-001 I-17, rev 0.7 재정의 — §15 L-8):
 *   ① 원 파일 상계 `acquisition_netting`(파일 레코드를 가리키는 행 — 정기 봉인만 쓴다, 세대 0),
 *   ② "취소 매입 불요" `acquisition_cancel_not_required` 중 r보다 **낮은 세대** 레코드를 원 SALE로 가리키는 행(같은 세대·높은 세대 판정은 r 실행 뒤의 일).
 *   ③(rev 0.7) CANCEL 레코드는 r의 상계에 들어가지 않는다 — CANCEL 레코드는 그 원승인의 가장 높은 세대 SALE이 ACCEPTED일 때만 생기고(D-ACQ-009, CC-8), SALE 재매입은
 *   어느 세대에도 매입 확정 표지가 없을 때만 실행된다(D-ACQ-014). 그래서 r 실행 시점에 그 원승인의 CANCEL 레코드는 있을 수 없고, B2가 연 "새 세대 ACCEPTED 뒤 CANCEL"은
 *   언제나 r 실행 **뒤**의 판정이다(rev 0.6 정의 "세대 0 CANCEL 레코드 합"은 그 뒤 CANCEL을 r에 섞어 거짓 불일치를 냈다 — L-8). 실행 시점에 CANCEL 레코드가 있었다면
 *   (도달 불가 상태) r.상계액이 ①+②보다 커서 이 검사가 불일치로 드러낸다(조용히 맞추지 않는다).
 */
internal object AcquisitionNettingIntegritySql {

    /** 세대 0 SALE 레코드 중 상계액 ≠ 상계 기록 합. 별칭 `r` = acquisition_file_record, `n` = (file_id, seq, s) 집계. */
    const val GENERATION_ZERO_MISMATCH =
        "r.record_type = 'SALE' AND r.generation = 0 AND r.netted_cancel_amount_minor <> COALESCE(n.s, 0)"

    /** 세대 ≥ 1 SALE 레코드 중 상계액 ≠ 판정 끝난 취소 합(위 ①+②). 별칭 `r` = acquisition_file_record. */
    const val REACQUISITION_MISMATCH =
        "r.record_type = 'SALE' AND r.generation >= 1 AND r.netted_cancel_amount_minor <> " +
            "COALESCE((SELECT SUM(n.amount_minor) FROM acquisition_netting n WHERE n.original_transaction_id = r.transaction_id AND n.file_id IS NOT NULL), 0) + " +
            "COALESCE((SELECT SUM(x.amount_minor) FROM acquisition_cancel_not_required x JOIN acquisition_file_record o " +
            "ON o.file_id = x.original_file_id AND o.seq = x.original_seq WHERE x.original_transaction_id = r.transaction_id AND o.generation < r.generation), 0)"
}
