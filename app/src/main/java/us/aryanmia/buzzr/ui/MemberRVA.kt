package us.aryanmia.buzzr.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.android.synthetic.main.list_members.view.*
import us.aryanmia.buzzr.R


class MemberRVA : RecyclerView.Adapter<MemberRVA.MemberViewHolder>() {

    private var memberList: MutableList<String> = mutableListOf()
    private var awakeList: MutableList<String> = mutableListOf()

    inner class MemberViewHolder(view: View): RecyclerView.ViewHolder(view) {
        val email: TextView = view.member_email
        val awake: TextView = view.member_awake
    }

    fun setItems(members: MutableList<String>, awake: MutableList<String>) {
        memberList = members
        awakeList = awake
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemberViewHolder {
        return MemberViewHolder(
            LayoutInflater.from(parent.context).inflate(
                R.layout.list_members,
                parent,
                false
            )
        )
    }

    override fun getItemCount(): Int = memberList.size

    override fun onBindViewHolder(holder: MemberViewHolder, position: Int) {
        val item = memberList[position]
        holder.email.text = item
        holder.awake.text = if (awakeList.contains(item)) "🏃" else "😴"
    }
}