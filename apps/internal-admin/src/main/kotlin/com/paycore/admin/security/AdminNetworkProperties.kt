package com.paycore.admin.security

import org.springframework.boot.context.properties.ConfigurationProperties

/** `paycore.admin.network.ip-allowlist`(필수 — 비거나 없으면 기동 실패, §4.8.1). 쉼표 구분 IP·CIDR. */
@ConfigurationProperties(prefix = "paycore.admin.network")
class AdminNetworkProperties(ipAllowlist: List<String>?) {
    val ipAllowlist: IpAllowlist = IpAllowlist.parse(ipAllowlist.orEmpty())
}
