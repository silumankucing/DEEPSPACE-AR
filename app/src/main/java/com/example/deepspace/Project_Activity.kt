package com.example.deepspace

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import java.io.File

class Project_Activity : BaseFullscreenActivity() {

    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_project)

        tabLayout = findViewById(R.id.libraryTabLayout)
        viewPager = findViewById(R.id.libraryViewPager)

        // Setup ViewPager with adapter
        viewPager.adapter = LibraryPagerAdapter(this)

        // Connect TabLayout with ViewPager
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Capture"
                1 -> "Export"
                else -> ""
            }
        }.attach()
    }
}

// ViewPager Adapter for tabs
class LibraryPagerAdapter(fragmentActivity: AppCompatActivity) : androidx.viewpager2.adapter.FragmentStateAdapter(fragmentActivity) {

    val fragments = mutableMapOf<Int, Fragment>()

    override fun getItemCount(): Int = 2

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> CaptureFragment()
            1 -> ExportFragment()
            else -> CaptureFragment()
        }.also { fragments[position] = it }
    }
}

// Helper: konfirmasi (OK/Cancel) sebelum membuka project
private fun confirmOpenProject(ctx: android.content.Context, fileName: String, onConfirm: () -> Unit) {
    android.app.AlertDialog.Builder(ctx)
        .setTitle("Open Project")
        .setMessage("Open \"$fileName\"?")
        .setPositiveButton("OK") { _, _ -> onConfirm() }
        .setNegativeButton("Cancel") { _, _ -> }
        .show()
}

// ===== Filter Project (Popup Filter) =====

/** Status filter yang diterapkan pada daftar project. */
data class ProjectFilterState(
    val showProject: Boolean = true,
    val showExplore: Boolean = false,
    val showPart: Boolean = true,
    val showAssembly: Boolean = true
)

/** Fragment yang mendukung tombol Filter pada halaman Project. */
interface ProjectFilterFragment {
    fun showFilterPopup()
}

/** Bagikan file (dipakai untuk item "From Explore" yang bukan project). */
private fun shareFile(ctx: android.content.Context, file: java.io.File) {
    try {
        val uri = android.net.Uri.fromFile(file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, "Share ${file.name}"))
    } catch (e: Exception) {
        Toast.makeText(ctx, "Cannot share file", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Buka file project. Halaman CAD/Polygon/Rendering sudah dihapus, jadi berkas
 * .dspart/.dsasm/.dsform/.dsrender kini hanya bisa dibagikan ke aplikasi lain.
 */
private fun openProjectFile(ctx: android.content.Context, file: java.io.File) {
    shareFile(ctx, file)
}

/** Popup Filter: checkbox + tombol Cancel/Apply. */
private fun showProjectFilterPopup(
    ctx: android.content.Context,
    isCad: Boolean,
    initial: ProjectFilterState,
    onApply: (ProjectFilterState) -> Unit
) {
    val pad = dpToPx(ctx, 16)

    val root = android.widget.LinearLayout(ctx)
    root.orientation = android.widget.LinearLayout.VERTICAL
    root.setPadding(pad, pad, pad, pad)
    root.setBackgroundColor(DeepspaceTheme.surfaceColor(ctx))

    fun makeCheck(label: String, checked: Boolean): android.widget.CheckBox {
        return android.widget.CheckBox(ctx).apply {
            text = label
            isChecked = checked
            textSize = 16f
            setTextColor(DeepspaceTheme.textPrimaryColor(ctx))
            buttonTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#3D93C8"))
            setPadding(0, dpToPx(ctx, 6), 0, dpToPx(ctx, 6))
        }
    }

    val cbProject = makeCheck("Project", initial.showProject)
    val cbExplore = makeCheck("From Explore", initial.showExplore)
    root.addView(cbProject)
    root.addView(cbExplore)

    val cbPart = if (isCad) makeCheck("Part", initial.showPart) else null
    val cbAssembly = if (isCad) makeCheck("Assembly", initial.showAssembly) else null
    if (cbPart != null) root.addView(cbPart)
    if (cbAssembly != null) root.addView(cbAssembly)

    val btnRow = android.widget.LinearLayout(ctx)
    btnRow.orientation = android.widget.LinearLayout.HORIZONTAL
    btnRow.gravity = android.view.Gravity.CENTER
    btnRow.layoutParams = android.widget.LinearLayout.LayoutParams(
        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
    ).also { it.topMargin = dpToPx(ctx, 12) }

    fun makeButton(text: String, blue: Boolean): android.widget.TextView {
        return android.widget.TextView(ctx).apply {
            this.text = text
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            setPadding(dpToPx(ctx, 20), dpToPx(ctx, 10), dpToPx(ctx, 20), dpToPx(ctx, 10))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(ctx, 20).toFloat()
                setColor(if (blue) android.graphics.Color.parseColor("#3D93C8") else android.graphics.Color.parseColor("#888888"))
            }
            isClickable = true
            isFocusable = true
        }
    }

    val btnCancel = makeButton("Cancel", false)
    val btnApply = makeButton("Apply", true)
    btnCancel.layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.rightMargin = dpToPx(ctx, 8) }
    btnApply.layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    btnRow.addView(btnCancel)
    btnRow.addView(btnApply)
    root.addView(btnRow)

    val dialog = android.app.AlertDialog.Builder(ctx).setView(root).create()
    dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded_white)

    btnCancel.setOnClickListener { dialog.dismiss() }
    btnApply.setOnClickListener {
        dialog.dismiss()
        onApply(ProjectFilterState(cbProject.isChecked, cbExplore.isChecked, cbPart?.isChecked ?: true, cbAssembly?.isChecked ?: true))
    }

    dialog.show()
}

