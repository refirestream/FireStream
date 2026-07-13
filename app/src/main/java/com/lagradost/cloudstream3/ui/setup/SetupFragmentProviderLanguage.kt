package com.lagradost.cloudstream3.ui.setup

import android.view.View
import android.widget.AbsListView
import android.widget.ArrayAdapter
import androidx.core.content.edit
import androidx.core.util.forEach
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.databinding.FragmentSetupProviderLanguagesBinding
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.settings.appLanguages
import com.lagradost.cloudstream3.ui.settings.nameNextToFlagEmoji
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiProviderLangSettings
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding

class SetupFragmentProviderLanguage : BaseFragment<FragmentSetupProviderLanguagesBinding>(
    BaseFragment.BindingCreator.Inflate(FragmentSetupProviderLanguagesBinding::inflate)
) {

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(view)
    }

    override fun onBindingCreated(binding: FragmentSetupProviderLanguagesBinding) {
        safe {
            val ctx = context ?: return@safe

            val settingsManager = PreferenceManager.getDefaultSharedPreferences(ctx)

            val arrayAdapter =
                ArrayAdapter<String>(ctx, R.layout.sort_bottom_single_choice)

            val currentLangTags = ctx.getApiProviderLangSettings()

            // Providers report their language as a primary subtag ("pt"), never as a regional
            // variant ("pt-BR"), so those variants are collapsed away to keep every entry selectable.
            val languagesTagName =
                listOf(Pair(AllLanguagesName, getString(R.string.all_languages_preference))) +
                        appLanguages
                            .distinctBy { it.second.substringBefore('-') }
                            .map { Pair(it.second.substringBefore('-'), it.nameNextToFlagEmoji()) }

            val currentIndexList = currentLangTags.map { langTag ->
                languagesTagName.indexOfFirst { lang -> lang.first == langTag }
            }.filter { it > -1 }

            arrayAdapter.addAll(languagesTagName.map { it.second })
            binding.apply {
                listview1.adapter = arrayAdapter
                listview1.choiceMode = AbsListView.CHOICE_MODE_MULTIPLE
                currentIndexList.forEach {
                    listview1.setItemChecked(it, true)
                }

                listview1.setOnItemClickListener { _, _, _, _ ->
                    val selectedLanguages = mutableSetOf<String>()
                    listview1.checkedItemPositions?.forEach { key, value ->
                        if (value) selectedLanguages.add(languagesTagName[key].first)
                    }
                    settingsManager.edit {
                        putStringSet(
                            ctx.getString(R.string.provider_lang_key),
                            selectedLanguages.toSet()
                        )
                    }
                }

                nextBtt.setOnClickListener {
                    // If no plugins go to plugins page
                    if (
                        PluginManager.getPluginsOnline().isEmpty()
                        && PluginManager.getPluginsLocal().isEmpty()
                    ) {
                        findNavController().navigate(
                            R.id.action_navigation_global_to_navigation_setup_extensions,
                            SetupFragmentExtensions.newInstance(true)
                        )
                    } else {
                        findNavController().navigate(R.id.navigation_setup_provider_languages_to_navigation_setup_media)
                    }
                }

                prevBtt.setOnClickListener {
                    findNavController().popBackStack()
                }
            }
        }
    }
}
