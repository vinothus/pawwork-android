package com.pawwork.android.chat

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pawwork.android.R

class ChatAdapter(
    private val messages: List<ChatMessage>,
    private val onFileClick: ((String) -> Unit)? = null,
) : RecyclerView.Adapter<ChatAdapter.VH>() {

    /** Positions that were just inserted, so bind can play the entry animation once. */
    private val freshPositions = mutableSetOf<Int>()

    fun markInserted(pos: Int) {
        freshPositions += pos
        notifyItemInserted(pos)
    }

    fun entryAnimation(holder: VH, pos: Int) {
            if (freshPositions.remove(pos)) {
                holder.itemView.alpha = 0f
                holder.itemView.translationY = 28f
                holder.itemView.animate()
                    .alpha(1f).translationY(0f)
                    .setDuration(260)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f))
                    .start()
            }
        }

    override fun getItemCount() = messages.size

    override fun getItemViewType(pos: Int): Int = when (messages[pos].role) {
        "user" -> TYPE_USER
        "working" -> TYPE_WORKING
        "file" -> TYPE_FILE
        else -> TYPE_ASSISTANT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = when (viewType) {
            TYPE_USER -> R.layout.item_chat_user
            TYPE_WORKING -> R.layout.item_chat_working
            TYPE_FILE -> R.layout.item_chat_file
            else -> R.layout.item_chat_assistant
        }
        return VH(LayoutInflater.from(parent.context).inflate(layout, parent, false), viewType)
    }

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val msg = messages[pos]
        if (holder.viewType == TYPE_FILE) {
            // text IS the absolute path — render a pretty label but remember the path
            val path = msg.text
            val name = path.substringAfterLast('/')
            holder.text.text = if (java.io.File(path).exists()) "📄 $name — tap to open"
            else "❓ $name (missing)"
            // clicking a file row opens it with the Android system viewer
            holder.itemView.setOnClickListener {
                onFileClick?.invoke(messages[pos].text)
            }
            holder.itemView.alpha = 0.92f
        } else {
            holder.text.text = msg.text
        }
        if (holder.viewType == TYPE_WORKING) holder.startDots()
        entryAnimation(holder, pos)
    }

    override fun onViewRecycled(holder: VH) {
        holder.stopDots()
        super.onViewRecycled(holder)
    }

    class VH(view: View, val viewType: Int) : RecyclerView.ViewHolder(view) {
        val text: TextView =
            view.findViewById<TextView?>(R.id.workingLabel) ?: view.findViewById(R.id.messageText)
        private val dots = listOfNotNull(
            view.findViewById(R.id.dot1),
            view.findViewById(R.id.dot2),
            view.findViewById(R.id.dot3),
        )
        private var dotAnim: AnimatorSet? = null

        fun startDots() {
            if (dotAnim != null) return
            val anims = dots.mapIndexed { i, dot ->
                ObjectAnimator.ofFloat(dot, "alpha", 0.25f, 1f).apply {
                    duration = 380
                    startDelay = i * 140L
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                }
            }
            dotAnim = AnimatorSet().apply {
                playTogether(anims)
                start()
            }
        }

        fun stopDots() {
            dotAnim?.cancel()
            dotAnim = null
        }
    }

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_ASSISTANT = 1
        private const val TYPE_WORKING = 2
        private const val TYPE_FILE = 3
    }
}