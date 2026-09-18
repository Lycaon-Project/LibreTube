package com.github.libretube.ui.fragments

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import androidx.activity.BackEventCompat
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.trackPipAnimationHintView
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.motion.widget.TransitionAdapter
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.core.os.postDelayed
import androidx.core.view.WindowCompat
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.libretube.R
import com.github.libretube.api.JsonHelper
import com.github.libretube.api.obj.ChapterSegment
import com.github.libretube.api.obj.Segment
import com.github.libretube.api.obj.Streams
import com.github.libretube.compat.PictureInPictureCompat
import com.github.libretube.compat.PictureInPictureParamsCompat
import com.github.libretube.constants.IntentData
import com.github.libretube.databinding.FragmentPlayerBinding
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.enums.FileType
import com.github.libretube.enums.PlayerCommand
import com.github.libretube.enums.PlayerEvent
import com.github.libretube.enums.SbSkipOptions
import com.github.libretube.enums.ShareObjectType
import com.github.libretube.extensions.formatShort
import com.github.libretube.extensions.parcelable
import com.github.libretube.extensions.serializableExtra
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.togglePlayPauseState
import com.github.libretube.extensions.updateIfChanged
import com.github.libretube.helpers.BackgroundHelper
import com.github.libretube.helpers.DownloadHelper
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.PlayerHelper.getCurrentSegment
import com.github.libretube.helpers.ThemeHelper
import com.github.libretube.helpers.WindowHelper
import com.github.libretube.obj.ShareData
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.services.AbstractPlayerService
import com.github.libretube.services.OfflinePlayerService
import com.github.libretube.services.OnlinePlayerService
import com.github.libretube.ui.activities.AbstractPlayerHostActivity
import com.github.libretube.ui.activities.NoInternetActivity
import com.github.libretube.ui.adapters.VideoCardsAdapter
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.dialogs.AddToPlaylistDialog
import com.github.libretube.ui.dialogs.PlayOfflineDialog
import com.github.libretube.ui.dialogs.ShareDialog
import com.github.libretube.ui.extensions.animateDown
import com.github.libretube.ui.extensions.getSystemInsets
import com.github.libretube.ui.extensions.setOnBackPressed
import com.github.libretube.ui.extensions.setupSubscriptionButton
import com.github.libretube.ui.interfaces.CustomPlayerCallback
import com.github.libretube.ui.interfaces.TimeFrameReceiver
import com.github.libretube.ui.listeners.SeekbarPreviewListener
import com.github.libretube.ui.models.ChaptersViewModel
import com.github.libretube.ui.models.CommentsViewModel
import com.github.libretube.ui.models.CommonPlayerViewModel
import com.github.libretube.ui.models.PlayerViewModel
import com.github.libretube.ui.sheets.CommentsSheet
import com.github.libretube.util.OfflineTimeFrameReceiver
import com.github.libretube.util.OnlineTimeFrameReceiver
import com.github.libretube.util.PlayingQueue
import com.github.libretube.util.TextUtils
import com.github.libretube.util.TextUtils.toTimeInSeconds
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.io.path.exists
import kotlin.math.absoluteValue


