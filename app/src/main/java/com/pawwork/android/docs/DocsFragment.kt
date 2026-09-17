package com.pawwork.android.docs

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pawwork.android.R
import java.io.File

class DocsFragment : Fragment(R.layout.fragment_docs) {

    private lateinit var fileList: MutableList<File>
    private lateinit var adapter: DocListAdapter
    private var docsDir: File? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        docsDir = File(requireContext().filesDir, "documents").also { it.mkdirs() }
        fileList = docsDir!!.listFiles()?.sortedByDescending { it.lastModified() }?.toMutableList() ?: mutableListOf()

        val recycler = view.findViewById<RecyclerView>(R.id.docsRecycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = DocListAdapter(fileList) { openFile(it) }
        recycler.adapter = adapter

        view.findViewById<TextView>(R.id.btnGenDocx).setOnClickListener { generateFile("docx") }
        view.findViewById<TextView>(R.id.btnGenXlsx).setOnClickListener { generateFile("xlsx") }
        view.findViewById<TextView>(R.id.btnGenPptx).setOnClickListener { generateFile("pptx") }
        view.findViewById<TextView>(R.id.btnGenPdf).setOnClickListener { generateFile("pdf") }

        refreshList()
    }

    private fun generateFile(type: String) {
        val dir = docsDir!!
        val file = when (type) {
            "docx" -> DocGenerator.generateDocx(dir)
            "xlsx" -> DocGenerator.generateXlsx(dir)
            "pptx" -> DocGenerator.generatePptx(dir)
            "pdf" -> DocGenerator.generatePdf(dir)
            else -> return
        }
        Toast.makeText(requireContext(), "✅ Generated: ${file.name}", Toast.LENGTH_SHORT).show()
        refreshList()
    }

    private fun refreshList() {
        fileList.clear()
        fileList.addAll(docsDir!!.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList())
        adapter.notifyDataSetChanged()
        view?.findViewById<TextView>(R.id.emptyText)?.visibility =
            if (fileList.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openFile(file: File) {
        val intent = Intent(requireContext(), DocPreviewActivity::class.java)
        intent.putExtra("filePath", file.absolutePath)
        startActivity(intent)
    }
}

class DocListAdapter(
    private val files: List<File>,
    private val onClick: (File) -> Unit
) : RecyclerView.Adapter<DocListAdapter.VH>() {
    override fun getItemCount() = files.size
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_doc, parent, false)
        return VH(view)
    }
    override fun onBindViewHolder(holder: VH, pos: Int) {
        val file = files[pos]
        val ext = file.extension.uppercase()
        val icon = when (ext) {
            "DOCX" -> "📄"; "XLSX" -> "📊"; "PPTX" -> "📑"; "PDF" -> "📕"; else -> "📎"
        }
        holder.icon.text = "$icon $ext"
        holder.name.text = file.name
        holder.size.text = formatSize(file.length())
        holder.itemView.setOnClickListener { onClick(file) }
    }
    private fun formatSize(bytes: Long) = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1048576 -> "${bytes / 1024} KB"
        else -> "${"%.1f".format(bytes / 1048576.0)} MB"
    }
    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: TextView = view.findViewById(R.id.fileIcon)
        val name: TextView = view.findViewById(R.id.fileName)
        val size: TextView = view.findViewById(R.id.fileSize)
    }
}