package com.niki914.s3ss10n.json

import android.util.Log
import com.google.gson.Gson

// TODO(T7): replace try/catch with xTry
class GsonJsonCodec(private val gson: Gson = Gson()) : JsonCodec {
    override fun encode(value: Any?): String = gson.toJson(value)

    override fun <T : Any> decode(json: String, type: Class<T>): T? = try {
        gson.fromJson(json, type)
    } catch (t: Throwable) {
        Log.e("qwerqwer", "GsonJsonCodec.decode<${type.simpleName}> failed", t)
        null
    }

    override fun decodeMap(json: String): Map<String, Any?>? = try {
        @Suppress("UNCHECKED_CAST")
        gson.fromJson(json, Map::class.java) as? Map<String, Any?>
    } catch (t: Throwable) {
        Log.e("qwerqwer", "GsonJsonCodec.decodeMap failed", t)
        null
    }

    override fun decodeList(json: String): List<Any?>? = try {
        @Suppress("UNCHECKED_CAST")
        gson.fromJson(json, List::class.java) as? List<Any?>
    } catch (t: Throwable) {
        Log.e("qwerqwer", "GsonJsonCodec.decodeList failed", t)
        null
    }
}
