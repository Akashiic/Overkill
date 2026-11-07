package com.akashic.overkill.bukkit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import com.akashic.overkill.core.Blade;
import com.akashic.overkill.core.BladeStats;
import com.akashic.overkill.core.Forge;
import com.akashic.overkill.core.SealTier;
import com.akashic.overkill.core.StoneTier;

/**
 * The forge (/forja): fortifies a katana, the AkashicFishing Upgrader's ladder, in a chest:
 *
 * <pre>
 *   .  [ofuda]  .   .  [pedra]  .   .   .   .
 *   . [katana]  ~   ~  [estado] ~   ~ [saída] .
 * [rep]   .   [  A P R I M O R A R  ]   .  [%]
 * </pre>
 *
 * The talisman and the whetstone (or the tamahagane, for the Ascension) are optional. A try pays its XP levels and the
 * stone at the click; the pipe ({@code ~}) then counts up to the roll, slowing down, and turns green or red; the
 * katana comes out on the right, up or not. The anvil on the left repairs the katana for proud soul. Closing the chest
 * gives everything back, a try in course already applied. Main thread.
 */
public final class ForgeGui implements Listener {

    static final int SIZE = 27;
    static final int SEAL_SLOT = 1;
    static final int STONE_SLOT = 4;
    static final int KATANA_SLOT = 10;
    static final int STATUS_SLOT = 13;
    static final int OUTPUT_SLOT = 16;
    static final int REPAIR_SLOT = 18;
    static final int ODDS_SLOT = 26;
    static final int[] PIPE = { 11, 12, 14, 15 };
    static final int[] BUTTON = { 20, 21, 22, 23, 24 };
    static final int[] INPUTS = { KATANA_SLOT, STONE_SLOT, SEAL_SLOT };
    static final String TITLE = "Forja";
    /** Ticks of the roll and of the result on show. */
    static final int ROLL_TICKS = 24;
    static final int RESULT_TICKS = 30;

    // stained glass colours
    private static final short WHITE = 0, ORANGE = 1, YELLOW = 4, LIME = 5, GRAY = 7, LIGHT_GRAY = 8, RED = 14,
        BLACK = 15;

    private final Plugin plugin;
    private final BladeItems blades;
    private final ForgeItems materials;
    private final Forge forge;
    private final Random random = new Random();
    private final Map<Inventory, Holder> open = new HashMap<>();
    /** From which fortification a success is announced to the server (0: never). */
    private int announceFrom = 10;

    private static final class Holder implements InventoryHolder {

        Inventory inventory;
        Player player;
        /** A try on show: the katana it is about (already changed), its outcome, and the animation. */
        ItemStack pending;
        Forge.Outcome outcome;
        BukkitTask task;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public ForgeGui(Plugin plugin, BladeItems blades, ForgeItems materials, Forge forge) {
        this.plugin = plugin;
        this.blades = blades;
        this.materials = materials;
        this.forge = forge;
    }

    public void announceFrom(int from) {
        this.announceFrom = from;
    }

    public void open(Player player) {
        Holder h = new Holder();
        Inventory inv = Bukkit.createInventory(h, SIZE, TITLE);
        h.inventory = inv;
        h.player = player;
        open.put(inv, h);
        refresh(h);
        player.openInventory(inv);
    }

    /** Closes every open forge (the plugin is stopping): everything goes back. */
    public void closeAll() {
        for (Holder h : new ArrayList<>(open.values())) {
            h.player.closeInventory();
            giveBack(h);
        }
        open.clear();
    }

    // ---- drawing ----------------------------------------------------------------------------------------------------

