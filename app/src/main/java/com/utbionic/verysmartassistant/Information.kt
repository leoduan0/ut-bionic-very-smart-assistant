package com.utbionic.verysmartassistant

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException

private val Application.dataStore by preferencesDataStore(name = "information")

class Information(application: Application) : AndroidViewModel(application) {
    private val dataStore = application.dataStore

    private val momNumberKey = stringPreferencesKey("mom_number")
    private val pswNumberKey = stringPreferencesKey("psw_number")
    private val controllerAddressKey = stringPreferencesKey("controller_address")
    private val wifiPasswordKey = stringPreferencesKey("wifi_password")

    var momNumber by mutableStateOf("1234567890")
        private set
    var pswNumber by mutableStateOf("1234567890")
        private set
    var controllerAddress by mutableStateOf("very-smart-controller.local")
        private set
    var wifiPassword by mutableStateOf("")
        private set
    var isLoaded by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            dataStore.data.catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }.map { prefs -> prefs.toInfoState() }.collect { state ->
                momNumber = state.momNumber
                pswNumber = state.pswNumber
                controllerAddress = state.controllerAddress
                wifiPassword = state.wifiPassword
                isLoaded = true
            }
        }
    }

    fun updateMomNumber(newMomNumber: String) {
        momNumber = newMomNumber
        persist { it[momNumberKey] = newMomNumber }
    }

    fun updatePswNumber(newPswNumber: String) {
        pswNumber = newPswNumber
        persist { it[pswNumberKey] = newPswNumber }
    }

    fun updateControllerAddress(newControllerAddress: String) {
        controllerAddress = newControllerAddress
        persist { it[controllerAddressKey] = newControllerAddress }
    }

    fun updateWifiPassword(newWifiPassword: String) {
        wifiPassword = newWifiPassword
        persist { it[wifiPasswordKey] = newWifiPassword }
    }

    private fun persist(block: (MutablePreferences) -> Unit) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                block(prefs)
            }
        }
    }

    private fun Preferences.toInfoState(): InfoState {
        return InfoState(
            momNumber = this[momNumberKey] ?: "1234567890",
            pswNumber = this[pswNumberKey] ?: "1234567890",
            controllerAddress = this[controllerAddressKey] ?: "very-smart-controller.local",
            wifiPassword = this[wifiPasswordKey] ?: "",
        )
    }

    private data class InfoState(
        val momNumber: String,
        val pswNumber: String,
        val controllerAddress: String,
        val wifiPassword: String,
    )
}