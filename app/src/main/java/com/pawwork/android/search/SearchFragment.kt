package com.pawwork.android.search

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.pawwork.android.R

class SearchFragment : Fragment(R.layout.fragment_search) {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val webView = view.findViewById<WebView>(R.id.webView)
        val searchInput = view.findViewById<EditText>(R.id.searchInput)
        val searchBtn = view.findViewById<ImageButton>(R.id.searchBtn)
        val statusText = view.findViewById<TextView>(R.id.statusText)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()

        val defaultUrl = "https://duckduckgo.com"
        webView.loadUrl(defaultUrl)
        statusText.text = "🌐 Web Search — powered by DuckDuckGo"

        searchBtn.setOnClickListener {
            val query = searchInput.text.toString().trim()
            if (query.isNotEmpty()) {
                webView.loadUrl("https://duckduckgo.com/?q=$query")
                searchInput.text.clear()
            }
        }
    }
}