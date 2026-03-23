package land.jarochitoland.townybankbridge;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.event.DeleteTownEvent;
import com.palmergames.bukkit.towny.event.PlotChangeTypeEvent;
import com.palmergames.bukkit.towny.event.TownBlockSettingsChangedEvent;
import com.palmergames.bukkit.towny.event.plot.PlayerChangePlotTypeEvent;
import com.palmergames.bukkit.towny.event.town.TownUnclaimEvent;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.TownBlock;
import com.palmergames.bukkit.towny.object.TownBlockType;
import com.palmergames.bukkit.towny.object.WorldCoord;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.trait.LookClose;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class TownyBankBridgePlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final String META_ROLE = "tbe.role";
    private static final String META_WORLD = "tbe.world";
    private static final String META_X = "tbe.x";
    private static final String META_Z = "tbe.z";
    private static final String META_TOWN = "tbe.town";

    private static final String LEGACY_META_ROLE = "tbb.role";
    private static final String LEGACY_META_WORLD = "tbb.world";
    private static final String LEGACY_META_X = "tbb.x";
    private static final String LEGACY_META_Z = "tbb.z";
    private static final String LEGACY_META_TOWN = "tbb.town";

    private final Map<PlotKey, ManagedPlot> plots = new HashMap<>();
    private final Map<UUID, TaxPromptSession> taxPrompts = new HashMap<>();
    private boolean startupSyncCompleted;

    private File dataFile;
    private YamlConfiguration dataConfig;

    @Override
    public void onEnable() {
        migrateLegacyFolderIfNeeded();
        saveDefaultConfig();

        if (!isPluginEnabled("Towny")) {
            getLogger().severe("Towny no esta habilitado. Deshabilitando TownyBagEconomy.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        if (!isPluginEnabled("Citizens")) {
            getLogger().severe("Citizens no esta habilitado. Deshabilitando TownyBagEconomy.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.getParentFile().exists()) {
            dataFile.getParentFile().mkdirs();
        }
        if (!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("No se pudo crear data.yml: " + e.getMessage());
            }
        }

        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        loadData();

        Objects.requireNonNull(getCommand("tbe"), "command tbe missing").setExecutor(this);
        Objects.requireNonNull(getCommand("tbe"), "command tbe missing").setTabCompleter(this);

        Bukkit.getPluginManager().registerEvents(this, this);
        // Fallback for uncommon reload flows where ServerLoadEvent may not fire for this plugin.
        scheduleStartupSync(240L, "fallback-enable", true);
    }

    @Override
    public void onDisable() {
        saveData();
        taxPrompts.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlotTypeChange(PlotChangeTypeEvent event) {
        handlePlotTypeChange(event.getTownBlock(), event.getOldType(), event.getNewType());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerPlotTypeChange(PlayerChangePlotTypeEvent event) {
        handlePlotTypeChange(event.getTownBlock(), event.getOldType(), event.getNewType());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTownBlockSettingsChanged(TownBlockSettingsChangedEvent event) {
        if (!startupSyncCompleted) {
            return;
        }
        TownBlock townBlock = event.getTownBlock();
        if (townBlock == null) {
            return;
        }
        reconcileTownBlock(townBlock);
    }

    private void handlePlotTypeChange(TownBlock townBlock, TownBlockType oldType, TownBlockType newType) {
        if (!startupSyncCompleted) {
            return;
        }
        if (townBlock == null) {
            return;
        }

        Town town = townBlock.getTownOrNull();
        if (town == null) {
            return;
        }

        PlotKey key = PlotKey.from(townBlock.getWorldCoord(), town.getUUID());

        boolean wasBank = isBankType(oldType);
        boolean isBank = isBankType(newType);

        if (!wasBank && isBank) {
            ensurePairForPlot(key, townBlock, town, true);
            saveData();
            return;
        }

        if (wasBank && !isBank) {
            removePairForKey(key, true);
            saveData();
        }
    }

    private void reconcileTownBlock(TownBlock townBlock) {
        Town town = townBlock.getTownOrNull();
        if (town == null) {
            return;
        }

        PlotKey key = PlotKey.from(townBlock.getWorldCoord(), town.getUUID());
        if (isBankType(townBlock.getType())) {
            ensurePairForPlot(key, townBlock, town, true);
        } else {
            removePairForKey(key, true);
        }
        saveData();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTownUnclaim(TownUnclaimEvent event) {
        if (!startupSyncCompleted) {
            return;
        }
        PlotKey key = PlotKey.from(event.getWorldCoord(), event.getTown().getUUID());
        removePairForKey(key, true);
        saveData();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTownDelete(DeleteTownEvent event) {
        if (!startupSyncCompleted) {
            return;
        }
        String townUuid = event.getTownUUID().toString();
        List<PlotKey> toRemove = new ArrayList<>();
        for (PlotKey key : plots.keySet()) {
            if (key.townUuid.equals(townUuid)) {
                toRemove.add(key);
            }
        }
        for (PlotKey key : toRemove) {
            removePairForKey(key, true);
        }
        saveData();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerLoad(ServerLoadEvent event) {
        // Main pass creates missing NPCs once Citizens has finished loading.
        scheduleStartupSync(60L, "server-load", true);
        // Late pass verifies/reconciles only, without creating new NPCs.
        scheduleStartupSync(220L, "server-load-verify", false);
    }

    private void scheduleStartupSync(long delayTicks, String reason, boolean allowCreate) {
        Bukkit.getScheduler().runTaskLater(this, () -> runStartupSync(reason, allowCreate), delayTicks);
    }

    private void runStartupSync(String reason, boolean allowCreate) {
        try {
            if (allowCreate && startupSyncCompleted) {
                getLogger().fine("Saltando sync inicial (" + reason + ") porque ya fue completado.");
                return;
            }
            syncFromTowny(allowCreate);
            if (allowCreate) {
                startupSyncCompleted = true;
            }
            getLogger().info("TownyBagEconomy sync inicial completado (" + reason + ").");
        } catch (Exception e) {
            getLogger().severe("Fallo en sync inicial (" + reason + "): " + e.getMessage());
            e.printStackTrace();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNpcRightClick(NPCRightClickEvent event) {
        NPC npc = event.getNPC();
        NpcRole role = roleFromNpcMetadata(npc);
        if (role == null) {
            return;
        }

        PlotKey key = keyFromNpcMetadata(npc);
        if (key == null) {
            return;
        }

        Player player = event.getClicker();
        AccessResult access = checkPlotAccess(player, key, false);
        if (!access.allowed) {
            event.setCancelled(true);
            player.sendMessage(color(msg("messages.denied-plot")));
            return;
        }

        if (role == NpcRole.TAX) {
            event.setCancelled(true);
            startTaxPrompt(player, key, access.town);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        taxPrompts.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTaxPromptChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        TaxPromptSession session = taxPrompts.get(player.getUniqueId());
        if (session == null) {
            return;
        }

        event.setCancelled(true);
        String input = event.getMessage() == null ? "" : event.getMessage().trim();

        if (input.equalsIgnoreCase("cancel") || input.equalsIgnoreCase("cancelar")) {
            taxPrompts.remove(player.getUniqueId());
            player.sendMessage(color(msg("messages.tax-cancel")));
            return;
        }

        int amount;
        try {
            amount = Integer.parseInt(input);
        } catch (NumberFormatException ex) {
            player.sendMessage(color(msg("messages.tax-invalid")));
            return;
        }

        if (amount <= 0) {
            player.sendMessage(color(msg("messages.tax-invalid")));
            return;
        }

        Bukkit.getScheduler().runTask(this, () -> processTaxPrompt(player, session, amount));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(color("&e/tbe npc tphere <investment|tax>"));
            sender.sendMessage(color("&e/tbe sync"));
            sender.sendMessage(color("&e/tbe reload"));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "sync" -> {
                if (!sender.hasPermission("townybageconomy.admin")) {
                    sender.sendMessage(color("&cSin permiso."));
                    return true;
                }
                syncFromTowny(true);
                sender.sendMessage(color(msg("messages.sync-done")));
                return true;
            }
            case "reload" -> {
                if (!sender.hasPermission("townybageconomy.admin")) {
                    sender.sendMessage(color("&cSin permiso."));
                    return true;
                }
                reloadConfig();
                sender.sendMessage(color("&aTownyBagEconomy recargado."));
                return true;
            }
            case "npc" -> {
                return handleNpcCommand(sender, args);
            }
            default -> {
                sender.sendMessage(color("&cSubcomando desconocido."));
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return partial(args[0], List.of("npc", "sync", "reload"));
        }
        if (args.length == 2 && "npc".equalsIgnoreCase(args[0])) {
            return partial(args[1], List.of("tphere"));
        }
        if (args.length == 3 && "npc".equalsIgnoreCase(args[0]) && "tphere".equalsIgnoreCase(args[1])) {
            return partial(args[2], List.of("investment", "tax"));
        }
        return Collections.emptyList();
    }

    private boolean handleNpcCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&cSolo jugadores."));
            return true;
        }

        if (args.length < 3 || !"tphere".equalsIgnoreCase(args[1])) {
            player.sendMessage(color("&eUso: /tbe npc tphere <investment|tax>"));
            return true;
        }

        if (!player.hasPermission("townybageconomy.npc.move")) {
            player.sendMessage(color("&cSin permiso."));
            return true;
        }

        NpcRole role = NpcRole.fromString(args[2]);
        if (role == null) {
            player.sendMessage(color("&cRol invalido. Usa investment o tax."));
            return true;
        }

        TownyAPI api = TownyAPI.getInstance();
        TownBlock current = api.getTownBlock(player);
        if (current == null || !isBankType(current.getType())) {
            player.sendMessage(color(msg("messages.not-in-bank-plot")));
            return true;
        }

        Town town = current.getTownOrNull();
        if (town == null) {
            player.sendMessage(color(msg("messages.not-owner-town")));
            return true;
        }

        Resident resident = api.getResident(player);
        if (resident == null || !town.hasResident(resident)) {
            player.sendMessage(color(msg("messages.not-owner-town")));
            return true;
        }

        if (!isMayorOrAssistant(town, resident)) {
            player.sendMessage(color(msg("messages.no-permission-rank")));
            return true;
        }

        PlotKey key = PlotKey.from(current.getWorldCoord(), town.getUUID());
        ensurePairForPlot(key, current, town, true);

        ManagedPlot managed = plots.get(key);
        if (managed == null) {
            player.sendMessage(color(msg("messages.move-cancel")));
            return true;
        }

        int npcId = managed.getNpcId(role);
        NPC npc = getRegistry().getById(npcId);
        if (npc == null) {
            player.sendMessage(color(msg("messages.move-cancel")));
            return true;
        }

        Location target = player.getLocation().clone();
        target.setPitch(0F);
        npc.teleport(target, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
        managed.setCustomLocation(role, SerializedLocation.of(target));
        saveData();

        player.sendMessage(color(msg("messages.move-success-self")).replace("{role}", role.name().toLowerCase(Locale.ROOT)));
        return true;
    }

    private void syncFromTowny(boolean allowCreate) {
        if (!isPluginEnabled("Towny") || !isPluginEnabled("Citizens")) {
            return;
        }

        TownyAPI api = TownyAPI.getInstance();
        Set<PlotKey> active = new HashSet<>();

        for (TownBlock townBlock : api.getTownBlocks()) {
            if (townBlock == null || !isBankType(townBlock.getType())) {
                continue;
            }
            Town town = townBlock.getTownOrNull();
            if (town == null) {
                continue;
            }

            PlotKey key = PlotKey.from(townBlock.getWorldCoord(), town.getUUID());
            active.add(key);
            ensurePairForPlot(key, townBlock, town, allowCreate);
        }

        List<PlotKey> stale = new ArrayList<>();
        for (PlotKey key : plots.keySet()) {
            if (!active.contains(key)) {
                stale.add(key);
            }
        }
        for (PlotKey key : stale) {
            removePairForKey(key, true);
        }

        cleanupDuplicateCitizens(active);
        cleanupOrphanCitizens(active);
        saveData();
    }

    private void cleanupDuplicateCitizens(Set<PlotKey> active) {
        NPCRegistry registry = getRegistry();
        Map<RoleSlot, List<NPC>> grouped = new HashMap<>();

        for (NPC npc : registry.sorted()) {
            PlotKey key = keyFromNpcMetadata(npc);
            if (key == null || !active.contains(key)) {
                continue;
            }
            NpcRole role = roleFromNpcMetadata(npc);
            if (role == null) {
                continue;
            }
            grouped.computeIfAbsent(new RoleSlot(key, role), unused -> new ArrayList<>()).add(npc);
        }

        for (Map.Entry<RoleSlot, List<NPC>> entry : grouped.entrySet()) {
            RoleSlot slot = entry.getKey();
            List<NPC> candidates = entry.getValue();
            if (candidates.isEmpty()) {
                continue;
            }

            ManagedPlot managed = plots.get(slot.key);
            int preferredId = managed == null ? 0 : managed.getNpcId(slot.role);
            NPC keeper = selectKeeper(candidates, preferredId);
            if (keeper == null) {
                continue;
            }

            if (managed != null && managed.getNpcId(slot.role) != keeper.getId()) {
                managed.setNpcId(slot.role, keeper.getId());
                getLogger().info("Reconciliado " + slot.role.name().toLowerCase(Locale.ROOT) + " en "
                        + slot.key.simple() + ": data.yml=" + preferredId + " -> keeper=" + keeper.getId());
            }

            for (NPC npc : candidates) {
                if (npc.getId() == keeper.getId()) {
                    continue;
                }
                getLogger().warning("Eliminando NPC duplicado [" + slot.role.name().toLowerCase(Locale.ROOT) + "] en "
                        + slot.key.simple() + ": keeper=" + keeper.getId() + ", removed=" + npc.getId());
                npc.destroy();
            }
        }
    }

    private NPC selectKeeper(List<NPC> candidates, int preferredId) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        if (preferredId > 0) {
            for (NPC npc : candidates) {
                if (npc.getId() == preferredId) {
                    return npc;
                }
            }
        }

        NPC keeper = candidates.get(0);
        for (NPC npc : candidates) {
            if (npc.getId() < keeper.getId()) {
                keeper = npc;
            }
        }
        return keeper;
    }

    private void ensurePairForPlot(PlotKey key, TownBlock townBlock, Town town, boolean allowCreate) {
        ManagedPlot managed = plots.computeIfAbsent(key, k -> new ManagedPlot());
        Location center = centerFor(townBlock);

        Location investmentTarget = withOffset(center,
                getConfig().getDouble("npc.offsets.investment-x", -1.0),
                getConfig().getDouble("npc.offsets.investment-y", 0.0),
                getConfig().getDouble("npc.offsets.investment-z", 0.0));

        Location taxTarget = withOffset(center,
                getConfig().getDouble("npc.offsets.tax-x", 1.0),
                getConfig().getDouble("npc.offsets.tax-y", 0.0),
                getConfig().getDouble("npc.offsets.tax-z", 0.0));

        Location invLoc = managed.investmentCustomLocation != null ? managed.investmentCustomLocation.toLocation() : investmentTarget;
        Location taxLoc = managed.taxCustomLocation != null ? managed.taxCustomLocation.toLocation() : taxTarget;
        if (invLoc == null) {
            invLoc = investmentTarget;
            managed.investmentCustomLocation = null;
        }
        if (taxLoc == null) {
            taxLoc = taxTarget;
            managed.taxCustomLocation = null;
        }

        int resolvedInvestment = ensureInvestmentNpc(key, managed.investmentNpcId, invLoc, allowCreate);
        if (resolvedInvestment > 0) {
            managed.investmentNpcId = resolvedInvestment;
        }

        int resolvedTax = ensureTaxNpc(key, managed.taxNpcId,
                color(getConfig().getString("npc.names.tax", "&bRecaudador Towny")), taxLoc, allowCreate);
        if (resolvedTax > 0) {
            managed.taxNpcId = resolvedTax;
        }
    }

    private int ensureInvestmentNpc(PlotKey key, int currentId, Location targetLocation, boolean allowCreate) {
        NPCRegistry registry = getRegistry();

        NPC npc = currentId > 0 ? registry.getById(currentId) : null;
        if (npc != null && !isValidInvestmentCandidate(npc, key)) {
            npc = null;
        }

        if (npc == null) {
            npc = findNpcByMetadata(registry, key, NpcRole.INVESTMENT);
        }

        if (npc == null) {
            npc = findBagBankerInPlot(registry, key);
        }

        if (npc == null && allowCreate) {
            npc = createOfficialBagOfGoldBanker(targetLocation);
            if (npc != null) {
                getLogger().info("NPC creado [INVESTMENT] en " + key.simple());
            }
        }

        if (npc == null) {
            getLogger().warning("No se pudo asegurar NPC de inversion en " + key.simple());
            return 0;
        }

        writeNpcMetadata(npc, key, NpcRole.INVESTMENT);
        ensureNpcPlacement(npc, key, targetLocation);
        return npc.getId();
    }

    private int ensureTaxNpc(PlotKey key, int currentId, String name, Location location, boolean allowCreate) {
        NPCRegistry registry = getRegistry();

        NPC npc = currentId > 0 ? registry.getById(currentId) : null;
        if (npc != null && !npcMatches(npc, key, NpcRole.TAX)) {
            npc = null;
        }

        if (npc == null) {
            npc = findNpcByMetadata(registry, key, NpcRole.TAX);
        }

        if (npc == null && allowCreate) {
            npc = registry.createNPC(EntityType.PLAYER, name);
            if (getConfig().getBoolean("npc.use-protection", true)) {
                npc.setProtected(true);
            }
            npc.spawn(location);
            getLogger().info("NPC creado [TAX] en " + key.simple());
        }

        if (npc == null) {
            getLogger().warning("No se pudo asegurar NPC tax en " + key.simple());
            return 0;
        }

        writeNpcMetadata(npc, key, NpcRole.TAX);
        npc.setName(name);
        applyLookCloseTrait(npc);

        ensureNpcPlacement(npc, key, location);
        return npc.getId();
    }

    private void ensureNpcPlacement(NPC npc, PlotKey key, Location location) {
        if (npc == null || location == null) {
            return;
        }

        Location current = npc.isSpawned() ? npc.getEntity().getLocation() : npc.getStoredLocation();
        boolean outsidePlot = current == null || current.getWorld() == null || !isNpcInsidePlot(npc, key);
        if (!outsidePlot) {
            return;
        }

        Location safe = location.clone();
        safe.setPitch(0F);
        if (npc.isSpawned()) {
            npc.teleport(safe, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
        } else {
            npc.spawn(safe);
        }
        getLogger().info("Reubicado NPC [" + roleFromNpcMetadata(npc) + "] al plot bank correcto en " + key.simple()
                + " (id=" + npc.getId() + ").");
    }

    private void applyLookCloseTrait(NPC npc) {
        LookClose lookClose = npc.getOrAddTrait(LookClose.class);
        lookClose.setRange(6);
        lookClose.setRealisticLooking(true);
        if (!lookClose.isEnabled()) {
            lookClose.toggle();
        }
    }

    private boolean isValidInvestmentCandidate(NPC npc, PlotKey key) {
        if (npcMatches(npc, key, NpcRole.INVESTMENT)) {
            return true;
        }
        return isBagOfGoldBanker(npc) && isNpcInsidePlot(npc, key);
    }

    private NPC findBagBankerInPlot(NPCRegistry registry, PlotKey key) {
        for (NPC npc : registry.sorted()) {
            if (!isBagOfGoldBanker(npc)) {
                continue;
            }
            if (isNpcInsidePlot(npc, key)) {
                return npc;
            }
        }
        return null;
    }

    private boolean isNpcInsidePlot(NPC npc, PlotKey key) {
        Location location = npc.isSpawned() ? npc.getEntity().getLocation() : npc.getStoredLocation();
        if (location == null || location.getWorld() == null) {
            return false;
        }
        WorldCoord wc = WorldCoord.parseWorldCoord(location);
        return key.world.equals(wc.getWorldName()) && key.x == wc.getX() && key.z == wc.getZ();
    }

    private NPC createOfficialBagOfGoldBanker(Location location) {
        Plugin bag = Bukkit.getPluginManager().getPlugin("BagOfGold");
        if (bag == null || !bag.isEnabled()) {
            getLogger().warning("BagOfGold no esta habilitado; no se puede crear banker oficial.");
            return null;
        }

        try {
            Method method = bag.getClass().getMethod("createBagOfGoldBankerNpc", Location.class);
            Object created = method.invoke(bag, location);
            if (created instanceof NPC npc) {
                return npc;
            }
        } catch (Throwable throwable) {
            getLogger().warning("No se pudo usar API oficial BagOfGold para crear banker: " + throwable.getMessage());
        }

        // Fallback de seguridad
        NPC fallback = getRegistry().createNPC(EntityType.PLAYER, color(getConfig().getString("npc.names.investment", "&6Banquero de Inversion")));
        if (getConfig().getBoolean("npc.use-protection", true)) {
            fallback.setProtected(true);
        }
        attachBagOfGoldTrait(fallback);
        fallback.spawn(location);
        return fallback;
    }

    private boolean isBagOfGoldBanker(NPC npc) {
        if (npc == null) {
            return false;
        }

        Plugin bag = Bukkit.getPluginManager().getPlugin("BagOfGold");
        if (bag == null || !bag.isEnabled()) {
            return false;
        }

        try {
            ClassLoader cl = bag.getClass().getClassLoader();
            Class<?> clazz = Class.forName("one.lindegaard.BagOfGold.bank.BagOfGoldBankerTrait", true, cl);
            if (!Trait.class.isAssignableFrom(clazz)) {
                return false;
            }
            @SuppressWarnings("unchecked")
            Class<? extends Trait> traitClass = (Class<? extends Trait>) clazz;
            return npc.hasTrait(traitClass);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void attachBagOfGoldTrait(NPC npc) {
        Plugin bag = Bukkit.getPluginManager().getPlugin("BagOfGold");
        if (bag == null || !bag.isEnabled()) {
            getLogger().warning("BagOfGold no esta habilitado; NPC de inversion creado sin trait funcional.");
            return;
        }

        try {
            ClassLoader cl = bag.getClass().getClassLoader();
            Class<?> clazz = Class.forName("one.lindegaard.BagOfGold.bank.BagOfGoldBankerTrait", true, cl);
            if (!Trait.class.isAssignableFrom(clazz)) {
                getLogger().warning("BagOfGoldBankerTrait no es Trait. Se omite.");
                return;
            }
            @SuppressWarnings("unchecked")
            Class<? extends Trait> traitClass = (Class<? extends Trait>) clazz;
            if (!npc.hasTrait(traitClass)) {
                npc.addTrait(traitClass);
            }
        } catch (Throwable t) {
            getLogger().warning("No se pudo cargar BagOfGoldBankerTrait: " + t.getMessage());
        }
    }

    private void startTaxPrompt(Player player, PlotKey key, Town town) {
        Resident resident = TownyAPI.getInstance().getResident(player);
        if (resident == null || !town.hasResident(resident)) {
            player.sendMessage(color(msg("messages.not-owner-town")));
            return;
        }

        TaxOperation operation = TaxOperation.TOWN_DEPOSIT;
        if (player.isSneaking()) {
            if (!isMayorOrAssistant(town, resident)) {
                player.sendMessage(color(msg("messages.no-permission-rank")));
                return;
            }
            operation = TaxOperation.TOWN_WITHDRAW;
        }

        taxPrompts.put(player.getUniqueId(), new TaxPromptSession(key, operation));
        String mode = operation == TaxOperation.TOWN_DEPOSIT
                ? msg("messages.tax-mode-deposit")
                : msg("messages.tax-mode-withdraw");
        player.sendMessage(color(mode));
    }

    private void processTaxPrompt(Player player, TaxPromptSession session, int amount) {
        AccessResult access = checkPlotAccess(player, session.key, true);
        if (!access.allowed || access.town == null) {
            taxPrompts.remove(player.getUniqueId());
            player.sendMessage(color(msg("messages.denied-plot")));
            return;
        }

        Town town = access.town;
        Resident resident = TownyAPI.getInstance().getResident(player);
        if (resident == null || !town.hasResident(resident)) {
            taxPrompts.remove(player.getUniqueId());
            player.sendMessage(color(msg("messages.not-owner-town")));
            return;
        }

        try {
            if (session.operation == TaxOperation.TOWN_DEPOSIT) {
                town.depositToBank(resident, amount);
                player.sendMessage(color(msg("messages.tax-success-deposit").replace("{amount}", String.valueOf(amount))));
            } else {
                if (!isMayorOrAssistant(town, resident)) {
                    player.sendMessage(color(msg("messages.no-permission-rank")));
                    return;
                }
                town.withdrawFromBank(resident, amount);
                player.sendMessage(color(msg("messages.tax-success-withdraw").replace("{amount}", String.valueOf(amount))));
            }
        } catch (Exception e) {
            String reason = e.getMessage() == null ? "Operacion no permitida." : e.getMessage();
            player.sendMessage(color(msg("messages.tax-fail").replace("{reason}", reason)));
        } finally {
            taxPrompts.remove(player.getUniqueId());
        }
    }

    private AccessResult checkPlotAccess(Player player, PlotKey key, boolean requireOwnerTown) {
        TownyAPI api = TownyAPI.getInstance();
        TownBlock current = api.getTownBlock(player);
        if (current == null || !isBankType(current.getType())) {
            return AccessResult.denied();
        }

        WorldCoord wc = current.getWorldCoord();
        if (!key.world.equals(wc.getWorldName()) || key.x != wc.getX() || key.z != wc.getZ()) {
            return AccessResult.denied();
        }

        Town town = current.getTownOrNull();
        if (town == null || !town.getUUID().toString().equals(key.townUuid)) {
            return AccessResult.denied();
        }

        if (!requireOwnerTown) {
            return AccessResult.allowed(town);
        }

        Resident resident = api.getResident(player);
        if (resident == null || !town.hasResident(resident)) {
            return AccessResult.denied();
        }

        return AccessResult.allowed(town);
    }

    private boolean isMayorOrAssistant(Town town, Resident resident) {
        if (town.isMayor(resident)) {
            return true;
        }

        String assistant = getConfig().getString("towny.assistant-rank-name", "assistant");
        return assistant != null && !assistant.isBlank() && town.hasResidentWithRank(resident, assistant);
    }

    private boolean isBankType(TownBlockType type) {
        return type != null && "bank".equalsIgnoreCase(type.getName());
    }

    private Location centerFor(TownBlock townBlock) {
        WorldCoord wc = townBlock.getWorldCoord();
        Location low = wc.getLowerMostCornerLocation();
        World world = low.getWorld();
        if (world == null) {
            throw new IllegalStateException("Mundo nulo para " + wc);
        }

        double x = low.getBlockX() + 8.5;
        double z = low.getBlockZ() + 8.5;
        int y = world.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z)) + 1;
        return new Location(world, x, y, z);
    }

    private Location withOffset(Location base, double dx, double dy, double dz) {
        return base.clone().add(dx, dy, dz);
    }

    private NPC findNpcByMetadata(NPCRegistry registry, PlotKey key, NpcRole role) {
        for (NPC npc : registry.sorted()) {
            if (npcMatches(npc, key, role)) {
                return npc;
            }
        }
        return null;
    }

    private boolean npcMatches(NPC npc, PlotKey key, NpcRole role) {
        PlotKey fromMeta = keyFromNpcMetadata(npc);
        if (fromMeta == null) {
            return false;
        }
        NpcRole fromRole = roleFromNpcMetadata(npc);
        return fromRole == role && key.equals(fromMeta);
    }

    private NpcRole roleFromNpcMetadata(NPC npc) {
        String raw = firstStringMeta(npc, META_ROLE, LEGACY_META_ROLE);
        return NpcRole.fromString(raw);
    }

    private PlotKey keyFromNpcMetadata(NPC npc) {
        String world = firstStringMeta(npc, META_WORLD, LEGACY_META_WORLD);
        String town = firstStringMeta(npc, META_TOWN, LEGACY_META_TOWN);
        Integer x = firstIntMeta(npc, META_X, LEGACY_META_X);
        Integer z = firstIntMeta(npc, META_Z, LEGACY_META_Z);

        if (world == null || world.isBlank() || town == null || town.isBlank() || x == null || z == null) {
            return null;
        }
        return new PlotKey(world, x, z, town);
    }

    private String firstStringMeta(NPC npc, String primary, String legacy) {
        Object value = getMetaValue(npc, primary);
        if (value == null || String.valueOf(value).isBlank()) {
            value = getMetaValue(npc, legacy);
        }
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private Integer firstIntMeta(NPC npc, String primary, String legacy) {
        Object value = getMetaValue(npc, primary);
        if (value == null) {
            value = getMetaValue(npc, legacy);
        }
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Object getMetaValue(NPC npc, String path) {
        Object direct = npc.data().get(path);
        if (direct != null) {
            return direct;
        }

        int dot = path.indexOf('.');
        if (dot <= 0 || dot == path.length() - 1) {
            return null;
        }

        String root = path.substring(0, dot);
        String leaf = path.substring(dot + 1);
        Object nested = npc.data().get(root);
        if (nested instanceof Map<?, ?> map) {
            return map.get(leaf);
        }
        if (nested instanceof ConfigurationSection section) {
            return section.get(leaf);
        }
        return null;
    }

    private void writeNpcMetadata(NPC npc, PlotKey key, NpcRole role) {
        npc.data().setPersistent(META_ROLE, role.name().toLowerCase(Locale.ROOT));
        npc.data().setPersistent(META_WORLD, key.world);
        npc.data().setPersistent(META_X, key.x);
        npc.data().setPersistent(META_Z, key.z);
        npc.data().setPersistent(META_TOWN, key.townUuid);
    }

    private NPCRegistry getRegistry() {
        return CitizensAPI.getNPCRegistry();
    }

    private boolean isPluginEnabled(String name) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
        return plugin != null && plugin.isEnabled();
    }

    private String msg(String path) {
        return getConfig().getString(path, "");
    }

    private String color(String in) {
        return ChatColor.translateAlternateColorCodes('&', in == null ? "" : in);
    }

    private List<String> partial(String token, List<String> options) {
        String lower = token.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(option);
            }
        }
        return out;
    }

    private void cleanupOrphanCitizens(Set<PlotKey> active) {
        NPCRegistry registry = getRegistry();
        for (NPC npc : registry.sorted()) {
            PlotKey key = keyFromNpcMetadata(npc);
            if (key == null) {
                continue;
            }
            NpcRole role = roleFromNpcMetadata(npc);
            if (role == null) {
                continue;
            }
            if (!active.contains(key)) {
                npc.destroy();
                getLogger().info("NPC huerfano eliminado: id=" + npc.getId());
            }
        }
    }

    private void removePairForKey(PlotKey key, boolean removeMapEntry) {
        ManagedPlot managed = plots.get(key);
        if (managed == null) {
            return;
        }

        destroyNpcIfPresent(managed.investmentNpcId);
        destroyNpcIfPresent(managed.taxNpcId);

        if (removeMapEntry) {
            plots.remove(key);
        }
    }

    private void destroyNpcIfPresent(int npcId) {
        if (npcId <= 0) {
            return;
        }
        NPC npc = getRegistry().getById(npcId);
        if (npc != null) {
            npc.destroy();
        }
    }

    private void loadData() {
        plots.clear();
        ConfigurationSection root = dataConfig.getConfigurationSection("plots");
        if (root == null) {
            return;
        }

        for (String id : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(id);
            if (sec == null) {
                continue;
            }

            String world = sec.getString("world", "");
            int x = sec.getInt("x");
            int z = sec.getInt("z");
            String townUuid = sec.getString("town-uuid", "");
            if (world.isBlank() || townUuid.isBlank()) {
                continue;
            }

            PlotKey key = new PlotKey(world, x, z, townUuid);
            ManagedPlot plot = new ManagedPlot();
            plot.investmentNpcId = sec.getInt("investment.npc-id", 0);
            plot.taxNpcId = sec.getInt("tax.npc-id", 0);
            plot.investmentCustomLocation = SerializedLocation.fromSection(sec.getConfigurationSection("investment.custom-location"));
            plot.taxCustomLocation = SerializedLocation.fromSection(sec.getConfigurationSection("tax.custom-location"));
            plots.put(key, plot);
        }
    }

    private void saveData() {
        dataConfig.set("plots", null);
        ConfigurationSection root = dataConfig.createSection("plots");

        for (Map.Entry<PlotKey, ManagedPlot> entry : plots.entrySet()) {
            PlotKey key = entry.getKey();
            ManagedPlot plot = entry.getValue();

            String id = key.id();
            ConfigurationSection sec = root.createSection(id);
            sec.set("world", key.world);
            sec.set("x", key.x);
            sec.set("z", key.z);
            sec.set("town-uuid", key.townUuid);

            sec.set("investment.npc-id", plot.investmentNpcId);
            if (plot.investmentCustomLocation != null) {
                plot.investmentCustomLocation.toSection(sec.createSection("investment.custom-location"));
            }

            sec.set("tax.npc-id", plot.taxNpcId);
            if (plot.taxCustomLocation != null) {
                plot.taxCustomLocation.toSection(sec.createSection("tax.custom-location"));
            }
        }

        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("No se pudo guardar data.yml: " + e.getMessage());
        }
    }

    private void migrateLegacyFolderIfNeeded() {
        File newFolder = getDataFolder();
        File pluginsFolder = newFolder.getParentFile();
        if (pluginsFolder == null) {
            return;
        }

        File legacyFolder = new File(pluginsFolder, "TownyBankBridge");
        if (newFolder.exists() || !legacyFolder.exists()) {
            return;
        }

        if (!newFolder.mkdirs()) {
            return;
        }

        copyIfExists(new File(legacyFolder, "config.yml"), new File(newFolder, "config.yml"));
        copyIfExists(new File(legacyFolder, "data.yml"), new File(newFolder, "data.yml"));
    }

    private void copyIfExists(File source, File target) {
        if (!source.exists() || target.exists()) {
            return;
        }
        try {
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            getLogger().warning("No se pudo migrar archivo legado " + source.getName() + ": " + e.getMessage());
        }
    }

    private enum NpcRole {
        INVESTMENT,
        TAX;

        static NpcRole fromString(String raw) {
            if (raw == null) {
                return null;
            }
            String val = raw.trim().toLowerCase(Locale.ROOT);
            return switch (val) {
                case "investment" -> INVESTMENT;
                case "tax" -> TAX;
                default -> null;
            };
        }
    }

    private enum TaxOperation {
        TOWN_DEPOSIT,
        TOWN_WITHDRAW
    }

    private static final class TaxPromptSession {
        private final PlotKey key;
        private final TaxOperation operation;

        private TaxPromptSession(PlotKey key, TaxOperation operation) {
            this.key = key;
            this.operation = operation;
        }
    }

    private static final class AccessResult {
        private final boolean allowed;
        private final Town town;

        private AccessResult(boolean allowed, Town town) {
            this.allowed = allowed;
            this.town = town;
        }

        static AccessResult denied() {
            return new AccessResult(false, null);
        }

        static AccessResult allowed(Town town) {
            return new AccessResult(true, town);
        }
    }

    private static final class RoleSlot {
        private final PlotKey key;
        private final NpcRole role;

        private RoleSlot(PlotKey key, NpcRole role) {
            this.key = key;
            this.role = role;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof RoleSlot roleSlot)) {
                return false;
            }
            return Objects.equals(key, roleSlot.key) && role == roleSlot.role;
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, role);
        }
    }

    private static final class ManagedPlot {
        private int investmentNpcId;
        private int taxNpcId;
        private SerializedLocation investmentCustomLocation;
        private SerializedLocation taxCustomLocation;

        int getNpcId(NpcRole role) {
            return role == NpcRole.INVESTMENT ? investmentNpcId : taxNpcId;
        }

        void setNpcId(NpcRole role, int npcId) {
            if (role == NpcRole.INVESTMENT) {
                investmentNpcId = npcId;
            } else {
                taxNpcId = npcId;
            }
        }

        void setCustomLocation(NpcRole role, SerializedLocation location) {
            if (role == NpcRole.INVESTMENT) {
                investmentCustomLocation = location;
            } else {
                taxCustomLocation = location;
            }
        }
    }

    private static final class SerializedLocation {
        private final String world;
        private final double x;
        private final double y;
        private final double z;
        private final float yaw;
        private final float pitch;

        private SerializedLocation(String world, double x, double y, double z, float yaw, float pitch) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        static SerializedLocation of(Location location) {
            String world = location.getWorld() == null ? "" : location.getWorld().getName();
            return new SerializedLocation(world, location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
        }

        Location toLocation() {
            World worldObj = Bukkit.getWorld(world);
            if (worldObj == null) {
                return null;
            }
            return new Location(worldObj, x, y, z, yaw, pitch);
        }

        void toSection(ConfigurationSection sec) {
            sec.set("world", world);
            sec.set("x", x);
            sec.set("y", y);
            sec.set("z", z);
            sec.set("yaw", yaw);
            sec.set("pitch", pitch);
        }

        static SerializedLocation fromSection(ConfigurationSection sec) {
            if (sec == null) {
                return null;
            }
            String world = sec.getString("world", "");
            if (world.isBlank()) {
                return null;
            }
            return new SerializedLocation(
                    world,
                    sec.getDouble("x"),
                    sec.getDouble("y"),
                    sec.getDouble("z"),
                    (float) sec.getDouble("yaw"),
                    (float) sec.getDouble("pitch")
            );
        }
    }

    private static final class PlotKey {
        private final String world;
        private final int x;
        private final int z;
        private final String townUuid;

        private PlotKey(String world, int x, int z, String townUuid) {
            this.world = world;
            this.x = x;
            this.z = z;
            this.townUuid = townUuid;
        }

        static PlotKey from(WorldCoord wc, UUID townUuid) {
            return new PlotKey(wc.getWorldName(), wc.getX(), wc.getZ(), townUuid.toString());
        }

        String id() {
            return world + ":" + x + ":" + z + ":" + townUuid;
        }

        String simple() {
            return world + "(" + x + "," + z + ")";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof PlotKey plotKey)) {
                return false;
            }
            return x == plotKey.x && z == plotKey.z && Objects.equals(world, plotKey.world) && Objects.equals(townUuid, plotKey.townUuid);
        }

        @Override
        public int hashCode() {
            return Objects.hash(world, x, z, townUuid);
        }
    }
}
