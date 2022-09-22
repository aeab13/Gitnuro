package app.viewmodels

import app.git.RefreshType
import app.git.TabState
import app.git.submodules.GetSubmodulesUseCase
import app.git.submodules.InitializeSubmoduleUseCase
import app.git.submodules.UpdateSubmoduleUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.submodule.SubmoduleStatus
import javax.inject.Inject

class SubmodulesViewModel @Inject constructor(
    private val tabState: TabState,
    private val getSubmodulesUseCase: GetSubmodulesUseCase,
    private val initializeSubmoduleUseCase: InitializeSubmoduleUseCase,
    private val updateSubmoduleUseCase: UpdateSubmoduleUseCase,
) : ExpandableViewModel() {
    private val _submodules = MutableStateFlow<List<Pair<String, SubmoduleStatus>>>(listOf())
    val submodules: StateFlow<List<Pair<String, SubmoduleStatus>>>
        get() = _submodules

    private suspend fun loadSubmodules(git: Git) {
        _submodules.value = getSubmodulesUseCase(git).toList()
    }

    fun initializeSubmodule(path: String) = tabState.safeProcessing(
        showError = true,
        refreshType = RefreshType.SUBMODULES,
    ) { git ->
        initializeSubmoduleUseCase(git, path)
        updateSubmoduleUseCase(git, path)
    }

    suspend fun refresh(git: Git) {
        loadSubmodules(git)
    }
}