package com.lagradost.cloudstream3.ui.settings.extensions

import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.MainActivity.Companion.afterRepositoryLoadedEvent
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FragmentExtensionsBinding
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.mvvm.observeNullable
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.result.FOCUS_SELF
import com.lagradost.cloudstream3.ui.result.setLinearListLayout
import com.lagradost.cloudstream3.ui.setRecycledViewPool
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setSystemBarsPadding
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setToolBarScrollFlags
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setUpToolbar
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiProviderLangSettings
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showMultiDialog
import com.lagradost.cloudstream3.utils.SubtitleHelper.getNameNextToFlagEmoji
import com.lagradost.cloudstream3.utils.setText

class ExtensionsFragment : BaseFragment<FragmentExtensionsBinding>(
    BaseFragment.BindingCreator.Inflate(FragmentExtensionsBinding::inflate)
) {

    private val extensionViewModel: ExtensionsViewModel by activityViewModels()
    private val pluginViewModel: PluginsViewModel by activityViewModels()

    private fun View.setLayoutWidth(weight: Int) {
        val param = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.MATCH_PARENT,
            weight.toFloat()
        )
        this.layoutParams = param
    }

    override fun onResume() {
        super.onResume()
        afterRepositoryLoadedEvent += ::reloadRepositories
    }

    override fun onStop() {
        super.onStop()
        afterRepositoryLoadedEvent -= ::reloadRepositories
    }

    private fun reloadRepositories(success: Boolean = true) {
        extensionViewModel.loadStats()
        extensionViewModel.loadRepositories()
    }

    override fun fixLayout(view: View) {
        setSystemBarsPadding()
    }

    private fun navigateToRepositories() {
        findNavController().navigate(R.id.navigation_settings_extensions_to_navigation_settings_repositories)
    }

    /** Language filter defaults to the languages selected in the provider settings */
    private fun setDefaultLanguages() {
        if (pluginViewModel.hasSetDefaultLanguages) return
        val providerLangs = activity?.getApiProviderLangSettings()?.toList() ?: return
        if (!providerLangs.contains(AllLanguagesName)) {
            pluginViewModel.selectedLanguages = mutableListOf("none") + providerLangs
        }
        pluginViewModel.hasSetDefaultLanguages = true
    }

    private fun showLanguageDialog() {
        val languagesTagName = pluginViewModel.pluginLanguages
            .map { langTag ->
                Pair(
                    langTag,
                    getNameNextToFlagEmoji(langTag) ?: langTag
                )
            }
            .sortedBy {
                it.second.substringAfter(" ").lowercase()
            } // name ignoring flag emoji
            .toMutableList()

        // Move "none" to 1st position as it's special code to indicate unknown/missing language
        if (languagesTagName.remove(Pair("none", "none"))) {
            languagesTagName.add(0, Pair("none", getString(R.string.no_data)))
        }

        val currentIndexList = pluginViewModel.selectedLanguages.map { langTag ->
            languagesTagName.indexOfFirst { lang -> lang.first == langTag }
        }

        activity?.showMultiDialog(
            languagesTagName.map { it.second },
            currentIndexList,
            getString(R.string.provider_lang_settings),
            {}
        ) { selectedList ->
            pluginViewModel.selectedLanguages = selectedList.map { languagesTagName[it].first }
            pluginViewModel.updateFilteredPlugins()
        }
    }

    override fun onBindingCreated(binding: FragmentExtensionsBinding) {
        setUpToolbar(R.string.extensions, showBackButton = false)
        setToolBarScrollFlags()
        setDefaultLanguages()

        binding.pluginRecyclerView.apply {
            setLinearListLayout(
                isHorizontal = false,
                nextUp = R.id.settings_toolbar,
                nextDown = R.id.plugin_storage_appbar,
                nextRight = FOCUS_SELF,
                nextLeft = R.id.nav_rail_view
            )
            setRecycledViewPool(PluginAdapter.sharedPool)
            adapter = PluginAdapter(showRepositoryNames = true) { plugin ->
                val repositories = extensionViewModel.repositories.value?.toList() ?: emptyList()
                pluginViewModel.handlePluginAction(activity, repositories, plugin, false)
            }
        }

        observe(extensionViewModel.repositories) { repos ->
            binding.pluginRecyclerView.isVisible = repos.isNotEmpty()
            binding.blankRepoScreen.isVisible = repos.isEmpty()
            pluginViewModel.updatePluginList(binding.root.context, repos.toList())
        }

        observe(pluginViewModel.filteredPlugins) { (scrollToTop, list) ->
            (binding.pluginRecyclerView.adapter as? PluginAdapter)?.submitList(list)
            if (scrollToTop) {
                binding.pluginRecyclerView.scrollToPosition(0)
            }
        }

        observeNullable(extensionViewModel.pluginStats) { value ->
            binding.apply {
                if (value == null) {
                    pluginStorageAppbar.isVisible = false
                    return@observeNullable
                }

                pluginStorageAppbar.isVisible = true
                if (value.total == 0) {
                    pluginDownload.setLayoutWidth(1)
                    pluginDisabled.setLayoutWidth(0)
                    pluginNotDownloaded.setLayoutWidth(0)
                } else {
                    pluginDownload.setLayoutWidth(value.downloaded)
                    pluginDisabled.setLayoutWidth(value.disabled)
                    pluginNotDownloaded.setLayoutWidth(value.notDownloaded)
                }
                pluginNotDownloadedTxt.setText(value.notDownloadedText)
                pluginDisabledTxt.setText(value.disabledText)
                pluginDownloadTxt.setText(value.downloadedText)
            }
        }

        binding.pluginStorageAppbar.setOnClickListener {
            findNavController().navigate(
                R.id.navigation_settings_extensions_to_navigation_settings_plugins,
                PluginsFragment.newLocalInstance(
                    getString(R.string.extensions),
                )
            )
        }

        binding.settingsToolbar.apply {
            setOnMenuItemClickListener { menuItem ->
                when (menuItem?.itemId) {
                    R.id.lang_filter -> showLanguageDialog()
                    R.id.repositories -> navigateToRepositories()
                    else -> {}
                }
                return@setOnMenuItemClickListener true
            }

            val searchView = menu?.findItem(R.id.search_button)?.actionView as? SearchView

            // Don't go back if active query
            setNavigationOnClickListener {
                if (searchView?.isIconified == false) {
                    searchView.isIconified = true
                } else {
                    dispatchBackPressed()
                }
            }

            searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    pluginViewModel.search(query)
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    pluginViewModel.search(newText)
                    return true
                }
            })
        }

        val isTv = isLayout(TV)
        binding.apply {
            repositoriesButtonHolder.isVisible = isTv

            // Band-aid for Fire TV
            pluginStorageAppbar.isFocusableInTouchMode = isTv
            repositoriesButtonImageview.isFocusableInTouchMode = isTv

            repositoriesButtonImageview.setOnClickListener { navigateToRepositories() }
            blankRepositoriesButton.setOnClickListener { navigateToRepositories() }
        }

        reloadRepositories()
    }
}
