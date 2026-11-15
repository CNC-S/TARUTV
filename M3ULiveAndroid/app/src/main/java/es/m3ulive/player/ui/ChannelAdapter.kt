package es.m3ulive.player.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import es.m3ulive.player.model.Channel

class ChannelAdapter(
    private val isFavorite: (Channel) -> Boolean,
    private val onChannelClick: (Channel) -> Unit,
    private val onFavoriteClick: (Channel) -> Unit,
    private val loadLogo: (String, ImageView) -> Unit,
    private val dp: (Int) -> Int
) : RecyclerView.Adapter<ChannelAdapter.ChannelHolder>() {

    private var channels: List<Channel> = emptyList()

    init {
        setHasStableIds(true)
    }

    fun submitChannels(items: List<Channel>) {
        channels = items
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long = channels[position].id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelHolder {
        val context = parent.context
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = roundedBackground(Color.rgb(21, 31, 44), dp(12))
            isFocusable = true
            isClickable = true
            foreground = selectableForeground()
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(76)
            ).apply {
                bottomMargin = dp(6)
            }
        }

        val logoFrame = FrameLayout(context).apply {
            background = roundedBackground(Color.rgb(38, 51, 66), dp(9))
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(54))
        }
        val logo = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            contentDescription = "Logotipo del canal"
        }
        val logoPlaceholder = TextView(context).apply {
            text = "TV"
            textSize = 13f
            setTextColor(Color.rgb(123, 148, 169))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        logoFrame.addView(logoPlaceholder)
        logoFrame.addView(logo)

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        val title = TextView(context).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val group = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.rgb(150, 167, 184))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        }
        textColumn.addView(title)
        textColumn.addView(group)

        val favorite = TextView(context).apply {
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(255, 207, 92))
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(48))
            background = selectableForeground()
            contentDescription = "Marcar como favorito"
        }

        row.addView(logoFrame)
        row.addView(textColumn)
        row.addView(favorite)
        return ChannelHolder(row, logo, logoPlaceholder, title, group, favorite)
    }

    override fun onBindViewHolder(holder: ChannelHolder, position: Int) {
        val channel = channels[position]
        holder.title.text = channel.name
        holder.group.text = channel.group
        holder.favorite.text = if (isFavorite(channel)) "★" else "☆"
        holder.favorite.contentDescription = if (isFavorite(channel)) {
            "Quitar de favoritos"
        } else {
            "Añadir a favoritos"
        }
        holder.logoPlaceholder.visibility = View.VISIBLE
        holder.logo.setImageDrawable(null)
        holder.logo.tag = channel.logoUrl
        if (channel.logoUrl.isNotBlank()) loadLogo(channel.logoUrl, holder.logo)
        holder.itemView.setOnClickListener { onChannelClick(channel) }
        holder.favorite.setOnClickListener { onFavoriteClick(channel) }
    }

    override fun getItemCount(): Int = channels.size

    class ChannelHolder(
        itemView: View,
        val logo: ImageView,
        val logoPlaceholder: TextView,
        val title: TextView,
        val group: TextView,
        val favorite: TextView
    ) : RecyclerView.ViewHolder(itemView)

    private fun roundedBackground(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }

    private fun selectableForeground(): android.graphics.drawable.Drawable =
        RippleDrawable(ColorStateList.valueOf(Color.argb(55, 255, 255, 255)), null, null)
}
