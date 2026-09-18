package com.pawwork.android.chat

import android.content.Context
import com.pawwork.android.R
import org.json.JSONArray

/**
 * The official OpenCode CLI tool definitions (opencode 1.18.31, MIT), shipped
 * as a raw resource (res/raw/opencode_tools.json) so no string escaping issues.
 *
 * Since 2026-09-18 the opencode.ai Zen free tier refuses requests that do not
 * carry its own client's tool set ("OpenCode's free tier can only be used from
 * within OpenCode", HTTP 403 FreeTierError). Verified live against the gateway:
 * the gate checks the tool NAMES (bash/edit/glob/grep/read core five at least)
 * and accepts any schemas/descriptions — but we embed the CLI's real schemas so
 * the model sees correct definitions, and ToolRegistry maps their execution to
 * PawWork's on-device implementations where possible.
 */
object OpenCodeTools {

    private var cached: JSONArray? = null

    fun tools(context: Context): JSONArray {
        cached?.let { return it }
        val text = context.resources.openRawResource(R.raw.opencode_tools)
            .bufferedReader(Charsets.UTF_8).readText()
        return JSONArray(text).also { cached = it }
    }

    fun names(context: Context): Set<String> = buildSet {
        val arr = tools(context)
        for (i in 0 until arr.length()) {
            add(arr.getJSONObject(i).getJSONObject("function").getString("name"))
        }
    }
}