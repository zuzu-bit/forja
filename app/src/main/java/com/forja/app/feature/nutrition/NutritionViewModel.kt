package com.forja.app.feature.nutrition

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.forja.app.ForjaApp
import com.forja.app.core.data.db.MealEntity
import com.forja.app.core.network.FoodProduct
import com.forja.app.core.util.Fmt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

val mealTypeNames = listOf("MIC DEJUN", "PRÂNZ", "CINĂ", "GUSTARE")

/** Produs găsit (cod de bare sau căutare) în curs de porționare. */
data class PendingProduct(
    val product: FoodProduct,
    val source: String            // „EXACT · COD DE BARE" sau „DIN BAZA DE DATE"
)

class NutritionViewModel(app: Application) : AndroidViewModel(app) {
    private val forja = app as ForjaApp
    private val dao = forja.db.mealDao()
    private val api = forja.foodApi

    val meals: StateFlow<List<MealEntity>> = dao.mealsForDay(Fmt.epochDay())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val kcalToday: StateFlow<Int> = dao.kcalForDay(Fmt.epochDay())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val kcalTarget: StateFlow<Int> = forja.prefs.kcalTarget
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 2250)

    private val _pending = MutableStateFlow<PendingProduct?>(null)
    val pending: StateFlow<PendingProduct?> = _pending.asStateFlow()

    private val _lookupBusy = MutableStateFlow(false)
    val lookupBusy: StateFlow<Boolean> = _lookupBusy.asStateFlow()

    private val _lookupError = MutableStateFlow<String?>(null)
    val lookupError: StateFlow<String?> = _lookupError.asStateFlow()

    private val _searchResults = MutableStateFlow<List<FoodProduct>>(emptyList())
    val searchResults: StateFlow<List<FoodProduct>> = _searchResults.asStateFlow()

    private val _searchBusy = MutableStateFlow(false)
    val searchBusy: StateFlow<Boolean> = _searchBusy.asStateFlow()

    private var searchJob: Job? = null

    /** Cod de bare scanat → valori exacte din OpenFoodFacts. */
    fun onBarcode(code: String) {
        if (_lookupBusy.value || _pending.value != null) return
        _lookupBusy.value = true
        _lookupError.value = null
        viewModelScope.launch {
            val product = api.byBarcode(code)
            _lookupBusy.value = false
            if (product == null) {
                _lookupError.value = "Produsul $code nu e în baza de date. Caută-l după nume."
            } else {
                _pending.value = PendingProduct(product, "EXACT · COD DE BARE")
            }
        }
    }

    fun search(query: String) {
        searchJob?.cancel()
        if (query.length < 3) {
            _searchResults.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(350)
            _searchBusy.value = true
            _searchResults.value = api.search(query)
            _searchBusy.value = false
        }
    }

    fun pickSearchResult(p: FoodProduct) {
        _pending.value = PendingProduct(p, "DIN BAZA DE DATE")
        _searchResults.value = emptyList()
    }

    fun dismissPending() { _pending.value = null }
    fun clearError() { _lookupError.value = null }

    /** Salvează cu porția aleasă + sincronizează în baza companiei. */
    fun confirmPending(mealType: Int, grams: Int) {
        val p = _pending.value ?: return
        val f = grams / 100.0
        viewModelScope.launch {
            val meal = MealEntity(
                epochDay = Fmt.epochDay(),
                mealType = mealType,
                name = p.product.name + (p.product.brand?.let { " · $it" } ?: ""),
                kcal = (p.product.kcal100 * f).roundToInt(),
                protein = (p.product.protein100 * f).roundToInt(),
                carbs = (p.product.carbs100 * f).roundToInt(),
                fat = (p.product.fat100 * f).roundToInt(),
                grams = grams,
                source = p.source,
                confidence = if (p.source.startsWith("EXACT")) "exactă" else "ridicată",
                at = System.currentTimeMillis(),
                barcode = p.product.barcode
            )
            val id = dao.insert(meal)
            com.forja.app.core.data.CloudSync.meal(forja.auth.currentUid, meal.copy(id = id))
            _pending.value = null
        }
    }

    /** Intrare manuală simplă — onestă: sursă MANUAL. */
    fun addManual(mealType: Int, name: String, kcal: Int, protein: Int, carbs: Int, fat: Int, grams: Int) {
        viewModelScope.launch {
            val meal = MealEntity(
                epochDay = Fmt.epochDay(), mealType = mealType, name = name,
                kcal = kcal, protein = protein, carbs = carbs, fat = fat, grams = grams,
                source = "MANUAL", confidence = "—", at = System.currentTimeMillis()
            )
            val id = dao.insert(meal)
            com.forja.app.core.data.CloudSync.meal(forja.auth.currentUid, meal.copy(id = id))
        }
    }

    fun deleteMeal(id: Long) {
        viewModelScope.launch {
            dao.delete(id)
            com.forja.app.core.data.CloudSync.deleteMeal(forja.auth.currentUid, id)
        }
    }
}