@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerFragment : Fragment(R.layout.fragment_player), CustomPlayerCallback {
    private var _binding: FragmentPlayerBinding? = null
    val binding get() = _binding!!

    private val playerControlsBinding get() = binding.player.binding
    private val playerBackgroundBinding get() = binding.player.backgroundBinding

    private val commonPlayerViewModel: CommonPlayerViewModel by activityViewModels()
    private val viewModel: PlayerViewModel by viewModels()
    private val commentsViewModel: CommentsViewModel by activityViewModels()
    private val chaptersViewModel: ChaptersViewModel by activityViewModels()
    private lateinit var playerController: MediaController

    private lateinit var videoId: String
    private var playlistId: String? = null
    private var channelId: String? = null
    var isOffline: Boolean = false
        private set

    private lateinit var streams: Streams

    private val handler = Handler(Looper.getMainLooper())

    private var seekBarPreviewListener: SeekbarPreviewListener? = null
    private var closedVideo = false
    private var autoPlayCountdownEnabled = PlayerHelper.autoPlayCountdown
    private var playerLayoutOrientation = Int.MIN_VALUE
    private var pipActivity: Activity? = null
    private var isEnteringPiPMode = false

    private val baseActivity get() = activity as AbstractPlayerHostActivity
    private val windowInsetsControllerCompat
        get() = WindowCompat
            .getInsetsController(requireActivity().window, requireActivity().window.decorView)

    private val fullscreenDialog by lazy {
        object : ComponentDialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen) {
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        unsetFullscreen()
                    }
                })
            }

            override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
                if (_binding?.player?.onKeyUp(keyCode, event) == true) {
                    return true
                }
                return super.onKeyUp(keyCode, event)
            }
        }
    }

    private val playerActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!::playerController.isInitialized) return
            val event = intent.serializableExtra<PlayerEvent>(PlayerHelper.CONTROL_TYPE) ?: return

            if (PlayerHelper.handlePlayerAction(playerController, event)) return

            when (event) {
                PlayerEvent.Next -> {
                    PlayingQueue.getNext()?.let { playVideo(it) }
                }
                PlayerEvent.Prev -> {
                    PlayingQueue.getPrev()?.let { playVideo(it) }
                }
                PlayerEvent.Background -> {
                    switchToAudioMode()
                    handler.postDelayed(500) {
                        pipActivity?.moveTaskToBack(false)
                        pipActivity = null
                    }
                }
                else -> Unit
            }
        }
    }

    private var bufferingTimeoutTask: Runnable? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            PictureInPictureCompat.setPictureInPictureParams(requireActivity(), pipParams)

            if (isPlaying && PlayerHelper.sponsorBlockEnabled) {
                handler.postDelayed(
                    this@PlayerFragment::checkForSegments,
                    100
                )
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            super.onEvents(player, events)

            if (events.containsAny(
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED
                ) && _binding != null
            ) {
                updatePlayPauseButton()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!::playerController.isInitialized) return

            if (playbackState == Player.STATE_BUFFERING && streams.isLive &&
                playerController.duration - playerController.currentPosition < 700
            ) {
                playerController.setPlaybackSpeed(1f)
            }

            if (playbackState == Player.STATE_ENDED) {
                playerBackgroundBinding.sbSkipBtn.isGone = true

                val isTransitioning = playerController.currentTracks.isEmpty
                if ((PlayingQueue.hasNext() || PlayerHelper.autoPlayEnabled) && autoPlayCountdownEnabled && !isTransitioning) {
                    showAutoPlayCountdown()
                } else {
                    binding.player.showControllerPermanently()
                }
            }

            if (playbackState == PlaybackState.STATE_STOPPED &&
                PictureInPictureCompat.isInPictureInPictureMode(requireActivity())
            ) {
                activity?.finish()
            }

            if (playbackState == Player.STATE_BUFFERING) {
                if (bufferingTimeoutTask == null) {
                    bufferingTimeoutTask = Runnable {
                        if (::playerController.isInitialized) {
                            playerController.pause()
                        }
                    }
                }
                handler.postDelayed(bufferingTimeoutTask!!, PlayerHelper.MAX_BUFFER_DELAY)
            } else {
                bufferingTimeoutTask?.let { handler.removeCallbacks(it) }
            }

            super.onPlaybackStateChanged(playbackState)
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            super.onMediaMetadataChanged(mediaMetadata)

            val maybeStreams: Streams? = mediaMetadata.extras?.getString(IntentData.streams)?.let {
                JsonHelper.json.decodeFromString(it)
            }
            maybeStreams?.let { streams ->
                this@PlayerFragment.streams = streams
                viewModel.segments.postValue(emptyList())
                updatePlayerView()
            }
        }

        override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) {
            super.onPlaylistMetadataChanged(mediaMetadata)

            mediaMetadata.extras?.getString(IntentData.videoId)?.let {
                videoId = it
                if (_binding != null) playerBackgroundBinding.autoplayCountdown.cancelAndHideCountdown()

                arguments?.run {
                    val playerData =
                        parcelable<PlayerData>(IntentData.playerData)!!.copy(videoId = videoId)
                    putParcelable(IntentData.playerData, playerData)
                }
            }

            val segments: List<Segment>? =
                mediaMetadata.extras?.getString(IntentData.segments)?.let {
                    JsonHelper.json.decodeFromString(it)
                }
            viewModel.segments.postValue(segments.orEmpty())
        }

        override fun onPlayerError(error: PlaybackException) {
            super.onPlayerError(error)
            try {
                if (::playerController.isInitialized) {
                    playerController.play()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            super.onMediaItemTransition(mediaItem, reason)
            if (mediaItem == null) {
                toggleVideoInfoVisibility(false)
                disableController()
                binding.titleTextView.text = ""
            }
        }
    }

    private val lockedOrientations = listOf(
        ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    )

    private var screenshotBitmap: Bitmap? = null
    private val openScreenshotFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
            if (uri == null) {
                screenshotBitmap = null
                return@registerForActivityResult
            }

            lifecycleScope.launch(Dispatchers.IO) {
                context?.contentResolver?.openOutputStream(uri)?.use { outputStream ->
                    screenshotBitmap?.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                }
                screenshotBitmap = null

                withContext(Dispatchers.Main) {
                    _binding?.let { binding ->
                        Snackbar.make(
                            binding.root,
                            R.string.screenshot_saved,
                            2500
                        ).apply {
                            setAction(R.string.share) {
                                startActivity(Intent.createChooser(Intent().apply {
                                    action = Intent.ACTION_SEND
                                    type = "image/png"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                }, null))
                            }
                            show()
                        }
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ContextCompat.registerReceiver(
            requireContext(),
            playerActionReceiver,
            IntentFilter(PlayerHelper.getIntentActionName(requireContext())),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentPlayerBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        activity?.getSystemInsets()?.let { systemBars ->
            with(binding.root) {
                setPadding(
                    paddingLeft,
                    paddingTop + systemBars.top,
                    paddingRight,
                    paddingBottom
                )
            }
        }

        val playerData = requireArguments().parcelable<PlayerData>(IntentData.playerData)!!
        playerData.videoId?.let { videoId = it }
        isOffline = playerData.isOffline
        playlistId = playerData.playlistId
        channelId = playerData.channelId

        requireArguments().putBoolean(IntentData.alreadyStarted, true)

        changeOrientationMode()

        playerLayoutOrientation = resources.configuration.orientation

        initializeTransitionLayout()
        initializeOnClickActions()

        if (PlayerHelper.autoFullscreenEnabled && resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            setFullscreen()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                requireActivity().trackPipAnimationHintView(binding.player)
            }
        }

        chaptersViewModel.chaptersLiveData.observe(viewLifecycleOwner) {
            binding.player.setCurrentChapterName()
            playerControlsBinding.exoProgress.setChapters(it.orEmpty())
        }

        viewModel.segments.observe(viewLifecycleOwner) { segments ->
            binding.descriptionLayout.setSegments(segments)
            playerControlsBinding.exoProgress.setSegments(segments)
            playerControlsBinding.sbToggle.isVisible = segments.isNotEmpty()
            getHighlight(segments)?.let {
                lifecycleScope.launch(Dispatchers.IO) { initializeHighlight(it) }
            }
        }

        if (!isOffline) {
            viewLifecycleOwner.lifecycleScope.launch {
                val localDownloadVersion = withContext(Dispatchers.IO) {
                    DatabaseHolder.Database.downloadDao().findById(videoId)
                }

                if (localDownloadVersion != null) {
                    val fragmentManager = requireActivity().supportFragmentManager

                    fragmentManager.setFragmentResultListener(
                        PlayOfflineDialog.PLAY_OFFLINE_DIALOG_REQUEST_KEY, viewLifecycleOwner
                    ) { _, bundle ->
                        isOffline = bundle.getBoolean(IntentData.isPlayingOffline)
                        attachToPlayerService(playerData)
                    }

                    val downloadInfo = withContext(Dispatchers.IO) {
                        DownloadHelper.extractDownloadInfoText(
                            requireContext(),
                            localDownloadVersion
                        ).toTypedArray()
                    }

                    PlayOfflineDialog().apply {
                        arguments = Bundle().apply {
                            putString(IntentData.videoId, videoId)
                            putString(IntentData.videoTitle, localDownloadVersion.download.title)
                            putStringArray(IntentData.downloadInfo, downloadInfo)
                        }
                    }.show(fragmentManager, null)
                } else {
                    attachToPlayerService(playerData)
                }
            }
        } else {
            attachToPlayerService(playerData)
        }

        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (commonPlayerViewModel.isFullscreen.value == true) unsetFullscreen()
                else {
                    binding.playerMotionLayout.setTransitionDuration(250)
                    binding.playerMotionLayout.transitionToEnd()
                    baseActivity.minimizePlayerContainerLayout()
                    baseActivity.requestOrientationChange()
                }
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                binding.playerMotionLayout.progress = backEvent.progress
            }

            override fun handleOnBackCancelled() {
                binding.playerMotionLayout.transitionToStart()
            }
        }
        setOnBackPressed(onBackPressedCallback)

        commonPlayerViewModel.isMiniPlayerVisible.observe(viewLifecycleOwner) { isMiniPlayerVisible ->
            if (!isMiniPlayerVisible) {
                onBackPressedCallback.remove()
                setOnBackPressed(onBackPressedCallback)
            }
            onBackPressedCallback.isEnabled = isMiniPlayerVisible != true
        }

        toggleVideoInfoVisibility(false)
    }

    private fun attachToPlayerService(playerData: PlayerData) {
        val (serviceClass, args) = if (isOffline) {
            val isNoInternet = activity is NoInternetActivity

            OfflinePlayerService::class.java to Bundle().apply {
                putParcelable(IntentData.playerData, playerData.copy(downloadTab = playerData.downloadTab ?: DownloadTab.VIDEO))
                putBoolean(IntentData.noInternet, isNoInternet)
            }
        } else {
            OnlinePlayerService::class.java to Bundle().apply {
                putParcelable(IntentData.playerData, playerData)
                putBoolean(IntentData.audioOnly, false)
            }
        }

        BackgroundHelper.startMediaService(
            requireContext(),
            serviceClass,
            args,
        ) {
            if (_binding == null) {
                if (::playerController.isInitialized) {
                    playerController.sendCustomCommand(
                        AbstractPlayerService.stopServiceCommand,
                        Bundle.EMPTY
                    )
                    playerController.release()
                }
                return@startMediaService
            }

            playerController = it
            playerController.addListener(playerListener)
            connectToPlayerView(playerController)
            updatePlayPauseButton()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initializeTransitionLayout() {
        baseActivity.setPlayerContainerProgress(0f)

        var transitionStartId = 0
        var transitionEndId = 0

        binding.playerMotionLayout.addTransitionListener(object : TransitionAdapter() {
            override fun onTransitionChange(
                motionLayout: MotionLayout?,
                startId: Int,
                endId: Int,
                progress: Float
            ) {
                if (_binding == null) return

                baseActivity.setPlayerContainerProgress(progress.absoluteValue)
                disableController()
                commonPlayerViewModel.setSheetExpand(false)
                transitionEndId = endId
                transitionStartId = startId
            }

            override fun onTransitionCompleted(motionLayout: MotionLayout?, currentId: Int) {
                if (_binding == null) return

                if (currentId == transitionStartId) {
                    commonPlayerViewModel.isMiniPlayerVisible.value = false
                    binding.player.updateCurrentSubtitle(viewModel.currentCaptionId)
                    binding.player.useController = true
                    commonPlayerViewModel.setSheetExpand(true)
                    baseActivity.setPlayerContainerProgress(0f)
                    changeOrientationMode()
                    baseActivity.clearSearchViewFocus()
                } else if (currentId == transitionEndId) {
                    commonPlayerViewModel.isMiniPlayerVisible.value = true
                    binding.player.updateCurrentSubtitle(null)
                    disableController()
                    commonPlayerViewModel.setSheetExpand(null)
                    playerBackgroundBinding.sbSkipBtn.isGone = true

                    baseActivity.setPlayerContainerProgress(1f)
                    baseActivity.requestOrientationChange()
                }

                updateMaxSheetHeight()
            }
        })

        binding.playerMotionLayout
            .addSwipeDownListener {
                if (commonPlayerViewModel.isMiniPlayerVisible.value == true) {
                    closeMiniPlayer()
                }
            }

        binding.playerMotionLayout.progress = 1F
        binding.playerMotionLayout.transitionToStart()

        val activity = requireActivity()
        PictureInPictureCompat.setPictureInPictureParams(activity, pipParams)
    }

    private fun closeMiniPlayer() {
        binding
            .playerMotionLayout
            .animateDown(
                duration = 300L,
                dy = 500F,
                onEnd = ::killPlayerFragment
            )
    }

    private fun initializeOnClickActions() {
        binding.closeImageView.setOnClickListener {
            killPlayerFragment()
        }
        playerControlsBinding.closeImageButton.setOnClickListener {
            killPlayerFragment()
        }

        binding.playImageView.setOnClickListener {
            if (::playerController.isInitialized) playerController.togglePlayPauseState()
        }

        activity?.supportFragmentManager
            ?.setFragmentResultListener(
                CommentsSheet.HANDLE_LINK_REQUEST_KEY,
                viewLifecycleOwner
            ) { _, bundle ->
                bundle.getString(IntentData.url)?.let { handleLink(it) }
            }

        binding.commentsToggle.setOnClickListener {
            if (!this::streams.isInitialized) return@setOnClickListener
            updateMaxSheetHeight()
            commentsViewModel.videoIdLiveData.updateIfChanged(videoId)
            CommentsSheet()
                .apply {
                    arguments = Bundle().apply {
                        putString(IntentData.channelAvatar, streams.uploaderAvatar)
                    }
                }
                .show(childFragmentManager)
        }

        binding.relPlayerShare.setOnClickListener {
            if (!this::streams.isInitialized) return@setOnClickListener
            val bundle = Bundle().apply {
                putString(IntentData.id, videoId)
                putSerializable(IntentData.shareObjectType, ShareObjectType.VIDEO)
                putParcelable(IntentData.shareData, ShareData(
                    currentVideo = streams.title,
                    currentPosition = if (::playerController.isInitialized) playerController.currentPosition / 1000 else 0
                ))
            }
            val newShareDialog = ShareDialog()
            newShareDialog.arguments = bundle
            newShareDialog.show(childFragmentManager, ShareDialog::class.java.name)
        }

        binding.relPlayerBackground.setOnClickListener {
            switchToAudioMode()
        }

        binding.relPlayerPip.isVisible = isPipAvailable()

        binding.relPlayerPip.setOnClickListener {
            PictureInPictureCompat.enterPictureInPictureMode(requireActivity(), pipParams)
            isEnteringPiPMode = true
        }

        binding.relatedRecView.layoutManager = LinearLayoutManager(
            context,
            if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                LinearLayoutManager.HORIZONTAL
            } else {
                LinearLayoutManager.VERTICAL
            },
            false
        )

        binding.relPlayerSave.setOnClickListener {
            if (!::streams.isInitialized) return@setOnClickListener

            AddToPlaylistDialog().apply {
                arguments = Bundle().apply {
                    putParcelable(IntentData.videoInfo, streams.toStreamItem(videoId))
                }
            }.show(childFragmentManager, AddToPlaylistDialog::class.java.name)
        }

        playerControlsBinding.skipPrev.setOnClickListener {
            PlayingQueue.getPrev()?.let { prev -> playVideo(prev) }
        }

        playerControlsBinding.skipNext.setOnClickListener {
            PlayingQueue.getNext()?.let { next -> playVideo(next) }
        }

        binding.relPlayerDownload.setOnClickListener {
            if (!this::streams.isInitialized) return@setOnClickListener

            DownloadHelper.startDownloadDialog(requireContext(), childFragmentManager, videoId)
        }

        binding.relPlayerScreenshot.setOnClickListener {
            if (!this::streams.isInitialized) return@setOnClickListener
            val surfaceView =
                binding.player.videoSurfaceView as? SurfaceView ?: return@setOnClickListener

            val bmp = createBitmap(surfaceView.width, surfaceView.height)

            PixelCopy.request(surfaceView, bmp, { _ ->
                screenshotBitmap = bmp
                val currentPosition =
                    if (::playerController.isInitialized) playerController.currentPosition.toFloat() / 1000 else 0f
                openScreenshotFile.launch("${streams.title}-${currentPosition}.png")
            }, handler)
        }

        binding.playerChannel.setOnClickListener {
            if (!this::streams.isInitialized) return@setOnClickListener

            NavigationHelper.navigateChannel(requireContext(), streams.uploaderUrl)
        }

        binding.descriptionLayout.handleLink = this::handleLink
    }

    private fun updateMaxSheetHeight() {
        val systemBars = baseActivity.getSystemInsets() ?: return
        val maxHeight = binding.root.height - (binding.player.height + systemBars.top)
        commonPlayerViewModel.maxSheetHeightPx = maxHeight
        chaptersViewModel.maxSheetHeightPx = maxHeight
    }

    fun switchToAudioMode() {
        if (!::playerController.isInitialized) return

        playerController.sendCustomCommand(
            AbstractPlayerService.runPlayerActionCommand,
            Bundle().apply {
                putBoolean(PlayerCommand.TOGGLE_AUDIO_ONLY_MODE.name, true)
            }
        )
        playerController.sendCustomCommand(
            AbstractPlayerService.runPlayerActionCommand,
            Bundle().apply {
                putBoolean(PlayerCommand.SET_AUTOPLAY_COUNTDOWN_ENABLED.name, false)
            }
        )

        binding.player.detachPlayer()

        playerController.release()
        killPlayerFragment()

        NavigationHelper.openAudioPlayerFragment(requireContext(), offlinePlayer = isOffline)
    }

    private fun updateFullscreenOrientation() {
        if (PlayerHelper.autoFullscreenEnabled || !this::streams.isInitialized) return

        baseActivity.requestedOrientation = PlayerHelper.getFullscreenOrientation(streams.isShort)
    }

    private fun setFullscreen() {
        windowInsetsControllerCompat.isAppearanceLightStatusBars = false

        commonPlayerViewModel.isFullscreen.value = true
        updateFullscreenOrientation()

        commonPlayerViewModel.setSheetExpand(null)

        openOrCloseFullscreenDialog(true)

        binding.player.updateMarginsByFullscreenMode()
    }

    @SuppressLint("SourceLockedOrientationActivity")
    fun unsetFullscreen() {
        if (activity == null || _binding == null) return

        commonPlayerViewModel.isFullscreen.value = false

        if (!PlayerHelper.autoFullscreenEnabled) {
            baseActivity.requestedOrientation = baseActivity.screenOrientationPref
        }

        openOrCloseFullscreenDialog(false)

        binding.player.updateMarginsByFullscreenMode()

        windowInsetsControllerCompat.isAppearanceLightStatusBars =
            !ThemeHelper.isDarkMode(requireContext())
    }

    override fun toggleFullscreen() {
        binding.player.hideController()

        val isFullscreen = commonPlayerViewModel.isFullscreen.value == true
        if (!isFullscreen) {
            setFullscreen()
        } else {
            unsetFullscreen()
        }
    }

    private fun openOrCloseFullscreenDialog(open: Boolean) {
        val playerView = binding.player
        (playerView.parent as ViewGroup).removeView(playerView)

        if (open) {
            fullscreenDialog.addContentView(
                binding.player,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
            fullscreenDialog.show()
            playerView.currentWindow = fullscreenDialog.window
        } else {
            binding.playerMotionLayout.addView(playerView)
            playerView.currentWindow = null
            fullscreenDialog.dismiss()
        }

        WindowHelper.toggleFullscreen(fullscreenDialog.window!!, open)
    }

    override fun onPause() {
        super.onPause()

        if (!::playerController.isInitialized) return

        val isInteractive = requireContext().getSystemService<PowerManager>()?.isInteractive ?: true

        if (!isInteractive && !isEnteringPiPMode) {
            setAutoPlayCountdownEnabled(false)
            setVideoTrackTypeDisabled(true)
        }

        if (PlayerHelper.pausePlayerOnScreenOffEnabled && !isInteractive && !isEnteringPiPMode) {
            playerController.pause()
        }

        isEnteringPiPMode = false
    }

    override fun onResume() {
        super.onResume()

        if (closedVideo) {
            closedVideo = false
        }

        if (!::playerController.isInitialized) return

        setAutoPlayCountdownEnabled(PlayerHelper.autoPlayCountdown)
        setVideoTrackTypeDisabled(false)
    }

    private fun setAutoPlayCountdownEnabled(enabled: Boolean) {
        if (!::playerController.isInitialized) return

        this.autoPlayCountdownEnabled = enabled

        playerController.sendCustomCommand(
            AbstractPlayerService.runPlayerActionCommand,
            Bundle().apply {
                putBoolean(PlayerCommand.SET_AUTOPLAY_COUNTDOWN_ENABLED.name, enabled)
            }
        )
    }

    private fun setVideoTrackTypeDisabled(disabled: Boolean) {
        if (!::playerController.isInitialized) return

        playerController.sendCustomCommand(
            AbstractPlayerService.runPlayerActionCommand,
            Bundle().apply {
                putBoolean(PlayerCommand.SET_VIDEO_TRACK_TYPE_DISABLED.name, disabled)
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()

        handler.removeCallbacksAndMessages(null)

        if (::playerController.isInitialized && playerController.isConnected) {
            playerController.removeListener(playerListener)
            playerController.pause()

            playerController.sendCustomCommand(
                AbstractPlayerService.stopServiceCommand,
                Bundle.EMPTY
            )
            playerController.release()
        }

        runCatching {
            PictureInPictureCompat
                .setPictureInPictureParams(requireActivity(), pipParams)
        }

        runCatching {
            if (fullscreenDialog.isShowing) fullscreenDialog.dismiss()
        }

        runCatching {
            context?.unregisterReceiver(playerActionReceiver)
        }

        baseActivity.requestOrientationChange()

        _binding = null
    }

    private fun killPlayerFragment() {
        binding.playerMotionLayout.transitionToEnd()

        commonPlayerViewModel.isMiniPlayerVisible.value = false

        if (commonPlayerViewModel.isFullscreen.value == true) {
            binding.playerMotionLayout.addTransitionListener(object : TransitionAdapter() {
                override fun onTransitionCompleted(motionLayout: MotionLayout?, currentId: Int) {
                    super.onTransitionCompleted(motionLayout, currentId)

                    baseActivity.supportFragmentManager.commit {
                        remove(this@PlayerFragment)
                    }
                }
            })

            unsetFullscreen()
        } else {
            baseActivity.supportFragmentManager.commit {
                remove(this@PlayerFragment)
            }
        }
    }

    private fun checkForSegments() {
        if (!::playerController.isInitialized || !playerController.isPlaying || !PlayerHelper.sponsorBlockEnabled) return

        handler.postDelayed(this::checkForSegments, 100)
        if (viewModel.segments.value.isNullOrEmpty()) return

        val segmentData = playerController.getCurrentSegment(
            viewModel.segments.value.orEmpty(),
            viewModel.sponsorBlockConfig
        )

        if (segmentData != null && commonPlayerViewModel.isMiniPlayerVisible.value != true) {
            val (segment, sbSkipOption) = segmentData

            val autoSkipTemporarilyDisabled =
                !binding.player.sponsorBlockAutoSkip && sbSkipOption != SbSkipOptions.OFF

            if (sbSkipOption in arrayOf(
                    SbSkipOptions.AUTOMATIC_ONCE,
                    SbSkipOptions.MANUAL
                ) || autoSkipTemporarilyDisabled
            ) {
                if (!PictureInPictureCompat.isInPictureInPictureMode(requireActivity())) {
                    playerBackgroundBinding.sbSkipBtn.isVisible = true
                }
                playerBackgroundBinding.sbSkipBtn.setOnClickListener {
                    playerController.seekTo((segment.segmentStartAndEnd.second * 1000f).toLong())
                    segment.skipped = true
                }
            }
        } else {
            playerBackgroundBinding.sbSkipBtn.isGone = true
        }
    }

    private fun setPlayerDefaults() {
        if (!::playerController.isInitialized) return

        playerControlsBinding.exoProgress.clearSegments()
        playerControlsBinding.sbToggle.isGone = true

        commentsViewModel.reset()

        playerBackgroundBinding.sbSkipBtn.isGone = true

        playerController.sendCustomCommand(
            AbstractPlayerService.runPlayerActionCommand,
            Bundle().apply {
                putInt(PlayerCommand.SET_AUDIO_ROLE_FLAGS.name, C.ROLE_FLAG_MAIN)
            }
        )

        setAutoPlayCountdownEnabled(PlayerHelper.autoPlayCountdown)

        binding.player.updateCurrentSubtitle(viewModel.currentCaptionId)

        binding.player.setToDefaultResolution()

        if (streams.category == Streams.CATEGORY_MUSIC) {
            playerController.setPlaybackSpeed(1f)
        }
    }

    fun playVideo(videoId: String) {
        if (!::playerController.isInitialized) return

        playerController.sendCustomCommand(
            AbstractPlayerService.runPlayerActionCommand,
            Bundle().apply {
                putString(PlayerCommand.PLAY_VIDEO_BY_ID.name, videoId)
            }
        )
    }

    fun playNextVideo() {
        if (!::playerController.isInitialized) return

        playerController.sendCustomCommand(
            AbstractPlayerService.runPlayerActionCommand,
            Bundle().apply {
                putString(PlayerCommand.PLAY_NEXT_VIDEO.name, "")
            }
        )
    }

    private fun dismissCommentsSheet() {
        childFragmentManager.fragments
            .filterIsInstance<CommentsSheet>()
            .firstOrNull()
            ?.dismiss()
    }

    private fun toggleVideoInfoVisibility(show: Boolean) {
        binding.descriptionLayout.collapseDescription()
        binding.descriptionLayout.isInvisible = !show
        binding.relatedRecView.isInvisible = !show
        binding.playerChannel.isInvisible = !show
        playerBackgroundBinding.videoTransitionProgress.isVisible = !show
    }

    private fun connectToPlayerView(player: Player) {
        binding.player.initialize(
            chaptersViewModel,
            commonPlayerViewModel,
            viewModel,
            viewLifecycleOwner,
            this,
            player
        )
    }

    @SuppressLint("SetTextI18n")
    private fun updatePlayerView() {
        dismissCommentsSheet()

        setPlayerDefaults()

        binding.player.useController = false

        if (binding.playerMotionLayout.progress != 1.0f) {
            val inPipMode = PictureInPictureCompat.isInPictureInPictureMode(requireActivity())
            if (!inPipMode) {
                binding.player.useController = true
            }
        }

        viewModel.isOrientationChangeInProgress = false

        binding.descriptionLayout.setStreams(streams)

        toggleVideoInfoVisibility(true)

        binding.apply {
            ImageHelper.loadImage(streams.uploaderAvatar, binding.playerChannelImage, true)
            binding.playerChannelImage.isVisible = streams.uploaderAvatar != null

            playerChannelName.text = streams.uploader
            titleTextView.text = streams.title

            playerChannelSubCount.text = context?.getString(
                R.string.subscribers,
                streams.uploaderSubscriberCount.formatShort()
            )
            playerChannelSubCount.isVisible = streams.uploaderSubscriberCount >= 0

            relPlayerDownload.isVisible = !streams.isLive && !isOffline
        }
        playerControlsBinding.exoTitle.text = streams.title

        chaptersViewModel.chaptersLiveData.postValue(streams.chapters)

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            showRelatedStreams()
        }

        if (streams.uploaderUrl != null) {
            binding.playerSubscribe.setupSubscriptionButton(
                streams.uploaderUrl!!.toID(),
                streams.uploader,
                streams.uploaderAvatar,
                streams.uploaderVerified
            )
        } else {
            binding.playerSubscribe.isGone = true
        }

        playerControlsBinding.seekbarPreview.isGone = true
        seekBarPreviewListener?.let { playerControlsBinding.exoProgress.removeSeekBarListener(it) }

        viewLifecycleOwner.lifecycleScope.launch {
            val timeFrameReceiver = getTimeFrameReceiver() ?: return@launch
            val listener = SeekbarPreviewListener(
                timeFrameReceiver,
                playerControlsBinding,
                streams.duration * 1000
            )

            seekBarPreviewListener = listener
            playerControlsBinding.exoProgress.addSeekBarListener(listener)
        }

        if (binding.playerMotionLayout.progress == 0f && PlayerHelper.autoFullscreenShortsEnabled && streams.isShort) {
            setFullscreen()
        }

        getHighlight(viewModel.segments.value.orEmpty())?.let {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { initializeHighlight(it) }
        }
    }

    private suspend fun showRelatedStreams() {
        if (!PlayerHelper.relatedStreamsEnabled) return

        val relatedStreams = if (isOffline) {
            withContext(Dispatchers.IO) {
                DatabaseHolder.Database.downloadDao().getAll()
                    .filter { it.download.videoId != videoId }
                    .map { it.download.toStreamItem() }
            }
        } else {
            streams.relatedStreams.filter { !it.title.isNullOrBlank() }
        }

        withContext(Dispatchers.Main) {
            val binding = _binding ?: return@withContext
            val relatedLayoutManager = binding.relatedRecView.layoutManager as LinearLayoutManager
            binding.relatedRecView.adapter = VideoCardsAdapter(
                columnWidthDp = if (relatedLayoutManager.orientation == LinearLayoutManager.HORIZONTAL) 250f else null
            ).also { adapter ->
                adapter.submitList(relatedStreams)
            }
        }
    }

    private fun showAutoPlayCountdown() {
        if (!PlayingQueue.hasNext()) return

        disableController()
        playerBackgroundBinding.autoplayCountdown.setHideSelfListener {
            runCatching {
                playerBackgroundBinding.autoplayCountdown.isGone = true
                binding.player.useController = true
            }
        }
        playerBackgroundBinding.autoplayCountdown.startCountdown {
            playNextVideo()
        }
    }

    private fun handleLink(link: String) {
        val uri = link.toUri()
        val videoId = TextUtils.getVideoIdFromUri(uri)

        if (videoId.isNullOrEmpty()) {
            val intent = Intent(Intent.ACTION_VIEW, uri)

            onUserLeaveHint()
            startActivity(intent)

            return
        }

        if (videoId == this.videoId) {
            uri.getQueryParameter("t")?.toTimeInSeconds()?.let {
                if (::playerController.isInitialized) {
                    playerController.seekTo(it * 1000)
                }
            }
        } else {
            playVideo(videoId)
        }
    }

    private fun updatePlayPauseButton() {
        if (!::playerController.isInitialized) return
        val playPauseAction = PlayerHelper.getPlayPauseActionIcon(playerController)
        binding.playImageView.setImageResource(playPauseAction)
    }

    private suspend fun getTimeFrameReceiver(): TimeFrameReceiver? = withContext(Dispatchers.IO) {
        return@withContext if (isOffline) {
            if (!::videoId.isInitialized) return@withContext null

            val downloadItems =
                DatabaseHolder.Database.downloadDao().getDownloadById(videoId)?.downloadItems
            downloadItems?.firstOrNull { it.path.exists() && it.type == FileType.VIDEO }?.path?.let {
                OfflineTimeFrameReceiver(requireContext(), it)
            }
        } else {
            if (!::streams.isInitialized) return@withContext null

            OnlineTimeFrameReceiver(requireContext(), streams.previewFrames)
        }
    }

    private fun getHighlight(segments: List<Segment>): Segment? {
        return segments.firstOrNull { it.category == PlayerHelper.SPONSOR_HIGHLIGHT_CATEGORY }
    }

    private suspend fun initializeHighlight(highlight: Segment) {
        val frameReceiver = getTimeFrameReceiver() ?: return

        val highlightStart = highlight.segmentStartAndEnd.first.toLong()
        val frame = withContext(Dispatchers.IO) {
            frameReceiver.getFrameAtTime(highlightStart * 1000)
        }
        val highlightChapter = ChapterSegment(
            title = getString(R.string.chapters_videoHighlight),
            start = highlightStart,
            highlightDrawable = frame?.toDrawable(requireContext().resources)
        )
        chaptersViewModel.chaptersLiveData.postValue(
            chaptersViewModel.chapters.plus(highlightChapter).sortedBy { it.start }
        )
    }

    @SuppressLint("SourceLockedOrientationActivity")
    private fun changeOrientationMode() {
        if (PlayerHelper.autoFullscreenEnabled) {
            baseActivity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        } else {
            baseActivity.requestedOrientation =
                (requireActivity() as BaseActivity).screenOrientationPref
        }
    }


    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        if (isInPictureInPictureMode) {
            disableController()

            binding.player.updateCurrentSubtitle(null)
            playerBackgroundBinding.sbSkipBtn.isGone = true

            openOrCloseFullscreenDialog(true)
            pipActivity = activity
        } else {
            binding.player.useController = true

            if (lifecycle.currentState == Lifecycle.State.CREATED) {
                if (::playerController.isInitialized) {
                    playerController.pause()
                }
                closedVideo = true
            }

            binding.player.updateCurrentSubtitle(viewModel.currentCaptionId)

            if (commonPlayerViewModel.isFullscreen.value != true) {
                openOrCloseFullscreenDialog(false)
            }
        }
    }

    fun onUserLeaveHint() {
        if (shouldStartPiP()) {
            PictureInPictureCompat.enterPictureInPictureMode(requireActivity(), pipParams)
        }
    }

    private val pipParams: PictureInPictureParamsCompat
        get() = run {
            val isPlaying = ::playerController.isInitialized && playerController.isPlaying

            PictureInPictureParamsCompat.Builder()
                .setActions(
                    PlayerHelper.getPiPModeActions(
                        requireActivity(),
                        isPlaying
                    )
                )
                .setAutoEnterEnabled(isPlaying)
                .apply {
                    if (isPlaying) {
                        setAspectRatio(playerController.videoSize)
                    }
                }
                .build()
        }

    private fun isPipAvailable() =
        PictureInPictureCompat.isPictureInPictureAvailable(requireContext())
                && PictureInPictureCompat.isPictureInPictureEnabled(requireContext())

    private fun shouldStartPiP(): Boolean {
        return isPipAvailable() && ::playerController.isInitialized && playerController.isPlaying
    }

    private fun restartActivityIfNeeded() {
        if (baseActivity.screenOrientationPref in lockedOrientations || viewModel.isOrientationChangeInProgress) return

        val orientation = resources.configuration.orientation
        if (commonPlayerViewModel.isFullscreen.value != true && orientation != playerLayoutOrientation) {
            playerLayoutOrientation = orientation

            viewModel.isOrientationChangeInProgress = true

            binding.player.detachPlayer()

            if (::playerController.isInitialized) playerController.release()

            activity?.recreate()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (_binding == null ||
            PictureInPictureCompat.isInPictureInPictureMode(requireActivity())
        ) {
            return
        }

        if (PlayerHelper.autoFullscreenEnabled) {
            when (newConfig.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> setFullscreen()
                else -> unsetFullscreen()
            }
        }

        restartActivityIfNeeded()
    }

    private fun disableController() {
        binding.player.useController = false
        binding.player.hideController()
    }

    fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return _binding?.player?.onKeyUp(keyCode, event) ?: false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun getVideoId(): String {
        return videoId
    }

    override fun isVideoShort(): Boolean {
        return ::streams.isInitialized && streams.isShort
    }

    override fun isVideoLive(): Boolean {
        return ::streams.isInitialized && streams.isLive
    }
}