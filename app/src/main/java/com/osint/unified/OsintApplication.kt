package com.osint.unified

import android.app.Application
import com.osint.unified.data.db.AppDatabase
import com.osint.unified.domain.ObservableRouter

/**
 * Minimal manual DI container. Hilt can replace this later; for Phase 1 a
 * hand-rolled container keeps the first cloud build simple and green.
 */
class OsintApplication : Application() {
    val database: AppDatabase by lazy { AppDatabase.get(this) }
    val router: ObservableRouter by lazy { ObservableRouter() }
}
