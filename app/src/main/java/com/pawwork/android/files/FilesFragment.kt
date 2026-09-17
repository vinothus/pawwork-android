package com.pawwork.android.files

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pawwork.android.R
import java.io.File

class FilesFragment : Fragment(R.layout.fragment_files) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val recycler = view.findViewById<RecyclerView>(R.id.filesRecycler)
        val emptyText = view.findViewById<TextView>(R.id.emptyFilesText)

        val allFiles = mutableListOf<File>()
        val appDocs = File(requireContext().filesDir, "documents")
        if (appDocs.exists()) allFiles.addAll(appDocs.listFiles()?.toList() ?: emptyList())
        val exportDir = requireContext().getExternalFilesDir(null) ?: requireContext().filesDir
        if (exportDir != appDocs && exportDir.exists()) {
            allFiles.addAll(exportDir.listFiles()?.filter { it.isFile } ?: emptyList())
        }

        allFiles.sortByDescending { it.lastModified() }

        if (allFiles.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            recycler.visibility = View.GONE
            return
        }

        emptyText.visibility = View.GONE
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = FileListAdapter(allFiles) { file ->
            Toast.makeText(requireContext(), "${file.name} (${formatSize(file.length())})", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatSize(bytes: Long) = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1048576 -> "${bytes / 1024} KB"
        else -> "${"%.1f".format(bytes / 1048576.0)} MB"
    }
}

class FileListAdapter(
    private val files: List<File>,
    private val onClick: (File) -> Unit
) : RecyclerView.Adapter<FileListAdapter.VH>() {
    override fun getItemCount() = files.size
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_doc, parent, false)
        return VH(view)
    }
    override fun onBindViewHolder(holder: VH, pos: Int) {
        val file = files[pos]
        holder.icon.text = "📁"
        holder.name.text = file.name
        holder.size.text = "${file.length()} bytes"
        holder.itemView.setOnClickListener { onClick(file) }
    }
    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: TextView = view.findViewById(R.id.fileIcon)
        val name: TextView = view.findViewById(R.id.fileName)
        val size: TextView = view.findViewById(R.id.fileSize)
    }
}