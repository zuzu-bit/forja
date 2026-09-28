package com.forja.app.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.Modifier
import java.lang.reflect.Method

/**
 * Un @Composable `private` de top-level, apelat prin reflecție: capturăm bucăți de ecran care trăiesc în fișierele
 * altor pachete (DayCard din NutritionScreen.kt, ResultStep din NutritionOnboarding.kt) fără să le schimbăm
 * vizibilitatea sau comportamentul.
 *
 * Compilatorul Compose emite funcția ca `static void Nume[-hash](<parametri>, Composer, int $changed…[, int $default…])`
 * (sufixul `-hash` apare când un parametru e clasă-valoare, ex. Dp → float). Trimitem 0 pentru toate măștile:
 * „nesigur” la $changed (Compose compară singur parametrii) și „toate date” la $default.
 *
 * Testul dă doar parametrii de date (prefixul); cei de la coadă care sunt callback-uri (`() -> Unit`, `(T) -> Unit`…)
 * sau `Modifier` se completează singuri cu valori neutre — așa un callback nou adăugat de alt pachet (ex. `onVoice`)
 * nu strică testul. Dacă prefixul nu se mai potrivește, [find] întoarce null și testul se sare cu un mesaj clar.
 */
class PrivateComposable private constructor(private val method: Method, private val prefix: Int) {

    private val types: Array<Class<*>> = method.parameterTypes
    private val composerAt: Int = types.indexOf(Composer::class.java)

    @Composable
    fun Render(vararg args: Any?) {
        check(args.size == prefix) { "${method.name}: aștept $prefix argumente de date, am primit ${args.size}" }
        val tail = Array(composerAt - prefix) { neutral(types[prefix + it]) }
        val masks = Array<Any?>(types.size - composerAt - 1) { 0 }
        val composer: Composer = currentComposer
        method.invoke(null, *args, *tail, composer, *masks)
    }

    companion object {
        private val INT: Class<*> = Int::class.javaPrimitiveType!!
        private val noop0: () -> Unit = {}
        private val noop1: (Any?) -> Unit = {}
        private val noop2: (Any?, Any?) -> Unit = { _, _ -> }

        private fun neutral(type: Class<*>): Any? = when (type) {
            Function0::class.java -> noop0
            Function1::class.java -> noop1
            Function2::class.java -> noop2
            Modifier::class.java -> Modifier
            else -> error("parametru fără valoare neutră: ${type.name}")
        }

        private fun fillable(type: Class<*>) =
            type == Function0::class.java || type == Function1::class.java || type == Function2::class.java || type == Modifier::class.java

        /** `fileFacade` = clasa fișierului (ex. „…NutritionScreenKt”); `prefix` = tipurile JVM ale parametrilor de date, în ordine. */
        fun find(fileFacade: String, name: String, vararg prefix: Class<*>): PrivateComposable? {
            val cls = try {
                Class.forName(fileFacade)
            } catch (_: ClassNotFoundException) {
                return null
            }
            val method = cls.declaredMethods.firstOrNull { m ->
                val t = m.parameterTypes
                val c = t.indexOf(Composer::class.java)
                (m.name == name || m.name.startsWith("$name-")) &&
                    c >= prefix.size && c + 1 < t.size &&
                    prefix.indices.all { t[it] == prefix[it] } &&
                    (prefix.size until c).all { fillable(t[it]) } &&
                    (c + 1 until t.size).all { t[it] == INT }
            } ?: return null
            method.isAccessible = true
            return PrivateComposable(method, prefix.size)
        }
    }
}
