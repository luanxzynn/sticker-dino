package com.stickermaker;

import android.net.Uri;

/**
 * Representa uma figurinha: pode ser estática (JPG/PNG → WebP) ou
 * animada (GIF → WebP animado).
 */
public class StickerItem {
    public final Uri sourceUri;
    public final boolean animated;   // true = GIF/WebP animado
    public final boolean video;      // true = vídeo que será convertido em WebP animado

    public StickerItem(Uri sourceUri, boolean animated) {
        this(sourceUri, animated, false);
    }

    public StickerItem(Uri sourceUri, boolean animated, boolean video) {
        this.sourceUri = sourceUri;
        this.animated  = animated;
        this.video = video;
    }
}
