package com.paycore.simulator.authentication.adapter.web

import com.paycore.simulator.authentication.application.provided.LookupAnswer
import com.paycore.simulator.authentication.application.provided.LookupAuthenticationResultUseCase
import com.paycore.simulator.authentication.application.provided.RegisterAuthenticationUseCase
import com.paycore.simulator.authentication.application.provided.SimulatorClientAuthenticator
import com.paycore.simulator.authentication.domain.RejectionReason
import com.paycore.simulator.authentication.domain.SimulatorRejection
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * PG 서버용 계약 API v1(AUTHN-001 §13.4): client별 Bearer 키(digest 대조 — AN-20), 엄격 JSON. 거절 code는 고정 분류만(입력값 echo 없음). 다른 client의 결과·
 * 모르는 참조는 404(존재 비노출). "첫 조회 지연" 시나리오는 TX가 끝난 뒤 응답 전에만 기다린다.
 */
@RestController
class SimulatorApiController(
    private val clients: SimulatorClientAuthenticator,
    private val registrations: RegisterAuthenticationUseCase,
    private val lookups: LookupAuthenticationResultUseCase,
) {

    @PostMapping("/sim/v1/authentications")
    fun register(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?, @RequestBody(required = false) body: ByteArray?): ResponseEntity<String> {
        val clientId = clientOf(authorization) ?: return json(HttpStatus.UNAUTHORIZED, SimulatorJson.error(RejectionReason.NOT_FOUND))
        return respond {
            val registration = registrations.register(clientId, SimulatorJson.parseRegistration(body ?: ByteArray(0)))
            json(if (registration.created) HttpStatus.CREATED else HttpStatus.OK, SimulatorJson.registration(registration))
        }
    }

    @GetMapping("/sim/v1/authentication-results/{resultRef}")
    fun lookup(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?, @PathVariable resultRef: String): ResponseEntity<String> {
        val clientId = clientOf(authorization) ?: return json(HttpStatus.UNAUTHORIZED, SimulatorJson.error(RejectionReason.NOT_FOUND))
        return respond {
            when (val answer = lookups.lookup(clientId, resultRef)) {
                LookupAnswer.ServerError -> json(HttpStatus.SERVICE_UNAVAILABLE, "{\"code\":\"TEMPORARILY_UNAVAILABLE\"}")
                is LookupAnswer.Result -> {
                    if (!answer.delay.isZero) Thread.sleep(answer.delay.toMillis())
                    json(HttpStatus.OK, SimulatorJson.lookup(answer.result))
                }
            }
        }
    }

    private fun clientOf(authorization: String?): String? =
        authorization?.takeIf { it.startsWith(BEARER) }?.removePrefix(BEARER)?.let { clients.authenticate(it) }

    private fun respond(block: () -> ResponseEntity<String>): ResponseEntity<String> = try {
        block()
    } catch (e: SimulatorRejection) {
        json(statusOf(e.reason), SimulatorJson.error(e.reason))
    }

    private fun statusOf(reason: RejectionReason): HttpStatus = when (reason) {
        RejectionReason.INVALID_REQUEST, RejectionReason.ENVIRONMENT_NOT_SUPPORTED, RejectionReason.RETURN_URL_NOT_ALLOWED, RejectionReason.UNKNOWN_CARD ->
            HttpStatus.BAD_REQUEST
        RejectionReason.STATE_REUSED_WITH_DIFFERENT_REQUEST, RejectionReason.ALREADY_COMPLETED -> HttpStatus.CONFLICT
        RejectionReason.NOT_FOUND -> HttpStatus.NOT_FOUND
    }

    private fun json(status: HttpStatus, body: String): ResponseEntity<String> =
        ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CACHE_CONTROL, "no-store").body(body)

    private companion object {
        const val BEARER = "Bearer "
    }
}
