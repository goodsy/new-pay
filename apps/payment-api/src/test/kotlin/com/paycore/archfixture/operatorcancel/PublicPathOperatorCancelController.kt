package com.paycore.archfixture.operatorcancel

/** ArchUnit 위반 fixture(§4.8 adminEntryMappedUnderAdminPath): 진입 컨트롤러가 공개 경로(/v1 아래)에 매핑돼 있다. */
@org.springframework.web.bind.annotation.RestController
@org.springframework.web.bind.annotation.RequestMapping("/v1/operator-cancellations")
class PublicPathOperatorCancelController
