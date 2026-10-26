package com.paycore.admin.security.session

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.session.config.SessionRepositoryCustomizer
import org.springframework.session.jdbc.JdbcIndexedSessionRepository
import org.springframework.session.web.http.CookieSerializer
import org.springframework.session.web.http.DefaultCookieSerializer

/**
 * 관리자 세션 저장소(Spring Session JDBC — D-ADMIN-004, §4.4.1, S0 §17.5·§17.6).
 * - 속성 표: Spring Session은 `%TABLE_NAME%_ATTRIBUTES`(대문자 접미)로 고정한다 → 권한 procedure 이름 규칙(소문자)에 맞춘 `admin_session_attributes`로
 *   속성 질의 5개를 교체한다(표 이름은 리터럴 — 다른 customizer가 table-name을 늦게 바꿔도 영향 없음, 마지막 순서로 적용).
 * - 값 변환: **문자열만** 직렬화·역직렬화([StringOnlySessionConversion]) — Java 직렬화(`ACED0005`) 경로가 없다. 손상 값은 문자열로만 읽혀 형식 검사에서 거부된다.
 * - 쿠키: `__Host-PCADMIN`(Secure·HttpOnly·SameSite=Strict·Path=/·Domain 없음).
 * 이 패키지 밖에서는 Spring Session 타입을 쓰지 않는다(ArchitectureSpec). DataSource·spring-jdbc 타입도 쓰지 않는다(§17.6).
 */
@Configuration(proxyBeanMethods = false)
class AdminSessionConfiguration {

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    fun adminSessionRepositoryCustomizer(): SessionRepositoryCustomizer<JdbcIndexedSessionRepository> = SessionRepositoryCustomizer { repository ->
        repository.setCreateSessionAttributeQuery(CREATE_SESSION_ATTRIBUTE)
        repository.setGetSessionQuery(GET_SESSION)
        repository.setUpdateSessionAttributeQuery(UPDATE_SESSION_ATTRIBUTE)
        repository.setDeleteSessionAttributeQuery(DELETE_SESSION_ATTRIBUTE)
        repository.setListSessionsByPrincipalNameQuery(LIST_SESSIONS_BY_PRINCIPAL)
        repository.setConversionService(StringOnlySessionConversion.conversionService())
    }

    @Bean
    fun adminSessionCookieSerializer(): CookieSerializer = DefaultCookieSerializer().apply {
        setCookieName(SESSION_COOKIE)
        setCookiePath("/")
        setUseSecureCookie(true)
        setUseHttpOnlyCookie(true)
        setSameSite("Strict")
    }

    companion object {
        const val SESSION_COOKIE = "__Host-PCADMIN"

        // Spring Session 4.1.1 JdbcIndexedSessionRepository 기본 질의(javap 확인)에서 속성 표 이름만 소문자 `admin_session_attributes`로 바꾼 것.
        const val CREATE_SESSION_ATTRIBUTE =
            "INSERT INTO admin_session_attributes (SESSION_PRIMARY_ID, ATTRIBUTE_NAME, ATTRIBUTE_BYTES) VALUES (?, ?, ?)"
        const val GET_SESSION =
            "SELECT S.PRIMARY_ID, S.SESSION_ID, S.CREATION_TIME, S.LAST_ACCESS_TIME, S.MAX_INACTIVE_INTERVAL, SA.ATTRIBUTE_NAME, SA.ATTRIBUTE_BYTES " +
                "FROM admin_session S LEFT JOIN admin_session_attributes SA ON S.PRIMARY_ID = SA.SESSION_PRIMARY_ID WHERE S.SESSION_ID = ?"
        const val UPDATE_SESSION_ATTRIBUTE =
            "UPDATE admin_session_attributes SET ATTRIBUTE_BYTES = ? WHERE SESSION_PRIMARY_ID = ? AND ATTRIBUTE_NAME = ?"
        const val DELETE_SESSION_ATTRIBUTE =
            "DELETE FROM admin_session_attributes WHERE SESSION_PRIMARY_ID = ? AND ATTRIBUTE_NAME = ?"
        const val LIST_SESSIONS_BY_PRINCIPAL =
            "SELECT S.PRIMARY_ID, S.SESSION_ID, S.CREATION_TIME, S.LAST_ACCESS_TIME, S.MAX_INACTIVE_INTERVAL, SA.ATTRIBUTE_NAME, SA.ATTRIBUTE_BYTES " +
                "FROM admin_session S LEFT JOIN admin_session_attributes SA ON S.PRIMARY_ID = SA.SESSION_PRIMARY_ID WHERE S.PRINCIPAL_NAME = ?"
    }
}
