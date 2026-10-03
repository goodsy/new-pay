package com.paycore.admin.security

import com.paycore.admin.InternalAdminApplication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.security.core.userdetails.UserDetailsService
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream

/**
 * Boot의 기본 사용자(user + 생성 비밀번호)가 만들어지거나 로그에 출력되지 않음을 확인한다.
 * 인증 방식이 정해지기 전에 추측 가능한 기본 계정이 생기면 안 된다.
 *
 * 기동 로그를 잡기 위해 이 테스트가 직접 애플리케이션을 띄운다(@SpringBootTest 컨텍스트는 spec 생성 전에 기동되어 로그를 잡을 수 없다).
 */
class NoGeneratedDefaultUserSpec : FunSpec({

    test("no UserDetailsService bean and no 'Using generated security password' log at startup") {
        val original = System.out
        val captured = ByteArrayOutputStream()
        val tee = object : OutputStream() {
            override fun write(b: Int) {
                captured.write(b)
                original.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                captured.write(b, off, len)
                original.write(b, off, len)
            }
        }

        System.setOut(PrintStream(tee, true, Charsets.UTF_8))
        val context = try {
            SpringApplicationBuilder(InternalAdminApplication::class.java)
                // 기본값은 호출 stack에서 main class를 추정해 GradleWorkerMain이 된다.
                .main(InternalAdminApplication::class.java)
                .properties("server.port=0")
                .run()
        } finally {
            System.setOut(original)
        }

        context.use {
            val output = captured.toString(Charsets.UTF_8)
            // 캡처가 실제로 동작함을 먼저 확인한다(아니면 아래 부정 검사가 무의미하다).
            // 실패 시 로그 전체(생성 비밀번호 포함 가능)가 보고서에 복사되지 않도록 boolean으로만 검사한다.
            withClue("output capture must contain the startup log line") {
                output.contains("Started InternalAdminApplication") shouldBe true
            }
            withClue("startup log must not contain a generated security password line") {
                output.contains(GENERATED_PASSWORD_LOG) shouldBe false
            }
            it.getBeanNamesForType(UserDetailsService::class.java).toList().shouldBeEmpty()
        }
    }
}) {
    private companion object {
        const val GENERATED_PASSWORD_LOG = "Using generated security password"
    }
}
