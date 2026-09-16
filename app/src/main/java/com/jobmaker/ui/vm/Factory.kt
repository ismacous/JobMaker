package com.jobmaker.ui.vm

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jobmaker.di.AppContainer

/** Fabrique unique : le conteneur d'application est la seule dependance. */
fun vmFactory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
    initializer { ProfileViewModel(container) }
    initializer { GenerateViewModel(container) }
    initializer { DocumentsViewModel(container) }
    initializer { ExplainViewModel(container) }
    initializer { ModelsViewModel(container) }
    initializer { MoteurIaViewModel(container) }
    initializer { SettingsViewModel(container) }
}
