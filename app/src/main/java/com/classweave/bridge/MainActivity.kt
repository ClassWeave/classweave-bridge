package com.classweave.bridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.classweave.bridge.permissions.PermissionHelper
import com.classweave.bridge.ui.MainScreen
import com.classweave.bridge.ui.MainViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        
        setContent {
            MaterialTheme {
                val viewModel: MainViewModel = viewModel()

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { results ->
                    viewModel.onPermissionsResult(results)
                }

                LaunchedEffect(Unit) {
                    if (!PermissionHelper.allGranted(this@MainActivity)) {
                        permissionLauncher.launch(
                            PermissionHelper.requiredPermissions().toTypedArray()
                        )
                    }
                }

                MainScreen(viewModel = viewModel)
            }
        }
    }
}
