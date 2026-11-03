package org.metamechanists.odysseia.events.rush;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.metamechanists.odysseia.events.OdysseiaEventManager;

import java.util.Locale;

public class RushListener implements Listener {

    private final OdysseiaEventManager eventManager;

    public RushListener(OdysseiaEventManager eventManager) {
        this.eventManager = eventManager;
    }

    private static boolean isEligiblePlayer(Player player) {
        if (player == null || player.getGameMode() != GameMode.SURVIVAL) return false;
        World w = player.getWorld();
        if (w == null) return false;
        String name = w.getName().toLowerCase(Locale.ROOT);
        return !name.contains("lab") && !name.equals("lobby");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!(eventManager.getCurrentRush() instanceof RushEvent rush)) return;
        if (rush.getRushType() == RushEvent.RushType.MINING) {
            Block b = event.getBlockPlaced();
            if (getMiningPoints(b.getType()) > 0) {
                rush.markPlacedBlock(b);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!(eventManager.getCurrentRush() instanceof RushEvent rush)) return;
        Player player = event.getPlayer();
        if (!isEligiblePlayer(player)) return;
        Block block = event.getBlock();

        // Check Rush Type
        if (rush.getRushType() == RushEvent.RushType.MINING) {
            if (rush.isPlacedBlock(block)) return;
            long points = getMiningPoints(block.getType());
            if (points > 0) {
                rush.addScore(player, points);
            }
        } else if (rush.getRushType() == RushEvent.RushType.HARVEST) {
            long points = getHarvestPoints(block);
            if (points > 0) {
                rush.addScore(player, points);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (!(eventManager.getCurrentRush() instanceof RushEvent rush)) return;
        if (rush.getRushType() != RushEvent.RushType.FISHING) return;
        if (!isEligiblePlayer(event.getPlayer())) return;

        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
            rush.addScore(event.getPlayer(), 5);
        } else if (event.getState() == PlayerFishEvent.State.CAUGHT_ENTITY && event.getCaught() instanceof Item) {
            rush.addScore(event.getPlayer(), 15);
        }
    }

    private long getMiningPoints(Material mat) {
        return switch (mat) {
            case ANCIENT_DEBRIS -> 20L;
            case DEEPSLATE_DIAMOND_ORE -> 10L;
            case DIAMOND_ORE -> 8L;
            case DEEPSLATE_EMERALD_ORE, EMERALD_ORE -> 12L;
            case DEEPSLATE_GOLD_ORE, GOLD_ORE, NETHER_GOLD_ORE -> 3L;
            case DEEPSLATE_IRON_ORE, IRON_ORE -> 1L;
            case DEEPSLATE_COPPER_ORE, COPPER_ORE -> 1L;
            default -> 0L;
        };
    }

    private long getHarvestPoints(Block block) {
        Material mat = block.getType();
        BlockData data = block.getBlockData();

        if (data instanceof Ageable ageable) {
            if (ageable.getAge() == ageable.getMaximumAge()) {
                return switch (mat) {
                    case POTATOES -> 5L; // Papa-Maratón bonus!
                    case CARROTS, WHEAT, BEETROOTS -> 2L;
                    case NETHER_WART -> 4L;
                    default -> 1L;
                };
            }
        }
        return 0L;
    }
}
