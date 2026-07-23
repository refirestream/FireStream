package com.lagradost.cloudstream3.ui.settings.extensions

import android.content.res.ColorStateList
import android.text.format.Formatter.formatFileSize
import android.util.Log
import android.view.View
import androidx.core.view.isVisible
import com.lagradost.cloudstream3.CloudStreamApp.Companion.openBrowser
import com.lagradost.cloudstream3.databinding.FragmentPluginDetailsBinding
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.VotingApi
import com.lagradost.cloudstream3.plugins.VotingApi.getScore
import com.lagradost.cloudstream3.plugins.VotingApi.hasVoted
import com.lagradost.cloudstream3.plugins.VotingApi.vote
import com.lagradost.cloudstream3.plugins.VotingApi.votedDirection
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.BaseBottomSheetDialogFragment
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLandscape
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.getImageFromDrawable
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.SubtitleHelper.getNameNextToFlagEmoji
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.toPx

class PluginDetailsFragment(val data: PluginViewData) : BaseBottomSheetDialogFragment<FragmentPluginDetailsBinding>(
    BaseFragment.BindingCreator.Inflate(FragmentPluginDetailsBinding::inflate)
) {

    companion object {
        private tailrec fun findClosestBase2(target: Int, current: Int = 16, max: Int = 512): Int {
            if (current >= max) return max
            if (current >= target) return current
            return findClosestBase2(target, current * 2, max)
        }

        private val iconSizeExact = 50.toPx
        private val iconSize by lazy {
            findClosestBase2(iconSizeExact, 16, 512)
        }
    }

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(
            view,
            padBottom = isLandscape(),
            padLeft = isLayout(TV or EMULATOR)
        )
    }

    override fun onBindingCreated(binding: FragmentPluginDetailsBinding) {
        val metadata = data.pluginWrapper.plugin
        binding.apply {
            pluginIcon.loadImage(metadata.iconUrl?.replace("%size%", "$iconSize")
                ?.replace("%exact_size%", "$iconSizeExact")) {
                error { getImageFromDrawable(context ?: return@error null , R.drawable.ic_baseline_extension_24) }
            }
            pluginName.text = metadata.name.removeSuffix("Provider")
            pluginVersion.text = metadata.version.toString()
            pluginDescription.text = metadata.description ?: getString(R.string.no_data)
            pluginSize.text =
                if (metadata.fileSize == null) getString(R.string.no_data) else formatFileSize(
                    context,
                    metadata.fileSize
                )
            pluginAuthor.text =
                if (metadata.authors.isEmpty()) getString(R.string.no_data) else metadata.authors.joinToString(
                    ", "
                )
            pluginStatus.text =
                resources.getStringArray(R.array.extension_statuses)[metadata.status]
            pluginTypes.text =
                if (metadata.tvTypes.isNullOrEmpty()) getString(R.string.no_data) else metadata.tvTypes.joinToString(
                    ", "
                )
            pluginLang.text = if (metadata.language == null)
                    getString(R.string.no_data)
                else
                    getNameNextToFlagEmoji(metadata.language) ?: metadata.language

            githubBtn.setOnClickListener {
                if (metadata.repositoryUrl != null) {
                    openBrowser(metadata.repositoryUrl)
                }
            }

            if (data.isDownloaded) {
                // On local plugins page the filepath is provided instead of url.
                val plugin =
                    (PluginManager.urlPlugins[metadata.url] ?: PluginManager.plugins[metadata.url]) as? com.lagradost.cloudstream3.plugins.Plugin
                if (plugin?.openSettings != null && context != null) {
                    actionSettings.isVisible = true
                    actionSettings.setOnClickListener {
                        try {
                            plugin.openSettings!!.invoke(requireContext())
                        } catch (e: Throwable) {
                            Log.e(
                                "PluginAdapter",
                                "Failed to open ${metadata.name} settings: ${
                                    Log.getStackTraceString(e)
                                }"
                            )
                        }
                    }
                } else {
                    actionSettings.isVisible = false
                }
            } else {
                actionSettings.isVisible = false
            }

            // Voting is parked (see VotingApi.ENABLED); with no reachable
            // canister the thumbs and the badge would sit there doing nothing.
            if (!VotingApi.ENABLED) {
                pluginVotes.isVisible = false
                upvote.isVisible = false
                downvote.isVisible = false
                return@apply
            }

            upvote.setOnClickListener {
                // Tint immediately; updateVoting reconciles once vote() returns,
                // reverting it if the vote was rejected.
                applyVoteTint(true)
                ioSafe {
                    metadata.vote(up = true).main {
                        updateVoting(it)
                    }
                }
            }

            downvote.setOnClickListener {
                applyVoteTint(false)
                ioSafe {
                    metadata.vote(up = false).main {
                        updateVoting(it)
                    }
                }
            }

            ioSafe {
                metadata.getScore().main {
                    updateVoting(it)
                }
            }
        }
    }

    // value = Wilson TrustScore % (0..100), null = no signal yet (never voted,
    // or votes decayed to zero weight). Not a raw upvote count — no count
    // endpoint exists (see fire-backend/CLAUDE.md).
    private fun updateVoting(value: Double?) {
        binding?.apply {
            // Same flame badge as the extension list. Missing score = neutral 50%, not "New".
            FireScore.bind(pluginVotes, value ?: FireScore.DEFAULT_SCORE, iconDp = 20)
        }
        // Reconcile thumbs from the locally stored direction — anonymous
        // ballots carry no identity, so the canister can't be asked "how did I vote?".
        applyVoteTint(data.pluginWrapper.plugin.votedDirection())
    }

    /** Tint whichever direction is cast; leave the other neutral. null = neither. */
    private fun applyVoteTint(direction: Boolean?) {
        binding?.apply {
            val active = ColorStateList.valueOf(
                context?.colorFromAttribute(R.attr.colorPrimary) ?: R.color.colorPrimary
            )
            val neutral = ColorStateList.valueOf(
                context?.colorFromAttribute(com.google.android.material.R.attr.colorOnSurface)
                    ?: R.color.white
            )
            upvote.imageTintList = if (direction == true) active else neutral
            downvote.imageTintList = if (direction == false) active else neutral
        }
    }
}