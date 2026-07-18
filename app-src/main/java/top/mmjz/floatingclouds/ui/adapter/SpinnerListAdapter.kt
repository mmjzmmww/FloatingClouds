package top.mmjz.floatingclouds.ui.adapter

import android.view.ViewGroup
import android.widget.TextView
import top.mmjz.floatingclouds.adapter.AbsListAdapter
import top.mmjz.floatingclouds.adapter.CommonListAdapter
import top.mmjz.floatingclouds.util.ext.dp

class SpinnerListAdapter(spinnerDataList: ArrayList<Pair<Int, String>>) :
    CommonListAdapter<Pair<Int, String>, AbsListAdapter.ViewHolder>() {
    init {
        setData(spinnerDataList)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(TextView(parent.context).also {
            val p = 4.dp
            it.setPadding(p, p, p, p)
        })
    }

    override fun onBindViewHolder(vh: ViewHolder, position: Int, parent: ViewGroup) {
        val itemView = vh.itemView
        if (itemView is TextView) {
            itemView.text = getItem(position)?.second
        }
    }
}
