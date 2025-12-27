package ru.rizonchik.refontsocial.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ru.rizonchik.refontsocial.RefontSocial;
import ru.rizonchik.refontsocial.service.ReputationService;
import ru.rizonchik.refontsocial.util.Colors;
import ru.rizonchik.refontsocial.util.ItemUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class ReasonsGui extends AbstractGui {

    private final RefontSocial plugin;
    private final ReputationService service;
    private final UUID target;
    private final String targetName;
    private final boolean like;

    private final List<String> tagKeys = new ArrayList<>();

    public ReasonsGui(RefontSocial plugin, ReputationService service, UUID target, String targetName, boolean like) {
        this.plugin = plugin;
        this.service = service;
        this.target = target;
        this.targetName = (targetName != null ? targetName : "Игрок");
        this.like = like;
    }

    @Override
    public void open(Player player) {
        String title = plugin.getConfig().getString("gui.reasons.title", "Причина");
        int size = plugin.getConfig().getInt("gui.reasons.size", 54);
        if (size < 9) size = 54;
        if (size % 9 != 0) size = 54;

        inventory = Bukkit.createInventory(null, size, title);

        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("reasons.tags");
        if (sec != null) {
            tagKeys.addAll(sec.getKeys(false));
            Collections.sort(tagKeys);
        }

        int slot = 0;
        for (String key : tagKeys) {
            if (slot >= 45) break;

            String display = plugin.getConfig().getString("reasons.tags." + key, key);

            ItemStack it = ItemUtil.fromGui(plugin, "reason_tag", "%reason%", display);

            if (it == null || it.getType() == Material.AIR) it = new ItemStack(Material.NAME_TAG);

            ItemMeta meta = it.getItemMeta();
            if (meta != null) {
                String name = meta.getDisplayName();
                if (name == null || name.trim().isEmpty() || name.equals(" ")) {
                    meta.setDisplayName("§f" + display);
                }

                List<String> lore = meta.getLore();
                if (lore == null) lore = new ArrayList<>();
                lore.add("§7Нажми, чтобы выбрать");
                lore.add("§7Игрок: §f" + targetName);
                lore.add(like ? "§7Оценка: §aлайк" : "§7Оценка: §cдизлайк");
                meta.setLore(lore);

                it.setItemMeta(meta);
            }

            inventory.setItem(slot++, it);
        }

        ItemStack filler = ItemUtil.fromGui(plugin, "filler");
        for (int i = 45; i < inventory.getSize(); i++) inventory.setItem(i, filler);
        inventory.setItem(inventory.getSize() - 9, ItemUtil.fromGui(plugin, "back"));

        player.sendMessage(Colors.msg(plugin, "reasonChooseTitle", "%target%", targetName));
        player.openInventory(inventory);
    }

    @Override
    public void onClick(Player player, int rawSlot, ItemStack clicked) {
        if (rawSlot == inventory.getSize() - 9) {
            player.closeInventory();
            return;
        }

        if (rawSlot < 0 || rawSlot >= 45) return;
        if (clicked == null || clicked.getType() == Material.AIR) return;
        if (rawSlot >= tagKeys.size()) return;

        String key = tagKeys.get(rawSlot);
        player.closeInventory();

        service.voteWithReason(player, target, targetName, like, key);
    }
}