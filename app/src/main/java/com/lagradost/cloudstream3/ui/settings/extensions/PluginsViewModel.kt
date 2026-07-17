package com.lagradost.cloudstream3.ui.settings.extensions

import android.app.Activity
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.PROVIDER_STATUS_DOWN
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.launchSafe
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.PluginManager.getPluginPath
import com.lagradost.cloudstream3.plugins.PluginWrapper
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.plugins.VotingApi
import com.lagradost.cloudstream3.utils.PreferenceDelegate
import com.lagradost.cloudstream3.utils.txt
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.Coroutines.runOnMainThread
import com.lagradost.cloudstream3.utils.Levenshtein
import java.io.File

/**
 * The boolean signifies if the plugin list should be scrolled to the top, used for searching.
 * */
typealias PluginViewDataUpdate = Pair<Boolean, List<PluginViewData>>

/** User-selectable ordering for the extension list, persisted across sessions. */
enum class PluginSortOrder(@StringRes val stringRes: Int) {
    NAME_ASC(R.string.sort_alphabetical_a),
    NAME_DESC(R.string.sort_alphabetical_z),
    SCORE_DESC(R.string.sort_rating_desc),
    SCORE_ASC(R.string.sort_rating_asc),
}

class PluginsViewModel : ViewModel() {

    /** plugins is an unaltered list of plugins */
    private var plugins: List<PluginViewData> = emptyList()
        set(value) {
            // Also set all the plugin languages for easier filtering
            value.map { pluginViewData ->
                val language = pluginViewData.pluginWrapper.plugin.language?.lowercase()
                pluginLanguages.add(
                    when {
                        language.isNullOrBlank() -> "none"
                        else -> language.lowercase()
                    }
                )
                // not sorting as most likely this is a language tag instead of name
            }
            field = value
        }
    var pluginLanguages = mutableSetOf<String>() // set to avoid duplicates

    /** filteredPlugins is a subset of plugins following the current search query and tv type selection */
    private var _filteredPlugins = MutableLiveData<PluginViewDataUpdate>()
    var filteredPlugins: LiveData<PluginViewDataUpdate> = _filteredPlugins

    val tvTypes = mutableListOf<String>()
    var selectedLanguages = listOf<String>()

    /** Set once the provider language settings have been applied as the default filter */
    var hasSetDefaultLanguages = false
    private var currentQuery: String? = null

    val sortOrder: PluginSortOrder
        get() = PluginSortOrder.entries.getOrElse(storedSortOrder) { PluginSortOrder.SCORE_DESC }

    fun setSortOrder(order: PluginSortOrder) {
        storedSortOrder = order.ordinal
        updateFilteredPlugins()
    }

    companion object {
        private val repositoryCache: MutableMap<String, List<PluginWrapper>> = mutableMapOf()
        const val TAG = "PLG"

        /** Ordinal rather than name so a renamed enum entry cannot poison the stored value. */
        private var storedSortOrder by PreferenceDelegate(
            "plugin_sort_order",
            PluginSortOrder.SCORE_DESC.ordinal
        )

        private fun isDownloaded(
            context: Context,
            pluginName: String,
            repositoryUrl: String
        ): Boolean {
            return getPluginPath(context, pluginName, repositoryUrl).exists()
        }

        private suspend fun getPlugins(
            repository: RepositoryData,
            canUseCache: Boolean = true
        ): List<PluginWrapper> {
            Log.i(TAG, "getPlugins = $repository")
            if (canUseCache && repositoryCache.containsKey(repository.url)) {
                repositoryCache[repository.url]?.let {
                    return it
                }
            }

            return RepositoryManager.getRepoPlugins(repository)
                ?.also { repositoryCache[repository.url] = it } ?: emptyList()
        }

        /**
         * @param viewModel optional, updates the plugins livedata for that viewModel if included
         * */
        fun downloadAll(activity: Activity?, repository: RepositoryData, viewModel: PluginsViewModel?) =
            ioSafe {
                if (activity == null) return@ioSafe
                val plugins = getPlugins(repository)

                plugins.filter { pluginWrapper ->
                    !isDownloaded(
                        activity,
                        pluginWrapper.plugin.internalName,
                        repository.url
                    )
                }.also { list ->
                    main {
                        showToast(
                            when {
                                // No plugins at all
                                plugins.isEmpty() -> txt(
                                    R.string.no_plugins_found_error,
                                )
                                // All plugins downloaded
                                list.isEmpty() -> txt(
                                    R.string.batch_download_nothing_to_download_format,
                                    txt(R.string.plugin)
                                )

                                else -> txt(
                                    R.string.batch_download_start_format,
                                    list.size,
                                    txt(if (list.size == 1) R.string.plugin_singular else R.string.plugin)
                                )
                            },
                            Toast.LENGTH_SHORT
                        )
                    }
                }.amap { (_, repo, metadata) ->
                    PluginManager.downloadPlugin(
                        activity,
                        metadata.url,
                        metadata.fileHash,
                        metadata.internalName,
                        repo.url,
                        metadata.status != PROVIDER_STATUS_DOWN
                    )
                }.main { list ->
                    if (list.any { it }) {
                        showToast(
                            txt(
                                R.string.batch_download_finish_format,
                                list.count { it },
                                txt(if (list.size == 1) R.string.plugin_singular else R.string.plugin)
                            ),
                            Toast.LENGTH_SHORT
                        )
                        viewModel?.updatePluginListPrivate(activity, listOf(repository))
                    } else if (list.isNotEmpty()) {
                        showToast(R.string.download_failed, Toast.LENGTH_SHORT)
                    }
                }
            }
    }

