package com.stickermaker;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.*;
import java.util.*;

/**
 * ContentProvider que o WhatsApp consulta para obter metadados e arquivos
 * do sticker pack. Implementa o contrato da API oficial de stickers do WhatsApp.
 */
public class StickerContentProvider extends ContentProvider {

    // Contrato de URIs definido pelo WhatsApp
    private static final String AUTHORITY_SUFFIX  = ".stickercontentprovider";
    private static final String METADATA_PATH     = "metadata";
    private static final String STICKERS_PATH     = "stickers";
    private static final String STICKERS_ASSET    = "stickers_asset";

    // Memória dos packs registrados em tempo de execução
    // (produção: usar Room/SQLite; aqui mantemos simples)
    private static final Map<String, PackInfo> packs = new HashMap<>();

    public static void registerPack(String id, String name, String publisher,
                                    List<String> files) {
        packs.put(id, new PackInfo(id, name, publisher, files));
    }

    private static class PackInfo {
        String id, name, publisher;
        List<String> files;
        PackInfo(String id, String name, String publisher, List<String> files) {
            this.id = id; this.name = name; this.publisher = publisher; this.files = files;
        }
    }

    @Override
    public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri uri, String[] proj, String sel, String[] args, String sort) {
        List<String> segs = uri.getPathSegments();
        if (segs.isEmpty()) return null;

        switch (segs.get(0)) {
            case METADATA_PATH:
                return getMetadata();
            case STICKERS_PATH:
                if (segs.size() >= 2) return getStickers(segs.get(1));
                break;
        }
        return null;
    }

    private Cursor getMetadata() {
        MatrixCursor cursor = new MatrixCursor(new String[]{
                "identifier", "name", "publisher", "tray_image_file",
                "publisher_email", "publisher_website", "privacy_policy_website",
                "license_agreement_website", "image_data_version",
                "avoid_cache", "animated_sticker_pack"
        });
        for (PackInfo p : packs.values()) {
            boolean hasAnimated = false;
            // Verifica se tem algum sticker animado (nome de arquivo termina em .webp
            // — todos são .webp; o campo animated_sticker_pack sinaliza se ≥1 é animado)
            // Como não temos essa informação aqui de forma simples, deixamos 1 para pacotes
            // que podem ter animados.
            cursor.addRow(new Object[]{
                    p.id, p.name, p.publisher, p.files.isEmpty() ? "" : p.files.get(0),
                    "", "", "", "", "1", "0", "1"
            });
        }
        return cursor;
    }

    private Cursor getStickers(String packId) {
        PackInfo p = packs.get(packId);
        if (p == null) return null;

        MatrixCursor cursor = new MatrixCursor(new String[]{"sticker_file_name", "emojis"});
        for (String f : p.files) {
            cursor.addRow(new Object[]{f, ""});
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        List<String> segs = uri.getPathSegments();
        // uri: .../stickers_asset/<packId>/<filename>
        if (segs.size() >= 3 && STICKERS_ASSET.equals(segs.get(0))) {
            String packId   = segs.get(1);
            String fileName = segs.get(2);
            File   packDir  = new File(getContext().getFilesDir(), packId);
            File   file     = new File(packDir, fileName);
            if (!file.exists()) throw new FileNotFoundException(file.getAbsolutePath());
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        throw new FileNotFoundException("URI não reconhecida: " + uri);
    }

    @Override public String getType(Uri uri) { return "image/webp"; }
    @Override public Uri    insert(Uri u, ContentValues v) { return null; }
    @Override public int    delete(Uri u, String s, String[] a) { return 0; }
    @Override public int    update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
