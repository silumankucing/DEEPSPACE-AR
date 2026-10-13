package com.example.deepspace

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Auto-save: project otomatis tersimpan setiap [INTERVAL_MS] (10 menit).
 *
 * Item *Auto-Save* di halaman Settings sudah dihapus, jadi auto-save tidak lagi
 * bisa dimatikan user: [isEnabled] selalu `true`. Flag lama di prefs (bila user
 * pernah mematikan auto-save sebelum item itu dihapus) sengaja diabaikan supaya
 * project tidak diam-diam berhenti tersimpan otomatis.
 */
object AutoSaveManager {

    /** Interval auto-save: 10 menit. */
    const val INTERVAL_MS = 10 * 60 * 1000L

    /** Auto-save selalu aktif (tidak ada lagi toggle di halaman Settings). */
    fun isEnabled(ctx: Context): Boolean = true

    /**
     * Jadwalkan [save] agar dipanggil berulang tiap [INTERVAL_MS], hanya bila
     * auto-save aktif. Mengembalikan pasangan (Handler, Runnable) untuk [cancel].
     */
    fun schedule(ctx: Context, save: () -> Unit): Pair<Handler, Runnable> {
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                try {
                    if (isEnabled(ctx)) save()
                } catch (_: Exception) {
                }
                handler.postDelayed(this, INTERVAL_MS)
            }
        }
        handler.postDelayed(runnable, INTERVAL_MS)
        return handler to runnable
    }

    fun cancel(job: Pair<Handler, Runnable>?) {
        job?.let { (handler, runnable) -> handler.removeCallbacks(runnable) }
    }
}