    /**
     * @param isLocal defines if the plugin data is from local data instead of repo
     * Will only allow removal of plugins. Used for the local file management.
     * */
    fun handlePluginAction(
        activity: Activity?,
        repositoryUrls: List<RepositoryData>,
        pluginWrapper: PluginWrapper,
        isLocal: Boolean
    ) = ioSafe {
        Log.i(TAG, "handlePluginAction = ${repositoryUrls}, $pluginWrapper, $isLocal")

        if (activity == null) return@ioSafe
        val (_, repositoryData, metadata) = pluginWrapper

        val file = if (isLocal) File(pluginWrapper.plugin.url) else getPluginPath(
            activity,
            pluginWrapper.plugin.internalName,
            pluginWrapper.repositoryData.url
        )

        val (success, message) = if (file.exists()) {
            PluginManager.deletePlugin(file) to R.string.plugin_deleted
        } else {
            val isEnabled = pluginWrapper.plugin.status != PROVIDER_STATUS_DOWN
            val message = if (isEnabled) R.string.plugin_loaded else R.string.plugin_downloaded
            PluginManager.downloadPlugin(
                activity,
                metadata.url,
                metadata.fileHash,
                metadata.internalName,
                repositoryData.url,
                isEnabled
            ) to message
        }

        runOnMainThread {
            if (success)
                showToast(message, Toast.LENGTH_SHORT)
            else
                showToast(R.string.error, Toast.LENGTH_SHORT)
        }

        if (success)
            if (isLocal)
                updatePluginListLocal()
            else
                updatePluginListPrivate(activity, repositoryUrls)
    }

    private suspend fun updatePluginListPrivate(context: Context, repositories: List<RepositoryData>) {
        val isAdult = PreferenceManager.getDefaultSharedPreferences(context)
            .getStringSet(context.getString(R.string.prefer_media_type_key), emptySet())
            ?.contains(TvType.NSFW.ordinal.toString()) == true

        val plugins = repositories.flatMap { repositoryUrl ->
            getPlugins(repositoryUrl)
        }

        val visible = plugins.filter {
            // Show all non-nsfw plugins or all if nsfw is enabled
            it.plugin.tvTypes?.contains(TvType.NSFW.name) != true || isAdult
        }

        // Two passes so the list is never blocked on the network: paint with
        // whatever scores are already cached (however stale), then repaint once
        // the refreshed ones land. On a warm cache both passes are identical and
        // DiffUtil settles it into a no-op.
        val cached = VotingApi.peekScores(visible.map { it.plugin.url })
        val list = visible.map { plugin ->
            PluginViewData(
                plugin,
                isDownloaded(context, plugin.plugin.internalName, plugin.repositoryData.url),
                score = cached[plugin.plugin.url],
                scoreKnown = cached.containsKey(plugin.plugin.url),
            )
        }

        this.plugins = list
        postFiltered()

        loadScores(list)
    }

    /**
     * Refresh the scores for [list] and republish it.
     *
     * VotingApi handles the fan-out: cached-and-fresh entries cost nothing and
     * the rest are queried concurrently under a fixed cap, so a 50-extension
     * repository is a handful of round trips rather than 50 serial ones.
     */
    private fun loadScores(list: List<PluginViewData>) = viewModelScope.launchSafe {
        val urls = list.mapNotNull { it.pluginWrapper.plugin.url.takeIf { url -> url.startsWith("http") } }
        if (urls.isEmpty()) return@launchSafe

        val scores = VotingApi.getScores(urls)
        if (scores.isEmpty()) return@launchSafe

        // The list may have been replaced (repo switch, local view) while the
        // scores were in flight; only apply them to the list they belong to.
        if (this@PluginsViewModel.plugins !== list) return@launchSafe

        this@PluginsViewModel.plugins = list.map { data ->
            val url = data.pluginWrapper.plugin.url
            if (scores.containsKey(url)) {
                data.copy(score = scores[url], scoreKnown = true)
            } else {
                data
            }
        }
        postFiltered()
    }

