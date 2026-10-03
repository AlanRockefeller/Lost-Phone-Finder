# App icon artwork

`icon-1b.svg` is the source for the Android launcher icon. `playstore-512.png` is the store-listing image.

The source SVG is ported to native Android vector layers in `app/src/main/res/drawable/ic_launcher_background.xml` and `ic_launcher_foreground.xml`. The port preserves the paths, radial gradients, circle outlines, opacity and original foreground placement. The foreground's scale and translation reproduce the SVG transform: `translate(54 54) scale(0.85) translate(-58 -61)`.

The adaptive icon definition in `mipmap-anydpi/ic_launcher.xml` references those layers. The app supports Android 8 and newer, so a legacy bitmap fallback is unnecessary. Older Android versions ignore the optional monochrome layer. The manifest uses the adaptive icon for both normal and round launcher icons. Android 13 and newer can use the foreground's alpha shape for themed icons. The launcher applies its own icon mask and, when enabled, themed colors. See [Android's adaptive icon guidance](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive).

When changing the SVG, update both vector layers to match. Keep the original SVG and Play Store image here; Android does not load SVG files directly as launcher resources.
