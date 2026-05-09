package com.niki914.s3ss10n.smoketest

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.PrintStream

fun main() {
    Log.e("SMOKE", "=== S3ss10n Smoke Test Results ===\n\n")

    val tests = listOf(
        "SessionEventTest" to { main1() },
        "ToolCallKindTest" to { main2() },
        "ToolCallRequestTest" to { main3() },
        "LocalToolRegistryTest" to { main4() },
        "McpTypesTest" to { main5() },
        "SessionConfigTest" to { main6() },
        "SessionImplTest" to { main7() },
        "IntegrationTest" to { main8() },
        "FullTextAccumulationTest" to { main9() },
    )

    var passed = 0
    var failed = 0

    for ((name, test) in tests) {
        val baos = ByteArrayOutputStream()
        val ps = PrintStream(baos, true, "UTF-8")
        val oldOut = System.out
        val start = System.currentTimeMillis()

        System.setOut(ps)
        try {
            test()
            passed++
        } catch (e: Exception) {
            failed++
            ps.println()
            ps.println("=== EXCEPTION ===")
            e.printStackTrace(ps)
        } finally {
            System.setOut(oldOut)
            ps.flush()
        }

        val elapsed = System.currentTimeMillis() - start
        val status = if (failed > passed) "FAIL" else "PASS" // heuristic
        val header = "--- $name ($elapsed ms) ---\n"

        Log.e("SMOKE", header + baos.toString("UTF-8") + "\n")
        Log.e("SMOKE", "  $name: ${elapsed}ms")
    }

    val summary = "\n=== Summary: $passed passed, $failed failed ===\n"
    Log.e("SMOKE", summary)
}
