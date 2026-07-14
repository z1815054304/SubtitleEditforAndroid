package com.subtitleedit.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.subtitleedit.R

data class LogSection(
    val title: String,
    val startedAt: String,
    val content: String,
    val lineCount: Int,
    var expanded: Boolean = false
)

class LogSectionAdapter(private val sections: List<LogSection>) :
    RecyclerView.Adapter<LogSectionAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_log_section, parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(sections[position])

    override fun getItemCount(): Int = sections.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val header: LinearLayout = view.findViewById(R.id.layoutSectionHeader)
        private val indicator: TextView = view.findViewById(R.id.tvSectionIndicator)
        private val title: TextView = view.findViewById(R.id.tvSectionTitle)
        private val info: TextView = view.findViewById(R.id.tvSectionInfo)
        private val content: TextView = view.findViewById(R.id.tvSectionContent)

        fun bind(section: LogSection) {
            title.text = section.title
            info.text = "${section.startedAt} · ${section.lineCount} 行"
            indicator.text = if (section.expanded) "▼" else "▶"
            content.visibility = if (section.expanded) View.VISIBLE else View.GONE
            if (section.expanded) content.text = section.content
            header.setOnClickListener {
                section.expanded = !section.expanded
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) notifyItemChanged(position)
            }
        }
    }
}
