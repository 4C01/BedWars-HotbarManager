package me.kiiya.hotbarmanager.listeners.bedwars1058;

import com.andrei1058.bedwars.BedWars;
import com.andrei1058.bedwars.api.arena.shop.IContentTier;
import com.andrei1058.bedwars.api.arena.team.ITeam;
import com.andrei1058.bedwars.api.arena.team.TeamEnchant;
import com.andrei1058.bedwars.api.events.shop.ShopBuyEvent;
import com.andrei1058.bedwars.api.server.VersionSupport;
import com.andrei1058.bedwars.configuration.Sounds;
import com.andrei1058.bedwars.shop.ShopCache;
import com.andrei1058.bedwars.shop.main.CategoryContent;
import me.kiiya.hotbarmanager.HotbarManager;
import me.kiiya.hotbarmanager.api.hotbar.Category;
import me.kiiya.hotbarmanager.api.hotbar.IHotbarPlayer;
import me.kiiya.hotbarmanager.utils.HotbarUtils;
import me.kiiya.hotbarmanager.utils.Utility;
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

        // 提前检查processing状态
        if (processing.contains(p.getUniqueId())) return;
        processing.add(p.getUniqueId());

        try {
            // MAIN VARIABLES - 移到try内部
            PlayerInventory inv = p.getInventory();
            IHotbarPlayer hp = HotbarManager.getAPI().getHotbarPlayer(p);
            Category cat = HotbarUtils.getCategoryFromString(e.getCategoryContent().getIdentifier());
            List<Category> hotbar = hp.getHotbarAsList();

            // 提前终止无效流程
            if (cat == null || cat == Category.NONE || !hotbar.contains(cat)) {
                return;
            }

            // ITEM AND SHOP VARIABLES
            ITeam t = e.getArena().getTeam(p);
            CategoryContent cc = (CategoryContent) e.getCategoryContent();
            String identifier = cc.getIdentifier();
            ShopCache cache = ShopCache.getShopCache(p.getUniqueId());

            // 提前获取缓存项并检查
            ShopCache.CachedItem cachedItem = cache.getCachedItem(cc);
            if (cachedItem == null) {
                debug("CachedItem is null for " + identifier);
                return;
            }

            IContentTier upgradableContent = cc.getContentTiers().get(cachedItem.getTier()-1);
            ItemStack item = Utility.formatItemStack(upgradableContent.getBuyItemsList().get(0).getItemStack(), t);

            // 提前计算金钱相关
            Material currency = upgradableContent.getCurrency();
            int price = upgradableContent.getPrice();
            int totalPlayerMoney = CategoryContent.calculateMoney(p, currency);

            // 优化循环处理
            for (int i = 0; i < 9; i++) {
                if (hotbar.get(i) != cat) continue;

                ItemStack itemSlot = inv.getItem(i);
                boolean slotEmpty = itemSlot == null || itemSlot.getType() == Material.AIR;

                // 处理武器升级逻辑
                if (BedWars.nms.isSword(item)) {
                    handleSwordUpgrade(p, inv, i, item, itemSlot, t, slotEmpty);
                    completePurchase(p, cc, cache, item, currency, price, totalPlayerMoney, identifier,inv);
                    e.setCancelled(true);
                    return;
                }

                // 处理工具/永久物品
                if (!slotEmpty && (BedWars.nms.isTool(itemSlot) || itemSlot.getType() == Material.SHEARS)) {
                    if (Utility.getItemCategory(itemSlot) == cat &&
                            !vs.getShopUpgradeIdentifier(itemSlot).equalsIgnoreCase(identifier)) {
                        continue;
                    }
                }

                // 处理物品替换/堆叠
                if (!slotEmpty) {
                    if(handleItemReplacement(p, inv, i, item, itemSlot, vs, identifier, cat)){
                        completePurchase(p, cc, cache, item, currency, price, totalPlayerMoney, identifier,inv);
                        e.setCancelled(true);
                        return;
                    }
                    continue;
                }

                // 处理空槽位
                handleEmptySlot(p, inv, i, item, cc, vs, identifier, t);
                completePurchase(p, cc, cache, item, currency, price, totalPlayerMoney, identifier,inv);
                e.setCancelled(true);
                return;
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        } finally {
            processing.remove(p.getUniqueId());
        }
    }

// 以下是提取出来的方法（保持原有逻辑不变）

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
                if (cloned != null) inv.addItem(cloned);
            } else {
                inv.addItem(newItem);
            }
        } else {
            inv.setItem(slot, newItem);
        }
        p.updateInventory();
    }

    private boolean handleItemReplacement(Player p, PlayerInventory inv, int slot, ItemStack newItem,
                                          ItemStack oldItem, VersionSupport vs, String identifier, Category cc) {
        // 相同identifier替换
        if (vs.getShopUpgradeIdentifier(oldItem) != null &&
                vs.getShopUpgradeIdentifier(oldItem).equals(identifier)) {
            inv.setItem(slot, vs.setShopUpgradeIdentifier(newItem, identifier));
            p.updateInventory();
            return true;
        }

        // 堆叠处理
        if (newItem.getType() == oldItem.getType() &&
                newItem.getDurability() == oldItem.getDurability()) {
            int total = oldItem.getAmount() + newItem.getAmount();
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
        if (Utility.getItemCategory(oldItem) != cc) {
            return false;
        }
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
                                  Material currency, int price, int totalMoney, String identifier,PlayerInventory inv) {
        CategoryContent.takeMoney(p, currency, price);
        Sounds.playSound("shop-bought", p);

        // 金钱校验逻辑保持不变
        int finalMoney = CategoryContent.calculateMoney(p, currency);
        if (finalMoney < (totalMoney - price)) {
            inv.addItem(new ItemStack(currency, (totalMoney - price) - finalMoney));
        } else if (finalMoney > (totalMoney - price)) {
            inv.removeItem(new ItemStack(currency, finalMoney - (totalMoney - price)));
        }

        // 发送消息逻辑
        p.sendMessage(Utility.getMsg(p, "shop-new-purchase")
                .replace("{prefix}", Utility.getMsg(p, "prefix"))
                .replace("{item}", Utility.getMsg(p, "shop-items-messages." + identifier.split("\\.")[0] + ".content-item-" + identifier.split("\\.")[2] + "-name"))
                .replace("{color}", "")
                .replace("{tier}", !BedWars.nms.isTool(item) ? "" : CategoryContent.getRomanNumber(cache.getCachedItem(cc).getTier())));
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