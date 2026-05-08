package com.niki914.s3ss10n

fun main() {
    println("=== LocalToolRegistry Smoke Test ===")

    // Test DSL
    val registry = LocalToolRegistryImpl()
    registry.add("toast") {
        description = "显示提示"
        string("message") {
            description = "消息内容"
            required = true
        }
        integer("duration") {
            description = "持续时间"
        }
    }

    val tools = registry.tools
    assertOrPrint("added 1 tool", tools.size == 1)
    val toast = tools["toast"]!!
    assertOrPrint("toast.description", toast.description == "显示提示")
    assertOrPrint("toast has 2 properties", toast.properties.size == 2)
    assertOrPrint("message property", toast.properties["message"]?.name == "message")
    assertOrPrint("message required", toast.properties["message"]?.required == true)
    assertOrPrint("message type", toast.properties["message"]?.type == ToolValueType.String)
    assertOrPrint("message desc", toast.properties["message"]?.description == "消息内容")
    assertOrPrint("duration type", toast.properties["duration"]?.type == ToolValueType.Integer)
    assertOrPrint("duration not required", toast.properties["duration"]?.required == false)

    // Test ToolDefinition generation
    val defs = registry.toToolDefinitions()
    assertOrPrint("1 definition", defs.size == 1)
    val def = defs[0]
    assertOrPrint("def name", def.function.name == "toast")
    assertOrPrint("required list", def.function.parameters.required == listOf("message"))
    assertOrPrint("properties count", def.function.parameters.properties.size == 2)

    // Test replace
    registry.replace("toast") {
        description = "新描述"
    }
    assertOrPrint("replaced description", registry.tools["toast"]?.description == "新描述")

    // Test remove
    registry.remove("toast")
    assertOrPrint("removed", registry.tools.isEmpty())

    // Test all property types
    registry.add("alltypes") {
        description = "test"
        string("s") {}
        integer("i") {}
        number("n") {}
        boolean("b") {}
        object_("o") {}
        array("a") {}
    }
    val allTypes = registry.tools["alltypes"]!!
    assertOrPrint("6 properties", allTypes.properties.size == 6)
    assertOrPrint("string jsonType", allTypes.properties["s"]?.type?.jsonType == "string")
    assertOrPrint("integer jsonType", allTypes.properties["i"]?.type?.jsonType == "integer")
    assertOrPrint("number jsonType", allTypes.properties["n"]?.type?.jsonType == "number")
    assertOrPrint("boolean jsonType", allTypes.properties["b"]?.type?.jsonType == "boolean")
    assertOrPrint("object jsonType", allTypes.properties["o"]?.type?.jsonType == "object")
    assertOrPrint("array jsonType", allTypes.properties["a"]?.type?.jsonType == "array")

    // rawJsonSchema
    registry.add("raw") {
        rawJsonSchema("""{"custom":true}""")
    }
    assertOrPrint("rawInputSchemaJson", registry.tools["raw"]?.rawInputSchemaJson == """{"custom":true}""")

    // LocalToolProperty enumValues
    val prop = LocalToolProperty("test", enumValues = listOf("a", "b", "c"))
    assertOrPrint("enumValues", prop.enumValues == listOf("a", "b", "c"))

    println("=== ALL PASSED ===")
}

fun assertOrPrint(name: String, condition: Boolean) {
    if (condition) println("  PASS: $name")
    else println("  FAIL: $name")
}
