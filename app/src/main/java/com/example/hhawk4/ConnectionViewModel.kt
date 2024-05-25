package com.example.hhawk4

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import dji.common.error.DJIError
import dji.common.error.DJISDKError
import dji.common.util.CommonCallbacks
import dji.sdk.base.BaseComponent
import dji.sdk.base.BaseProduct
import dji.sdk.sdkmanager.DJISDKInitEvent
import dji.sdk.sdkmanager.DJISDKManager
import android.widget.Toast

/*
This ViewModel stores important variables and functions needed for mobile SDK registration
and connection to the DJI product. This allows the app to maintain its connection state
across rotation death.
 */
class ConnectionViewModel(application: Application) : AndroidViewModel(application) {

    // Product is a BaseProduct object which stores an instance of the currently connected DJI product
    val product: MutableLiveData<BaseProduct?> by lazy {
        MutableLiveData<BaseProduct?>()
    }

    // Connection status boolean describes whether or not a DJI product is connected
    val connectionStatus: MutableLiveData<Boolean> = MutableLiveData(false)

    // DJI SDK app registration
    fun registerApp() {
        DJISDKManager.getInstance().registerApp(getApplication(), object : DJISDKManager.SDKManagerCallback {
            override fun onRegister(error: DJIError?) {
                if (error == DJISDKError.REGISTRATION_SUCCESS) {
                    Log.i(ConnectionActivity.TAG, "onRegister: Registration Successful")
                    enableLDMIfSupported()
                } else {
                    Log.i(ConnectionActivity.TAG, "onRegister: Registration Failed - ${error?.description}")
                    showToast("Registration Failed: ${error?.description}")
                }
            }

            override fun onProductDisconnect() {
                Log.i(ConnectionActivity.TAG, "onProductDisconnect: Product Disconnected")
                connectionStatus.postValue(false)
            }

            override fun onProductConnect(baseProduct: BaseProduct?) {
                Log.i(ConnectionActivity.TAG, "onProductConnect: Product Connected")
                product.postValue(baseProduct)
                connectionStatus.postValue(true)
            }

            override fun onProductChanged(baseProduct: BaseProduct?) {
                Log.i(ConnectionActivity.TAG, "onProductChanged: Product Changed - $baseProduct")
                product.postValue(baseProduct)
            }

            override fun onComponentChange(componentKey: BaseProduct.ComponentKey?, oldComponent: BaseComponent?, newComponent: BaseComponent?) {
                Log.i(ConnectionActivity.TAG, "onComponentChange key: $componentKey, oldComponent: $oldComponent, newComponent: $newComponent")
                newComponent?.setComponentListener { connected ->
                    Log.i(ConnectionActivity.TAG, "onComponentConnectivityChange: $connected")
                }
            }

            override fun onInitProcess(event: DJISDKInitEvent?, totalProcess: Int) {}

            override fun onDatabaseDownloadProgress(current: Long, total: Long) {}
        })
    }
    // Function to enable LDM if it is supported
    private fun enableLDMIfSupported() {
        val ldmManager = DJISDKManager.getInstance().ldmManager
        Log.i(ConnectionActivity.TAG, "Checking if LDM is supported...")
        if (ldmManager.isLDMSupported) {
            Log.i(ConnectionActivity.TAG, "LDM is supported. Enabling LDM...")
            ldmManager.enableLDM(object : CommonCallbacks.CompletionCallback<DJIError> {
                override fun onResult(error: DJIError?) {
                    if (error == null) {
                        Log.i(ConnectionActivity.TAG, "LDM Enabled Successfully")
                        showToast("LDM Enabled Successfully")
                    } else {
                        Log.e(ConnectionActivity.TAG, "Failed to enable LDM: ${error.description}")
                        showToast("Failed to enable LDM: ${error.description}")
                    }
                }
            })
        } else {
            Log.i(ConnectionActivity.TAG, "LDM is not supported on this device")
            showToast("LDM is not supported on this device")
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_LONG).show()
    }
}


