package com.github.jikoo.enchantableblocks;

import com.github.jikoo.enchantableblocks.block.impl.furnace.EnchantableFurnaceRegistration;
import com.github.jikoo.enchantableblocks.listener.AnvilEnchanter;
import com.github.jikoo.enchantableblocks.listener.TableEnchanter;
import com.github.jikoo.enchantableblocks.listener.WorldListener;
import com.github.jikoo.enchantableblocks.registry.EnchantableBlockManager;
import java.io.File;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.java.JavaPluginLoader;
import org.jetbrains.annotations.NotNull;

/**
 * A Bukkit plugin for adding effects to block based on enchantments.
 */
public class EnchantableBlocksPlugin extends JavaPlugin {

  private EnchantableBlockManager blockManager;

  public EnchantableBlocksPlugin() {
    super();
  }

  public EnchantableBlocksPlugin(
      @NotNull JavaPluginLoader loader,
      @NotNull PluginDescriptionFile description,
      @NotNull File dataFolder,
      @NotNull File file) {
    super(loader, description, dataFolder, file);
  }

  @Override
  public void onLoad() {
    this.blockManager = new EnchantableBlockManager(this);
  }

  @Override
  public void onEnable() {
    this.saveDefaultConfig();

    // Register generic listeners for block management.
    this.getServer().getPluginManager().registerEvents(
        new WorldListener(this, getBlockManager()), this);
    this.getServer().getPluginManager().registerEvents(
        new TableEnchanter(this, this.getBlockManager().getRegistry()), this);
    this.getServer().getPluginManager().registerEvents(
        new AnvilEnchanter(this, this.getBlockManager().getRegistry()), this);

    // Register implementation-specific details for furnaces.
    this.blockManager.getRegistry().register(
        new EnchantableFurnaceRegistration(this, this.getBlockManager()));

    // Only load blocks when server startup is complete to allow other providers time to enable.
    getServer().getScheduler().runTask(this, this::loadEnchantableBlocks);

  }

  private void loadEnchantableBlocks() {
    long startTime = System.nanoTime();
    // Load all EnchantableBlocks for loaded chunks.
    for (World world : this.getServer().getWorlds()) {
      for (Chunk chunk : world.getLoadedChunks()) {
        this.blockManager.loadChunkBlocks(chunk);
      }
    }
    getLogger().info(() ->
        "Loaded all active blocks in "
            + ((System.nanoTime() - startTime) / 1_000_000_000D) + " seconds");
  }

  @Override
  public void onDisable() {
    this.getServer().getScheduler().cancelTasks(this);
    this.blockManager.expireCache();
  }

  @Override
  public boolean onCommand(
      @NotNull CommandSender sender,
      @NotNull Command command,
      @NotNull String label,
      @NotNull String @NotNull [] args) {
    if (args.length < 1 || !args[0].equalsIgnoreCase("reload")) {
      sender.sendMessage("EnchantableBlocks v" + getDescription().getVersion());
      return false;
    }

    this.reloadConfig();
    this.blockManager.getRegistry().reload();
    sender.sendMessage(
        "[EnchantableBlocks v"
            + getDescription().getVersion()
            + "] Reloaded config and registry cache.");
    return true;
  }

  public EnchantableBlockManager getBlockManager() {
    return this.blockManager;
  }

}
