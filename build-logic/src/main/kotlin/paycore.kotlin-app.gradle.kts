// PayCore 실행 단위(apps/*) 공통 설정. 각 app build 파일에는 그 app의 의존성과 고유 설정만 둔다.
plugins {
    id("paycore.kotlin-base")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.jetbrains.kotlin.plugin.jpa")
    id("org.springframework.boot")
}

val catalog = the<VersionCatalogsExtension>().named("libs")

// JPA Entity는 Hibernate 프록시를 위해 open이어야 한다. 순수 Domain 모델에는 JPA annotation이 없으므로 영향이 없다
// (Entity는 adapter.persistence에만 둔다 — PayCoreArchitectureRules가 검사). DB가 없는 app에는 해당 클래스가 없어 무효과다.
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

dependencies {
    // Mockito 제외는 paycore.kotlin-base의 테스트 classpath exclude가 담당한다.
    "testImplementation"(catalog.findLibrary("spring-boot-starter-test").get())
}
