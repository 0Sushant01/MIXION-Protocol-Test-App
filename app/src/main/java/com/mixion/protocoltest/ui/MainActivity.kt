package com.mixion.protocoltest.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.mixion.protocoltest.ui.screens.ProtocolTestScreen
import com.mixion.protocoltest.ui.theme.MixionProtocolTestTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ProtocolTestViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MixionProtocolTestTheme {
                ProtocolTestScreen(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshUsbDevices()
    }
}
