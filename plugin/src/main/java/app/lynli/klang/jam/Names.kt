package app.lynli.klang.jam

import android.content.Context
import org.json.JSONObject
import java.security.SecureRandom

/** Nonhuman dictionaries from unique-names-generator (MIT). */
internal object Names {
    private val random = SecureRandom()
    private var colors = listOf("Silver")
    private var animals = listOf("Otter")
    fun load(context: Context) {
        val json = JSONObject(context.resources.openRawResource(R.raw.member_names).bufferedReader().use { it.readText() })
        fun list(key: String) = json.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) } }
        colors = list("colors"); animals = list("animals")
    }
    fun random(): String = (colors[random.nextInt(colors.size)] + " " + animals[random.nextInt(animals.size)] + " " + (10 + random.nextInt(90)))
        .split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }.take(60)
}
