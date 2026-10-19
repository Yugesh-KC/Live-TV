package com.livetv.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.livetv.app.databinding.ItemChannelBinding
import com.livetv.app.databinding.ItemHeaderBinding

class ChannelAdapter(
    private val onClick: (Channel) -> Unit,
    private val onFavorite: (Channel) -> Unit,
) : ListAdapter<ListItem, RecyclerView.ViewHolder>(Diff) {

    var playingUrl: String? = null
        set(value) {
            if (field == value) return
            val old = field
            field = value
            currentList.forEachIndexed { i, item ->
                if (item is ListItem.Row && (item.channel.url == old || item.channel.url == value)) {
                    notifyItemChanged(i, PAYLOAD_PLAYING)
                }
            }
        }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is ListItem.Header -> TYPE_HEADER
        is ListItem.Row -> TYPE_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(ItemHeaderBinding.inflate(inflater, parent, false))
        } else {
            RowVH(ItemChannelBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ListItem.Header -> (holder as HeaderVH).b.title.text = item.title
            is ListItem.Row -> (holder as RowVH).bind(item)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        val item = getItem(position)
        if (payloads.contains(PAYLOAD_PLAYING) && holder is RowVH && item is ListItem.Row) {
            holder.bindPlaying(item.channel)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    class HeaderVH(val b: ItemHeaderBinding) : RecyclerView.ViewHolder(b.root)

    inner class RowVH(private val b: ItemChannelBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: ListItem.Row) {
            val ch = item.channel
            b.name.text = ch.name
            b.subtitle.text = ch.subtitle
            b.logo.load(ch.logo) {
                crossfade(true)
                placeholder(R.drawable.ic_tv)
                error(R.drawable.ic_tv)
                fallback(R.drawable.ic_tv)
            }
            b.favorite.setImageResource(if (item.favorite) R.drawable.ic_star else R.drawable.ic_star_border)
            b.favorite.contentDescription = b.root.context.getString(
                if (item.favorite) R.string.remove_favorite else R.string.add_favorite
            )
            b.favorite.setOnClickListener { onFavorite(ch) }
            b.root.setOnClickListener { onClick(ch) }
            bindPlaying(ch)
        }

        fun bindPlaying(ch: Channel) {
            val playing = ch.url == playingUrl
            b.root.isActivated = playing
            b.playingIndicator.visibility = if (playing) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    private object Diff : DiffUtil.ItemCallback<ListItem>() {
        override fun areItemsTheSame(a: ListItem, b: ListItem) = when {
            a is ListItem.Header && b is ListItem.Header -> a.title.substringBefore('(') == b.title.substringBefore('(')
            a is ListItem.Row && b is ListItem.Row -> a.channel.url == b.channel.url && a.inFavoritesSection == b.inFavoritesSection
            else -> false
        }

        override fun areContentsTheSame(a: ListItem, b: ListItem) = a == b
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ROW = 1
        private const val PAYLOAD_PLAYING = "playing"
    }
}
