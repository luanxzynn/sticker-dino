package com.stickermaker;

import android.net.Uri;

/**
 * Representa uma figurinha: pode ser estática (JPG/PNG → WebP) ou
 * animada (GIF → WebP animado).
 */
public class StickerItem {
    public final Uri sourceUri;
    public final boolean animated;   // true = GIF/WebP animado

    public StickerItem(Uri sourceUri, boolean animated) {
        this.sourceUri = sourceUri;
        this.animated  = animated;
    }
}
