package com.forja.app.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import kotlin.math.roundToInt

/** Un aliment din baza de date verificată — AI-ul identifică, baza de date dă valorile. */
data class FoodProduct(
    val name: String,
    val brand: String?,
    val kcal100: Int,
    val protein100: Double,
    val carbs100: Double,
    val fat100: Double,
    val servingGrams: Int?,
    val barcode: String?
)

class OpenFoodFacts {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private fun parseProduct(p: JsonObject, barcode: String?): FoodProduct? {
        val nutr = p["nutriments"] as? JsonObject ?: return null
        fun num(vararg keys: String): Double? {
            for (k in keys) {
                val v = nutr[k] ?: continue
                val d = try { v.jsonPrimitive.doubleOrNull } catch (_: Exception) { null }
                if (d != null) return d
            }
            return null
        }
        val kcal = num("energy-kcal_100g")
            ?: num("energy_100g")?.let { it / 4.184 }
            ?: return null
        val name = (p["product_name_ro"]?.jsonPrimitive?.contentOrNull)
            ?.takeIf { it.isNotBlank() }
            ?: (p["product_name"]?.jsonPrimitive?.contentOrNull)?.takeIf { it.isNotBlank() }
            ?: return null
        val servingQty = p["serving_quantity"]?.jsonPrimitive?.doubleOrNull?.roundToInt()
        return FoodProduct(
            name = name,
            brand = p["brands"]?.jsonPrimitive?.contentOrNull?.split(',')?.firstOrNull()?.trim(),
            kcal100 = kcal.roundToInt(),
            protein100 = num("proteins_100g") ?: 0.0,
            carbs100 = num("carbohydrates_100g") ?: 0.0,
            fat100 = num("fat_100g") ?: 0.0,
            servingGrams = servingQty,
            barcode = barcode
        )
    }

    /** Cod de bare → valori exacte. */
    suspend fun byBarcode(code: String): FoodProduct? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://world.openfoodfacts.org/api/v2/product/$code.json?fields=product_name,product_name_ro,brands,nutriments,serving_quantity")
                .header("User-Agent", "FORJA Android - zuzu-bit/forja")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val root = json.parseToJsonElement(body).jsonObject
                val product = root["product"] as? JsonObject ?: return@withContext null
                parseProduct(product, code)
            }
        } catch (_: Exception) { null }
    }

    /** Căutare după nume (acoperire bună RO în OpenFoodFacts). */
    suspend fun search(query: String): List<FoodProduct> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val req = Request.Builder()
                .url("https://world.openfoodfacts.org/cgi/search.pl?search_terms=$q&search_simple=1&action=process&json=1&page_size=20&fields=product_name,product_name_ro,brands,nutriments,serving_quantity,code")
                .header("User-Agent", "FORJA Android - zuzu-bit/forja")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val body = resp.body?.string() ?: return@withContext emptyList()
                val root = json.parseToJsonElement(body).jsonObject
                val products = root["products"]?.jsonArray ?: return@withContext emptyList()
                products.mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    parseProduct(obj, obj["code"]?.jsonPrimitive?.contentOrNull)
                }
            }
        } catch (_: Exception) { emptyList() }
    }
}
