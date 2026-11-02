plugins {
    `kotlin-dsl`
}

// precompiled script plugin이 적용할 Gradle plugin 구현을 classpath에 둔다.
// 버전은 catalog [plugins]에서 읽어 plugin marker 좌표로 바꾼다(버전 literal을 여기 두지 않는다).
fun pluginMarker(plugin: Provider<PluginDependency>): Provider<String> =
    plugin.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version.requiredVersion}" }

dependencies {
    implementation(pluginMarker(libs.plugins.kotlin.jvm))
    implementation(pluginMarker(libs.plugins.kotlin.spring))
    implementation(pluginMarker(libs.plugins.kotlin.jpa))
    implementation(pluginMarker(libs.plugins.spring.boot))
    // paycore.frontend(ADMIN-BOOT-001): 고정 Node 배포판 내려받기·압축 해제만 이 plugin에 맡긴다(npm 실행은 격리 환경의 Exec task).
    implementation(pluginMarker(libs.plugins.node.gradle))
    // 공급망 검사 규칙 시험(D-ADMIN-UI-002 — FrontendLockfileCheckTask.violations는 순수 함수라 Gradle TestKit 없이 시험한다).
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.test {
    useJUnitPlatform()
}
