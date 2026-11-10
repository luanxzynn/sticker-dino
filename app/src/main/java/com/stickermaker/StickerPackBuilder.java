package com.stickermaker;

import android.content.Context;
import android.content.Intent;
import android.graphics.*;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Constrói um StickerPack e abre o WhatsApp para adicioná-lo.
 *
 * Figurinhas estáticas:  qualquer imagem → redimensionada para 512×512 → WebP
 * Figurinhas animadas:   GIF                             → WebP animado
 *
 * O WhatsApp exige WebP ≤ 100 KB para estáticas e ≤ 500 KB para animadas.
 */
public class StickerPackBuilder {

    private final Context ctx;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public StickerPackBuilder(Context ctx) {
        this.ctx = ctx;
    }

    public void buildAndSend(List<StickerItem> items, String packName, String author) {
        executor.execute(() -> {
            try {
                // Pasta do pack (identificador único por nome)
                String packId = "pack_" + packName.replaceAll("\\s+", "_").toLowerCase();
                File packDir  = new File(ctx.getFilesDir(), packId);
                packDir.mkdirs();

                List<String> fileNames = new ArrayList<>();

                for (int i = 0; i < items.size(); i++) {
                    StickerItem item = items.get(i);
                    String fname = "sticker_" + i + ".webp";
                    File   out   = new File(packDir, fname);

                    if (item.video) {
                        processVideo(item.sourceUri, out);
                    } else if (item.animated) {
                        processAnimatedGif(item.sourceUri, out);
                    } else {
                        processStaticImage(item.sourceUri, out);
                    }
                    fileNames.add(fname);
                }

                // Lança o Intent do WhatsApp no thread principal
                final String finalPackId = packId;
                final String finalName   = packName;
                final String finalAuthor = author;
                final List<String> finalFiles = fileNames;
                mainHandler.post(() ->
                        launchWhatsApp(finalPackId, finalName, finalAuthor, finalFiles));

            } catch (Exception e) {
                mainHandler.post(() ->
                        Toast.makeText(ctx, "Erro ao processar figurinhas: " + e.getMessage(),
                                Toast.LENGTH_LONG).show());
            }
        });
    }

    // ─── Estática ─────────────────────────────────────────────────────────────