// Helper: show popup menu (Delete, Rename, Share) untuk project
private fun showProjectPopup(anchor: android.view.View, projectFile: java.io.File, onRefresh: () -> Unit) {
    val ctx = anchor.context

    data class MenuItem(val id: Int, val label: String, val iconRes: Int, val action: () -> Unit)
    val items = listOf(
        MenuItem(1, "Delete", R.drawable.icon_delete) {
            android.app.AlertDialog.Builder(ctx)
                .setTitle("Delete Project")
                .setMessage("Are you sure you want to delete this project?")
                .setPositiveButton("Delete") { _, _ -> projectFile.delete(); onRefresh() }
                .setNegativeButton("Cancel", null)
                .show()
        },
        MenuItem(2, "Rename", R.drawable.icon_rename) {
            val input = android.widget.EditText(ctx)
            input.setHint("Enter new project name")
            input.setTextColor(DeepspaceTheme.textPrimaryColor(ctx))
            input.setHintTextColor(DeepspaceTheme.textSecondaryColor(ctx))
            input.setSingleLine(true)
            val padding = dpToPx(ctx, 16)
            input.setPadding(padding, padding, padding, padding)
            android.app.AlertDialog.Builder(ctx)
                .setTitle("Rename Project")
                .setView(input)
                .setPositiveButton("Rename") { _, _ ->
                    val newName = input.text.toString().trim()
                    if (newName.isNotEmpty()) {
                        projectFile.renameTo(java.io.File(projectFile.parent, newName + "." + projectFile.extension))
                        onRefresh()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        },
        MenuItem(3, "Share", R.drawable.icon_share) {
            showSharePopup(ctx, projectFile)
        }
    )

    val textColor = DeepspaceTheme.textPrimaryColor(ctx)
    val gridView = android.widget.GridView(ctx)
    gridView.numColumns = 3
    gridView.horizontalSpacing = dpToPx(ctx, 4)
    gridView.verticalSpacing = dpToPx(ctx, 4)
    gridView.setPadding(dpToPx(ctx, 4), dpToPx(ctx, 4), dpToPx(ctx, 4), dpToPx(ctx, 4))
    gridView.stretchMode = android.widget.GridView.STRETCH_COLUMN_WIDTH
    gridView.columnWidth = dpToPx(ctx, 90)
    gridView.setBackgroundColor(DeepspaceTheme.surfaceColor(ctx))

    gridView.adapter = object : android.widget.BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(pos: Int) = items[pos]
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(pos: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
            val item = items[pos]
            val v = convertView ?: run {
                val ll = android.widget.LinearLayout(ctx)
                ll.orientation = android.widget.LinearLayout.VERTICAL
                ll.gravity = android.view.Gravity.CENTER
                ll.setPadding(dpToPx(ctx, 2), dpToPx(ctx, 2), dpToPx(ctx, 2), dpToPx(ctx, 2))
                ll.setBackgroundResource(android.R.drawable.list_selector_background)

                val icon = android.widget.ImageView(ctx)
                icon.id = android.R.id.icon
                icon.layoutParams = android.widget.LinearLayout.LayoutParams(dpToPx(ctx, 40), dpToPx(ctx, 40))
                icon.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER

                val text = android.widget.TextView(ctx)
                text.id = android.R.id.text1
                text.layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text.gravity = android.view.Gravity.CENTER
                text.textSize = 10f
                text.setTextColor(textColor)
                text.maxLines = 2
                text.ellipsize = android.text.TextUtils.TruncateAt.END

                ll.addView(icon)
                ll.addView(text)
                ll
            }

            v.findViewById<android.widget.ImageView>(android.R.id.icon)?.setImageDrawable(
                androidx.core.content.ContextCompat.getDrawable(ctx, item.iconRes)
            )
            v.findViewById<android.widget.TextView>(android.R.id.text1)?.text = item.label
            return v
        }
    }

    val dialog = android.app.AlertDialog.Builder(ctx)
        .setTitle("")
        .setView(gridView)
        .create()

    gridView.onItemClickListener = android.widget.AdapterView.OnItemClickListener { _, _, position, _ ->
        dialog.dismiss()
        items[position].action()
    }

    dialog.show()
    dialog.window?.let { window ->
        window.setBackgroundDrawableResource(R.drawable.bg_render_menu_popup)
        val popupWidth = (ctx.resources.displayMetrics.widthPixels * 0.45f).toInt()
        window.setLayout(popupWidth, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

private fun dpToPx(ctx: android.content.Context, valueDp: Int): Int {
    return android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_DIP,
        valueDp.toFloat(),
        ctx.resources.displayMetrics,
    ).toInt()
}

// AR Project List - grid 3 kolom
class ArProjectListFragment : Fragment(R.layout.fragment_library_drawing) {
    private var _recyclerView: androidx.recyclerview.widget.RecyclerView? = null
    private var _emptyState: android.view.View? = null
    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _emptyState = view.findViewById(R.id.drawingEmptyState)
        _recyclerView = view.findViewById(R.id.drawingRecyclerView)
        _recyclerView?.layoutManager = androidx.recyclerview.widget.GridLayoutManager(requireContext(), 2)
        refreshProjects()
    }
    override fun onResume() { super.onResume(); refreshProjects() }
    override fun onHiddenChanged(hidden: Boolean) { super.onHiddenChanged(hidden); if (!hidden) refreshProjects() }
    private fun refreshProjects() {
        val emptyState = _emptyState ?: return
        val recyclerView = _recyclerView ?: return
        val ctx = context ?: return
        val dir = DeepspaceDirs.getArDir(ctx)
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (!dir.exists() || files.isEmpty()) {
            emptyState.visibility = android.view.View.VISIBLE; recyclerView.visibility = android.view.View.GONE; return
        }
        emptyState.visibility = android.view.View.GONE; recyclerView.visibility = android.view.View.VISIBLE
                recyclerView.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
                                        override fun onCreateViewHolder(p: android.view.ViewGroup, vt: Int) = object : androidx.recyclerview.widget.RecyclerView.ViewHolder(android.view.LayoutInflater.from(p.context).inflate(R.layout.item_grid_square, p, false)) {}
                    override fun onBindViewHolder(h: androidx.recyclerview.widget.RecyclerView.ViewHolder, pos: Int) {
                        val f = files[pos]
                        h.itemView.findViewById<android.widget.ImageView>(R.id.itemThumbnail).setImageResource(R.drawable.icon_ar)
                        h.itemView.findViewById<android.widget.TextView>(R.id.itemProjectName).text = f.nameWithoutExtension
                        h.itemView.findViewById<android.widget.ImageButton>(R.id.itemMenuButton).setOnClickListener { v ->
                            showProjectPopup(v, f) { refreshProjects() }
                        }
                        h.itemView.setOnClickListener {
                            confirmOpenProject(requireContext(), f.nameWithoutExtension) {
                                startActivity(Intent(requireContext(), AR_Activity::class.java).putExtra("EXTRA_MODEL_URI", android.net.Uri.fromFile(f).toString()))
                            }
                        }
                    }
                    override fun getItemCount() = files.size
                }
    }
}

// Utility: extract project display name from JSON file
fun getProjectDisplayName(file: java.io.File): String {
    return try {
        val content = file.readText()
        val json = org.json.JSONObject(content)
        if (json.has("projectName")) {
            json.getString("projectName")
        } else if (json.has("name")) {
            json.getString("name")
        } else {
            // Fallback: extract timestamp from filename
            file.name.replace("proj_", "").replace(".json", "")
        }
    } catch (e: Exception) {
        file.name.replace("proj_", "").replace(".json", "")
    }
}

// Capture Fragment - menampilkan hasil capture/screenshot dalam Grid 2 kolom
class CaptureFragment : Fragment(R.layout.fragment_capture) {

    private var _recyclerView: androidx.recyclerview.widget.RecyclerView? = null
    private var _emptyState: android.view.View? = null

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _emptyState = view.findViewById(R.id.captureEmptyState)
        _recyclerView = view.findViewById(R.id.captureRecyclerView)
        _recyclerView?.layoutManager = androidx.recyclerview.widget.GridLayoutManager(requireContext(), 2)
        refreshCaptures()
    }

    override fun onResume() {
        super.onResume()
        refreshCaptures()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refreshCaptures()
    }

        private fun refreshCaptures() {
        val emptyState = _emptyState ?: return
        val recyclerView = _recyclerView ?: return
        val ctx = context ?: return

        val captureDir = DeepspaceDirs.getCaptureDir(ctx)
        if (!captureDir.exists() || captureDir.listFiles()?.isEmpty() == true) {
            emptyState.visibility = android.view.View.VISIBLE
            recyclerView.visibility = android.view.View.GONE
            return
        }

        emptyState.visibility = android.view.View.GONE
        recyclerView.visibility = android.view.View.VISIBLE

        val imageFiles = captureDir.listFiles { f -> f.extension == "png" || f.extension == "jpg" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

                recyclerView.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
                    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                        val itemView = android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_grid_square, parent, false)
                        return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView) {}
                    }
                    override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                        val file = imageFiles[position]
                        val thumbnail = holder.itemView.findViewById<android.widget.ImageView>(R.id.itemThumbnail)
                        val nameText = holder.itemView.findViewById<android.widget.TextView>(R.id.itemProjectName)

                        try {
                            val opts = android.graphics.BitmapFactory.Options()
                            opts.inSampleSize = 2
                            val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
                            if (bmp != null) {
                                thumbnail.setImageBitmap(bmp)
                                thumbnail.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                            } else {
                                thumbnail.setImageResource(R.drawable.icon_capture)
                            }
                        } catch (e: Exception) {
                            thumbnail.setImageResource(R.drawable.icon_capture)
                        }

                                                nameText.text = file.name
                        val btnMenu = holder.itemView.findViewById<android.widget.ImageButton>(R.id.itemMenuButton)
                        btnMenu.visibility = android.view.View.VISIBLE
                        btnMenu.setOnClickListener { v ->
                            showProjectPopup(v, file) { refreshCaptures() }
                        }

                        holder.itemView.setOnClickListener {
                            showCapturePreview(file)
                        }
                    }
                    override fun getItemCount() = imageFiles.size
                }
    }

    /** Tampilkan popup preview gambar capture dengan tombol Close & Share overlay di dalam gambar. */
    private fun showCapturePreview(file: java.io.File) {
        val ctx = context ?: return

        // Dimensi asli gambar (untuk menjaga aspect ratio window agar gambar memenuhi popup)
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try { BitmapFactory.decodeFile(file.absolutePath, bounds) } catch (_: Exception) {}

        // Preview gambar (resolusi diturunkan agar hemat memori)
        val imageView = android.widget.ImageView(ctx)
        imageView.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        val opts = android.graphics.BitmapFactory.Options()
        opts.inSampleSize = 2
        val bmp = try { BitmapFactory.decodeFile(file.absolutePath, opts) } catch (_: Exception) { null }
        if (bmp != null) imageView.setImageBitmap(bmp) else imageView.setImageResource(R.drawable.icon_capture)

        val root = android.widget.FrameLayout(ctx)
        root.addView(imageView, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Tombol Close & Share overlay di kanan bawah (di dalam gambar)
        val btnRow = android.widget.LinearLayout(ctx)
        btnRow.orientation = android.widget.LinearLayout.HORIZONTAL
        btnRow.gravity = android.view.Gravity.CENTER
        val rowLp = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM or android.view.Gravity.END
        )
        rowLp.setMargins(0, 0, dpToPx(ctx, 12), dpToPx(ctx, 12))
        btnRow.layoutParams = rowLp

        fun makeButton(text: String): android.widget.TextView {
            val b = android.widget.TextView(ctx)
            b.text = text
            b.textSize = 14f
            b.setTextColor(android.graphics.Color.WHITE)
            b.gravity = android.view.Gravity.CENTER
            b.setPadding(dpToPx(ctx, 18), dpToPx(ctx, 10), dpToPx(ctx, 18), dpToPx(ctx, 10))
            b.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(ctx, 20).toFloat()
                setColor(android.graphics.Color.parseColor("#CC3D93C8"))
            }
            b.isClickable = true
            b.isFocusable = true
            return b
        }

        var dialog: android.app.Dialog? = null

        val btnClose = makeButton("Close")
        btnClose.setOnClickListener { dialog?.dismiss() }
        val closeLp = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        )
        closeLp.setMargins(0, 0, dpToPx(ctx, 8), 0)
        btnClose.layoutParams = closeLp
        btnRow.addView(btnClose)

        val btnShare = makeButton("Share")
        btnShare.setOnClickListener { shareCapture(file) }
        btnRow.addView(btnShare, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        root.addView(btnRow)

        dialog = android.app.Dialog(ctx)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(root)
        dialog.show()

        dialog.window?.let { w ->
            // Transparan agar hanya gambar yang tampil (tanpa background putih)
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            val dm = ctx.resources.displayMetrics
            // Ukuran window disesuaikan aspect ratio gambar agar gambar memenuhi popup
            // dan tombol Close/Share berada di dalam area gambar.
            val iw = if (bounds.outWidth > 0) bounds.outWidth else dm.widthPixels
            val ih = if (bounds.outHeight > 0) bounds.outHeight else dm.heightPixels
            val maxW = (dm.widthPixels * 0.95f).toInt()
            val maxH = (dm.heightPixels * 0.92f).toInt()
            val scale = minOf(maxW.toFloat() / iw.toFloat(), maxH.toFloat() / ih.toFloat())
            val wpx = (iw * scale).toInt().coerceAtLeast(1)
            val hpx = (ih * scale).toInt().coerceAtLeast(1)
            w.setLayout(wpx, hpx)
        }
    }

    /** Tampilkan popup pilihan share (WhatsApp, Facebook, Instagram, LinkedIn, Twitter, Email). */
    private fun shareCapture(file: java.io.File) {
        val ctx = context ?: return
        showSharePopup(ctx, file)
    }
}

