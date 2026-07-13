package com.lagradost.cloudstream3.ui.settings.extensions

import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.marginBottom
import androidx.core.view.marginTop
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainActivity.Companion.afterRepositoryLoadedEvent
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.AddRepoInputBinding
import com.lagradost.cloudstream3.databinding.FragmentRepositoriesBinding
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.result.FOCUS_SELF
import com.lagradost.cloudstream3.ui.result.setLinearListLayout
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setSystemBarsPadding
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setToolBarScrollFlags
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setUpToolbar
import com.lagradost.cloudstream3.utils.AppContextUtils.addRepositoryDialog
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.UIHelper.hideProgress
import com.lagradost.cloudstream3.utils.UIHelper.showProgress

class RepositoriesFragment : BaseFragment<FragmentRepositoriesBinding>(
    BaseFragment.BindingCreator.Inflate(FragmentRepositoriesBinding::inflate)
) {

    private val extensionViewModel: ExtensionsViewModel by activityViewModels()

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

    override fun onBindingCreated(binding: FragmentRepositoriesBinding) {
        setUpToolbar(R.string.repositories)
        setToolBarScrollFlags()

        binding.repoRecyclerView.apply {
            setLinearListLayout(
                isHorizontal = false,
                nextUp = R.id.settings_toolbar,
                nextDown = R.id.add_repo_button_imageview,
                nextRight = FOCUS_SELF,
                nextLeft = R.id.nav_rail_view
            )

            if (!isLayout(TV))
                binding.addRepoButton.let { button ->
                    button.post {
                        setPadding(
                            paddingLeft,
                            paddingTop,
                            paddingRight,
                            button.measuredHeight + button.marginTop + button.marginBottom
                        )
                    }
                }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                    val dy = scrollY - oldScrollY
                    if (dy > 0) { // check for scroll down
                        binding.addRepoButton.shrink() // hide
                    } else if (dy < -5) {
                        binding.addRepoButton.extend() // show
                    }
                }
            }

            adapter = RepoAdapter(false, { repo ->
                findNavController().navigate(
                    R.id.navigation_settings_repositories_to_navigation_settings_plugins,
                    PluginsFragment.newInstance(repo)
                )
            }, { repo ->
                // Prompt user before deleting repo
                main {
                    val uiContext = context ?: binding.root.context
                    val builder = AlertDialog.Builder(uiContext)
                    val dialogClickListener =
                        DialogInterface.OnClickListener { _, which ->
                            when (which) {
                                DialogInterface.BUTTON_POSITIVE -> {
                                    ioSafe {
                                        RepositoryManager.removeRepository(
                                            uiContext.applicationContext,
                                            repo
                                        )
                                        extensionViewModel.loadStats()
                                        extensionViewModel.loadRepositories()
                                    }
                                }

                                DialogInterface.BUTTON_NEGATIVE -> {}
                            }
                        }

                    builder.setTitle(R.string.delete_repository)
                        .setMessage(uiContext.getString(R.string.delete_repository_plugins))
                        .setPositiveButton(R.string.delete, dialogClickListener)
                        .setNegativeButton(R.string.cancel, dialogClickListener)
                        .show().setDefaultFocus()
                }
            })
        }

        observe(extensionViewModel.repositories) { repos ->
            binding.repoRecyclerView.isVisible = repos.isNotEmpty()
            binding.blankRepoScreen.isVisible = repos.isEmpty()
            (binding.repoRecyclerView.adapter as? RepoAdapter)?.submitList(repos.toList())
        }

        val addRepositoryClick = View.OnClickListener {
            val ctx = context ?: return@OnClickListener
            val dialogBinding = AddRepoInputBinding.inflate(LayoutInflater.from(ctx), null, false)
            val builder =
                AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
                    .setView(dialogBinding.root)

            val dialog = builder.create()
            dialog.show()
            (activity?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip?.getItemAt(
                0
            )?.text?.toString()?.let { copiedText ->
                if (copiedText.contains(RepoAdapter.SHAREABLE_REPO_SEPARATOR)) {
                    // text is of format <repository name> : <repository url>
                    val (name, url) = copiedText.split(
                        RepoAdapter.SHAREABLE_REPO_SEPARATOR,
                        limit = 2
                    )
                    dialogBinding.repoUrlInput.setText(url.trim())
                    dialogBinding.repoNameInput.setText(name.trim())
                } else {
                    dialogBinding.repoUrlInput.setText(copiedText)
                }
            }

            dialogBinding.applyBtt.setOnClickListener secondListener@{
                val name = dialogBinding.repoNameInput.text?.toString()
                val urlInput = dialogBinding.repoUrlInput.text?.toString()
                if (urlInput.isNullOrEmpty()) {
                    showToast(R.string.error_invalid_url, Toast.LENGTH_SHORT)
                    return@secondListener
                }
                dialogBinding.applyBtt.showProgress()
                ioSafe {
                    try {
                        val url = RepositoryManager.parseRepoUrl(urlInput)
                        if (url.isNullOrBlank()) {
                            showToast(R.string.error_invalid_data, Toast.LENGTH_SHORT)
                            return@ioSafe
                        }
                        val repository = RepositoryManager.parseRepository(url)

                        // Exit if wrong repository
                        if (repository == null) {
                            showToast(R.string.no_repository_found_error, Toast.LENGTH_LONG)
                            return@ioSafe
                        }

                        val fixedName = if (!name.isNullOrBlank()) name
                        else repository.name
                        val newRepo = RepositoryData(repository.iconUrl, fixedName, url)
                        RepositoryManager.addRepository(newRepo)
                        extensionViewModel.loadStats()
                        extensionViewModel.loadRepositories()

                        dialog.dismissSafe(activity) // Only dismiss if the repo was added

                        val plugins = RepositoryManager.getRepoPlugins(newRepo)
                        if (plugins.isNullOrEmpty()) {
                            showToast(R.string.no_plugins_found_error, Toast.LENGTH_LONG)
                            return@ioSafe
                        }

                        this@RepositoriesFragment.activity?.addRepositoryDialog(
                            newRepo
                        )
                    } finally {
                        dialogBinding.applyBtt.hideProgress()
                    }
                }
            }
            dialogBinding.cancelBtt.setOnClickListener {
                dialog.dismissSafe(activity)
            }
        }

        val isTv = isLayout(TV)
        binding.apply {
            addRepoButton.isGone = isTv
            addRepoAppbar.isVisible = isTv

            // Band-aid for Fire TV
            addRepoButtonImageview.isFocusableInTouchMode = isTv

            addRepoButton.setOnClickListener(addRepositoryClick)
            addRepoButtonImageview.setOnClickListener(addRepositoryClick)
        }

        reloadRepositories()
    }
}
