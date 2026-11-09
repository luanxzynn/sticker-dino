package com.stickermaker;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import java.util.List;

public class StickerAdapter extends RecyclerView.Adapter<StickerAdapter.VH> {

    private final Context context;
    private final List<StickerItem> items;

    public StickerAdapter(Context context, List<StickerItem> items) {
        this.context = context;
        this.items   = items;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_sticker, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        StickerItem item = items.get(position);

        // Glide carrega GIF animado automaticamente se o mime-type for image/gif
        Glide.with(context)
                .asGif()
                .load(item.animated ? item.sourceUri : null)
                .error(
                    Glide.with(context)
                         .load(item.sourceUri)
                         .diskCacheStrategy(DiskCacheStrategy.ALL)
                )
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(holder.ivSticker);

        // Para itens não-animados, carregamos normalmente
        if (!item.animated) {
            Glide.with(context)
                    .load(item.sourceUri)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(holder.ivSticker);
        }

        holder.tvBadge.setVisibility(item.animated ? View.VISIBLE : View.GONE);

        // Remover ao clicar longo
        holder.itemView.setOnLongClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos != RecyclerView.NO_ID) {
                items.remove(pos);
                notifyItemRemoved(pos);
            }
            return true;
        });
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivSticker;
        TextView  tvBadge;
        VH(View v) {
            super(v);
            ivSticker = v.findViewById(R.id.ivSticker);
            tvBadge   = v.findViewById(R.id.tvBadge);
        }
    }
}
