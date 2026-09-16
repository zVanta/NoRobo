package com.example.robocallguard

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogFragment : Fragment(R.layout.fragment_log) {

    data class Item(
        val title: String,
        val subtitle: String,
        val detail: String,
        val call: CallRecord?,
        val sms: SmsRecord?
    )

    private lateinit var store: CallLogStore
    private lateinit var settings: SettingsStore
    private lateinit var rules: RuleStore
    private lateinit var adapter: LogAdapter
    private lateinit var emptyView: TextView
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        store = CallLogStore(ctx)
        settings = SettingsStore(ctx)
        rules = RuleStore(ctx)
        emptyView = view.findViewById(R.id.log_empty)

        adapter = LogAdapter(mutableListOf()) { onItemClick(it) }
        val rv = view.findViewById<RecyclerView>(R.id.log_list)
        rv.layoutManager = LinearLayoutManager(ctx)
        rv.adapter = adapter

        view.findViewById<MaterialButton>(R.id.btn_refresh)
            .setOnClickListener { refresh() }
        view.findViewById<MaterialButton>(R.id.btn_clear)
            .setOnClickListener {
                store.clearAll()
                refresh()
                toast("Log cleared")
            }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) refresh()
    }

    private fun refresh() {
        val items = mutableListOf<Item>()
        store.recent(100).forEach { r ->
            val extra = listOfNotNull(
                r.lineType,
                r.business,
                r.spamScore?.let { "spam ${"%.2f".format(it)}" },
                r.carrier
            ).joinToString(" | ")
            items.add(
                Item(
                    "${timeFmt.format(Date(r.timestamp))}  ${r.number}",
                    "${r.action} — ${r.reason}",
                    extra,
                    call = r,
                    sms = null
                )
            )
        }
        store.recentSms(50).forEach { s ->
            items.add(
                Item(
                    "${timeFmt.format(Date(s.ts))}  SMS ${s.sender}",
                    "flag: ${s.flag}",
                    s.body.take(120),
                    call = null,
                    sms = s
                )
            )
        }
        adapter.submit(items)
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onItemClick(item: Item) {
        val actions = if (item.call != null) {
            arrayOf("Report as spam", "Block this number", "Allow this number")
        } else {
            arrayOf("Report as spam", "Block this number", "Report SMS text (trains AI)")
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(item.title)
            .setItems(actions) { _, which -> handle(item, which) }
            .show()
    }

    private fun handle(item: Item, which: Int) {
        val number = item.call?.number ?: item.sms?.sender ?: return
        when (which) {
            0 -> reportSpam(number)
            1 -> {
                rules.blocklist = rules.blocklist + number
                toast("Blocked $number")
            }
            2 -> if (item.call != null) {
                rules.allowlist = rules.allowlist + number
                toast("Allowed $number")
            } else {
                reportSmsText(number, item.sms?.body.orEmpty())
            }
        }
    }

    private fun reportSpam(number: String) {
        if (settings.baseUrl.isBlank()) {
            toast("Set the backend URL first")
            return
        }
        Thread {
            val ok = LookupClient(settings.baseUrl, settings.token).report(number, "spam")
            activity?.runOnUiThread {
                toast(if (ok) "Reported — thanks" else "Report failed (check server)")
            }
        }.start()
    }

    private fun reportSmsText(sender: String, body: String) {
        if (settings.baseUrl.isBlank()) {
            toast("Set the backend URL first")
            return
        }
        Thread {
            val ok = LookupClient(settings.baseUrl, settings.token).reportSms(sender, body)
            activity?.runOnUiThread {
                toast(if (ok) "Reported — feeds the AI model" else "Report failed (check server)")
            }
        }.start()
    }

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
}

class LogAdapter(
    private val items: MutableList<LogFragment.Item>,
    private val onClick: (LogFragment.Item) -> Unit
) : RecyclerView.Adapter<LogAdapter.Holder>() {

    fun submit(values: List<LogFragment.Item>) {
        items.clear()
        items.addAll(values)
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.log_title)
        val subtitle: TextView = view.findViewById(R.id.log_subtitle)
        val detail: TextView = view.findViewById(R.id.log_detail)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
        val v = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log, parent, false)
        return Holder(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.subtitle.text = item.subtitle
        holder.detail.text = item.detail
        holder.detail.visibility = if (item.detail.isBlank()) View.GONE else View.VISIBLE
        holder.itemView.setOnClickListener { onClick(item) }
    }
}
