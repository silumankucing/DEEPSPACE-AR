package com.example.deepspace

import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import java.io.File

fun showImagePreviewDialog(ctx: android.content.Context, imageFile: java.io.File) {
    val bmp = BitmapFactory.decodeFile(imageFile.absolutePath)
    if (bmp == null) {
        Toast.makeText(ctx, "Cannot open image", Toast.LENGTH_SHORT).show()
        return
    }

    val rootLayout = android.widget.FrameLayout(ctx)
    rootLayout.layoutParams = android.widget.FrameLayout.LayoutParams(
        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
    )
    rootLayout.setPadding(0, 0, 0, 0)

    val card = android.widget.FrameLayout(ctx)
    card.background = null
    card.layoutParams = android.widget.FrameLayout.LayoutParams(
        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
    )

    val imageView = android.widget.ImageView(ctx)
    val imageWidth = bmp.width
    val imageHeight = bmp.height
    val displayHeight = if (imageHeight > 0) {
        (dpToPx2(ctx, 320).toFloat() * imageHeight / imageWidth.toFloat()).toInt().coerceAtLeast(dpToPx2(ctx, 180))
    } else {
        dpToPx2(ctx, 320)
    }
    imageView.layoutParams = android.widget.FrameLayout.LayoutParams(
        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
        displayHeight
    )
    imageView.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
    imageView.setImageBitmap(bmp)
    imageView.setPadding(0, 0, 0, 0)
    imageView.background = null
    card.addView(imageView)

    val btnClose = android.widget.ImageButton(ctx)
    btnClose.layoutParams = android.widget.FrameLayout.LayoutParams(
        dpToPx2(ctx, 40), dpToPx2(ctx, 40)
    ).apply {
        gravity = android.view.Gravity.TOP or android.view.Gravity.END
        topMargin = dpToPx2(ctx, 10)
        marginEnd = dpToPx2(ctx, 10)
    }
    btnClose.setImageResource(R.drawable.icon_cancel)
    btnClose.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
    btnClose.setBackgroundResource(R.drawable.circle_button_bg)
    btnClose.setColorFilter(android.graphics.Color.WHITE)
    btnClose.setPadding(dpToPx2(ctx, 8), dpToPx2(ctx, 8), dpToPx2(ctx, 8), dpToPx2(ctx, 8))

    val btnShare = android.widget.ImageButton(ctx)
    btnShare.layoutParams = android.widget.FrameLayout.LayoutParams(
        dpToPx2(ctx, 40), dpToPx2(ctx, 40)
    ).apply {
        gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
        bottomMargin = dpToPx2(ctx, 10)
        marginEnd = dpToPx2(ctx, 10)
    }
    btnShare.setImageResource(R.drawable.icon_share)
    btnShare.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
    btnShare.setBackgroundResource(R.drawable.circle_button_bg)
    btnShare.setColorFilter(android.graphics.Color.WHITE)
    btnShare.setPadding(dpToPx2(ctx, 8), dpToPx2(ctx, 8), dpToPx2(ctx, 8), dpToPx2(ctx, 8))

    card.addView(btnClose)
    card.addView(btnShare)
    rootLayout.addView(card)

    val dialog = android.app.AlertDialog.Builder(ctx)
        .setTitle("")
        .setView(rootLayout)
        .create()

    btnClose.setOnClickListener { dialog.dismiss() }

    btnShare.setOnClickListener {
        try {
            val uri = android.net.Uri.fromFile(imageFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(intent, "Share Image"))
        } catch (e: Exception) {
            Toast.makeText(ctx, "Cannot share image", Toast.LENGTH_SHORT).show()
        }
        dialog.dismiss()
    }

    dialog.show()
    dialog.window?.let { window ->
        window.setBackgroundDrawableResource(android.R.color.transparent)
        val popupWidth = (ctx.resources.displayMetrics.widthPixels * 0.90f).toInt()
        window.setLayout(popupWidth, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

fun showExportPreviewDialog(ctx: android.content.Context, file: java.io.File) {
    val ext = file.extension.lowercase()
    val isImage = ext == "png" || ext == "jpg" || ext == "jpeg"

    val dialogLayout = android.widget.LinearLayout(ctx)
    dialogLayout.orientation = android.widget.LinearLayout.VERTICAL
    dialogLayout.setBackgroundColor(DeepspaceTheme.surfaceColor(ctx))

    if (isImage) {
        val bmp = BitmapFactory.decodeFile(file.absolutePath)
        val imageView = android.widget.ImageView(ctx)
        imageView.layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.height = dpToPx2(ctx, 320) }
        imageView.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        if (bmp != null) imageView.setImageBitmap(bmp)
        else imageView.setImageResource(R.drawable.icon_capture)
        imageView.setPadding(0, dpToPx2(ctx, 8), 0, 0)
        dialogLayout.addView(imageView)
    } else {
        val iconView = android.widget.ImageView(ctx)
        iconView.layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx2(ctx, 120)
        ).also { it.topMargin = dpToPx2(ctx, 16) }
        iconView.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        iconView.setImageResource(R.drawable.icon_export)
        dialogLayout.addView(iconView)

        val fileNameText = android.widget.TextView(ctx)
        fileNameText.text = file.name
        fileNameText.textSize = 14f
        fileNameText.setTextColor(DeepspaceTheme.textPrimaryColor(ctx))
        fileNameText.gravity = android.view.Gravity.CENTER
        fileNameText.setPadding(dpToPx2(ctx, 16), dpToPx2(ctx, 8), dpToPx2(ctx, 16), 0)
        dialogLayout.addView(fileNameText)

        val fileSizeText = android.widget.TextView(ctx)
        fileSizeText.text = formatFileSize(file.length())
        fileSizeText.textSize = 12f
        fileSizeText.setTextColor(DeepspaceTheme.textSecondaryColor(ctx))
        fileSizeText.gravity = android.view.Gravity.CENTER
        dialogLayout.addView(fileSizeText)
    }

    val btnLayout = android.widget.LinearLayout(ctx)
    btnLayout.orientation = android.widget.LinearLayout.HORIZONTAL
    btnLayout.gravity = android.view.Gravity.CENTER
    btnLayout.layoutParams = android.widget.LinearLayout.LayoutParams(
        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
    )
    btnLayout.setPadding(0, dpToPx2(ctx, 12), 0, 0)

    val btnClose = com.google.android.material.button.MaterialButton(ctx)
    btnClose.layoutParams = android.widget.LinearLayout.LayoutParams(
        0, dpToPx2(ctx, 48)
    ).also { it.weight = 1f; it.setMargins(0, 0, dpToPx2(ctx, 4), 0) }
    btnClose.text = "Close"
    btnClose.isAllCaps = false
    btnClose.textSize = 14f
    btnClose.setTypeface(null, android.graphics.Typeface.BOLD)
    btnClose.cornerRadius = dpToPx2(ctx, 12)
    btnClose.setTextColor(android.graphics.Color.WHITE)
    btnClose.setBackgroundColor(android.graphics.Color.parseColor("#888888"))

    val btnShare = com.google.android.material.button.MaterialButton(ctx)
    btnShare.layoutParams = android.widget.LinearLayout.LayoutParams(
        0, dpToPx2(ctx, 48)
    ).also { it.weight = 1f; it.setMargins(dpToPx2(ctx, 4), 0, 0, 0) }
    btnShare.text = "Share"
    btnShare.isAllCaps = false
    btnShare.textSize = 14f
    btnShare.setTypeface(null, android.graphics.Typeface.BOLD)
    btnShare.cornerRadius = dpToPx2(ctx, 12)
    btnShare.setTextColor(android.graphics.Color.WHITE)
    btnShare.setBackgroundColor(android.graphics.Color.parseColor("#3D93C8"))

    btnLayout.addView(btnClose)
    btnLayout.addView(btnShare)
    dialogLayout.addView(btnLayout)

    val dialog = android.app.AlertDialog.Builder(ctx)
        .setTitle("")
        .setView(dialogLayout)
        .create()

    btnClose.setOnClickListener { dialog.dismiss() }

    btnShare.setOnClickListener {
        try {
            val uri = android.net.Uri.fromFile(file)
            val mimeType = if (isImage) "image/png" else "*/*"
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(intent, "Share ${file.name}"))
        } catch (e: Exception) {
            Toast.makeText(ctx, "Cannot share file", Toast.LENGTH_SHORT).show()
        }
        dialog.dismiss()
    }

    dialog.show()
    dialog.window?.let { window ->
        // Latar popup mengikuti toggle Dark Mode di Setting (light: putih, dark: dark_surface).
        window.setBackgroundDrawable(DeepspaceTheme.popupBackground(ctx, 0f))
        val popupWidth = (ctx.resources.displayMetrics.widthPixels * 0.85f).toInt()
        window.setLayout(popupWidth, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

fun dpToPx2(ctx: android.content.Context, valueDp: Int): Int {
    return android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_DIP,
        valueDp.toFloat(),
        ctx.resources.displayMetrics,
    ).toInt()
}

fun formatFileSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> String.format("%.1f KB", size / 1024.0)
        else -> String.format("%.1f MB", size / (1024.0 * 1024.0))
    }
}
