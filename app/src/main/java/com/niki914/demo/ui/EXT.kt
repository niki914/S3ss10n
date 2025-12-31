package com.niki914.demo.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

val composableContext: Context
    @Composable
    get() {
        return LocalContext.current
    }

val Int.resString: String
    @Composable
    get() {
        return stringResource(this)
    }