package com.utbionic.verysmartassistant

import androidx.lifecycle.ViewModel

// TODO implement persistent storage
class Information : ViewModel() {
    var momNumber = "1234567890"
    var pswNumber = "1234567890"
    var controllerAddress = "very-smart-controller.local"

    fun updateMomNumber(newMomNumber: String) {
        momNumber = newMomNumber
    }

    fun updatePswNumber(newPswNumber: String) {
        pswNumber = newPswNumber
    }
}