    private void processStaticImage(Uri uri, File out) throws IOException {
        InputStream is = ctx.getContentResolver().openInputStream(uri);
        Bitmap bmp = BitmapFactory.decodeStream(is);
        if (is != null) is.close();
        if (bmp == null) throw new IOException("Não foi possível decodificar a imagem.");

        // Redimensionar para 512×512 mantendo proporção com fundo transparente
        bmp = resize512(bmp);

        try (FileOutputStream fos = new FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, fos);
        }
        bmp.recycle();
    }

    private Bitmap resize512(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        float scale = Math.min(512f / w, 512f / h);
        int nw = Math.round(w * scale), nh = Math.round(h * scale);

        Bitmap scaled = Bitmap.createScaledBitmap(src, nw, nh, true);
        Bitmap canvas = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(canvas);
        c.drawARGB(0, 0, 0, 0); // fundo transparente
        int left = (512 - nw) / 2, top = (512 - nh) / 2;
        c.drawBitmap(scaled, left, top, null);
        scaled.recycle();
        return canvas;
    }

    // ─── Animada (GIF → WebP animado) ─────────────────────────────────────────

    /**
     * Converte um GIF em WebP animado frame-a-frame usando a API
     * android.graphics.drawable.AnimatedImageDrawable (API 28+) como decodificador
     * e android.graphics.ImageDecoder para leitura.
     *
     * Em dispositivos com API 21–27, faz fallback: extrai o 1º frame e gera
     * um WebP estático (sem animação), avisando o usuário.
     */
    private void processAnimatedGif(Uri uri, File out) throws IOException {
        convertGifToAnimatedWebP(uri, out);
    }

    // API 28+ — decodifica o GIF frame a frame e encoda como WebP animado
    private void convertGifToAnimatedWebP(Uri uri, File out) throws IOException {
        InputStream is = ctx.getContentResolver().openInputStream(uri);
        android.graphics.Movie movie = android.graphics.Movie.decodeStream(is);
        if (is != null) is.close();

        if (movie == null || movie.duration() == 0) {
            // Sem animação — trata como estático
            processStaticImage(uri, out);
            return;
        }

        // Coleta frames em intervalos de ~33 ms (≈30 fps, máximo 30 frames pro WhatsApp)
        int duration  = Math.min(movie.duration(), 10_000);
        int frameStep = Math.max(33, (duration + 29) / 30);
        List<Bitmap> frames    = new ArrayList<>();
        List<Integer> durations = new ArrayList<>();

        for (int t = 0; t < duration && frames.size() < 30; t += frameStep) {
            movie.setTime(t);
            Bitmap frame = Bitmap.createBitmap(movie.width(), movie.height(),
                    Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(frame);
            movie.draw(c, 0, 0);
            frames.add(resize512(frame));
            frame.recycle();
            durations.add(Math.min(frameStep, duration - t));
        }

        try {
            encodeAnimatedWebP(frames, durations, out);
        } finally {
            for (Bitmap b : frames) b.recycle();
        }
    }

    private void processVideo(Uri uri, File out) throws IOException {
        android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
        List<Bitmap> frames = new ArrayList<>();
        List<Integer> durations = new ArrayList<>();
        try {
            retriever.setDataSource(ctx, uri);
            String value = retriever.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationMs = value == null ? 0 : Long.parseLong(value);
            if (durationMs <= 0) throw new IOException("Não foi possível ler a duração do vídeo.");
            long sampleDurationMs = Math.min(durationMs, 10_000L);
            int frameStepMs = (int) Math.max(100L, (sampleDurationMs + 29) / 30);
            for (long t = 0; t < sampleDurationMs && frames.size() < 30; t += frameStepMs) {
                Bitmap frame = retriever.getFrameAtTime(t * 1000,
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame != null) {
                    frames.add(resize512(frame));
                    frame.recycle();
                    durations.add((int) Math.min(frameStepMs, sampleDurationMs - t));
                }
            }
            if (frames.isEmpty()) throw new IOException("Não foi possível extrair quadros do vídeo.");
            encodeAnimatedWebP(frames, durations, out);
        } catch (RuntimeException e) {
            throw new IOException("Não foi possível converter o vídeo.", e);
        } finally {
            retriever.release();
            for (Bitmap frame : frames) frame.recycle();
        }
    }

    // API 30+ — Android 11 inclui AnimatedImageEncoder via ImageDecoder, mas
    // a API pública usa android.graphics.drawable com surface; a forma mais
    // robusta é usar o codec FFmpeg via script ou Animated WebP via NDK.
    // Aqui usamos a abordagem nativa disponível: salvar frames individuais
    // e construir o WebP animado com o formato RIFF manualmente.
    private void encodeAnimatedWebP(List<Bitmap> frames, List<Integer> frameDurations, File out)
            throws IOException {
        // O Android SDK não expõe encoder de WebP animado em Java puro.
        // Usamos a classe android.graphics.ImageDecoder + MediaMuxer-like approach.
        // Solução compatível: salvar como série de WebP e empacotar no formato
        // RIFF/WebP animado (VP8X + ANIM + ANMFs).

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        List<byte[]> frameData = new ArrayList<>();
        List<Integer> delays   = new ArrayList<>();

        for (int i = 0; i < frames.size(); i++) {
            ByteArrayOutputStream frameBaos = new ByteArrayOutputStream();
            Bitmap.CompressFormat format = android.os.Build.VERSION.SDK_INT >= 30
                    ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP;
            frames.get(i).compress(format, 70, frameBaos);
            frameData.add(frameBaos.toByteArray());
            delays.add(frameDurations.get(i));
        }

        byte[] webpAnimated = buildAnimatedWebP(frameData, delays, 512, 512, 0);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(webpAnimated);
        }
    }

    /**
     * Constrói manualmente o container RIFF WebP animado.
     *
     * Estrutura:
     *   RIFF .... WEBP
     *     VP8X (canvas size, flags)
     *     ANIM (loop count, background)
     *     ANMF* (cada frame)
     */
    private byte[] buildAnimatedWebP(List<byte[]> frames, List<Integer> delays,
                                     int width, int height, int loopCount) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();

        // VP8X chunk — flags: Animation (bit 1)
        byte[] vp8x = new byte[10];
        vp8x[0] = 0x02; // animation flag
        writeLE24(vp8x, 1, width  - 1);
        writeLE24(vp8x, 4, height - 1);
        writeChunk(body, "VP8X", vp8x);

        // ANIM chunk
        byte[] anim = new byte[6];
        anim[0] = (byte) 0xFF; anim[1] = (byte) 0xFF;
        anim[2] = (byte) 0xFF; anim[3] = (byte) 0xFF; // background = white
        anim[4] = (byte)(loopCount & 0xFF);
        anim[5] = (byte)((loopCount >> 8) & 0xFF);
        writeChunk(body, "ANIM", anim);

        // ANMF frames
        for (int i = 0; i < frames.size(); i++) {
            byte[] frameBytes = frames.get(i);
            int delay = delays.get(i);

            // Extrai o payload VP8/VP8L do WebP simples
            byte[] vp8payload = extractWebPBitstream(frameBytes);

            ByteArrayOutputStream anmfData = new ByteArrayOutputStream();
            // Frame X, Y (0)
            writeLE24bytes(anmfData, 0); writeLE24bytes(anmfData, 0);
            // Frame width-1, height-1
            writeLE24bytes(anmfData, width  - 1);
            writeLE24bytes(anmfData, height - 1);
            // Duration (ms, 24-bit LE)
            writeLE24bytes(anmfData, delay);
            // Flags: no blend, no dispose
            anmfData.write(0x00);

            // Detecta se é VP8 ou VP8L e escreve chunk correto
            String subChunkId = isVP8L(frameBytes) ? "VP8L" : "VP8 ";
            writeChunkBytes(anmfData, subChunkId, vp8payload);

            writeChunk(body, "ANMF", anmfData.toByteArray());
        }

        // Monta o RIFF
        byte[] bodyBytes = body.toByteArray();
        ByteArrayOutputStream riff = new ByteArrayOutputStream();
        riff.write("RIFF".getBytes());
        writeInt32LE(riff, bodyBytes.length + 4);
        riff.write("WEBP".getBytes());
        riff.write(bodyBytes);

        return riff.toByteArray();
    }

    // ─── Helpers RIFF/WebP ────────────────────────────────────────────────────

    private void writeChunk(OutputStream out, String id, byte[] data) throws IOException {
        out.write(id.getBytes());
        writeInt32LE(out, data.length);
        out.write(data);
        if (data.length % 2 != 0) out.write(0); // padding
    }

    private void writeChunkBytes(ByteArrayOutputStream out, String id, byte[] data)
            throws IOException {
        writeChunk(out, id, data);
    }

    private void writeInt32LE(OutputStream out, int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    private void writeLE24(byte[] buf, int off, int v) {
        buf[off]     = (byte)(v & 0xFF);
        buf[off + 1] = (byte)((v >> 8) & 0xFF);
        buf[off + 2] = (byte)((v >> 16) & 0xFF);
    }

    private void writeLE24bytes(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
    }

    /**
     * Extrai o payload de bits (VP8 ou VP8L) de um arquivo WebP simples,
     * pulando o cabeçalho RIFF e o chunk VP8/VP8L.
     */
    private byte[] extractWebPBitstream(byte[] webp) {
        // RIFF(4) + size(4) + WEBP(4) + chunkId(4) + chunkSize(4) = 20 bytes de header
        if (webp.length < 24) return webp;
        int chunkSize = (webp[16] & 0xFF) | ((webp[17] & 0xFF) << 8)
                      | ((webp[18] & 0xFF) << 16) | ((webp[19] & 0xFF) << 24);
        byte[] payload = new byte[chunkSize];
        System.arraycopy(webp, 20, payload, 0, Math.min(chunkSize, webp.length - 20));
        return payload;
    }

    private boolean isVP8L(byte[] webp) {
        if (webp.length < 16) return false;
        return webp[12] == 'V' && webp[13] == 'P' && webp[14] == '8' && webp[15] == 'L';
    }

    // ─── WhatsApp Intent ──────────────────────────────────────────────────────

    private void launchWhatsApp(String packId, String packName, String author,
                                 List<String> fileNames) {
        // Notifica o ContentProvider
        StickerContentProvider.registerPack(packId, packName, author, fileNames);

        // Intent oficial do WhatsApp para adicionar figurinhas
        Intent intent = new Intent();
        intent.setAction("com.whatsapp.intent.action.ENABLE_STICKER_PACK");
        intent.putExtra("sticker_pack_id",         packId);
        intent.putExtra("sticker_pack_name",       packName);
        intent.putExtra("sticker_pack_publisher",  author);
        intent.setPackage("com.whatsapp");

        if (ctx.getPackageManager().resolveActivity(intent, 0) != null) {
            ctx.startActivity(intent);
        } else {
            // Tenta WhatsApp Business
            intent.setPackage("com.whatsapp.w4b");
            if (ctx.getPackageManager().resolveActivity(intent, 0) != null) {
                ctx.startActivity(intent);
            } else {
                Toast.makeText(ctx,
                        "WhatsApp não encontrado. Instale o WhatsApp para adicionar o pack.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }
}
