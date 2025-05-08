package me.kiiya.hotbarmanager.listeners.bedwars1058;

import com.andrei1058.bedwars.BedWars;
import com.andrei1058.bedwars.api.arena.shop.IContentTier;
import com.andrei1058.bedwars.api.arena.team.ITeam;
import com.andrei1058.bedwars.api.arena.team.TeamEnchant;
import com.andrei1058.bedwars.api.events.shop.ShopBuyEvent;
import com.andrei1058.bedwars.api.server.VersionSupport;
import com.andrei1058.bedwars.configuration.Sounds;
import com.andrei1058.bedwars.shop.ShopCache;
import com.andrei1058.bedwars.shop.ShopManager;
import com.andrei1058.bedwars.shop.main.CategoryContent;
import com.andrei1058.bedwars.shop.main.ShopCategory;
import com.andrei1058.bedwars.shop.main.ShopIndex;
import com.andrei1058.bedwars.shop.quickbuy.PlayerQuickBuyCache;
import com.andrei1058.bedwars.shop.quickbuy.QuickBuyElement;
import me.kiiya.hotbarmanager.HotbarManager;
import me.kiiya.hotbarmanager.api.events.HotbarItemSetEvent;
import me.kiiya.hotbarmanager.api.hotbar.Category;
import me.kiiya.hotbarmanager.api.hotbar.IHotbarPlayer;
import me.kiiya.hotbarmanager.utils.HotbarUtils;
import me.kiiya.hotbarmanager.utils.Utility;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

import static me.kiiya.hotbarmanager.utils.Utility.debug;

public class ShopBuy implements Listener {

    private final Set<UUID> processing = Collections.synchronizedSet(new HashSet<>());