    private static ItemStack icon(Material m, short data, String name, List<String> lore) {
        ItemStack it = new ItemStack(m, 1, data);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack pane(short color, String name, String... lore) {
        return icon(Material.STAINED_GLASS_PANE, color, name, Arrays.asList(lore));
    }

    private static String percent(double v) {
        double p = v * 100;
        String s = p >= 10 || p == Math.floor(p) ? String.format(Locale.ROOT, "%.0f", p)
            : p >= 1 ? String.format(Locale.ROOT, "%.1f", p) : String.format(Locale.ROOT, "%.2f", p);
        return s.replace('.', ',') + "%";
    }

    private static short chanceColor(double chance) {
        return chance >= 0.5 ? LIME : chance >= 0.2 ? YELLOW : chance >= 0.05 ? ORANGE : RED;
    }

    private boolean isDecor(int raw) {
        return raw >= 0 && raw < SIZE && raw != SEAL_SLOT && raw != STONE_SLOT && raw != KATANA_SLOT
            && raw != OUTPUT_SLOT;
    }

    /** Redraws everything but the inputs and the output from what is in them. */
    private void refresh(Holder h) {
        Inventory inv = h.inventory;
        for (int i = 0; i < SIZE; i++) {
            if (isDecor(i)) {
                inv.setItem(i, pane(BLACK, " "));
            }
        }
        inv.setItem(0, pane(BLACK, ChatColor.GRAY + "Ofuda de Proteção " + ChatColor.DARK_GRAY + "(opcional) →"));
        inv.setItem(3, pane(BLACK, ChatColor.GRAY + "Pedra de Afiar ou Tamahagane " + ChatColor.DARK_GRAY + "(opcional) →"));
        inv.setItem(9, pane(BLACK, ChatColor.GRAY + "Katana →"));
        inv.setItem(17, pane(BLACK, ChatColor.GRAY + "← Saída"));
        BladeItems.Held held = blades.read(inv.getItem(KATANA_SLOT));
        Player p = h.player;
        if (held == null) {
            for (int s : PIPE) {
                inv.setItem(s, pane(GRAY, ChatColor.DARK_GRAY + "Sem katana"));
            }
            inv.setItem(STATUS_SLOT, icon(Material.NETHER_STAR, (short) 0, ChatColor.GOLD + "Forja",
                Arrays.asList(ChatColor.GRAY + "Coloque uma katana à esquerda.",
                    ChatColor.GRAY + "Pedras de afiar multiplicam a chance; ofudas",
                    ChatColor.GRAY + "seguram a regressão de uma tentativa que falha.",
                    ChatColor.DARK_GRAY + "Pedras, ofudas e tamahagane caem dos inimigos",
                    ChatColor.DARK_GRAY + "abatidos com uma katana.")));
            button(inv, GRAY, ChatColor.DARK_GRAY + "APRIMORAR", ChatColor.GRAY + "Coloque uma katana.");
            inv.setItem(REPAIR_SLOT, icon(Material.ANVIL, (short) 0, ChatColor.DARK_GRAY + "Reparar",
                Arrays.asList(ChatColor.GRAY + "Coloque uma katana.")));
            inv.setItem(ODDS_SLOT, pane(BLACK, " "));
            return;
        }
        Blade b = held.blade;
        BladeStats s = held.stats;
        int level = s.level(blades.leveling());
        Forge.Step step = forge.next(s.enhancement);
        StoneTier stone = materials.stoneTier(inv.getItem(STONE_SLOT));
        boolean tama = materials.isTamahagane(inv.getItem(STONE_SLOT));
        SealTier seal = materials.sealTier(inv.getItem(SEAL_SLOT));
        boolean busy = !isEmpty(inv.getItem(OUTPUT_SLOT));
        repairIcon(inv, b, s);
        if (step == null) {
            for (int i : PIPE) {
                inv.setItem(i, pane(LIGHT_GRAY, ChatColor.LIGHT_PURPLE + "Ascendida"));
            }
            inv.setItem(STATUS_SLOT, icon(Material.NETHER_STAR, (short) 0,
                ChatColor.LIGHT_PURPLE + "ASCENSÃO", Arrays.asList(ChatColor.GRAY + "Esta katana chegou ao topo.",
                    ChatColor.GRAY + "Atributos ×" + BladeItems.number(Forge.multiplier(s.enhancement)))));
            button(inv, GRAY, ChatColor.DARK_GRAY + "APRIMORAR", ChatColor.GRAY + "Já ascendida.");
            inv.setItem(ODDS_SLOT, pane(BLACK, " "));
            return;
        }
        double chance = forge.chance(step, stone);
        double regression = forge.regression(step, s.enhancement, seal);
        List<String> lore = new ArrayList<>();
        lore.add((level >= step.minLevel ? ChatColor.GREEN : ChatColor.RED) + "Nível da katana: " + level + " / "
            + step.minLevel);
        lore.add((p.getLevel() >= step.cost ? ChatColor.GREEN : ChatColor.RED) + "Custo: " + step.cost
            + " nível(is) de XP");
        if (step.ascension()) {
            lore.add((tama ? ChatColor.GREEN : ChatColor.RED) + "Pede um Tamahagane no espaço da pedra");
        }
        lore.add(ChatColor.GRAY + "Chance: " + ChatColor.WHITE + percent(chance)
            + (stone != null && !step.ascension() && chance > step.chance
                ? " §" + stone.colorCode() + "(+" + percent(chance - step.chance) + " da pedra)" : ""));
        lore.add(ChatColor.GRAY + "Regressão na falha: " + (regression > 0 ? ChatColor.RED : ChatColor.GREEN)
            + percent(regression) + (seal != null && regression < step.regression ? ChatColor.DARK_GRAY + " (ofuda)"
                : ""));
        lore.add(ChatColor.GRAY + "Atributos: ×" + BladeItems.number(Forge.multiplier(s.enhancement)) + " → "
            + ChatColor.GREEN + "×" + BladeItems.number(Forge.multiplier(step.target)));
        inv.setItem(STATUS_SLOT, icon(Material.NETHER_STAR, (short) 0,
            ChatColor.GOLD + Forge.label(s.enhancement) + " → " + BladeItems.enhancementColor(step.target)
                + Forge.label(step.target), lore));
        for (int i : PIPE) {
            inv.setItem(i, pane(chanceColor(chance), ChatColor.GRAY + "Chance " + percent(chance)));
        }
        String why = busy ? "Tire a katana da saída."
            : level < step.minLevel ? "A katana precisa do nível " + step.minLevel + "."
                : step.ascension() && !tama ? "Falta o Tamahagane."
                    : p.getLevel() < step.cost ? "Faltam níveis de XP (" + step.cost + ")." : null;
        if (why == null) {
            button(inv, LIME, ChatColor.GREEN + "" + ChatColor.BOLD + "APRIMORAR",
                ChatColor.GRAY + "Para " + Forge.label(step.target) + ": " + percent(chance) + " de sucesso.");
        } else {
            button(inv, GRAY, ChatColor.DARK_GRAY + "APRIMORAR", ChatColor.RED + why);
        }
        boolean success = chance >= 0.5;
        inv.setItem(ODDS_SLOT, icon(Material.PAPER, (short) 0,
            (success ? ChatColor.GREEN : ChatColor.RED) + percent(success ? chance : 1 - chance),
            Arrays.asList(ChatColor.GRAY + (success ? percent(chance) + " de sucesso"
                : percent(1 - chance) + " de falha"))));
    }

    private static void button(Inventory inv, short color, String name, String... lore) {
        for (int s : BUTTON) {
            inv.setItem(s, pane(color, name, lore));
        }
    }

    private void repairIcon(Inventory inv, Blade b, BladeStats s) {
        int cost = s.repairCost();
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Durabilidade: " + (b.durability(s) - s.wear) + "/" + b.durability(s));
        if (s.wear == 0) {
            lore.add(ChatColor.GREEN + "Nada a reparar.");
        } else {
            lore.add((s.proudSoul >= cost ? ChatColor.GREEN : ChatColor.RED) + "Custo: " + cost
                + " almas (a katana tem " + s.proudSoul + ")");
            lore.add(ChatColor.DARK_GRAY + "Clique para reparar tudo.");
        }
        inv.setItem(REPAIR_SLOT, icon(Material.ANVIL, (short) 0,
            (s.wear == 0 ? ChatColor.DARK_GRAY : ChatColor.YELLOW) + "Reparar", lore));
    }

    private static boolean isEmpty(ItemStack it) {
        return it == null || it.getType() == Material.AIR;
    }

    private boolean fits(int slot, ItemStack it) {
        if (isEmpty(it)) {
            return false;
        }
        switch (slot) {
            case KATANA_SLOT:
                return blades.read(it) != null && it.getAmount() == 1;
            case STONE_SLOT:
                return materials.stoneTier(it) != null || materials.isTamahagane(it);
            case SEAL_SLOT:
                return materials.sealTier(it) != null;
            default:
                return false;
        }
    }

    // ---- clicks -----------------------------------------------------------------------------------------------------

    private Holder holder(Inventory top) {
        return top == null ? null : open.get(top);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        Holder h = holder(e.getView()
            .getTopInventory());
        if (h == null || !(e.getWhoClicked() instanceof Player)) {
            return;
        }
        if (h.pending != null || e.getClick() == ClickType.DOUBLE_CLICK) {
            // nothing moves while the pipe runs; a double click would gather the panes onto the cursor
            e.setCancelled(true);
            return;
        }
        Inventory top = h.inventory;
        int raw = e.getRawSlot();
        ItemStack cursor = e.getCursor();
        boolean keyboard = e.getClick()
            .isKeyboardClick();
        if (raw == OUTPUT_SLOT) {
            if (!isEmpty(cursor) || keyboard) {
                e.setCancelled(true);
            }
        } else if (raw == KATANA_SLOT || raw == STONE_SLOT || raw == SEAL_SLOT) {
            boolean ok = isEmpty(cursor) || fits(raw, cursor);
            if (!ok || keyboard || (e.isShiftClick() && raw != KATANA_SLOT && raw != STONE_SLOT && raw != SEAL_SLOT)) {
                e.setCancelled(true);
            } else if (raw == SEAL_SLOT && !isEmpty(cursor) && cursor.getAmount() > 1) {
                e.setCancelled(true); // one talisman at a time (they never stack anyway)
            } else if (raw == KATANA_SLOT && !isEmpty(cursor) && cursor.getAmount() > 1) {
                e.setCancelled(true);
            }
        } else if (raw >= 0 && raw < SIZE) {
            e.setCancelled(true);
            if (contains(BUTTON, raw)) {
                ItemStack c = e.getCursor();
                if (fits(KATANA_SLOT, c) && isEmpty(top.getItem(KATANA_SLOT))) {
                    top.setItem(KATANA_SLOT, c.clone());
                    e.getView()
                        .setCursor(null);
                }
                attempt(h);
                return;
            }
            if (raw == REPAIR_SLOT) {
                repair(h);
                return;
            }
        } else if (e.isShiftClick()) {
            e.setCancelled(true);
            ItemStack clicked = e.getCurrentItem();
            for (int slot : INPUTS) {
                if (fits(slot, clicked) || slot == KATANA_SLOT && blades.read(clicked) != null) {
                    moveInto(top, slot, clicked, e);
                    break;
                }
            }
        }
        Bukkit.getScheduler()
            .runTask(plugin, () -> {
                if (open.containsKey(top) && h.pending == null) {
                    refresh(h);
                }
            });
    }

    private static boolean contains(int[] slots, int raw) {
        for (int s : slots) {
            if (s == raw) {
                return true;
            }
        }
        return false;
    }

    /** Shift+click from the player's inventory: into the slot, whole or as much as stacks; katanas and talismans alone. */
    private void moveInto(Inventory top, int slot, ItemStack clicked, InventoryClickEvent e) {
        ItemStack there = top.getItem(slot);
        if (isEmpty(there)) {
            if (slot != STONE_SLOT && clicked.getAmount() > 1) {
                ItemStack one = clicked.clone();
                one.setAmount(1);
                top.setItem(slot, one);
                clicked.setAmount(clicked.getAmount() - 1);
                e.setCurrentItem(clicked);
                return;
            }
            top.setItem(slot, clicked.clone());
            e.setCurrentItem(null);
        } else if (slot == STONE_SLOT && there.isSimilar(clicked)) {
            int move = Math.min(clicked.getAmount(), there.getMaxStackSize() - there.getAmount());
            there.setAmount(there.getAmount() + move);
            top.setItem(slot, there);
            if (move >= clicked.getAmount()) {
                e.setCurrentItem(null);
            } else {
                clicked.setAmount(clicked.getAmount() - move);
                e.setCurrentItem(clicked);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        Holder h = holder(e.getView()
            .getTopInventory());
        if (h == null) {
            return;
        }
        for (int raw : e.getRawSlots()) {
            if (raw < SIZE) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Holder h = open.remove(e.getInventory());
        if (h != null) {
            giveBack(h);
        }
    }

    private void giveBack(Holder h) {
        if (h.task != null) {
            h.task.cancel();
            h.task = null;
        }
        List<ItemStack> back = new ArrayList<>();
        if (h.pending != null) {
            back.add(h.pending);
            h.pending = null;
        }
        for (int s : new int[] { KATANA_SLOT, STONE_SLOT, SEAL_SLOT, OUTPUT_SLOT }) {
            ItemStack it = h.inventory.getItem(s);
            if (!isEmpty(it)) {
                back.add(it);
                h.inventory.setItem(s, null);
            }
        }
        HumanEntity p = h.player;
        for (ItemStack it : back) {
            for (ItemStack left : p.getInventory()
                .addItem(it)
                .values()) {
                p.getWorld()
                    .dropItem(p.getLocation(), left);
            }
        }
    }

    // ---- the try ----------------------------------------------------------------------------------------------------

    private void repair(Holder h) {
        Inventory inv = h.inventory;
        ItemStack it = inv.getItem(KATANA_SLOT);
        BladeItems.Held held = blades.read(it);
        Player p = h.player;
        if (held == null || held.stats.wear == 0) {
            return;
        }
        int cost = held.stats.repairCost();
        if (!held.stats.spend(cost)) {
            p.sendMessage(ChatColor.RED + "A katana não tem almas para o reparo (" + cost + ").");
            p.playSound(p.getLocation(), Sound.ANVIL_LAND, 0.6f, 1.4f);
            return;
        }
        held.stats.wear = 0;
        blades.write(it, held.blade, held.stats);
        inv.setItem(KATANA_SLOT, it);
        p.playSound(p.getLocation(), Sound.ANVIL_USE, 0.8f, 1.2f);
        p.sendMessage(ChatColor.GREEN + "Katana reparada por " + cost + " almas.");
        refresh(h);
    }

    private void attempt(Holder h) {
        Inventory inv = h.inventory;
        Player p = h.player;
        ItemStack katana = inv.getItem(KATANA_SLOT);
        BladeItems.Held held = blades.read(katana);
        if (held == null || !isEmpty(inv.getItem(OUTPUT_SLOT))) {
            return;
        }
        BladeStats s = held.stats;
        ItemStack stoneItem = inv.getItem(STONE_SLOT);
        StoneTier stone = materials.stoneTier(stoneItem);
        boolean tama = materials.isTamahagane(stoneItem);
        ItemStack sealItem = inv.getItem(SEAL_SLOT);
        SealTier seal = materials.sealTier(sealItem);
        int before = s.enhancement;
        Forge.Outcome o = forge.upgrade(before, s.level(blades.leveling()), p.getLevel(), stone, tama, seal, random);
        if (!o.tried()) {
            p.playSound(p.getLocation(), Sound.ANVIL_LAND, 0.4f, 1.6f);
            refresh(h);
            return;
        }
        p.setLevel(p.getLevel() - o.step.cost);
        if (stone != null || tama) {
            consume(inv, STONE_SLOT, stoneItem);
        }
        if (o.sealSaved) {
            consume(inv, SEAL_SLOT, sealItem);
        }
        s.enhancement = o.enhancement;
        blades.write(katana, held.blade, s);
        inv.setItem(KATANA_SLOT, null);
        h.pending = katana;
        h.outcome = o;
        animate(h, held.blade, before);
    }

    private static void consume(Inventory inv, int slot, ItemStack it) {
        if (it.getAmount() <= 1) {
            inv.setItem(slot, null);
        } else {
            it.setAmount(it.getAmount() - 1);
            inv.setItem(slot, it);
        }
    }

    /** The pipe counts up to the roll, slowing down; then the result, then the katana to the output. */
    private void animate(Holder h, Blade blade, int before) {
        Inventory inv = h.inventory;
        Forge.Outcome o = h.outcome;
        Player p = h.player;
        button(inv, ORANGE, ChatColor.GOLD + "...", ChatColor.GRAY + "Sorteando.");
        inv.setItem(ODDS_SLOT, icon(Material.PAPER, (short) 0, ChatColor.GOLD + "...", new ArrayList<String>()));
        final int[] t = { 0 };
        h.task = Bukkit.getScheduler()
            .runTaskTimer(plugin, () -> {
                int tick = ++t[0];
                if (tick <= ROLL_TICKS) {
                    double x = tick / (double) ROLL_TICKS;
                    double shown = o.roll * (1 - Math.pow(1 - x, 3)); // eases out
                    int lit = (int) Math.ceil(Math.min(1, shown) * PIPE.length);
                    for (int i = 0; i < PIPE.length; i++) {
                        inv.setItem(PIPE[i], pane(i < lit ? WHITE : GRAY, ChatColor.WHITE + percent(shown)));
                    }
                    inv.setItem(STATUS_SLOT, icon(Material.NETHER_STAR, (short) 0,
                        ChatColor.GOLD + "Sorteio " + percent(shown), Arrays.asList(
                            ChatColor.GRAY + "Chance " + percent(o.chance))));
                    if (tick % 3 == 0) {
                        p.playSound(p.getLocation(), Sound.CLICK, 0.4f, 0.8f + (float) x);
                    }
                    return;
                }
                if (tick == ROLL_TICKS + 1) {
                    result(h, blade, before);
                    return;
                }
                if (tick >= ROLL_TICKS + RESULT_TICKS) {
                    h.task.cancel();
                    h.task = null;
                    inv.setItem(OUTPUT_SLOT, h.pending);
                    h.pending = null;
                    h.outcome = null;
                    refresh(h);
                }
            }, 1, 1);
    }

    private void result(Holder h, Blade blade, int before) {
        Inventory inv = h.inventory;
        Forge.Outcome o = h.outcome;
        Player p = h.player;
        boolean up = o.result == Forge.Result.UPGRADED;
        for (int s : PIPE) {
            inv.setItem(s, pane(up ? LIME : RED, (up ? ChatColor.GREEN : ChatColor.RED) + percent(o.roll)));
        }
        String name = ChatColor.translateAlternateColorCodes('&', blade.name);
        String numbers = ChatColor.DARK_GRAY + " (sorteio " + percent(o.roll) + ", chance " + percent(o.chance) + ")";
        String title;
        String line;
        if (up) {
            title = ChatColor.GREEN + "" + ChatColor.BOLD + "SUCESSO " + Forge.label(o.enhancement);
            line = ChatColor.GREEN + "A " + name + ChatColor.GREEN + " subiu para " + Forge.label(o.enhancement) + "!";
            p.playSound(p.getLocation(), Sound.LEVEL_UP, 1.0f, 1.0f);
            p.playSound(p.getLocation(), Sound.ANVIL_USE, 0.7f, 1.3f);
        } else if (o.result == Forge.Result.REGRESSED) {
            title = ChatColor.DARK_RED + "" + ChatColor.BOLD + "REGREDIU " + Forge.label(o.enhancement);
            line = ChatColor.RED + "Falhou e a " + name + ChatColor.RED + " voltou para " + Forge.label(o.enhancement)
                + ".";
            p.playSound(p.getLocation(), Sound.ITEM_BREAK, 1.0f, 0.8f);
        } else {
            title = ChatColor.RED + "" + ChatColor.BOLD + "FALHOU";
            line = ChatColor.RED + "Falhou: a " + name + ChatColor.RED + " continua em " + Forge.label(before) + "."
                + (o.sealSaved ? ChatColor.AQUA + " O ofuda segurou a regressão." : "");
            p.playSound(p.getLocation(), Sound.ANVIL_LAND, 0.8f, 1.0f);
        }
        inv.setItem(STATUS_SLOT, icon(Material.NETHER_STAR, (short) 0, title,
            Arrays.asList(ChatColor.GRAY + "Sorteio " + percent(o.roll) + ", chance " + percent(o.chance))));
        p.sendMessage(line + numbers);
        if (up && announceFrom > 0 && o.enhancement >= announceFrom) {
            String msg = ChatColor.GOLD + p.getName() + ChatColor.GRAY + " fortificou a " + name + ChatColor.GRAY
                + " para " + BladeItems.enhancementColor(o.enhancement) + Forge.label(o.enhancement) + ChatColor.GRAY
                + " (chance de " + percent(o.chance) + ")!";
            for (org.bukkit.World w : Bukkit.getWorlds()) {
                for (Player each : w.getPlayers()) {
                    each.sendMessage(msg);
                }
            }
        }
    }
}
