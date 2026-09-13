package com.landosol.toolbox.labyrinth

import android.content.Context

/** App-private source-scoped storage; never uses the Bilibili account tables. */
class AndroidImportedRouteTextStore(context: Context) : ImportedRouteTextStore {
    private val preferences = context.getSharedPreferences("manual_labyrinth_route", Context.MODE_PRIVATE)
    override fun read(): Pair<String, Int>? = preferences.getString("text", null)?.let {
        it to preferences.getInt("openingGuildId", 5)
    }
    override fun write(text: String, openingGuildId: Int) {
        check(preferences.edit().putString("text", text).putInt("openingGuildId", openingGuildId).commit())
    }
    override fun clear() { check(preferences.edit().clear().commit()) }
}
