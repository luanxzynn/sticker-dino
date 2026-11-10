package com.stickermaker;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    // Tipos de pick de arquivo
    private static final int PICK_IMAGE_REQUEST    = 1001;
    private static final int PICK_GIF_REQUEST      = 1002;
    private static final int PICK_ANIMATED_REQUEST = 1003;
    private static final int PERMISSION_REQUEST    = 2001;

    private EditText etPackName, etAuthorName;
    private CheckBox cbHideNames;
    private RecyclerView rvStickers;
    private StickerAdapter adapter;
    private List<StickerItem> stickerList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etPackName   = findViewById(R.id.etPackName);
        etAuthorName = findViewById(R.id.etAuthorName);
        cbHideNames  = findViewById(R.id.cbHideNames);
        rvStickers   = findViewById(R.id.rvStickers);

        adapter = new StickerAdapter(this, stickerList);
        rvStickers.setLayoutManager(new GridLayoutManager(this, 3));
        rvStickers.setAdapter(adapter);

        // Botão — adicionar imagem estática
        findViewById(R.id.btnAddSticker).setOnClickListener(v -> checkPermissionAndPick(false));

        // O botão existente aceita GIF ou vídeo, sem acrescentar elementos à tela.
        findViewById(R.id.btnAddAnimated).setOnClickListener(v -> openAnimatedPicker());

        // Botão — enviar pack ao WhatsApp
        findViewById(R.id.btnSendWhatsApp).setOnClickListener(v -> sendToWhatsApp());
    }

    // ─── Permissões ───────────────────────────────────────────────────────────

    private void checkPermissionAndPick(boolean animated) {
        String perm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;

        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            openPicker(animated);
        } else {
            // Guarda o contexto para saber o que fazer após a permissão
            pendingAnimated = animated;
            ActivityCompat.requestPermissions(this, new String[]{perm}, PERMISSION_REQUEST);
        }
    }

    private boolean pendingAnimated = false;

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openPicker(pendingAnimated);
            } else {
                Toast.makeText(this, "Permissão necessária para escolher arquivos.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ─── Seleção de arquivo ────────────────────────────────────────────────────

    private void openPicker(boolean animated) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        if (animated) {
            intent.setType("image/gif");
        } else {
            intent.setType("image/*");
        }
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(Intent.createChooser(intent,
                animated ? "Escolher GIF animado" : "Escolher imagem"),
                animated ? PICK_GIF_REQUEST : PICK_IMAGE_REQUEST);
    }

    private void openAnimatedPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/gif", "video/*"});
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(Intent.createChooser(intent, "Escolher GIF ou vídeo"),
                PICK_ANIMATED_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null) return;

        boolean animated = requestCode == PICK_GIF_REQUEST || requestCode == PICK_ANIMATED_REQUEST;
        List<Uri> uris = new ArrayList<>();

        if (data.getClipData() != null) {
            int count = data.getClipData().getItemCount();
            for (int i = 0; i < count; i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }

        for (Uri uri : uris) {
            String mimeType = getContentResolver().getType(uri);
            boolean video = animated && mimeType != null && mimeType.startsWith("video/");
            StickerItem item = new StickerItem(uri, animated, video);
            stickerList.add(item);
            adapter.notifyItemInserted(stickerList.size() - 1);
        }
    }

    // ─── Enviar ao WhatsApp ────────────────────────────────────────────────────

    private void sendToWhatsApp() {
        if (stickerList.isEmpty()) {
            Toast.makeText(this, "Adicione ao menos uma figurinha!", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean hide    = cbHideNames.isChecked();
        String packName = hide ? " " : etPackName.getText().toString().trim();
        String author   = hide ? " " : etAuthorName.getText().toString().trim();

        if (packName.isEmpty()) packName = "Meu Pack";
        if (author.isEmpty())   author   = "Autor";

        // Salva os stickers processados e cria o pack
        StickerPackBuilder builder = new StickerPackBuilder(this);
        builder.buildAndSend(stickerList, packName, author);
    }
}
