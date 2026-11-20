package com.peretz.barkannav

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

class MainActivity : ComponentActivity() {
    private lateinit var store: Store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(applicationContext)
        handleShare(intent)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                NavScreen(store)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** A place shared from Google Maps is saved as a lot and selected. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val lot = Store.lotFromShared(text)
        if (lot == null) {
            Toast.makeText(this, "לא הצלחתי לזהות שם של מקום", Toast.LENGTH_SHORT).show()
            return
        }
        store.addLot(lot)
        Toast.makeText(this, "החניון נשמר: ${lot.name}", Toast.LENGTH_SHORT).show()
        setIntent(Intent(this, MainActivity::class.java))
    }
}
