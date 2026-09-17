package com.pawwork.android.automations

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.work.*
import com.pawwork.android.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class AutomationsFragment : Fragment(R.layout.fragment_automations) {
    private var taskCount = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val addBtn = view.findViewById<TextView>(R.id.btnAddTask)
        val listText = view.findViewById<TextView>(R.id.tasksList)
        val countText = view.findViewById<TextView>(R.id.taskCount)

        countText.text = "Active tasks: $taskCount"
        listText.text = "No automations yet.\n\nTap below to create a recurring PawWork task!"

        addBtn.setOnClickListener {
            taskCount++
            val name = "PawWork Task #$taskCount"
            val data = workDataOf("task_name" to name, "created_at" to System.currentTimeMillis())

            val request = PeriodicWorkRequestBuilder<PawWorkTaskWorker>(15, TimeUnit.MINUTES)
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()

            WorkManager.getInstance(requireContext())
                .enqueueUniquePeriodicWork(
                    "pawwork_task_$taskCount",
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )

            val time = SimpleDateFormat("HH:mm", Locale.US).format(Date())
            listText.text = "✅ $name\n   Created: $time\n   Frequency: Every 15 minutes\n   Status: Queued\n\n" +
                    listText.text
            countText.text = "Active tasks: $taskCount"
            Toast.makeText(requireContext(), "✅ $name scheduled!", Toast.LENGTH_SHORT).show()
        }
    }
}

class PawWorkTaskWorker(context: android.content.Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val name = inputData.getString("task_name") ?: "unknown"
        val prefs = applicationContext.getSharedPreferences("pawwork_automations", android.content.Context.MODE_PRIVATE)
        val count = prefs.getInt("run_count", 0) + 1
        prefs.edit().putInt("run_count", count).apply()
        return Result.success(workDataOf("runs" to count))
    }
}