package com.paycore.buildlogic.frontend

import java.io.File

/**
 * npm·Node 도구를 실행할 때 쓰는 **격리 환경**(ADMIN-BOOT-001 S0 §17.7 실측 결과).
 *
 * - `npm_config_*`·`NPM_CONFIG_*` 환경변수는 프로젝트 `.npmrc`의 registry·ignore-scripts·engine-strict를 덮고, `npm ci`가 lockfile의
 *   `resolved` host를 그 registry로 바꿔 받는다. 그래서 Gradle 프로세스의 환경을 물려주지 않고 아래 값만 가진 새 환경으로 실행한다.
 * - `~/.npmrc`는 프로젝트가 정하지 않은 키(`strict-ssl`·`script-shell` 등)를 더한다 → `npm_config_userconfig`를 빈 파일로 고정한다.
 * - PATH는 고정 Node 배포판의 bin과 시스템 기본 경로만. HOME도 build 안 임시 디렉터리다.
 */
object FrontendNpmEnvironment {

    fun of(nodeBinDir: File, home: File, emptyUserConfig: File, npmCache: File): Map<String, String> = linkedMapOf(
        "PATH" to "${nodeBinDir.path}:/usr/bin:/bin",
        "HOME" to home.path,
        "npm_config_userconfig" to emptyUserConfig.path,
        "npm_config_cache" to npmCache.path,
        "LANG" to "C.UTF-8",
        "TZ" to "UTC",
        "CI" to "true",
    )
}
