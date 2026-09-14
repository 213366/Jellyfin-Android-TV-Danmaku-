package org.jellyfin.androidtv.ui.playback.overlay.action

import android.content.Context
import android.view.View
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.playback.PlaybackController
import org.jellyfin.androidtv.ui.playback.overlay.CustomPlaybackTransportControlGlue
import org.jellyfin.androidtv.ui.playback.overlay.VideoPlayerAdapter

class DanmakuAction(
	context: Context,
	customPlaybackTransportControlGlue: CustomPlaybackTransportControlGlue,
) : CustomAction(context, customPlaybackTransportControlGlue) {

	init {
		initializeWithIcon(R.drawable.ic_danmaku_white_24dp)
	}

	override fun handleClickAction(
		playbackController: PlaybackController,
		videoPlayerAdapter: VideoPlayerAdapter,
		context: Context,
		view: View,
	) {
		val overlayFragment = videoPlayerAdapter.leanbackOverlayFragment
		overlayFragment.setFading(false)
		videoPlayerAdapter.masterOverlayFragment.danmakuController?.showSettings {
			overlayFragment.setFading(true)
		}
	}
}
