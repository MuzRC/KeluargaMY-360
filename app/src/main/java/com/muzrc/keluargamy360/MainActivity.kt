package com.muzrc.keluargamy360

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.muzrc.keluargamy360.ui.navigation.AppNavigation
import com.muzrc.keluargamy360.ui.theme.KeluargaMY360Theme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KeluargaMY360Theme {
                AppNavigation()
            }
        }
    }
}
