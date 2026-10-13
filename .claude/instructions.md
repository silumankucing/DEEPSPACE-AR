# DeepSpace Codebase Cleanup Summary

## Changes Made

### Removed Unused Layout Files
These layout files referenced activities that no longer exist in the codebase:
- `activity_xr.xml` - No XR_Activity class exists
- `activity_actual_library.xml` - No ActualLibrary_Activity class exists  
- `activity_freeform3d.xml` (both normal and landscape) - No Freeform3d_Activity class exists
- `activity_maker3d.xml` (both normal and landscape) - No Maker3d_Activity class exists

### Removed Unused Image Resources
Icons not referenced from any source code:
- `menu_ar.png`, `menu_ar_black.png`, `menu_cad.png`, `menu_freeform.png`, `menu_library.png`, `menu_project.png`, `menu_rendering.png`, `menu_rendering_black.png` - Old menu icons from legacy navigation
- `nav_bar.png` - Unused navigation bar asset
- `ic_grid_menu.xml`, `ic_hamburger_menu.xml`, `ic_more_vert.xml` - Unreferenced icon XMLs (replaced by `icon_hamburger_menu.xml`)
- `bg_circle_blue.xml`, `bg_circle_grey.xml` - Unreferenced background drawables

### Removed Unused Menu XML
- `right_drawer_menu.xml` - Not used by any Activity (no NavigationView/DrawerLayout references)

### Unused Variable Cleanup
- `AR_Activity.kt`: Removed unused `imageReader`, `cameraDevice` variables  
- `AR_Activity.kt`: Removed unused `import` for unused types
- `GeometryUtils.kt`: Simplified - removed unreferenced duplicate shape builder functions
- Various unused imports across files

### Dead Code Cleanup
- `Rendering_Activity.kt`: Removed commented-out EXTRA_MODEL_URI dead code path
- `Rendering_Activity.kt`: Simplified non-functional menu items, consolidated toast patterns
- `AR_Activity.kt`: Simplified OpenCV initialization (removed redundant logging/duplicate checks)

### Code Duplication Reduction
- `Settings_Activity.kt`: Consolidated 6 individual `shareTo*` methods into a generic `shareToApp(packageName, text)` method
- `Project_Activity.kt`: Identified overlapping fragment code (not fully refactored due to complexity)
- `GeometryUtils.kt`: Removed `createColoredMaterial`, `createBoxRenderable` - unused dead code

## VR Mode Removal (VR_Activity etc.)

Seluruh mode VR (Cardboard) dihapus; aplikasi sekarang AR-only dengan `AR_Activity` sebagai launcher.

- Deleted sources: `VR_Activity.kt`, `VrDistortionShader.kt`, `VrGazeCursor.kt`, `CardboardSdkHelper.kt`,
  `DsVrFormat.kt`, `EdgeLines.kt`, `EdgeLinesTest.kt`
- Deleted resources: `layout/activity_vr.xml`, `drawable/bg_vr_notification.xml` (+ night variant),
  `drawable/icon_add_vr.png`, `drawable/menu_vr.png`, `drawable/menu_vr_black.png`
- Deleted dirs: `app/src/main/cpp/` (cardboard_jni.cpp/CMake), `app/libs/` (Cardboard AAR)
- `AndroidManifest.xml`: `AR_Activity` jadi launcher (MAIN/LAUNCHER, exported), NFC + gyroscope/accelerometer
  features dihapus
- `build.gradle.kts`: NDK/`externalNativeBuild`/`jniLibs`, deps Cardboard + Google Vision + protobuf dihapus
  (Filament/gltfio/filamat tetap karena dipakai AR)
- UI: `btnVR` (Home), `btnModeSwitch` (AR), `brightnessContainer` + grup VR (Settings),
  `VrProjectListFragment` (Project), `showVrArModePopup` (BaseFullscreenActivity) dihapus
- Strings `drawer_vr`, `brightness`, `vr`, `vr_title`, `vr_mode` dihapus
