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
}
