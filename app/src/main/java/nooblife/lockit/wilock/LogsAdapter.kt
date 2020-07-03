package nooblife.lockit.wilock

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class LogsAdapter(val context: Context, val logs: ArrayList<AppLog>) : RecyclerView.Adapter<LogsAdapter.ViewHolder>() {

    fun addLog(log: AppLog) {
        logs.add(log)
        notifyItemInserted(itemCount - 1)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var status: ImageView = itemView.findViewById(R.id.log_status_icon)
        var message: TextView = itemView.findViewById(R.id.log_message)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(LayoutInflater.from(context).inflate(R.layout.item_log, parent, false))
    }

    override fun getItemCount(): Int = logs.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val log = logs[position]
        holder.status.setImageResource(if (log.status == "success") R.drawable.check_circle else R.drawable.cancel)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            holder.status.imageTintList = ColorStateList.valueOf(
                Color.parseColor(
                    if (log.status == "success") "#66bb6a" else "#ef5350"
            )
            )
        }

        holder.message.text =
            if (log.status == "success")
                "${log.action[0].toUpperCase()}${log.action.substring(1)}ed Successfully"
            else
                "${log.action[0].toUpperCase()}${log.action.substring(1)} Failed"

    }
}