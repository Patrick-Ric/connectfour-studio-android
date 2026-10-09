package io.github.patrickric.connectfourstudio

import android.content.Context
import android.content.res.ColorStateList
import android.os.Build

/** Theme colour from res/values(-night)/colors.xml (Context.getColor needs API 23). */
fun Context.col(id: Int): Int =
    if (Build.VERSION.SDK_INT >= 23) getColor(id) else @Suppress("DEPRECATION") resources.getColor(id)

fun Context.colList(id: Int): ColorStateList =
    if (Build.VERSION.SDK_INT >= 23) getColorStateList(id) else @Suppress("DEPRECATION") resources.getColorStateList(id)