// Export Fragment - menampilkan hasil export dalam Grid 2 kolom
class ExportFragment : Fragment(R.layout.fragment_export) {

    private var _recyclerView: androidx.recyclerview.widget.RecyclerView? = null
    private var _emptyState: android.view.View? = null

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _emptyState = view.findViewById(R.id.exportEmptyState)
        _recyclerView = view.findViewById(R.id.exportRecyclerView)
        _recyclerView?.layoutManager = androidx.recyclerview.widget.GridLayoutManager(requireContext(), 2)
        refreshExports()
    }

    override fun onResume() {
        super.onResume()
        refreshExports()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refreshExports()
    }

        private fun refreshExports() {
        val emptyState = _emptyState ?: return
        val recyclerView = _recyclerView ?: return
        val ctx = context ?: return

        val exportDir = DeepspaceDirs.getExportDir(ctx)
        if (!exportDir.exists() || exportDir.listFiles()?.isEmpty() == true) {
            emptyState.visibility = android.view.View.VISIBLE
            recyclerView.visibility = android.view.View.GONE
            return
        }

        emptyState.visibility = android.view.View.GONE
        recyclerView.visibility = android.view.View.VISIBLE

        val exportFiles = exportDir.listFiles()
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

                                recyclerView.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                val itemView = android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_grid_square, parent, false)
                return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView) {}
            }
                        override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val file = exportFiles[position]
                val thumbnail = holder.itemView.findViewById<android.widget.ImageView>(R.id.itemThumbnail)
                val nameText = holder.itemView.findViewById<android.widget.TextView>(R.id.itemProjectName)

                val ext = file.extension.lowercase()
                val isImageExt = ext == "png" || ext == "jpg" || ext == "jpeg"

                if (isImageExt) {
                    val bmp = BitmapFactory.decodeFile(file.absolutePath)
                    if (bmp != null) {
                        thumbnail.setImageBitmap(bmp)
                        thumbnail.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    } else {
                        thumbnail.setImageResource(R.drawable.icon_capture)
                    }
                } else {
                    val iconRes = when (ext) {
                        "obj" -> R.drawable.icon_export
                        "ply" -> R.drawable.icon_export
                        "stl" -> R.drawable.icon_export
                        "glb" -> R.drawable.icon_export
                        "gltf" -> R.drawable.icon_export
                        else -> R.drawable.icon_view
                    }
                    thumbnail.setImageResource(iconRes)
                }

                                nameText.text = file.name
                val btnMenu = holder.itemView.findViewById<android.widget.ImageButton>(R.id.itemMenuButton)
                btnMenu.visibility = android.view.View.VISIBLE
                btnMenu.setOnClickListener { v ->
                    showProjectPopup(v, file) { refreshExports() }
                }

                holder.itemView.setOnClickListener {
                    Toast.makeText(ctx, file.name, Toast.LENGTH_SHORT).show()
                }
            }
            override fun getItemCount() = exportFiles.size
        }
    }
}

