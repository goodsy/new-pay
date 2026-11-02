package com.paycore.buildlogic.frontend

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder

/**
 * D-ADMIN-UI-002(Q-ADMIN-UI-1 판정 A): `resolved`·`integrity`가 없는 lockfile 항목은 계속 거부하고, `inBundle: true` 항목만
 * 가장 가까운 부모 패키지 항목이 그 이름을 bundleDependencies(bundledDependencies)로 선언하고 registry `resolved` + sha512 `integrity`를 가질 때 허용한다
 * (묶인 파일은 부모 tarball 해시로 보호된다).
 */
class FrontendLockfileCheckSpec : FunSpec({

    val registry = FrontendLockfileCheckTask.REGISTRY
    val parentPath = "node_modules/@tailwindcss/oxide-wasm32-wasi"
    val childPath = "$parentPath/node_modules/@emnapi/core"

    fun registryEntry(name: String, version: String = "1.0.0") = mapOf(
        "version" to version,
        "resolved" to "${registry}$name/-/${name.substringAfterLast('/')}-$version.tgz",
        "integrity" to "sha512-AAAA",
    )

    val bundlingParent = registryEntry("@tailwindcss/oxide-wasm32-wasi", "4.3.3") + mapOf(
        "optional" to true,
        "bundleDependencies" to listOf("@emnapi/core", "tslib"),
    )
    val bundledChild = mapOf("version" to "1.11.1", "inBundle" to true, "optional" to true)

    fun violationsFor(extra: Map<String, Any?>): List<String> {
        val packageJson = mapOf(
            "dependencies" to mapOf("react" to "19.3.0"),
            "devDependencies" to emptyMap<String, Any?>(),
            "engines" to mapOf("node" to "24.22.1"),
        )
        val lock = mapOf(
            "lockfileVersion" to 3,
            "packages" to mapOf<String, Any?>(
                "" to mapOf("dependencies" to mapOf("react" to "19.3.0"), "devDependencies" to emptyMap<String, Any?>()),
                "node_modules/react" to registryEntry("react", "19.3.0"),
            ) + extra,
        )
        return FrontendLockfileCheckTask.violations(
            packageJson = packageJson,
            lock = lock,
            npmrcLines = FrontendLockfileCheckTask.NPMRC_ALLOWED.map { (k, v) -> "$k=$v" },
            policy = mapOf("before" to "2026-10-10T23:59:59Z"),
            nodeVersion = "24.22.1",
        )
    }

    test("a bundled entry is allowed when its parent declares it and has a registry resolved URL and a sha512 integrity") {
        violationsFor(mapOf(parentPath to bundlingParent, childPath to bundledChild)).shouldBeEmpty()
    }

    test("the bundledDependencies spelling is accepted too, also for an unscoped child") {
        val parent = bundlingParent - "bundleDependencies" + ("bundledDependencies" to listOf("tslib"))
        violationsFor(mapOf(parentPath to parent, "$parentPath/node_modules/tslib" to bundledChild)).shouldBeEmpty()
    }

    test("an entry without resolved and integrity and without inBundle is still rejected") {
        violationsFor(mapOf("node_modules/loose" to mapOf("version" to "1.0.0"))).shouldContainExactlyInAnyOrder(
            "node_modules/loose: resolved must start with $registry (was null)",
            "node_modules/loose: integrity must be sha512 (was null)",
        )
    }

    test("an inBundle entry whose parent entry is missing is rejected") {
        violationsFor(mapOf(childPath to bundledChild))
            .shouldContain("$childPath: inBundle entry needs a parent package entry $parentPath that bundles it (D-ADMIN-UI-002)")
    }

    test("an inBundle entry at the top level (no parent package) is rejected") {
        violationsFor(mapOf("node_modules/tslib" to bundledChild))
            .shouldContain("node_modules/tslib: inBundle entry needs a parent package entry that bundles it (D-ADMIN-UI-002)")
    }

    test("an inBundle entry whose parent does not declare it in bundleDependencies is rejected") {
        val parent = bundlingParent + ("bundleDependencies" to listOf("tslib"))
        violationsFor(mapOf(parentPath to parent, childPath to bundledChild))
            .shouldContain("$childPath: parent $parentPath does not declare @emnapi/core in bundleDependencies (D-ADMIN-UI-002)")
    }

    test("an inBundle entry whose parent lacks a sha512 integrity is rejected") {
        val parent = bundlingParent - "integrity"
        violationsFor(mapOf(parentPath to parent, childPath to bundledChild))
            .shouldContain("$childPath: parent $parentPath must have a registry resolved URL and a sha512 integrity (D-ADMIN-UI-002)")
    }

    test("an inBundle entry whose parent resolves outside the registry is rejected") {
        val parent = bundlingParent + ("resolved" to "https://evil.example/oxide-wasm32-wasi-4.3.3.tgz")
        violationsFor(mapOf(parentPath to parent, childPath to bundledChild))
            .shouldContain("$childPath: parent $parentPath must have a registry resolved URL and a sha512 integrity (D-ADMIN-UI-002)")
    }

    test("review n-2: an inBundle entry that carries its own resolved/integrity is rejected (npm never writes that — fail closed)") {
        val child = bundledChild + registryEntry("@emnapi/core", "1.11.1")
        violationsFor(mapOf(parentPath to bundlingParent, childPath to child))
            .shouldContain("$childPath: inBundle entry must not carry its own resolved or integrity (D-ADMIN-UI-002)")
    }

    test("review n-2: a boolean bundleDependencies (bundle everything) is not accepted as a declaration") {
        val parent = bundlingParent + ("bundleDependencies" to true)
        violationsFor(mapOf(parentPath to parent, childPath to bundledChild))
            .shouldContain("$childPath: parent $parentPath does not declare @emnapi/core in bundleDependencies (D-ADMIN-UI-002)")
    }

    test("an inBundle entry whose parent is itself only bundled (no own tarball hash) is rejected") {
        val nestedPath = "$childPath/node_modules/tslib"
        val child = bundledChild + ("bundleDependencies" to listOf("tslib"))
        violationsFor(mapOf(parentPath to bundlingParent, childPath to child, nestedPath to bundledChild))
            .shouldContain("$nestedPath: parent $childPath must have a registry resolved URL and a sha512 integrity (D-ADMIN-UI-002)")
    }
})
