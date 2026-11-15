package com.paycore.admin.merchantops.adapter.integration

import com.paycore.admin.merchantops.application.required.MasterCommandKind
import tools.jackson.databind.JsonNode

/**
 * MASTER 응답 허용 필드 모양(MERCHANT-OPS-001 §4 — fail-closed). 객체는 **정해진 키 집합과 정확히 같아야** 하고(알 수 없는 필드 = 거부: MASTER가 실수로 새 값을 내보내도 화면으로
 * 흘리지 않는다), 값은 형식 정규식·정수 범위·불린·목록 길이 상한으로 검사한다. 통과하면 Kotlin 기본 값(String·Long·Boolean·null·List·LinkedHashMap)으로 다시 만든다.
 * `institutionMerchantRef`·`detail`·등록번호 평문 필드는 어느 모양에도 없다.
 */
internal sealed interface MasterResponseShape {
    /** 검사·변환. 위반이면 [IllegalArgumentException]. */
    fun read(node: JsonNode?): Any?

    class Obj(private val fields: Map<String, MasterResponseShape>) : MasterResponseShape {
        override fun read(node: JsonNode?): Map<String, Any?> {
            require(node != null && node.isObject) { "object" }
            require(node.propertyNames().toSet() == fields.keys) { "fields" }
            return fields.entries.associateTo(LinkedHashMap()) { (name, shape) -> name to shape.read(node.get(name)) }
        }
    }

    class Arr(private val item: MasterResponseShape, private val max: Int) : MasterResponseShape {
        override fun read(node: JsonNode?): List<Any?> {
            require(node != null && node.isArray && node.size() <= max) { "array" }
            return node.iterator().asSequence().map { item.read(it) }.toList()
        }
    }

    class Text(private val format: Regex) : MasterResponseShape {
        override fun read(node: JsonNode?): String {
            require(node != null && node.isString) { "string" }
            return node.asString().also { require(format.matches(it)) { "format" } }
        }
    }

    class Whole(private val min: Long) : MasterResponseShape {
        override fun read(node: JsonNode?): Long {
            require(node != null && node.isIntegralNumber && node.canConvertToLong()) { "integer" }
            return node.asLong().also { require(it >= min) { "range" } }
        }
    }

    data object Flag : MasterResponseShape {
        override fun read(node: JsonNode?): Boolean {
            require(node != null && node.isBoolean) { "boolean" }
            return node.asBoolean()
        }
    }

    class Nullable(private val inner: MasterResponseShape) : MasterResponseShape {
        override fun read(node: JsonNode?): Any? {
            require(node != null) { "missing" }
            return if (node.isNull) null else inner.read(node)
        }
    }

