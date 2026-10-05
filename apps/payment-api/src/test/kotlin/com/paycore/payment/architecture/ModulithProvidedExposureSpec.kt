package com.paycore.payment.architecture

import com.paycore.testsupport.architecture.PayCoreArchitectureRules
import com.tngtech.archunit.core.importer.ClassFileImporter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.string.shouldContain
import org.springframework.modulith.core.Violations

/**
 * Modulith 공개 범위 관례(Coordinator 결정, 가역적): 각 모듈은 `application.provided` 패키지만 named interface `provided`로 공개한다.
 * 방식: 그 패키지의 `package-info.java`(`@org.springframework.modulith.NamedInterface("provided")`)를 `src/<sourceSet>/java`에 둔다
 * (fixture: src/test/java/com/paycore/modulithexposure/.../package-info.java). src/test/kotlin에 두면 javac가 컴파일하지 않아
 * named interface가 생기지 않는다(첫 실행에서 `<<UNNAMED>>`만 보인 실패로 확인).
 *
 * fixture(com.paycore.modulithexposure.<case>, 테스트 소스): 모듈 `order`, `billing`. billing이 order의 각 계층을 쓴다.
 * - provided: package-info 있음 → verify 통과
 * - providedwithoutni: provided와 동일하되 package-info 없음 → 실패 (통과 원인이 NamedInterface임을 보이는 대조군)
 * - domain / required / integration: order 내부 계층 사용 → 실패 (integration·web 캡슐화의 모듈 간 부분은 Modulith가 담당)
 * 테스트 소스 import 방식: [TestSourceApplicationModules]. ignored predicate는 운영과 같은 generatedCode()다.
 */
class ModulithProvidedExposureSpec : FunSpec({

    fun modules(case: String): TestSourceApplicationModules {
        val root = "$ROOT.$case"
        return TestSourceApplicationModules(root, PayCoreArchitectureRules(root).generatedCode())
    }

    test("billing -> order.application.provided (named interface 'provided') passes verification") {
        val modules = modules("provided")
        val order = modules.getModuleByName("order").orElseThrow()

        order.namedInterfaces.map { it.name } shouldContain "provided"
        modules.verify()
    }

    test("the package-info fixtures satisfy namedInterfacesOnlyOnProvidedPackages (ArchUnit and Modulith agree)") {
        // provided 케이스: order.application.provided의 package-info 하나만 @NamedInterface를 가진다.
        val root = "$ROOT.provided"
        PayCoreArchitectureRules(root).namedInterfacesOnlyOnProvidedPackages()
            .check(ClassFileImporter().importPackages(root))
    }

    test("control: same dependency without package-info NamedInterface fails verification") {
        val violations = shouldThrow<Violations> { modules("providedwithoutni").verify() }
        violations.message shouldContain "$ROOT.providedwithoutni.order.application.provided.PlaceOrder"
    }

    test("billing -> order.domain fails verification") {
        val violations = shouldThrow<Violations> { modules("domain").verify() }
        violations.message shouldContain "$ROOT.domain.order.domain.Order"
    }

    test("billing -> order.application.required fails verification") {
        val violations = shouldThrow<Violations> { modules("required").verify() }
        violations.message shouldContain "$ROOT.required.order.application.required.OrderStore"
    }

    test("billing -> order.adapter.integration fails verification (cross-module integration access)") {
        val violations = shouldThrow<Violations> { modules("integration").verify() }
        violations.message shouldContain "$ROOT.integration.order.adapter.integration.VanClient"
    }
}) {
    private companion object {
        const val ROOT = "com.paycore.modulithexposure"
    }
}