    private fun postFiltered(scrollToTop: Boolean = false) {
        _filteredPlugins.postValue(
            scrollToTop to plugins.filterTvTypes().filterLang().sortByQuery(currentQuery)
        )
    }

    // Perhaps can be optimized?
    private fun List<PluginViewData>.filterTvTypes(): List<PluginViewData> {
        if (tvTypes.isEmpty()) return this
        return this.filter {
            (it.pluginWrapper.plugin.tvTypes?.any { type -> tvTypes.contains(type) } == true) ||
                    (tvTypes.contains(TvType.Others.name) && (it.pluginWrapper.plugin.tvTypes
                        ?: emptyList()).isEmpty())
        }
    }

    private fun List<PluginViewData>.filterLang(): List<PluginViewData> {
        if (selectedLanguages.isEmpty()) return this // do not filter
        return this.filter {
            if (it.pluginWrapper.plugin.language == null) {
                return@filter selectedLanguages.contains("none")
            }
            selectedLanguages.contains(it.pluginWrapper.plugin.language.lowercase())
        }
    }

    /**
     * An extension with no score is a neutral 50%, not a 0% extension, so
     * unrated ones rank in the middle rather than at either extreme. Name breaks
     * ties, otherwise equal scores shuffle between refreshes.
     */
    private fun List<PluginViewData>.sortByOrder(): List<PluginViewData> {
        val byName = compareBy<PluginViewData> { it.pluginWrapper.plugin.name }
        return when (sortOrder) {
            PluginSortOrder.NAME_ASC -> sortedWith(byName)
            PluginSortOrder.NAME_DESC -> sortedWith(byName.reversed())
            PluginSortOrder.SCORE_DESC ->
                sortedWith(compareByDescending<PluginViewData> { it.score ?: FireScore.DEFAULT_SCORE }.then(byName))
            PluginSortOrder.SCORE_ASC ->
                sortedWith(compareBy<PluginViewData> { it.score ?: FireScore.DEFAULT_SCORE }.then(byName))
        }
    }

    private fun List<PluginViewData>.sortByQuery(query: String?): List<PluginViewData> {
        return if (query.isNullOrBlank()) {
            // Return list to base state if no query
            this.sortByOrder()
        } else {
            this.mapNotNull {
                // Try matching name
                val score = Levenshtein.partialRatio(
                    it.pluginWrapper.plugin.name.lowercase(),
                    query.lowercase()
                ).takeIf { score -> score > 80 } ?:
                // Fallback to description, but limit characters to reduce lag
                it.pluginWrapper.plugin.description?.lowercase()?.take(64)
                    ?.let { description ->
                        Levenshtein.partialRatio(
                            description,
                            query.lowercase()
                        )
                    }?.takeIf { score -> score > 80 } ?: return@mapNotNull null
                it to score
            }.sortedBy {
                -it.second
            }.map { it.first }
        }
    }

    fun updateFilteredPlugins() {
        postFiltered()
    }

    fun clear() {
        currentQuery = null
        _filteredPlugins.postValue(
            false to emptyList()
        )
    }

    fun updatePluginList(context: Context?, repositories: List<RepositoryData>) =
        viewModelScope.launchSafe {
            if (context == null) return@launchSafe
            Log.i(TAG, "updatePluginList = $repositories")
            updatePluginListPrivate(context, repositories)
        }

    fun search(query: String?) {
        currentQuery = query
        postFiltered(scrollToTop = true)
    }

    /**
     * Update the list but only with the local data. Used for file management.
     * */
    fun updatePluginListLocal() = viewModelScope.launchSafe {
        Log.i(TAG, "updatePluginList = local")

        val downloadedPlugins = (PluginManager.getPluginsOnline() + PluginManager.getPluginsLocal())
            .distinctBy { it.filePath }
            .map {
                PluginViewData(PluginWrapper.getLocalPluginWrapper(it.toSitePlugin()), true)
            }

        // No scores here: the local view lists plugins by file path, which is
        // not a votable subject.
        plugins = downloadedPlugins
        postFiltered()
    }
}