    companion object {
        private const val ULID = "[0-9A-HJKMNP-TV-Z]{26}"
        private val ID = Text(Regex("^[a-z]{2,4}_$ULID$"))
        private val MERCHANT_ID = Text(Regex("^mer_$ULID$"))
        private val BUSINESS_ID = Text(Regex("^biz_$ULID$"))
        private val TIME = Text(Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z$"))
        private val CODE = Text(Regex("^[A-Za-z][A-Za-z0-9_]{0,47}$"))
        private val NAME = Text(Regex("^[^\\p{Cntrl}]{1,200}$"))
        private val MASKED = Text(Regex("^[0-9]{3}\\*{7}$"))
        private val CHANNEL = Text(Regex("^[a-z0-9._-]{1,253}$"))
        private val INSTITUTION_CODE = Text(Regex("^[A-Z0-9]{1,32}$"))
        private val CURRENCY = Text(Regex("^[A-Z]{3}$"))
        private val ACTOR = Text(Regex("^[A-Za-z0-9._:@-]{1,64}$"))

        /** MERCHANT-CODE-001 §3.1 가맹점 코드(MASTER `MerchantCode.FORMAT`과 같은 문법). */
        private val MERCHANT_CODE = Text(Regex("^[a-z][a-z0-9]{5,13}$"))
        private val COUNT = Whole(0)
        private const val MAX_ITEMS = 1_000

        private fun obj(vararg fields: Pair<String, MasterResponseShape>) = Obj(linkedMapOf(*fields))
        private fun list(item: MasterResponseShape) = Arr(item, MAX_ITEMS)
        private fun opt(inner: MasterResponseShape) = Nullable(inner)

        /** 목록·기존 검색 항목 9키 + `merchantCode`(MERCHANT-CODE-001 §3.8 — MASTER와 같은 배포 단위, C-22). */
        private val DIRECTORY_ITEM_FIELDS: Array<Pair<String, MasterResponseShape>> = arrayOf(
            "merchantId" to MERCHANT_ID, "merchantName" to NAME, "businessId" to BUSINESS_ID, "businessName" to NAME,
            "registrationNumberMasked" to MASKED, "lifecycle" to CODE, "entitlementRevision" to COUNT, "midCount" to COUNT, "createdAt" to TIME,
            "merchantCode" to MERCHANT_CODE,
        )

        /** 목록·기존 검색(10키). `matchedRetiredCode`가 오면 키 집합이 달라 해석 불가(fail-closed — 코드 검색 의미가 목록에 섞이지 않는다). */
        val DIRECTORY: MasterResponseShape = obj(
            "items" to Arr(obj(*DIRECTORY_ITEM_FIELDS), max = 50),
            "nextCursor" to opt(MERCHANT_ID),
        )

        /**
         * MERCHANT-CODE-001 §3.8 코드 검색(11키 = 10키 + `matchedRetiredCode`, 0 또는 1개). ADMIN이 **보낸 기준**(본문에 merchantCode)으로 이 모양을 고른다 — 응답을 보고 고르지 않는다.
         * 사용처: MC-2 ADMIN 코드 검색(`HttpMasterOpsGateway.search` — 기준에 `merchantCode`가 있을 때만, MASTER 응답 golden으로 고정).
         */
        val DIRECTORY_BY_CODE: MasterResponseShape = obj(
            "items" to Arr(obj(*DIRECTORY_ITEM_FIELDS, "matchedRetiredCode" to Flag), max = 1),
            "nextCursor" to opt(MERCHANT_ID),
        )

        val HISTORY: MasterResponseShape = obj(
            "items" to Arr(
                obj(
                    "seq" to Whole(1), "occurredAt" to TIME, "operation" to CODE, "aggregateType" to CODE, "aggregateId" to ID, "changeType" to CODE,
                    "actorRef" to ACTOR,
                ),
                max = 50,
            ),
            "nextCursor" to opt(Whole(1)),
        )

        /**
         * MERCHANT-OPS-002 §3.3 명령 성공(저장된 판정). `reference`는 **명령 종류의 접두사**여야 한다(사업자 `biz_`·가맹점 `mer_`·Site `site_`·계약 `ctr_`·CSA `csa_` — 리뷰 n-3):
         * 다른 종류의 ID가 오면 다른 모양 위반과 같이 해석 불가(Unknown)로 둔다.
         */
        fun commandSucceeded(kind: MasterCommandKind): MasterResponseShape = COMMAND_SUCCEEDED.getValue(kind)

        private val COMMAND_SUCCEEDED: Map<MasterCommandKind, MasterResponseShape> = MasterCommandKind.entries.associateWith { kind ->
            val prefix = when (kind) {
                MasterCommandKind.REGISTER_BUSINESS -> "biz"
                MasterCommandKind.REGISTER_MERCHANT -> "mer"
                MasterCommandKind.REGISTER_MERCHANT_SITE -> "site"
                MasterCommandKind.REGISTER_CONTRACT -> "ctr"
                MasterCommandKind.ADD_SERVICE_AGREEMENT -> "csa"
                MasterCommandKind.REGISTER_CONTRACT_REVISION -> "ctr"
                MasterCommandKind.REGISTER_PAYMENT_METHOD_ACTIVATION -> "act"
                MasterCommandKind.REJECT_SITE_REVIEW -> "site"
            }
            // B2-1 Revision만 `ctr_…#<n>`(n = 1..2147483647 — Domain·DB에 상한이 없어 INT 범위, MERCHANT-OPS-002 C-30), B3-1 Site 심사 거절만 `site_…#<seq>`(결정 행 순번).
            // 다른 종류에 `#`가 오면 해석 불가.
            val suffix = when (kind) {
                MasterCommandKind.REGISTER_CONTRACT_REVISION -> "#$REVISION_NO"
                MasterCommandKind.REJECT_SITE_REVIEW -> "#$DECISION_SEQ"
                else -> ""
            }
            obj("outcome" to Text(Regex("^SUCCEEDED$")), "reference" to Text(Regex("^${prefix}_$ULID$suffix$")), "replayed" to Flag)
        }

        /** 1..2147483647(Int 범위)의 10진 표기 — 앞자리 0 없음. */
        private const val REVISION_NO =
            "([1-9][0-9]{0,8}|1[0-9]{9}|20[0-9]{8}|21[0-3][0-9]{7}|214[0-6][0-9]{6}|2147[0-3][0-9]{5}|21474[0-7][0-9]{4}|214748[0-2][0-9]{3}|2147483[0-5][0-9]{2}|21474836[0-3][0-9]|214748364[0-7])"

        /** Site 심사 결정 순번(MASTER 결정 행 seq — 1..10자리, 앞자리 0 없음 — B3-1). */
        private const val DECISION_SEQ = "[1-9][0-9]{0,9}"

        /** 명령 업무 거절 — **`reference` 키 없음**(C-12: 오면 키 집합이 달라 해석 불가). */
        val COMMAND_REJECTED: MasterResponseShape = obj("outcome" to Text(Regex("^REJECTED$")), "rejection" to Text(Regex("^[A-Z][A-Z0-9_]{0,47}$")), "replayed" to Flag)

        // ---------------------------------------------------------------- MERCHANT-OPS-002 B2-2 승인 명령(§9.4 — C-33: 실행·기한 확인은 §3.3 모양, 기한 전만 NOT_YET_DUE)

        private val REJECTION = Text(Regex("^[A-Z][A-Z0-9_]{0,47}$"))
        private val MOP1 = Text(Regex("^mop1:[0-9a-f]{64}$"))
        private val DECIDED_VIA = Text(Regex("^(EXECUTE|DEADLINE_CONFIRM)$"))
        private val CONTRACT_REVISION_REFERENCE = Text(Regex("^ctr_$ULID#$REVISION_NO$"))

        private val MERCHANT_ACCOUNT_REFERENCE = Text(Regex("^mid_$ULID$"))
        private val ACTIVATION_REFERENCE = Text(Regex("^act_$ULID$"))
        private val SITE_REVIEW_REFERENCE = Text(Regex("^site_$ULID#$DECISION_SEQ$"))

        /** B3-2 통화 활성화 기록 `cur_…`(§8.4 B3-2-5). */
        private val CURRENCY_REFERENCE = Text(Regex("^cur_$ULID$"))

        /** MC-1b 코드 변경 성공 reference = 가맹점 내부 키 `mer_…`(§3.7.2). */
        private val MERCHANT_REFERENCE = Text(Regex("^mer_$ULID$"))

        /**
         * operation code → 성공 reference 문법(계약 승인 `ctr_…#n` — C-30, 첫 MID `mid_…` — B2-3, B3-1 개통 승인 `act_…`·Site 심사 승인 `site_…#n` — minor-4).
         * 다른 문법이면 해석 불가(Unknown). **이 표의 키가 결정 조회 `operation` 허용 목록이다** — 목록 밖 operation은 해석 불가(fail-closed).
         */
        private val APPROVAL_REFERENCES: Map<String, MasterResponseShape> = mapOf(
            "ApproveContractRevision" to CONTRACT_REVISION_REFERENCE, "IssueFirstDefaultMerchantAccount" to MERCHANT_ACCOUNT_REFERENCE,
            "ApprovePaymentMethodActivation" to ACTIVATION_REFERENCE, "DecideSiteReview" to SITE_REVIEW_REFERENCE,
            // B3-2(§8.4 B3-2-5): 통화 활성화 → `cur_`.
            "EnableCurrency" to CURRENCY_REFERENCE,
            // MERCHANT-CODE-001 MC-1b(§3.7.2): 코드 변경 → 그 가맹점 `mer_…`.
            "ChangeMerchantCode" to MERCHANT_REFERENCE,
        )

        /** 결정 조회 응답 `operation` 허용 목록(minor-4). */
        val APPROVAL_OPERATIONS: Set<String> get() = APPROVAL_REFERENCES.keys

        fun approvalSucceeded(operationCode: String): MasterResponseShape =
            obj("outcome" to Text(Regex("^SUCCEEDED$")), "reference" to APPROVAL_REFERENCES.getValue(operationCode), "replayed" to Flag)

        /** 기한 확인 — MASTER 시각상 기한 전(저장 없음). */
        val NOT_YET_DUE: MasterResponseShape = obj("decision" to Text(Regex("^NOT_YET_DUE$")))

        /** 결정 조회 — 아직 커밋된 결정 없음(효과 없음 아님). */
        val DECISION_NONE: MasterResponseShape = obj("decision" to Text(Regex("^NONE$")))

        /** 결정 조회 DECIDED 성공(reference만 — operation의 문법)·거절(rejection만). 지문은 `mop1`, 경로 표식은 EXECUTE·DEADLINE_CONFIRM. 모르는 operation이면 해석 불가. */
        fun decisionSucceeded(operationCode: String): MasterResponseShape = obj(
            "decision" to Text(Regex("^DECIDED$")), "operation" to Text(Regex("^" + Regex.escape(operationCode) + "$")), "outcome" to Text(Regex("^SUCCEEDED$")),
            "reference" to APPROVAL_REFERENCES.getValue(operationCode), "approvalFingerprint" to MOP1, "decidedVia" to DECIDED_VIA,
        )
        val DECISION_REJECTED: MasterResponseShape = obj(
            "decision" to Text(Regex("^DECIDED$")), "operation" to Text(Regex("^(" + APPROVAL_REFERENCES.keys.joinToString("|") + ")$")), "outcome" to Text(Regex("^REJECTED$")),
            "rejection" to REJECTION, "approvalFingerprint" to MOP1, "decidedVia" to DECIDED_VIA,
        )

        val DETAIL: MasterResponseShape = obj(
            // MERCHANT-CODE-001 §3.8: 현재 코드.
            "merchant" to obj("merchantId" to MERCHANT_ID, "name" to NAME, "lifecycle" to CODE, "entitlementRevision" to COUNT, "createdAt" to TIME, "merchantCode" to MERCHANT_CODE),
            "business" to obj("businessId" to BUSINESS_ID, "name" to NAME, "registrationNumberMasked" to MASKED, "lifecycle" to CODE),
            "sites" to list(
                obj(
                    "merchantSiteId" to ID, "channelType" to CODE, "channelIdentifier" to CHANNEL, "lifecycle" to CODE,
                    "review" to opt(obj("decision" to CODE, "decidedAt" to TIME)),
                    // MERCHANT-OPS-002 B3-1(MAJOR-1): Site 등록자(`merchant_site.created_by` — 표시 + ADMIN TX1 등록자 403 근거, D-MOPS-009 ③).
                    "registeredBy" to ACTOR,
                ),
            ),
            "contracts" to list(
                obj(
                    "contractId" to ID, "createdAt" to TIME,
                    "revisions" to list(
                        obj(
                            "revisionNo" to Whole(1), "scopeKind" to CODE, "effectiveFrom" to TIME, "effectiveTo" to opt(TIME), "approvedAt" to opt(TIME),
                            "scopeSiteIds" to list(ID),
                            // MERCHANT-OPS-002 B2-2(D-MOPS-008 ③): Revision 등록자 — MASTER와 함께 바꾼다(필드 집합 정확 일치).
                            "registeredBy" to ACTOR,
                        ),
                    ),
                    "agreements" to list(obj("agreementId" to ID, "serviceKind" to CODE, "addedAt" to TIME)),
                ),
            ),
            "accounts" to list(
                obj(
                    "merchantAccountId" to ID, "lifecycle" to CODE, "isDefault" to Flag, "issuingContractId" to ID, "issuingAgreementId" to ID,
                    "issuingRevisionNo" to Whole(1), "issuedAt" to TIME,
                    "assignments" to list(
                        obj(
                            "assignmentId" to ID, "merchantSiteId" to ID, "role" to CODE, "status" to CODE, "effectiveFrom" to TIME, "effectiveTo" to opt(TIME),
                        ),
                    ),
                    // B3-1: 개통 등록자(`registeredBy` — 표시용).
                    "paymentMethods" to list(obj("activationId" to ID, "paymentMethod" to CODE, "status" to opt(CODE), "registeredAt" to TIME, "registeredBy" to ACTOR)),
                    // B3-1(C-43): Site 적용 확인 목록 — 근거 참조·사유 없음(MASTER SELECT 목록에 없다).
                    "siteConfirmations" to list(
                        obj(
                            "confirmationId" to ID, "merchantSiteId" to ID, "paymentMethod" to CODE, "activationId" to ID, "confirmedAt" to TIME, "revoked" to Flag,
                        ),
                    ),
                    "currencies" to list(
                        obj("enablementId" to ID, "currencyCode" to CURRENCY, "effectiveFrom" to TIME, "effectiveTo" to opt(TIME), "endedAt" to opt(TIME)),
                    ),
                    "cardRoutes" to list(
                        obj(
                            "activationId" to ID, "paymentMethod" to CODE, "acquirerCode" to INSTITUTION_CODE, "routeRef" to INSTITUTION_CODE,
                            "status" to CODE, "registeredAt" to TIME,
                        ),
                    ),
                ),
            ),
            "limits" to list(
                obj(
                    "policyId" to ID, "scopeKind" to CODE, "agreementId" to opt(ID), "merchantAccountId" to opt(ID), "currencyCode" to CURRENCY,
                    "perTxKind" to CODE, "perTxAmount" to opt(COUNT), "dailyKind" to CODE, "dailyAmount" to opt(COUNT), "effectiveFrom" to TIME,
                    "effectiveTo" to opt(TIME), "endedAt" to opt(TIME),
                ),
            ),
            "propagation" to opt(
                obj(
                    "latestRevision" to COUNT, "latestRevisionIssuedAt" to TIME, "latestAckRevision" to opt(COUNT), "latestAckAt" to opt(TIME),
                    "effectConfirmedAt" to opt(TIME), "effectBasis" to opt(CODE),
                ),
            ),
            // MERCHANT-CODE-001 §3.8: 코드 배정 이력(최대 2 — seq 1 등록·채우기, seq 2 유일한 변경; 승인 ID 없음)·변경 가능 여부(advisory).
            "merchantCodeHistory" to Arr(
                obj(
                    "code" to MERCHANT_CODE, "seq" to Whole(1), "source" to Text(Regex("^(REGISTRATION|BACKFILL|CHANGE)$")), "replacesCode" to opt(MERCHANT_CODE),
                    "assignedAt" to TIME, "assignedBy" to ACTOR, "reasonCode" to opt(CODE),
                ),
                max = 2,
            ),
            "merchantCodeChangeable" to Flag,
        )
    }
}