    @EventHandler
    public void onBuy(ShopBuyEvent e) {
        VersionSupport vs = HotbarManager.getBW1058Api().getVersionSupport();
        Player p = e.getBuyer();

        // check processing status
        if (processing.contains(p.getUniqueId())) return;
        processing.add(p.getUniqueId());

        try {
            // MAIN VARIABLES
            PlayerInventory inv = p.getInventory();
            IHotbarPlayer hp = HotbarManager.getAPI().getHotbarPlayer(p);
            Category cat = HotbarUtils.getCategoryFromString(e.getCategoryContent().getIdentifier());
            List<Category> hotbar = hp.getHotbarAsList();


            if (cat == null || cat == Category.NONE || !hotbar.contains(cat)) {
                return;
            }

            // ITEM AND SHOP VARIABLES
            ITeam t = e.getArena().getTeam(p);
            CategoryContent cc = (CategoryContent) e.getCategoryContent();
            String identifier = cc.getIdentifier();
            ShopCache cache = ShopCache.getShopCache(p.getUniqueId());
            PlayerQuickBuyCache quickBuyCache = PlayerQuickBuyCache.getQuickBuyCache(p.getUniqueId());
            if (quickBuyCache == null) quickBuyCache = new PlayerQuickBuyCache(p);
            QuickBuyElement element = quickBuyCache.getElements().stream()
                    .filter(el -> el.getCategoryContent().getIdentifier().equals(identifier))
                    .findFirst()
                    .orElse(null);
            List<UUID> indexViewers = ShopIndex.getIndexViewers();

            if (indexViewers.contains(p.getUniqueId()) && element != null) cache.upgradeCachedItem(cc, element.getSlot());
            else cache.upgradeCachedItem(cc, cc.getSlot());ShopCache.CachedItem cachedItem = cache.getCachedItem(cc);

            if (cachedItem == null) {
                debug("CachedItem is null for " + identifier);
                return;
            }

            // item slots processing
            for (int i = 0; i < 9; i++) {
                if (hotbar.get(i) != cat) continue;

                HotbarItemSetEvent event = new HotbarItemSetEvent(p, cat, i);
                Bukkit.getPluginManager().callEvent(event);
                if (event.isCancelled()) {
                    debug("Event was cancelled for slot " + i);
                    return;
                }
                ItemStack itemSlot = inv.getItem(i);
                boolean slotEmpty = itemSlot == null || itemSlot.getType() == Material.AIR;

                IContentTier upgradableContent = cc.getContentTiers().get(cachedItem.getTier()-1);
                ItemStack item = Utility.formatItemStack(upgradableContent.getBuyItemsList().get(0).getItemStack(), t);

                // calc currency
                Material currency = upgradableContent.getCurrency();
                int price = upgradableContent.getPrice();
                int totalPlayerMoney = CategoryContent.calculateMoney(p, currency);


                // if on purchasing item is sword
                if (BedWars.nms.isSword(item)) {
                    handleSwordUpgrade(p, inv, i, item, itemSlot, t, slotEmpty);
                    completePurchase(p, cc, cache, item, currency, price, totalPlayerMoney, identifier,inv,indexViewers,quickBuyCache);
                    e.setCancelled(true);
                    return;
                }

                // tools
                if (!slotEmpty && (BedWars.nms.isTool(itemSlot) || itemSlot.getType() == Material.SHEARS)) {
                    if (Utility.getItemCategory(itemSlot) == cat &&
                            !vs.getShopUpgradeIdentifier(itemSlot).equalsIgnoreCase(identifier)) {
                        continue;
                    }
                }

                // stack item or replace item
                if (!slotEmpty) {
                    if(handleItemReplacement(p, inv, i, item, itemSlot, vs, identifier, cat)){
                        debug("successfully replaced or stacked item");
                        completePurchase(p, cc, cache, item, currency, price, totalPlayerMoney, identifier,inv,indexViewers,quickBuyCache);
                        e.setCancelled(true);
                        return;
                    }
                    debug("cant replaced or stacked item,skip");
                    continue;
                }

                // empty slot
                handleEmptySlot(p, inv, i, item, cc, vs, identifier, t);
                completePurchase(p, cc, cache, item, currency, price, totalPlayerMoney, identifier,inv,indexViewers,quickBuyCache);
                e.setCancelled(true);
                return;
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        } finally {
            processing.remove(p.getUniqueId());
        }
    }


    private void handleSwordUpgrade(Player p, PlayerInventory inv, int slot, ItemStack newItem,
                                    ItemStack oldItem, ITeam team, boolean slotEmpty) {
        unbreakable(newItem);
        for (TeamEnchant teamEnchant : team.getSwordsEnchantments()) {
            newItem.addEnchantment(teamEnchant.getEnchantment(), teamEnchant.getAmplifier());
        }

        if (!slotEmpty && BedWars.nms.isSword(oldItem)) {
            if (Utility.isItemHigherTier(newItem, oldItem)) {
                ItemStack cloned = oldItem.clone();
                inv.setItem(slot, newItem);
                if (BedWars.nms.isSword(newItem)) {
                    inv.remove(Material.getMaterial(BedWars.getForCurrentVersion("WOOD_SWORD", "WOOD_SWORD", "WOODEN_SWORD")));
                }
                if (cloned != null && cloned.getType() != Material.getMaterial(BedWars.getForCurrentVersion("WOOD_SWORD", "WOOD_SWORD", "WOODEN_SWORD"))){
                    inv.addItem(cloned);
                }
            } else {
                inv.addItem(newItem);
            }
        } else {
            inv.setItem(slot, newItem);
        }
        p.updateInventory();
    }

    private boolean handleItemReplacement(Player p, PlayerInventory inv, int slot, ItemStack newItem,
                                          ItemStack oldItem, VersionSupport vs, String identifier, Category category) {
        // replace same identifier
        if (vs.getShopUpgradeIdentifier(oldItem) != null &&
                vs.getShopUpgradeIdentifier(oldItem).equals(identifier)) {
            debug("item has same identifier" + slot + newItem.getType().name() + " to " + oldItem.getType().name());
            inv.setItem(slot, vs.setShopUpgradeIdentifier(newItem, identifier));
            p.updateInventory();
            return true;
        }
        // stacking item
        if (newItem.getType() == oldItem.getType() &&
                newItem.getDurability() == oldItem.getDurability()) {
            int total = oldItem.getAmount() + newItem.getAmount();
            debug("stacking item" + slot + newItem.getType().name() + " to " + oldItem.getType().name());
            if (total > newItem.getMaxStackSize()) {
                oldItem.setAmount(newItem.getMaxStackSize());
                newItem.setAmount(total - newItem.getMaxStackSize());
                inv.addItem(newItem);
            } else {
                oldItem.setAmount(total);
            }
            p.updateInventory();
            return true;
        }
        // if same category but not same type,skip it
        if ((Utility.getItemCategory(oldItem) == category) && newItem.getType() != oldItem.getType()) {
            debug("item has same category but not same type" + slot + newItem.getType().name() + " to " + oldItem.getType().name());
            return false;
        }
        debug("item has been replaced" + slot + newItem.getType().name() + " to " + oldItem.getType().name());
        inv.setItem(slot, newItem);
        inv.addItem(oldItem);
        p.updateInventory();
        return true;
    }

    private void handleEmptySlot(Player p, PlayerInventory inv, int slot, ItemStack item,
                                 CategoryContent cc, VersionSupport vs, String identifier,
                                 ITeam team) {
        if (cc.isPermanent()) {
            unbreakable(item);
            item = vs.setShopUpgradeIdentifier(item, identifier);
        }
        if (BedWars.nms.isSword(item)) {
            unbreakable(item);
            for (TeamEnchant teamEnchant : team.getSwordsEnchantments()) {
                item.addEnchantment(teamEnchant.getEnchantment(), teamEnchant.getAmplifier());
            }
        }
        inv.setItem(slot, item);
        p.updateInventory();
    }

    private void completePurchase(Player p, CategoryContent cc, ShopCache cache, ItemStack item,
                                  Material currency, int price, int totalMoney, String identifier,
                                  PlayerInventory inv,List<UUID> indexViewers,PlayerQuickBuyCache quickBuyCache) {
        CategoryContent.takeMoney(p, currency, price);
        Sounds.playSound("shop-bought", p);

        int finalMoney = CategoryContent.calculateMoney(p, currency);
        if (finalMoney < (totalMoney - price)) {
            inv.addItem(new ItemStack(currency, (totalMoney - price) - finalMoney));
        } else if (finalMoney > (totalMoney - price)) {
            inv.removeItem(new ItemStack(currency, finalMoney - (totalMoney - price)));
        }

        p.sendMessage(Utility.getMsg(p, "shop-new-purchase")
                .replace("{prefix}", Utility.getMsg(p, "prefix"))
                .replace("{item}", Utility.getMsg(p, "shop-items-messages." + identifier.split("\\.")[0] + ".content-item-" + identifier.split("\\.")[2] + "-name"))
                .replace("{color}", "")
                .replace("{tier}", !BedWars.nms.isTool(item) ? "" : CategoryContent.getRomanNumber(cache.getCachedItem(cc).getTier())));
        if (indexViewers.contains(p.getUniqueId())) {
            ShopManager.shop.open(p, quickBuyCache, false);
        } else {
            for (ShopCategory sc : ShopManager.shop.getCategoryList()) {
                String ccId = cc.getIdentifier().split("\\.")[0];
                String scId = sc.getCategoryContentList().get(0).getIdentifier().split("\\.")[0];
                if (ccId.equals(scId)) {
                    sc.open(p, ShopManager.shop, cache);
                    break;
                }
            }
        }
    }

    private int getPrice(CategoryContent cc, int tier) {
        return cc.getContentTiers().get(tier-1).getPrice();
    }

    private int getPrice(IContentTier contentTier) {
        return contentTier.getPrice();
    }

    private void unbreakable(ItemStack itemStack) {
        ItemMeta meta = itemStack.getItemMeta();
        BedWars.nms.setUnbreakable(meta);
        itemStack.setItemMeta(meta);
    }
}