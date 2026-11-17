package com.paycore.admin.merchantops.adapter.integration

import com.paycore.admin.merchantops.application.provided.BusinessFieldError
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

    /** 배열: 길이 [min]..[max]([min] 기본 0 — 기존 모양 불변. BIZ-3 리뷰 NIT-4: 400 fieldErrors는 1 이상). */
    class Arr(private val item: MasterResponseShape, private val max: Int, private val min: Int = 0) : MasterResponseShape {
        override fun read(node: JsonNode?): List<Any?> {
            require(node != null && node.isArray && node.size() in min..max) { "array" }
            return node.iterator().asSequence().map { item.read(it) }.toList()
        }
    }

    /**
     * 문자열: 형식 정규식 + (선택) code point 길이 1..[maxCodePoints](TXN-LIST-ENRICH-001 M-4 — 정규식 `{n}`은 UTF-16 단위라 보충 문자를 2로 센다, 길이는 따로 센다). 기본은 길이 검사 없음
     * (기존 모양 동작 불변).
     */
    class Text(private val format: Regex, private val maxCodePoints: Int? = null) : MasterResponseShape {
        override fun read(node: JsonNode?): String {
            require(node != null && node.isString) { "string" }
            return node.asString().also {
                require(format.matches(it)) { "format" }
                if (maxCodePoints != null) require(it.codePointCount(0, it.length) in 1..maxCodePoints) { "length" }
            }
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

    /**
     * BUSINESS-PROFILE-001(BIZ-3): 정해진 객체 모양 중 **하나와 정확히** 같아야 한다(사업자 이력 `changes` 항목 — 값 변경 `field·before·after`·행 변경 `field·ended·added`, 생성은
     * before·ended 없음). 각 후보는 [Obj](키 집합 정확 일치 + `field` 값 문법)라 둘 이상이 맞지 않는다. 하나도 맞지 않으면 위반.
     */
    class AnyOf(private val options: List<MasterResponseShape>) : MasterResponseShape {
        override fun read(node: JsonNode?): Any? {
            options.forEach { option -> runCatching { option.read(node) }.onSuccess { return it } }
            throw IllegalArgumentException("no matching shape")
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

        /**
         * TXN-LIST-ENRICH-001 M-4: 상호 = MASTER `DisplayName` 규칙과 같은 검사 — 1..100 **code point**, ISO 제어문자(U+0000..U+001F, U+007F..U+009F)만 금지(U+2028·U+2029 허용),
         * 짝 없는 서로게이트 금지(올바른 UTF-16), 앞뒤 공백 금지.
         */
        private val DISPLAY_NAME = Text(Regex("^(?! )[^\\u0000-\\u001F\\u007F-\\u009F\\uD800-\\uDFFF]+(?<! )$"), maxCodePoints = 100)
        private val MERCHANT_ACCOUNT_ID = Text(Regex("^mid_$ULID$"))

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

        /**
         * TXN-LIST-ENRICH-001 §4.2·§4.4 라벨 묶음: 항목 정확 3키(MID·상호·현재 코드), 50개 이하. 내부 키(`merchantId`·`businessId`)·사업자·lifecycle이 오면 키 집합이 달라 해석 불가.
         * 이 요청의 답인지(보낸 MID의 부분집합·중복 없음·오름차순)는 gateway가 따로 본다.
         */
        val MERCHANT_ACCOUNT_LABELS: MasterResponseShape = obj(
            "items" to Arr(obj("merchantAccountId" to MERCHANT_ACCOUNT_ID, "merchantName" to DISPLAY_NAME, "merchantCode" to MERCHANT_CODE), max = 50),
        )

        /**
         * MERCHANT-CODE-001 §5.6.3(MC-3b) 가맹점 라벨 묶음: 항목 정확 3키(merchantId `mer_` 형식 — ADMIN 대응용, 상호 = `DisplayName` 규칙(TXN-LIST-ENRICH-001 M-4), 코드 §3.1), 50개 이하.
         * 사업자 키·lifecycle·코드 이력이 오면 키 집합이 달라 해석 불가. 이 요청의 답인지(보낸 키의 부분집합·중복 없음·오름차순)는 gateway가 따로 본다.
         */
        val MERCHANT_LABELS: MasterResponseShape = obj(
            "items" to Arr(obj("merchantId" to MERCHANT_ID, "merchantName" to DISPLAY_NAME, "merchantCode" to MERCHANT_CODE), max = 50),
        )

        // ---------------------------------------------------------------- BUSINESS-PROFILE-001 BIZ-3(§6.2~§6.8 — 골든 business-profile-response-shapes-v1, ADMIN 바이트 동일 사본)

        /** 사업자 문자열(MASTER `BusinessTextRule`과 같은 문자 규칙 — 제어문자·짝 없는 서로게이트·앞뒤 공백 금지) + code point 상한. */
        private fun plain(maxCodePoints: Int) = Text(Regex("^(?! )[^\\u0000-\\u001F\\u007F-\\u009F\\uD800-\\uDFFF]+(?<! )$"), maxCodePoints)
        private fun oneOf(vararg values: String) = Text(Regex("^(" + values.joinToString("|") + ")$"))
        private val BUSINESS_TYPE = oneOf("INDIVIDUAL", "CORPORATION")
        private val REGISTRATION_STATUS = oneOf("DRAFT", "REGISTERED", "INACTIVE")

        /** 진위확인 상태 — 이번 범위는 `NOT_CHECKED`만(TBD-06, DB CHECK). 다른 값이 오면 해석 불가(검증 성공 위조 방지). */
        private val VERIFICATION_STATUS = oneOf("NOT_CHECKED")
        private val BUSINESS_LIFECYCLE = oneOf("ACTIVE", "TERMINATED")
        private val PERSON_NAME = plain(50)
        private val CATEGORY_NAME = plain(50)
        private val ADDRESS_LINE = plain(200)
        private val CHANGE_REASON = plain(200)

        /** 이력 마스킹 값(§9 — 상호·마스킹 이름·이메일·전화·주소·날짜·구분 등, 저장된 문자열 그대로). */
        private val HISTORY_VALUE = plain(254)
        private val DATE = Text(Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$"))
        private val PHONE = Text(Regex("^[0-9]{8,12}$"))
        private val EMAIL = Text(Regex("^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{3,189}$"))
        private const val HISTORY_PAGE = 20
        private const val ROWS = 20

        /** 중복 요약·번호 조회 항목(4키 — D-BIZ-003·D-BIZ-006 ③, 내부 키는 화면이 그리지 않는다). 원문 번호 키가 오면 키 집합이 달라 해석 불가. */
        private val BUSINESS_SUMMARY = obj(
            "businessId" to BUSINESS_ID, "businessName" to DISPLAY_NAME, "registrationNumberMasked" to MASKED, "registrationStatus" to REGISTRATION_STATUS,
        )

        /** 목록(§6.2 — 항목 10키, 50 이하, keyset `next`). 원문 번호·대표자 원문·연락처 없음. */
        val BUSINESS_LIST: MasterResponseShape = obj(
            "items" to Arr(
                obj(
                    "businessId" to BUSINESS_ID, "businessName" to DISPLAY_NAME, "registrationNumberMasked" to MASKED, "businessType" to opt(BUSINESS_TYPE),
                    "registrationStatus" to REGISTRATION_STATUS, "verificationStatus" to VERIFICATION_STATUS, "representativeNameMasked" to opt(PERSON_NAME),
                    "representativeCount" to COUNT, "merchantCount" to COUNT, "updatedAt" to TIME,
                ),
                max = 50,
            ),
            "next" to opt(BUSINESS_ID),
        )

        /** 번호 조회(§6.3 — 0/1, 찾음·못 찾음 같은 모양). */
        val BUSINESS_LOOKUP: MasterResponseShape = obj("items" to Arr(BUSINESS_SUMMARY, max = 1))

        /** 상세(§6.4 — 개인정보 원문, 사업자등록번호는 마스킹만 — D-BIZ-006 ②). 목록 상한 = MASTER 구조 상한(대표자 5·업태+종목 20·주소 2·담당자 5). */
        val BUSINESS_DETAIL: MasterResponseShape = obj(
            "business" to obj(
                "businessId" to BUSINESS_ID, "businessName" to DISPLAY_NAME, "registrationNumberMasked" to MASKED, "businessType" to opt(BUSINESS_TYPE),
                "registrationStatus" to REGISTRATION_STATUS, "verificationStatus" to VERIFICATION_STATUS, "lifecycle" to BUSINESS_LIFECYCLE, "version" to COUNT,
                "createdAt" to TIME, "updatedAt" to TIME, "merchantCount" to COUNT,
            ),
            "registration" to obj(
                "corporateRegistrationNumber" to opt(Text(Regex("^[0-9]{13}$"))), "openingDate" to opt(DATE), "countryCode" to oneOf("KR"),
                "englishName" to opt(Text(Regex("^(?! )[ -~]{1,100}(?<! )$"))), "phoneNumber" to opt(PHONE), "email" to opt(EMAIL),
            ),
            "representatives" to Arr(obj("name" to PERSON_NAME, "representativeType" to oneOf("SOLE", "JOINT", "EACH")), max = 5),
            "categories" to Arr(obj("categoryType" to oneOf("BUSINESS_TYPE", "BUSINESS_ITEM"), "name" to CATEGORY_NAME), max = 20),
            "addresses" to Arr(
                obj("addressType" to oneOf("REGISTERED", "ACTUAL"), "postalCode" to Text(Regex("^[0-9]{5}$")), "baseAddress" to ADDRESS_LINE, "detailAddress" to opt(ADDRESS_LINE)),
                max = 2,
            ),
            "contacts" to Arr(obj("name" to PERSON_NAME, "email" to EMAIL, "phoneNumber" to PHONE, "primary" to Flag), max = 5),
        )

        /** 이력 행 항목(§5.6·E-6·E-11 — 저장된 마스킹 값). 필드별 키 집합이 정해져 있다(주소는 순서 키 없음). */
        private val HISTORY_ROWS: Map<String, MasterResponseShape> = mapOf(
            "representatives" to obj("name" to HISTORY_VALUE, "type" to oneOf("SOLE", "JOINT", "EACH"), "order" to Whole(1)),
            "categories" to obj("type" to oneOf("BUSINESS_TYPE", "BUSINESS_ITEM"), "name" to HISTORY_VALUE, "order" to Whole(1)),
            "addresses" to obj(
                "type" to oneOf("REGISTERED", "ACTUAL"), "postalCode" to Text(Regex("^[0-9]{5}$")), "baseAddress" to HISTORY_VALUE, "detailAddress" to opt(HISTORY_VALUE),
            ),
            "contacts" to obj("name" to HISTORY_VALUE, "email" to HISTORY_VALUE, "phoneNumber" to HISTORY_VALUE, "primary" to Flag, "order" to Whole(1)),
        )
        private val HISTORY_VALUE_FIELDS = oneOf(
            "registrationStatus", "displayName", "businessType", "registration\\.corporateRegistrationNumber", "registration\\.openingDate", "registration\\.englishName",
            "registration\\.phoneNumber", "registration\\.email",
        )

        /** 이력 `changes` 항목: 값 변경(생성 = after만)·행 변경(생성 = added만). 그 밖 키·필드는 해석 불가. */
        private val HISTORY_CHANGE = AnyOf(
            listOf(
                obj("field" to HISTORY_VALUE_FIELDS, "after" to opt(HISTORY_VALUE)),
                obj("field" to HISTORY_VALUE_FIELDS, "before" to opt(HISTORY_VALUE), "after" to opt(HISTORY_VALUE)),
            ) + HISTORY_ROWS.flatMap { (field, row) ->
                listOf(
                    obj("field" to oneOf(field), "added" to Arr(row, ROWS)),
                    obj("field" to oneOf(field), "ended" to Arr(row, ROWS), "added" to Arr(row, ROWS)),
                )
            },
        )

        /** 이력(§6.5 — 쪽 20, `nextAfter` 순번). 옛 `BusinessRegistered` 행은 사유 null·changes 빈 목록. */
        val BUSINESS_HISTORY: MasterResponseShape = obj(
            "items" to Arr(
                obj(
                    "seq" to Whole(1), "changeType" to CODE, "actorRef" to ACTOR, "occurredAt" to TIME, "reason" to opt(CHANGE_REASON),
                    "changes" to Arr(HISTORY_CHANGE, max = 16),
                ),
                max = HISTORY_PAGE,
            ),
            "nextAfter" to opt(Whole(1)),
        )

        /** 사업자 명령 성공(첫 응답·재생 같은 모양 — reference = `biz_`, C-18). */
        val BUSINESS_COMMAND_SUCCEEDED: MasterResponseShape = obj("outcome" to oneOf("SUCCEEDED"), "reference" to BUSINESS_ID, "replayed" to Flag)

        /** 중복 거절만 `existing`(D-BIZ-003·C-14). 다른 거절은 [COMMAND_REJECTED](existing·reference 없음 — C-12). */
        val BUSINESS_DUPLICATE_REJECTED: MasterResponseShape = obj(
            "outcome" to oneOf("REJECTED"), "rejection" to oneOf("BUSINESS_REGISTRATION_NUMBER_DUPLICATE"), "replayed" to Flag, "existing" to BUSINESS_SUMMARY,
        )

        /** 400 값 규칙 위반(§4.6 — path 문법·code 집합 밖이면 해석 불가, 최대 50, 값 없음). */
        val BUSINESS_VALIDATION_FAILED: MasterResponseShape = obj(
            "error" to oneOf("BUSINESS_VALIDATION_FAILED"),
            "fieldErrors" to Arr(
                obj("path" to Text(BusinessFieldError.PATH), "code" to oneOf(*BusinessFieldError.CODES.toTypedArray())),
                max = BusinessFieldError.MAX,
                // BIZ-3 리뷰 NIT-4: 값 규칙 위반 400에 오류가 하나도 없으면 모양 위반(fail-closed — OUTCOME_UNKNOWN).
                min = 1,
            ),
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
