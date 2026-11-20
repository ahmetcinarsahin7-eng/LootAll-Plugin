package me.lootall;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

public final class LootAllPlugin extends JavaPlugin implements Listener {

    private record Loot(Material material, int amount, int weight) {}

    /** GUI'yi tanimak icin ozel holder. */
    private static final class WheelHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final int WHEEL_START = 9;   // orta satirin ilk slotu
    private static final int WHEEL_WIDTH = 9;
    private static final int CENTER_OFFSET = 4; // orta slot (13) = 9 + 4

    private final List<Loot> loot = new ArrayList<>();
    private final Random random = new Random();
    private boolean spinning = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadLoot();
        getServer().getPluginManager().registerEvents(this, this);
    }

    private void loadLoot() {
        loot.clear();
        for (Map<?, ?> map : getConfig().getMapList("items")) {
            Object matObj = map.get("material");
            Material mat = matObj == null ? null : Material.matchMaterial(String.valueOf(matObj));
            if (mat == null || !mat.isItem()) {
                getLogger().warning("Gecersiz esya atlandi: " + matObj);
                continue;
            }
            int amount = map.get("amount") instanceof Number n ? Math.max(1, n.intValue()) : 1;
            int weight = map.get("weight") instanceof Number n ? Math.max(1, n.intValue()) : 1;
            loot.add(new Loot(mat, amount, weight));
        }
        getLogger().info(loot.size() + " esya yuklendi.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadLoot();
            sender.sendMessage(Component.text("Config yenilendi. " + loot.size() + " esya yuklendi.", NamedTextColor.GREEN));
            return true;
        }

        if (loot.isEmpty()) {
            sender.sendMessage(Component.text("config.yml icinde gecerli esya yok!", NamedTextColor.RED));
            return true;
        }
        if (spinning) {
            sender.sendMessage(Component.text("Cark zaten donuyor!", NamedTextColor.RED));
            return true;
        }
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            return true;
        }

        startSpin();
        return true;
    }

    // ------------------------------------------------------------------
    // Cark animasyonu
    // ------------------------------------------------------------------

    private void startSpin() {
        spinning = true;

        Loot winner = pickWeighted();
        int steps = 40 + random.nextInt(10);

        // Seritteki esyalar; kazanan, animasyon bitince tam ortada kalacak yere konur.
        List<Loot> strip = new ArrayList<>();
        for (int i = 0; i < steps + WHEEL_WIDTH; i++) {
            strip.add(loot.get(random.nextInt(loot.size())));
        }
        strip.set(steps + CENTER_OFFSET, winner);

        WheelHolder holder = new WheelHolder();
        Inventory inv = Bukkit.createInventory(holder, 27,
                Component.text("Şans Çarkı", NamedTextColor.GOLD, TextDecoration.BOLD));
        holder.inventory = inv;

        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "));
        for (int i = 0; i < 27; i++) {
            inv.setItem(i, filler);
        }
        inv.setItem(4, named(Material.HOPPER, Component.text("▼", NamedTextColor.YELLOW)));
        inv.setItem(22, named(Material.HOPPER, Component.text("▲", NamedTextColor.YELLOW)));

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.openInventory(inv);
        }

        step(inv, strip, 0, steps, winner);
    }

    private void step(Inventory inv, List<Loot> strip, int frame, int steps, Loot winner) {
        // Seridi kaydir
        for (int i = 0; i < WHEEL_WIDTH; i++) {
            Loot l = strip.get(frame + i);
            inv.setItem(WHEEL_START + i, new ItemStack(l.material(), Math.min(l.amount(), 64)));
        }

        // Tik sesi
        float pitch = 0.8f + (float) frame / steps * 0.8f;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, pitch);
        }

        if (frame >= steps) {
            Bukkit.getScheduler().runTaskLater(this, () -> finish(winner), 15L);
            return;
        }

        // Giderek yavaslayan hiz
        double p = (double) frame / steps;
        long delay = p < 0.5 ? 1 : p < 0.7 ? 2 : p < 0.85 ? 3 : p < 0.95 ? 5 : 8;

        Bukkit.getScheduler().runTaskLater(this,
                () -> step(inv, strip, frame + 1, steps, winner), delay);
    }

    private void finish(Loot winner) {
        Component itemName = Component.text(winner.amount() + "x ", NamedTextColor.YELLOW)
                .append(Component.translatable(winner.material().translationKey(), NamedTextColor.YELLOW));

        for (Player p : Bukkit.getOnlinePlayers()) {
            ItemStack reward = new ItemStack(winner.material(), winner.amount());
            Map<Integer, ItemStack> leftover = p.getInventory().addItem(reward);
            for (ItemStack rest : leftover.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), rest);
            }

            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
            p.showTitle(Title.title(
                    Component.text("KAZANAN!", NamedTextColor.GOLD, TextDecoration.BOLD),
                    itemName));
            p.sendMessage(Component.text("Çarktan çıkan ödül herkese verildi: ", NamedTextColor.GREEN)
                    .append(itemName));
        }

        // Biraz bekleyip GUI'yi kapat
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getOpenInventory().getTopInventory().getHolder() instanceof WheelHolder) {
                    p.closeInventory();
                }
            }
            spinning = false;
        }, 40L);
    }

    private Loot pickWeighted() {
        int total = 0;
        for (Loot l : loot) total += l.weight();
        int roll = random.nextInt(total);
        for (Loot l : loot) {
            roll -= l.weight();
            if (roll < 0) return l;
        }
        return loot.get(0);
    }

    private ItemStack named(Material material, Component name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    // ------------------------------------------------------------------
    // GUI'den esya alinmasini engelle
    // ------------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof WheelHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof WheelHolder) {
            event.setCancelled(true);
        }
    }
}
