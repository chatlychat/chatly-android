package com.chatly.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import com.chatly.R
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Tiny background downloader for inline images, cached by URL so scrolling
 * doesn't re-download the same asset.
 *
 * It lived in [com.chatly.ui.adapters.MessageListAdapter]'s private
 * companion and moved here when the block renderer needed the same loader
 * for a card's image — a second copy is how two images in one thread come to
 * cache differently. Intentionally lightweight: a host that needs real
 * Glide/Coil integration renders the blocks itself.
 */
internal object ChatlyThumbnails {

    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val executor = Executors.newFixedThreadPool(2)

    /**
     * Fetch [url] into [target], or do nothing if it cannot be fetched.
     *
     * The URL is stashed as a tag so a holder recycled mid-download does not
     * get the previous row's picture.
     */
    fun load(target: ImageView, url: String) {
        val cached = cache[url]
        if (cached != null) {
            target.setImageBitmap(cached)
            return
        }
        target.setTag(R.id.message_body, url)
        executor.submit {
            val bitmap = runCatching {
                URL(url).openStream().use { BitmapFactory.decodeStream(it) }
            }.getOrNull() ?: return@submit
            cache[url] = bitmap
            target.post {
                if (target.getTag(R.id.message_body) == url) {
                    target.setImageBitmap(bitmap)
                }
            }
        }
    }
}