// Import Fragment - menampilkan file hasil Import dalam Grid 2 kolom
class ImportFragment : Fragment(R.layout.fragment_import), ProjectFilterFragment {

    private var _recyclerView: androidx.recyclerview.widget.RecyclerView? = null
    private var _emptyState: android.view.View? = null
    private var _filter = ProjectFilterState(showProject = true, showExplore = true)

    // Membuka file manager untuk memilih file, lalu menyalinnya ke folder Import.
    private val importLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        if (uri != null) copyImportedFile(uri)
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _emptyState = view.findViewById(R.id.importEmptyState)
        _recyclerView = view.findViewById(R.id.importRecyclerView)
        _recyclerView?.layoutManager = androidx.recyclerview.widget.GridLayoutManager(requireContext(), 2)
        (_recyclerView?.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.bottomMargin = dpToPx(requireContext(), 72)

        view.findViewById<android.widget.ImageButton>(R.id.importAddButton)?.setOnClickListener {
            // Filter disamakan dengan ImportBrowserActivity: hanya format proyek Deepspace dan
            // format mesh/CAD yang bisa diimpor yang ditawarkan file explorer bawaan Android.
            importLauncher.launch(ImportBrowserActivity.SYSTEM_PICKER_MIME_TYPES)
        }

        refreshImports()
    }

