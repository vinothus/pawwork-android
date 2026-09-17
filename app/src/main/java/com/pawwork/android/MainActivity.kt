package com.pawwork.android

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.pawwork.android.chat.ChatFragment
import com.pawwork.android.docs.DocGenerator
import com.pawwork.android.docs.DocsFragment
import com.pawwork.android.search.SearchFragment
import com.pawwork.android.files.FilesFragment
import com.pawwork.android.automations.AutomationsFragment
import java.io.File

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        // Optional deep-start: adb shell am start -n com.pawwork.android/.MainActivity --es tab docs
        val initialTab = intent.getStringExtra("tab")
        val action = intent.getStringExtra("action")
        if (action == "generate_docx") {
            Thread {
                try {
                    val dir = File(filesDir, "documents").apply { mkdirs() }
                    DocGenerator.generateDocx(dir)
                } catch (_: Exception) {}
            }.start()
        }
        val initialFragment: Fragment = when (initialTab) {
            "docs" -> DocsFragment()
            "files" -> FilesFragment()
            "tasks" -> AutomationsFragment()
            "search" -> SearchFragment()
            else -> ChatFragment()
        }
        if (savedInstanceState == null) loadFragment(initialFragment)
        bottomNav.setOnItemSelectedListener { item ->
            val fragment: Fragment = when (item.itemId) {
                R.id.nav_chat -> ChatFragment()
                R.id.nav_docs -> DocsFragment()
                R.id.nav_search -> SearchFragment()
                R.id.nav_files -> FilesFragment()
                R.id.nav_auto -> AutomationsFragment()
                else -> ChatFragment()
            }
            loadFragment(fragment)
            true
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }
}