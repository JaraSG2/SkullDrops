package cz.jarasg2.skulldrops;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import com.google.gson.*;
import java.lang.reflect.Method;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class SkullDrops extends JavaPlugin implements Listener, CommandExecutor {
    // Temporary test mode: not persisted; reset to OFF after server restart.
    private boolean testDropsEnabled = false;
    private YamlConfiguration apiCache;
    private File apiCacheFile;
    private File secretsFile;
    private YamlConfiguration secrets;
    private final HttpClient apiHttp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private YamlConfiguration placed;
    private File placedFile;
    private final Set<String> placedHearts = new HashSet<>();
    private final Set<String> placedStatues = new HashSet<>();
    private final Set<String> missingTexturesLogged = new HashSet<>();

    @Override public void onEnable() {
        saveDefaultConfig();
        loadSecrets();
        apiCacheFile = new File(getDataFolder(), "minecraft-heads-cache.yml");
        apiCache = YamlConfiguration.loadConfiguration(apiCacheFile);
        placedFile = new File(getDataFolder(), "placed-hearts.yml");
        placed = YamlConfiguration.loadConfiguration(placedFile);
        placedHearts.addAll(placed.getStringList("locations"));
        placedStatues.addAll(placed.getStringList("statues"));
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("skulldrops")).setExecutor(this);
        getLogger().info("Loading cached Minecraft-Heads textures...");
        getServer().getScheduler().runTaskLater(this, this::refreshCatalog, 40L);
        getLogger().info("Minecraft-Heads startup synchronization scheduled.");
    }
    private void loadSecrets() {
        secretsFile = new File(getDataFolder(), "secrets.yml");
        if (!secretsFile.exists()) {
            try {
                Files.createDirectories(getDataFolder().toPath());
                YamlConfiguration defaults = new YamlConfiguration();
                defaults.set("minecraft-heads.api-key", "");
                defaults.save(secretsFile);
            } catch (Exception ex) {
                getLogger().warning("Could not create secrets.yml: " + ex.getClass().getSimpleName());
            }
        }
        secrets = YamlConfiguration.loadConfiguration(secretsFile);
    }

    @Override public void onDisable() { persistHearts(); saveApiCache(); }
    private void saveApiCache() {
        try { apiCache.save(apiCacheFile); }
        catch (Exception ex) { getLogger().warning("Could not save API cache: " + ex.getMessage()); }
    }

    // API v2 returns paginated custom heads. Index all fetched pages off the main thread.
    // Raw catalog entries are kept separately from the normalized matching index.
    private record CatalogEntry(String name, String category, String hash) { }
    private volatile List<CatalogEntry> catalogEntries = List.of();
    private volatile Map<String, String> headCatalog = Map.of();

    private String csvField(String input) {
        return "\"" + input.replace("\"", "\"\"") + "\"";
    }

    private void exportCatalog(List<CatalogEntry> entries, CommandSender sender) {
        // Snapshot on the server thread, write CSV asynchronously.
        final List<CatalogEntry> snapshot = List.copyOf(entries);
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Path output = new File(getDataFolder(), "minecraft-heads-catalog.csv").toPath();
                Files.createDirectories(output.getParent());
                Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
                try (java.io.BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                    writer.write("name,category_id,texture_hash,texture_url\n");
                    for (CatalogEntry entry : snapshot) {
                        writer.write(csvField(entry.name()) + "," + csvField(entry.category()) + ","
                                + csvField(entry.hash()) + ","
                                + csvField("https://textures.minecraft.net/texture/" + entry.hash()) + "\n");
                    }
                }
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
                if (sender != null && isEnabled()) getServer().getScheduler().runTask(this,
                        () -> sender.sendMessage("§aExported " + snapshot.size()
                                + " Minecraft-Heads entries to plugins/SkullDrops/minecraft-heads-catalog.csv"));
            } catch (Exception ex) {
                getLogger().warning("Catalog CSV export failed: " + ex.getMessage());
                if (sender != null && isEnabled()) getServer().getScheduler().runTask(this,
                        () -> sender.sendMessage("§cCatalog CSV export failed; see server console."));
            }
        });
    }

    private volatile boolean catalogLoading = false;
    private volatile boolean catalogReady = false;
    private volatile long catalogLastAttempt = 0L;
    private volatile long catalogNextAllowed = 0L;
    private volatile int duplicatePageFailures = 0;
    private volatile int catalogPages = 0;
    private volatile int catalogMatched = 0;

    private String normalizeHeadName(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("(?i)\\bhead\\b", "")
                .replaceAll("[^a-z0-9]", "");
    }

    private String cacheKey(String type, String variant) { return type + "." + variant; }

    // Only index names that correspond to configured mob/variant textures.
    // Pagination is resumed from disk; cached texture mappings survive restarts.
    private Map<String, Set<String>> wantedNames() {
        Map<String, Set<String>> wanted = new LinkedHashMap<>();
        ConfigurationSection mobs = getConfig().getConfigurationSection("creatures");
        if (mobs == null) return wanted;
        for (String type : mobs.getKeys(false)) {
            if (!mobs.getBoolean(type + ".enabled", true)) continue;
            Set<String> variants = new LinkedHashSet<>();
            variants.add("DEFAULT");
            ConfigurationSection section = mobs.getConfigurationSection(type + ".variants");
            if (section != null) variants.addAll(section.getKeys(false));
            for (String variant : variants) for (boolean baby : new boolean[]{false, true}) {
                String texture = textureKey(type, variant, baby);
                String key = cacheKey(type, texture);
                if (validTextureUrl(apiCache.getString(key + ".url", ""))) continue;
                if (type.equals("SALMON") &&
                        (texture.equals("SMALL_SALMON") || texture.equals("MEDIUM_SALMON") ||
                         texture.equals("LARGE_SALMON")) &&
                        validTextureUrl(apiCache.getString("SALMON.SALMON.url", ""))) continue;
                Set<String> candidates = new LinkedHashSet<>();
                for (String name : apiNames(type, texture)) candidates.add(normalizeHeadName(name));
                wanted.put(key, candidates);
            }
        }
        return wanted;
    }

    private void refreshCatalog() {
        if (!getConfig().getBoolean("minecraft-heads.enabled", true) || catalogLoading) return;
        if (System.currentTimeMillis() < catalogNextAllowed ||
                System.currentTimeMillis() - catalogLastAttempt < 25000L) return;
        String app = getConfig().getString("minecraft-heads.app-uuid", "");
        if (app == null || !app.matches("[a-fA-F0-9-]{36}")) {
            getLogger().warning("Minecraft-Heads App UUID is missing or invalid.");
            return;
        }
        final Map<String, Set<String>> wanted = wantedNames();
        catalogMatched = countCachedMappings();
        if (wanted.isEmpty()) {
            catalogReady = true;
            getLogger().info("All configured mob/variant textures already cached; no API requests needed.");
            return;
        }
        catalogLoading = true;
        catalogReady = false;
        catalogLastAttempt = System.currentTimeMillis();
        // A single page per run prevents bursts and keeps all HTTP work off the server thread.
        final int maxPages = 1;
        final int firstPage = Math.max(1, getConfig().getInt("minecraft-heads.start-page", 1));
        final int savedPage = apiCache.getInt("sync.next-page", firstPage);
        if (savedPage == -1) {
            catalogLoading = false;
            catalogReady = false;
            getLogger().info("API catalog already scanned to the end. Existing texture mappings remain cached.");
            return;
        }
        final int startPage = Math.max(firstPage, savedPage);
        final int category = getConfig().getInt("minecraft-heads.category-id", 0);
        final String licenseKey = secrets.getString("minecraft-heads.api-key", "").trim();
        getLogger().info("Minecraft-Heads license key: " + (licenseKey.isEmpty() ? "not configured" : "configured")
                + "; requesting page " + startPage + ". Secret value will not be logged.");
        // The provider's exact credential parameter must be confirmed against API v2 documentation.
        final String keyParameter = getConfig().getString("minecraft-heads.api-key-parameter", "api_key");
        if (!licenseKey.isEmpty() && !keyParameter.matches("[a-zA-Z][a-zA-Z0-9_]{0,40}")) {
            catalogLoading = false;
            getLogger().warning("Invalid Minecraft-Heads API key parameter name in config.yml.");
            return;
        }
        final long pause = Math.max(1000, getConfig().getLong("minecraft-heads.page-delay-ms", 10000));
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            Map<String, String> matched = new HashMap<>();
            List<CatalogEntry> entries = new ArrayList<>();
            Set<String> seenPages = new HashSet<>();
            final String previousSignature = apiCache.getString("sync.last-page-signature", "");
            String lastSignature = previousSignature;
            Set<String> remaining = new HashSet<>(wanted.keySet());
            int fetched = 0, nextPage = startPage, retries = 0;
            boolean exhausted = false;
            String failure = null;
            for (int page = startPage; page < startPage + maxPages && !remaining.isEmpty(); page++) {
                try {
                    String url = "https://minecraft-heads.com/api/heads/custom-heads?app_uuid="
                            + URLEncoder.encode(app, StandardCharsets.UTF_8) + "&page=" + page;
                    if (category > 0) url += "&category_id=" + category;
                    if (!licenseKey.isEmpty()) url += "&" + keyParameter + "="
                            + URLEncoder.encode(licenseKey, StandardCharsets.UTF_8);
                    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(30)).header("Accept", "application/json")
                            .header("User-Agent", "SkullDrops/1.1 (Paper plugin)").GET().build();
                    HttpResponse<String> response = apiHttp.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 429) {
                        long waitMs = Math.max(60000L, pause * 6);
                        String retryAfter = response.headers().firstValue("Retry-After").orElse("");
                        try { waitMs = Math.max(waitMs, Long.parseLong(retryAfter.trim()) * 1000L); }
                        catch (NumberFormatException ignored) {
                            try {
                                var date = ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME);
                                waitMs = Math.max(waitMs, java.time.Duration.between(
                                        java.time.Instant.now(), date.toInstant()).toMillis());
                            } catch (Exception ignoredDate) { }
                        }
                        catalogNextAllowed = System.currentTimeMillis() + Math.min(waitMs, 3600000L);
                        failure = "HTTP 429 on page " + page + "; retry scheduled later";
                        break;
                    }
                    if (response.statusCode() != 200) {
                        failure = "HTTP " + response.statusCode() + " on page " + page;
                        break;
                    }
                    retries = 0;
                    JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                    JsonObject meta = root.has("meta") && root.get("meta").isJsonObject()
                            ? root.getAsJsonObject("meta") : null;
                    if (meta != null) {
                        String demo = meta.has("demo_mode") ? meta.get("demo_mode").toString() : "unknown";
                        String limited = meta.has("data_limited") ? meta.get("data_limited").toString() : "unknown";
                        getLogger().info("Minecraft-Heads page " + page + " metadata: demo_mode=" + demo
                                + ", data_limited=" + limited + ", key=" + (licenseKey.isEmpty() ? "not configured" : "configured"));
                        if ("true".equalsIgnoreCase(limited) || "true".equalsIgnoreCase(demo)) {
                            failure = "Provider reports limited/demo data on page " + page;
                            break;
                        }
                    } else {
                        getLogger().info("Minecraft-Heads page " + page + ": no meta object; key="
                                + (licenseKey.isEmpty() ? "not configured" : "configured"));
                    }
                    JsonArray data = root.getAsJsonArray("data");
                    if (data == null) { failure = "Missing data on page " + page; break; }
                    if (data.isEmpty()) { exhausted = true; break; }
                    String signature = data.toString();
                    String pageSignature = Integer.toHexString(signature.hashCode()) + ":" + signature.length();
                    if (pageSignature.equals(previousSignature) || !seenPages.add(pageSignature)) {
                        failure = "Repeated API page " + page + " (" + data.size() + " entries; possible ignored pagination or limited catalog)";
                        break;
                    }
                    lastSignature = pageSignature;
                    fetched++;
                    nextPage = page + 1;
                    for (JsonElement element : data) {
                        if (!element.isJsonObject()) continue;
                        JsonObject item = element.getAsJsonObject();
                        if (!item.has("n") || !item.has("u")
                                || item.get("n").isJsonNull() || item.get("u").isJsonNull()) continue;
                        String raw = item.get("n").getAsString();
                        String normalized = normalizeHeadName(raw);
                        String hash = item.get("u").getAsString();
                        if (!hash.matches("[a-fA-F0-9]{32,128}")) continue;
                        for (String key : new ArrayList<>(remaining)) {
                            if (!wanted.get(key).contains(normalized)) continue;
                            String textureUrl = "https://textures.minecraft.net/texture/" + hash;
                            matched.put(key, textureUrl);
                            entries.add(new CatalogEntry(raw,
                                    item.has("c") && !item.get("c").isJsonNull()
                                            ? item.get("c").getAsString() : "", hash));
                            remaining.remove(key);
                        }
                    }
                    // Commit each successful page so a crash/restart never discards scan progress.
                    final int checkpoint = nextPage;
                    final String checkpointSignature = lastSignature;
                    final Map<String, String> checkpointMatches = Map.copyOf(matched);
                    getServer().getScheduler().runTask(this, () -> {
                        for (var entry : checkpointMatches.entrySet())
                            apiCache.set(entry.getKey() + ".url", entry.getValue());
                        apiCache.set("sync.next-page", checkpoint);
                        apiCache.set("sync.last-page-signature", checkpointSignature);
                        saveApiCache();
                    });
                    if (!remaining.isEmpty()) Thread.sleep(pause);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt(); failure = "Interrupted"; break;
                } catch (Exception ex) {
                    failure = "Page " + page + ": " + ex.getClass().getSimpleName(); break;
                }
            }
            final int pages = fetched, cursor = nextPage, matches = matched.size();
            final String finalSignature = lastSignature;
            final boolean complete = remaining.isEmpty(), end = exhausted;
            final String error = failure;
            final List<CatalogEntry> downloadedEntries = List.copyOf(entries);
            if (!isEnabled()) return;
            getServer().getScheduler().runTask(this, () -> {
                for (var entry : matched.entrySet())
                    apiCache.set(entry.getKey() + ".url", entry.getValue());
                // A full scan ends at an empty page. Do not endlessly restart from page 1.
                apiCache.set("sync.next-page", end ? -1 : cursor);
                if (pages > 0) apiCache.set("sync.last-page-signature", finalSignature);
                saveApiCache();
                catalogLoading = false;
                catalogReady = complete;
                catalogPages += pages;
                catalogMatched = countCachedMappings();
                catalogEntries = downloadedEntries;
                headCatalog = Map.of();
                if (!downloadedEntries.isEmpty()) exportCatalog(downloadedEntries, null);
                getLogger().info("Minecraft-Heads targeted scan: " + pages + " pages this run, "
                        + matches + " new mappings, " + catalogMatched + " cached mappings, "
                        + remaining.size() + " still missing.");
                if (error != null) {
                    if (error.startsWith("Repeated API page")) {
                        duplicatePageFailures++;
                        // A repeated page is not proof of reaching the end of the catalog.
                        // Back off instead of advancing the cursor or spamming the API.
                        // Do not retry the same page on a timer. Only a subsequent startup/reload can retry.
                        catalogNextAllowed = Long.MAX_VALUE;
                        if (duplicatePageFailures == 1) getLogger().warning(
                                "Minecraft-Heads repeated page " + cursor + "; synchronization stopped. "
                                + "Cached heads remain usable. Check provider pagination/license metadata.");
                    } else {
                        duplicatePageFailures = 0;
                        getLogger().warning("Minecraft-Heads scan paused: " + error);
                    }
                } else duplicatePageFailures = 0;
                if (end && !complete) getLogger().warning(
                        "Reached the end of the API catalog; unmatched variants need manual mapping.");
            });
        });
    }

    private int countCachedMappings() {
        int count = 0;
        ConfigurationSection mobs = getConfig().getConfigurationSection("creatures");
        if (mobs == null) return count;
        for (String type : mobs.getKeys(false)) {
            if (!mobs.getBoolean(type + ".enabled", true)) continue;
            Set<String> variants = new LinkedHashSet<>();
            variants.add("DEFAULT");
            ConfigurationSection section = mobs.getConfigurationSection(type + ".variants");
            if (section != null) variants.addAll(section.getKeys(false));
            for (String variant : variants) for (boolean baby : new boolean[]{false, true})
                if (validTextureUrl(apiCache.getString(cacheKey(type, textureKey(type, variant, baby)) + ".url", "")))
                    count++;
        }
        return count;
    }

    private int prefillCache(Map<String, String> catalog) {
        int matched = 0;
        ConfigurationSection mobs = getConfig().getConfigurationSection("creatures");
        if (mobs == null) return 0;
        for (String type : mobs.getKeys(false)) {
            Set<String> variants = new LinkedHashSet<>();
            variants.add("DEFAULT");
            ConfigurationSection section = mobs.getConfigurationSection(type + ".variants");
            if (section != null) variants.addAll(section.getKeys(false));
            for (String variant : variants) {
                for (boolean baby : new boolean[]{false, true}) {
                    String key = textureKey(type, variant, baby);
                    String cache = cacheKey(type, key);
                    String existing = apiCache.getString(cache + ".url");
                    if (existing != null && validTextureUrl(existing)) { matched++; continue; }
                    for (String name : apiNames(type, key)) {
                        String url = catalog.get(normalizeHeadName(name));
                        if (url != null) {
                            apiCache.set(cache + ".url", url);
                            matched++;
                            break;
                        }
                    }
                }
            }
        }
        return matched;
    }

    private boolean validTextureUrl(String url) {
        return url.matches("https://textures\\.minecraft\\.net/texture/[a-fA-F0-9]{32,128}");
    }

    private void requestApiTexture(String type, String variant) {
        if (!getConfig().getBoolean("minecraft-heads.enabled", true)) return;
        if (headCatalog.isEmpty()) { refreshCatalog(); return; }
        String key = cacheKey(type, variant);
        if (apiCache.contains(key + ".url")) return;
        for (String name : apiNames(type, variant)) {
            String url = headCatalog.get(normalizeHeadName(name));
            if (url != null) {
                apiCache.set(key + ".url", url);
                saveApiCache();
                return;
            }
        }
    }

    private void persistHearts() {
        placed.set("locations", new ArrayList<>(placedHearts));
        placed.set("statues", new ArrayList<>(placedStatues));
        try { placed.save(placedFile); } catch (Exception e) { getLogger().warning("Could not save heart locations: " + e.getMessage()); }
    }
    private String id(Block block) { return block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ(); }
    private boolean roll(double percent) { return ThreadLocalRandom.current().nextDouble(100.0) < Math.max(0, Math.min(100, percent)); }
    private int looting(Player p) { return p.getInventory().getItemInMainHand().getEnchantmentLevel(Enchantment.LOOTING); }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void placed(BlockPlaceEvent e) {
        if (e.getBlockPlaced().getType() == Material.CREAKING_HEART) { placedHearts.add(id(e.getBlockPlaced())); persistHearts(); }
        if (isCopperStatue(e.getBlockPlaced().getType())) { placedStatues.add(id(e.getBlockPlaced())); persistHearts(); }
    }
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void broken(BlockBreakEvent e) {
        if (isCopperStatue(e.getBlock().getType())) {
            breakCopperStatue(e);
            return;
        }
        if (e.getBlock().getType() != Material.CREAKING_HEART) return;
        String key = id(e.getBlock());
        boolean playerPlaced = placedHearts.remove(key);
        persistHearts();
        if (!getConfig().getBoolean("heart.enabled", true)) return;
        if (playerPlaced && getConfig().getBoolean("heart.prevent-replaced-farming", true)) return;
        if (getConfig().getBoolean("heart.ignore-silk-touch", true) && e.getPlayer().getInventory().getItemInMainHand().containsEnchantment(Enchantment.SILK_TOUCH)) return;
        if (!testDropsEnabled && !roll(getConfig().getDouble("heart.chance", 1.0))) return;
        ItemStack head = makeHead("CREAKING", "DEFAULT");
        if (head != null) {
            applyHeadLore(head, e.getPlayer().getName(), "Creaking", "DEFAULT", false);
            e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation(), head);
        }
    }
    private boolean isCopperStatue(Material material) {
        return material.name().endsWith("COPPER_GOLEM_STATUE");
    }
    private void breakCopperStatue(BlockBreakEvent event) {
        Block block = event.getBlock();
        boolean placedByPlayer = placedStatues.remove(id(block));
        persistHearts();
        if (!getConfig().getBoolean("copper-statue.enabled", true)) return;
        if (placedByPlayer && getConfig().getBoolean("copper-statue.prevent-replaced-farming", true)) return;
        if (event.getPlayer().getGameMode() == GameMode.CREATIVE
                && !testDropsEnabled && !getConfig().getBoolean("settings.drop-in-creative", false)) return;
        if (getConfig().getBoolean("copper-statue.ignore-silk-touch", true)
                && event.getPlayer().getInventory().getItemInMainHand().containsEnchantment(Enchantment.SILK_TOUCH)) return;
        if (!testDropsEnabled && !roll(getConfig().getDouble("copper-statue.chance", 1.0))) return;
        String material = block.getType().name();
        boolean waxed = material.startsWith("WAXED_");
        String stage = material.replaceFirst("^WAXED_", "")
                .replace("_COPPER_GOLEM_STATUE", "");
        if (stage.equals("COPPER")) stage = "UNAFFECTED";
        ItemStack head = makeHead("COPPER_GOLEM", textureKey("COPPER_GOLEM", stage, false));
        if (head == null) return;
        applyHeadLore(head, event.getPlayer().getName(), "Copper Golem Statue",
                (waxed ? "WAXED_" : "") + stage, false);
        block.getWorld().dropItemNaturally(block.getLocation(), head);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void dead(EntityDeathEvent e) {
        LivingEntity mob = e.getEntity();
        Player killer = mob.getKiller();
        if (killer == null && getConfig().getBoolean("settings.require-player-kill", true)) return;
        if (killer != null && killer.getGameMode() == GameMode.CREATIVE && !testDropsEnabled && !getConfig().getBoolean("settings.drop-in-creative", false)) return;
        if (mob instanceof Player player) {
            if (!testDropsEnabled && (!getConfig().getBoolean("players.enabled", true) || !roll(getConfig().getDouble("players.chance", 10)))) return;
            ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) skull.getItemMeta();
            meta.setOwningPlayer(player); skull.setItemMeta(meta);
            applyHeadLore(skull, killer != null ? killer.getName() : "Unknown", player.getName(), "PLAYER", false);
            e.getDrops().add(skull); return;
        }
        String type = mob.getType().name();
        if (!testDropsEnabled && !getConfig().getBoolean("creatures." + type + ".enabled", false)) return;
        double chance = getConfig().getDouble("creatures." + type + ".chance", 0);
        if (mob instanceof Ageable age && !age.isAdult()) {
            Object baby = getConfig().get("creatures." + type + ".baby-chance");
            chance = baby instanceof Number n ? n.doubleValue() : chance * getConfig().getDouble("settings.baby-chance-multiplier", 1);
        }
        String variant = variantOf(mob);
        boolean baby = isBaby(mob);
        double variantChance = variantChance(type, variant, baby, chance);
        if (killer != null) variantChance += looting(killer) * getConfig().getDouble("settings.looting-extra-per-level", 0.001) * 100;
        if (!testDropsEnabled && !roll(variantChance)) return;
        ItemStack head = makeHead(type, textureKey(type, variant, baby));
        if (head != null) {
            applyHeadLore(head, killer != null ? killer.getName() : "Unknown", friendly(type), variant, baby);
            e.getDrops().add(head);
        }
    }
    // Resolve variants from entity state, never from the biome of the death location.
    private String variantOf(LivingEntity mob) {
        String type = mob.getType().name();
        switch (type) {
            case "CAT" -> { return property(mob, "getCatType", "getVariant"); }
            case "COW", "PIG", "CHICKEN", "FROG", "AXOLOTL", "RABBIT", "PARROT",
                 "LLAMA", "TRADER_LLAMA", "SALMON", "COPPER_GOLEM", "ZOMBIE_NAUTILUS" -> {
                String value = property(mob, "getVariant", "getColor", "getWeatherState");
                if (type.equals("AXOLOTL") && value.equals("LEUCISTIC")) return "LUCY";
                if (type.equals("PARROT") && value.equals("CYAN")) return "LIGHT_BLUE";
                if (type.equals("PARROT") && value.equals("GREY")) return "GRAY";
                if (type.equals("RABBIT") && customName(mob).equalsIgnoreCase("Toast")) return "TOAST";
                return value;
            }
            case "WOLF" -> {
                String coat = property(mob, "getVariant");
                return isAngry(mob) ? "ANGRY_" + coat : coat;
            }
            case "HORSE" -> { return property(mob, "getColor") + "_" + property(mob, "getStyle"); }
            case "FOX" -> { return property(mob, "getFoxType", "getVariant"); }
            case "MOOSHROOM" -> { return property(mob, "getVariant"); }
            case "PANDA" -> { return property(mob, "getMainGene"); }
            case "GOAT" -> { return Boolean.TRUE.equals(flag(mob, "isScreaming")) ? "SCREAMING" : "DEFAULT"; }
            case "CREEPER" -> { return Boolean.TRUE.equals(flag(mob, "isPowered")) ? "CHARGED" : "DEFAULT"; }
            case "BEE" -> {
                boolean angry = isAngry(mob);
                boolean pollen = Boolean.TRUE.equals(flag(mob, "hasNectar"));
                return angry ? (pollen ? "ANGRY_POLLINATED" : "ANGRY") : (pollen ? "POLLINATED" : "DEFAULT");
            }
            case "STRIDER" -> { return Boolean.TRUE.equals(flag(mob, "isShivering")) ? "COLD" : "DEFAULT"; }
            case "SHEEP" -> { return property(mob, "getColor"); }
            case "SHULKER" -> { return property(mob, "getColor"); }
            case "TROPICAL_FISH" -> {
                return property(mob, "getPattern") + "_" + property(mob, "getBodyColor")
                        + "_" + property(mob, "getPatternColor");
            }
            case "SNOW_GOLEM" -> { return Boolean.TRUE.equals(flag(mob, "isDerp")) ? "NO_PUMPKIN" : "DEFAULT"; }
            case "VILLAGER", "ZOMBIE_VILLAGER" -> {
                String profession = property(mob, "getProfession");
                String biome = property(mob, "getVillagerType");
                // The supplied texture pack has profession heads, not profession-by-biome heads.
                return profession.equals("DEFAULT") ? biome : (type.equals("ZOMBIE_VILLAGER") ? "ZOMBIE_" + profession : profession);
            }
            default -> { return "DEFAULT"; }
        }
    }
    private String customName(LivingEntity mob) {
        return mob.customName() == null ? "" : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(mob.customName());
    }
    private boolean isAngry(LivingEntity mob) {
        Object anger = invoke(mob, "getAnger");
        return (anger instanceof Number n && n.intValue() > 0)
                || Boolean.TRUE.equals(flag(mob, "isAngry"));
    }

    private Object invoke(Object target, String method) {
        try { Method m = target.getClass().getMethod(method); return m.invoke(target); }
        catch (ReflectiveOperationException ignored) { return null; }
    }
    private String property(Object target, String... methods) {
        for (String method : methods) {
            Object result = invoke(target, method);
            if (result != null) {
                // Paper 26.3 variant objects may be CraftVariant records. Their
                // toString() contains the whole registry definition, not a variant ID.
                if (result instanceof Enum<?> e) return normalize(e.name());
                if (result instanceof org.bukkit.Keyed keyed)
                    return normalize(keyed.getKey().getKey());
                Object key = invoke(result, "getKey");
                if (key instanceof org.bukkit.NamespacedKey namespaced)
                    return normalize(namespaced.getKey());
                if (key instanceof String stringKey)
                    return normalize(stringKey.substring(stringKey.lastIndexOf(':') + 1));
                Object name = invoke(result, "name");
                if (name instanceof String text) return normalize(text);
                // Never use a complex object's toString() as a cache key.
                if (result instanceof String || result instanceof Number)
                    return normalize(result.toString());
            }
        }
        return "DEFAULT";
    }
    private Boolean flag(Object target, String... methods) {
        for (String method : methods) {
            Object result = invoke(target, method);
            if (result instanceof Boolean b) return b;
            if (result instanceof Number n) return n.intValue() > 0;
        }
        return null;
    }
    private String normalize(String value) {
        return value.toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
    }
    private boolean isBaby(LivingEntity mob) {
        if (mob instanceof Ageable age) return !age.isAdult();
        return Boolean.TRUE.equals(flag(mob, "isBaby"));
    }
    private double variantChance(String type, String variant, boolean baby, double inherited) {
        String path = "creatures." + type + ".variants." + variant;
        if (baby) {
            Object specific = getConfig().get(path + ".baby-chance");
            if (specific instanceof Number n) return n.doubleValue();
        }
        Object specific = getConfig().get(path + ".chance");
        return specific instanceof Number n ? n.doubleValue() : inherited;
    }
    // Return a stable, variant-specific cache key; no imported texture lookup.
    private String textureKey(String type, String variant, boolean baby) {
        String normalized = variant.equals("DEFAULT") ? type
                : variant.equals(type) || variant.endsWith("_" + type) ? variant
                : variant + "_" + type;
        return (baby ? "BABY_" : "") + normalized;
    }

    // Exact catalog names only: never substitute an unrelated mob's skin.
    private List<String> apiNames(String type, String key) {
        String name = key.replace('_', ' ');
        List<String> candidates = new ArrayList<>();
        candidates.add(name);
        if (key.equals(type)) candidates.add(type.replace('_', ' ') + " head");
        if (key.startsWith("BABY_")) {
            candidates.add(name.substring(5) + " baby");
            candidates.add(name.substring(5) + " baby head");
        }
        return candidates;
    }

    private String friendly(String key) {
        StringBuilder out = new StringBuilder();
        for (String word : key.toLowerCase(Locale.ROOT).split("_")) {
            if (!out.isEmpty()) out.append(' ');
            if (!word.isEmpty()) out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }
    private void applyHeadLore(ItemStack head, String killerName, String victimName, String variant, boolean baby) {
        ItemMeta meta = head.getItemMeta();
        if (meta == null) return;
        // Always set a single canonical name for every head created by SkullDrops.
        // This also fixes duplicated mob/variant names (IRON GOLEM IRON GOLEM Head).
        String cleanName = friendly(variant.equals("DEFAULT") ? victimName
                : variant.equalsIgnoreCase(victimName.replace(' ', '_')) ? victimName
                : variant.replace('_', ' ') + " " + victimName);
        // If the variant already includes the entity name, do not append it twice.
        String entity = victimName.toUpperCase(Locale.ROOT).replace(' ', '_');
        if (!variant.equals("DEFAULT") && variant.toUpperCase(Locale.ROOT).endsWith("_" + entity))
            cleanName = friendly(variant);
        meta.setDisplayName(cleanName + " Head");
        if (!getConfig().getBoolean("head-lore.enabled", true)) {
            head.setItemMeta(meta);
            return;
        }
        String timezone = getConfig().getString("head-lore.timezone", "Europe/Prague");
        String dateFormat = getConfig().getString("head-lore.date-format", "dd.MM.yyyy HH:mm:ss");
        String date;
        try { date = ZonedDateTime.now(ZoneId.of(timezone)).format(DateTimeFormatter.ofPattern(dateFormat)); }
        catch (Exception ex) { date = ZonedDateTime.now(ZoneId.of("Europe/Prague")).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")); }
        List<String> lore = new ArrayList<>();
        String shownVariant = variant.equals("DEFAULT") ? "Default" : friendly(variant);
        List<String> templates = getConfig().getStringList("head-lore.lines");
        if (templates.isEmpty()) templates = List.of(
                "&7Killed by &f{KILLER}", "&7Victim &f{VICTIM}",
                "&7Variant &f{VARIANT}", "&7Age &f{AGE}", "&7Killed on &f{DATE}");
        for (String line : templates) {
            lore.add(ChatColor.translateAlternateColorCodes('&', line
                    .replace("{KILLER}", killerName).replace("{VICTIM}", victimName)
                    .replace("{VARIANT}", shownVariant).replace("{AGE}", baby ? "Baby" : "Adult")
                    .replace("{DATE}", date)));
        }
        meta.setLore(lore);
        head.setItemMeta(meta);
    }

    private ItemStack makeHead(String type, String variant) {
        String apiKey = type + "." + variant;
        String cachedUrl = apiCache.getString(apiKey + ".url");
        if (!validTextureUrl(cachedUrl) && type.equals("SALMON") &&
                (variant.equals("SMALL_SALMON") || variant.equals("MEDIUM_SALMON") ||
                 variant.equals("LARGE_SALMON"))) {
            cachedUrl = apiCache.getString("SALMON.SALMON.url");
        }
        if (!validTextureUrl(cachedUrl)) {
            requestApiTexture(type, variant);
            cachedUrl = apiCache.getString(apiKey + ".url");
            if (!validTextureUrl(cachedUrl) && type.equals("SALMON"))
                cachedUrl = apiCache.getString("SALMON.SALMON.url");
        }
        if (cachedUrl != null && validTextureUrl(cachedUrl)) {
            try {
                ItemStack item = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta meta = (SkullMeta) item.getItemMeta();
                PlayerProfile profile = Bukkit.createProfile(UUID.nameUUIDFromBytes(apiKey.getBytes(StandardCharsets.UTF_8)));
                PlayerTextures skin = profile.getTextures();
                skin.setSkin(URI.create(cachedUrl).toURL());
                profile.setTextures(skin);
                meta.setOwnerProfile(profile);
                item.setItemMeta(meta);
                return item;
            } catch (Exception ex) { getLogger().warning("Invalid cached head " + apiKey + ": " + ex.getMessage()); }
        }
        String warn = type + "/" + variant;
        if (missingTexturesLogged.add(warn)) {
            getLogger().warning("No Minecraft-Heads API texture for " + warn + "; no head will drop until a matching texture is available.");
        }
        return null;
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("skulldrops.admin")) return true;
        if (args.length >= 2 && args[0].equalsIgnoreCase("texture")
                && args[1].equalsIgnoreCase("missing")) {
            Map<String, Set<String>> missing = wantedNames();
            sender.sendMessage("§eMissing configured texture mappings: " + missing.size()
                    + " §7(showing first 20)");
            int shown = 0;
            for (String key : missing.keySet()) {
                if (shown++ >= 20) break;
                sender.sendMessage("§7- " + key.replace('.', '/'));
            }
            if (missing.size() > 20) sender.sendMessage("§7... and " + (missing.size() - 20) + " more.");
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("texture")
                && args[1].equalsIgnoreCase("export")) {
            if (catalogEntries.isEmpty()) {
                sender.sendMessage("§cNo downloaded catalog entries yet. Wait for API sync.");
            } else {
                sender.sendMessage("§eExporting " + catalogEntries.size() + " catalog entries...");
                exportCatalog(catalogEntries, sender);
            }
            return true;
        }
        if (args.length >= 3 && args[0].equalsIgnoreCase("texture")
                && args[1].equalsIgnoreCase("search")) {
            String query = String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                    .toLowerCase(Locale.ROOT).trim();
            if (query.isBlank()) {
                sender.sendMessage("§cUsage: /sd texture search <name>");
                return true;
            }
            int total = 0;
            int shown = 0;
            for (CatalogEntry entry : catalogEntries) {
                if (!entry.name().toLowerCase(Locale.ROOT).contains(query)) continue;
                total++;
                if (shown++ < 10) {
                    sender.sendMessage("§e" + entry.name() + " §7(category " + entry.category()
                            + ", hash " + entry.hash().substring(0, Math.min(12, entry.hash().length())) + "...)");
                }
            }
            sender.sendMessage("§7Found " + total + " matches"
                    + (total > 10 ? " (showing first 10; use CSV export for all)" : "")
                    + ". Catalog " + (catalogReady ? "complete" : "partial") + ".");
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("test")) {
            // OP only, even if another plugin grants skulldrops.admin to a non-OP.
            if (!(sender instanceof org.bukkit.command.ConsoleCommandSender) && !sender.isOp()) {
                sender.sendMessage("§cOnly server operators can toggle SkullDrops test mode.");
                return true;
            }
            if (args.length == 1 || (args.length == 2 && args[1].equalsIgnoreCase("status"))) {
                sender.sendMessage("§eSkullDrops 100% test drops: " + (testDropsEnabled ? "§aON" : "§cOFF"));
                return true;
            }
            if (args.length == 2 && (args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) {
                testDropsEnabled = args[1].equalsIgnoreCase("on");
                sender.sendMessage("§eSkullDrops 100% test drops: " + (testDropsEnabled ? "§aON" : "§cOFF")
                        + "§7 (temporary; normal chances restored when OFF)");
                getLogger().info(sender.getName() + " set SkullDrops test drops " + (testDropsEnabled ? "ON" : "OFF"));
                return true;
            }
            sender.sendMessage("§cUsage: /sd test <on|off|status>");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadSecrets();
            catalogNextAllowed = 0L;
            duplicatePageFailures = 0;
            headCatalog = Map.of(); catalogLastAttempt = 0L; refreshCatalog();
            sender.sendMessage("§aSkullDrops reloaded."); return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("§eSkullDrops API: " + (catalogLoading ? "loading" : catalogReady ? "ready" : "partial/offline")
                    + "§7 | test=" + (testDropsEnabled ? "ON" : "OFF") + " | pages=" + catalogPages + " | names=" + catalogEntries.size()
                    + " | mappings=" + catalogMatched);
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("chance")) {
            try {
                String mob = args[1].toUpperCase(Locale.ROOT);
                double chance = Double.parseDouble(args[2]);
                if (chance < 0 || chance > 100 || !getConfig().contains("creatures." + mob)) throw new IllegalArgumentException();
                getConfig().set("creatures." + mob + ".chance", chance); saveConfig();
                sender.sendMessage("§a" + mob + " chance: " + chance + "%");
            } catch (Exception ex) { sender.sendMessage("§cUsage: /sd chance MOB 0..100"); }
            return true;
        }
        sender.sendMessage("§e/sd texture missing §7| §e/sd texture search NAME §7| §e/sd texture export §7| §e/sd test on|off|status §7| §e/sd reload §7| §e/sd status §7| §e/sd chance MOB PERCENT"); return true;
    }
}
