package ru.rizonchik.refontsocial.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import ru.rizonchik.refontsocial.RefontSocial;
import ru.rizonchik.refontsocial.service.ReputationService;
import ru.rizonchik.refontsocial.storage.model.PlayerRep;
import ru.rizonchik.refontsocial.util.ItemUtil;
import ru.rizonchik.refontsocial.util.NumberUtil;

import java.util.UUID;

public final class RateGui extends AbstractGui {

    private final RefontSocial plugin;
    private final ReputationService service;
    private final UUID target;
    private final String targetName;

    public RateGui(RefontSocial plugin, ReputationService service, UUID target, String targetName) {
        this.plugin = plugin;
        this.service = service;
        this.target = target;
        this.targetName = targetName != null ? targetName : "Player";
    }

    @Override
    public void open(Player player) {
        String title = plugin.getConfig().getString("gui.rate.title", "Rate");
        int size = plugin.getConfig().getInt("gui.rate.size", 27);
        if (size < 9) size = 27;
        if (size % 9 != 0) size = 27;

        inventory = Bukkit.createInventory(null, size, title);

        PlayerRep rep = service.getOrCreate(target, targetName);

        ItemStack like = ItemUtil.fromGui(plugin, "like",
                "%score%", NumberUtil.formatScore(plugin, rep.getScore()));
        ItemStack dislike = ItemUtil.fromGui(plugin, "dislike",
                "%score%", NumberUtil.formatScore(plugin, rep.getScore()));
        ItemStack info = ItemUtil.fromGui(plugin, "info",
                "%target%", targetName,
                "%score%", NumberUtil.formatScore(plugin, rep.getScore()),
                "%likes%", String.valueOf(rep.getLikes()),
                "%dislikes%", String.valueOf(rep.getDislikes()),
                "%votes%", String.valueOf(rep.getVotes())
        );

        inventory.setItem(11, like);
        inventory.setItem(15, dislike);
        inventory.setItem(13, info);

        player.openInventory(inventory);
    }

    @Override
    public void onClick(Player player, int rawSlot, ItemStack clicked) {
        if (clicked == null) return;
        Material type = clicked.getType();

        if (rawSlot == 11 && type != Material.AIR) {
            player.closeInventory();
            service.vote(player, target, targetName, true);
            return;
        }

        if (rawSlot == 15 && type != Material.AIR) {
            player.closeInventory();
            service.vote(player, target, targetName, false);
        }
    }
}