    override fun showFilterPopup() {
        showProjectFilterPopup(requireContext(), isCad = false, initial = _filter) { newState ->
            _filter = newState
            refreshImports()
        }
    }

    // Menyalin file yang dipilih dari file manager ke folder Import.
    private fun copyImportedFile(uri: android.net.Uri) {
        val ctx = context ?: return
        try {
            val displayName = displayNameOf(uri)
            val ext = displayName.substringAfterLast('.', "").lowercase()
            val baseName = displayName.substringBeforeLast('.', displayName)

            // Filter ekstensi: file explorer bawaan Android hanya bisa menyaring berbasis MIME,
            // sedangkan format Deepspace (.dsform/.dspart/.dsasm/.dsrender) terbaca sebagai
            // "application/octet-stream" — jadi ekstensinya diverifikasi ulang di sini.
            if (!ImportBrowserActivity.ALLOWED_EXTENSIONS.contains(ext)) {
                Toast.makeText(
                    ctx,
                    "Format .${ext.ifEmpty { "?" }} tidak didukung.\n" +
                        "Format yang bisa diimpor: " +
                        ImportBrowserActivity.ALLOWED_EXTENSIONS.sorted().joinToString(", "),
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            val inputStream = ctx.contentResolver.openInputStream(uri)
            val bytes = inputStream?.readBytes()
            inputStream?.close()

            if (bytes == null) {
                Toast.makeText(ctx, "Failed to read file", Toast.LENGTH_SHORT).show()
                return
            }

            val importDir = DeepspaceDirs.getImportDir(ctx)
            val targetName = if (ext.isNotEmpty()) "$baseName.$ext" else baseName
            val targetFile = DeepspaceDirs.uniqueFile(importDir, targetName)
            targetFile.writeBytes(bytes)

            Toast.makeText(ctx, "Imported: ${targetFile.name}", Toast.LENGTH_SHORT).show()
            refreshImports()
        } catch (e: Exception) {
            Toast.makeText(ctx, "Failed to import: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Nama file dari content URI file explorer bawaan Android (DocumentsUI).
     *
     * `uri.lastPathSegment` pada URI document provider hanya berisi ID dokumen
     * (mis. "msf:1000000001"), bukan nama berkas — jadi nama asli dibaca lewat kolom
     * DISPLAY_NAME, dengan fallback ke segmen terakhir URI.
     */
    private fun displayNameOf(uri: android.net.Uri): String {
        val ctx = context
        if (ctx != null) {
            try {
                ctx.contentResolver.query(
                    uri,
                    arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            val name = cursor.getString(idx)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
            } catch (_: Exception) {
                // Fallback ke segmen terakhir URI di bawah.
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
            ?: "imported_${System.currentTimeMillis()}"
    }

    override fun onResume() {
        super.onResume()
        refreshImports()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refreshImports()
    }

    private fun refreshImports() {
        val emptyState = _emptyState ?: return
        val recyclerView = _recyclerView ?: return
        val ctx = context ?: return

        val importDir = DeepspaceDirs.getImportDir(ctx)
        val projectExts = setOf("dsform", "dsrender", "dspart", "dsasm")
        val allImport = importDir.listFiles()
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        val importFiles = (
            (if (_filter.showProject) allImport.filter { projectExts.contains(it.extension.lowercase()) } else emptyList()) +
            (if (_filter.showExplore) allImport.filter { !projectExts.contains(it.extension.lowercase()) } else emptyList())
        ).sortedByDescending { it.lastModified() }

        if (!importDir.exists() || importFiles.isEmpty()) {
            emptyState.visibility = android.view.View.VISIBLE
            recyclerView.visibility = android.view.View.GONE
            return
        }

        emptyState.visibility = android.view.View.GONE
        recyclerView.visibility = android.view.View.VISIBLE

        recyclerView.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                val itemView = android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_grid_square, parent, false)
                return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView) {}
            }
            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val file = importFiles[position]
                val thumbnail = holder.itemView.findViewById<android.widget.ImageView>(R.id.itemThumbnail)
                val nameText = holder.itemView.findViewById<android.widget.TextView>(R.id.itemProjectName)

                val ext = file.extension.lowercase()
                val isImageExt = ext == "png" || ext == "jpg" || ext == "jpeg"

                if (isImageExt) {
                    val bmp = BitmapFactory.decodeFile(file.absolutePath)
                    if (bmp != null) {
                        thumbnail.setImageBitmap(bmp)
                        thumbnail.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    } else {
                        thumbnail.setImageResource(R.drawable.icon_import)
                    }
                } else {
                    val iconRes = when (ext) {
                        "glb" -> R.drawable.icon_import
                        "gltf" -> R.drawable.icon_import
                        "obj", "ply", "stl" -> R.drawable.icon_export
                        else -> R.drawable.icon_view
                    }
                    thumbnail.setImageResource(iconRes)
                }

                                nameText.text = file.name
                val btnMenu = holder.itemView.findViewById<android.widget.ImageButton>(R.id.itemMenuButton)
                btnMenu.visibility = android.view.View.VISIBLE
                btnMenu.setOnClickListener { v ->
                    showProjectPopup(v, file) { refreshImports() }
                }

                holder.itemView.setOnClickListener {
                    if (projectExts.contains(file.extension.lowercase())) {
                        openProjectFile(ctx, file)
                    } else {
                        shareFile(ctx, file)
                    }
                }
            }
            override fun getItemCount() = importFiles.size
        }
    }
}

// Popup Share: WhatsApp, Facebook, Instagram, LinkedIn, Twitter, Email.
fun showSharePopup(ctx: android.content.Context, file: java.io.File) {
    data class ShareTarget(val name: String, val packageName: String, val iconRes: Int)
    val targets = listOf(
        ShareTarget("WhatsApp", "com.whatsapp", R.drawable.whatsapp),
        ShareTarget("Facebook", "com.facebook.katana", R.drawable.facebook),
        ShareTarget("Instagram", "com.instagram.android", R.drawable.instagram),
        ShareTarget("LinkedIn", "com.linkedin.android", R.drawable.linkedin),
        ShareTarget("Twitter", "com.twitter.android", R.drawable.twitter),
        ShareTarget("Email", "email", R.drawable.email),
    )

    val gridView = android.widget.GridView(ctx)
    gridView.numColumns = 3
    gridView.horizontalSpacing = dpToPx(ctx, 8)
    gridView.verticalSpacing = dpToPx(ctx, 8)
    gridView.setPadding(dpToPx(ctx, 16), dpToPx(ctx, 16), dpToPx(ctx, 16), dpToPx(ctx, 16))
    gridView.stretchMode = android.widget.GridView.STRETCH_COLUMN_WIDTH
    gridView.columnWidth = dpToPx(ctx, 80)
    gridView.setBackgroundColor(DeepspaceTheme.surfaceColor(ctx))

    gridView.adapter = object : android.widget.BaseAdapter() {
        override fun getCount() = targets.size
        override fun getItem(pos: Int) = targets[pos]
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(pos: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
            val item = targets[pos]
            val v = convertView ?: run {
                val ll = android.widget.LinearLayout(ctx)
                ll.orientation = android.widget.LinearLayout.VERTICAL
                ll.gravity = android.view.Gravity.CENTER
                ll.setPadding(dpToPx(ctx, 8), dpToPx(ctx, 8), dpToPx(ctx, 8), dpToPx(ctx, 8))
                ll.setBackgroundResource(android.R.drawable.list_selector_background)
                val icon = android.widget.ImageView(ctx).apply {
                    id = android.R.id.icon
                    layoutParams = android.widget.LinearLayout.LayoutParams(dpToPx(ctx, 44), dpToPx(ctx, 44))
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                }
                val text = android.widget.TextView(ctx).apply {
                    id = android.R.id.text1
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    gravity = android.view.Gravity.CENTER
                    textSize = 11f
                    setTextColor(DeepspaceTheme.textPrimaryColor(ctx))
                    maxLines = 1
                }
                ll.addView(icon); ll.addView(text); ll
            }
            v.findViewById<android.widget.ImageView>(android.R.id.icon)?.setImageDrawable(
                androidx.core.content.ContextCompat.getDrawable(ctx, item.iconRes))
            v.findViewById<android.widget.TextView>(android.R.id.text1)?.text = item.name
            return v
        }
    }

    val dialog = android.app.AlertDialog.Builder(ctx)
        .setTitle("Share")
        .setView(gridView)
        .create()

    gridView.onItemClickListener = android.widget.AdapterView.OnItemClickListener { _, _, position, _ ->
        dialog.dismiss()
        shareFileToApp(ctx, file, targets[position].name, targets[position].packageName)
    }

    dialog.show()
    dialog.window?.let { window ->
        window.setBackgroundDrawableResource(R.drawable.bg_render_menu_popup)
        window.setLayout((ctx.resources.displayMetrics.widthPixels * 0.85f).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

// Bagikan file ke aplikasi tertentu (atau email) via FileProvider.
fun shareFileToApp(ctx: android.content.Context, file: java.io.File, appName: String, packageName: String) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (packageName == "email") {
            intent.putExtra(android.content.Intent.EXTRA_SUBJECT, "Shared from DEEPSPACE")
            ctx.startActivity(android.content.Intent.createChooser(intent, "Share via Email"))
        } else {
            intent.setPackage(packageName)
            try {
                ctx.startActivity(intent)
            } catch (e: Exception) {
                android.widget.Toast.makeText(ctx, "$appName is not installed", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    } catch (e: Exception) {
        android.widget.Toast.makeText(ctx, "Cannot share file", android.widget.Toast.LENGTH_SHORT).show()
    }
}

data class MakerProject(val thumbnail: java.io.File?, val project: java.io.File)







