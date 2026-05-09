package com.niki914.s3ss10n.smoketest

import com.niki914.s3ss10n.McpRegistryImpl
import com.niki914.s3ss10n.McpServerConfig
import com.niki914.s3ss10n.McpTransport

fun main5() {
    println("=== McpTypes Smoke Test ===")

    // McpTransport
    val http = McpTransport.Http(url = "http://127.0.0.1:51338/mcp")
    assertOrPrint("Http.url", http.url == "http://127.0.0.1:51338/mcp")
    assertOrPrint("Http is McpTransport", http is McpTransport)

    // McpServerConfig defaults
    val cfg = McpServerConfig()
    assertOrPrint("default enabled", cfg.enabled)
    assertOrPrint("default transport is Http", cfg.transport is McpTransport.Http)
    assertOrPrint("default headers empty", cfg.headers.isEmpty())

    // McpServerConfig DSL
    val cfg2 = McpServerConfig().apply {
        http {
            url = "http://example.com/mcp"
        }
    }
    val t = cfg2.transport as McpTransport.Http
    assertOrPrint("DSL url set", t.url == "http://example.com/mcp")

    // McpRegistry
    val registry = McpRegistryImpl()
    registry.add("aslocate") {
        http { url = "http://127.0.0.1:51338/mcp" }
    }

    val servers = registry.servers
    assertOrPrint("1 server added", servers.size == 1)
    val aslocate = servers["aslocate"]!!
    assertOrPrint("aslocate http url", (aslocate.transport as McpTransport.Http).url == "http://127.0.0.1:51338/mcp")

    // replace
    registry.replace("aslocate") {
        enabled = false
    }
    assertOrPrint("replaced enabled=false", registry.servers["aslocate"]?.enabled == false)

    // remove
    registry.remove("aslocate")
    assertOrPrint("removed", registry.servers.isEmpty())

    println("=== ALL PASSED ===")
}


