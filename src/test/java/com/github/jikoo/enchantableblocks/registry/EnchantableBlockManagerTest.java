package com.github.jikoo.enchantableblocks.registry;

import static com.github.jikoo.enchantableblocks.util.matcher.IsSimilarMatcher.isSimilar;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import com.github.jikoo.enchantableblocks.block.EnchantableBlock;
import com.github.jikoo.enchantableblocks.block.impl.dummy.DummyEnchantableBlock.DummyEnchantableRegistration;
import com.github.jikoo.enchantableblocks.registry.EnchantableBlockManager.RegionStorageData;
import com.github.jikoo.enchantableblocks.util.Region;
import com.github.jikoo.enchantableblocks.util.RegionStorage;
import com.github.jikoo.enchantableblocks.util.logging.PatternCountHandler;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@DisplayName("Feature: Manage enchantable blocks.")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EnchantableBlockManagerTest {

  private static final String NORMAL_WORLD_NAME = "world";
  private static final String DISABLED_WORLD_NAME = "lame_vanilla_world";
  private static final String DISABLED_WORLD_PATH =
      "blocks.DummyEnchantableBlock.overrides." + DISABLED_WORLD_NAME + ".enabled";

  private Plugin plugin;
  private EnchantableBlockManager manager;
  private Block block;
  private Block blockDisabledWorld;
  private Chunk chunk;
  private Chunk chunkBad;

  @BeforeAll
  void setUp() {
    ServerMock serverMock = MockBukkit.mock();
    block = serverMock.addSimpleWorld(NORMAL_WORLD_NAME).getBlockAt(0, 0, 0);
    blockDisabledWorld = serverMock.addSimpleWorld(DISABLED_WORLD_NAME).getBlockAt(0, 0, 0);
  }

  @BeforeEach
  void setUpEach() {
    // New fake plugin and manager
    plugin = MockBukkit.createMockPlugin("EnchantableBlocks");
    manager = new EnchantableBlockManager(plugin);

    // Disable dummy in disabled world
    plugin.getConfig().set(DISABLED_WORLD_PATH, false);

    // Register dummy with manager
    DummyEnchantableRegistration registration = new DummyEnchantableRegistration(
        plugin,
        Set.of(Enchantment.DIG_SPEED),
        EnumSet.of(Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE)
    );
    manager.getRegistry().register(registration);

    // Reset block types
    block.setType(Material.DIRT);
    blockDisabledWorld.setType(Material.DIRT);
  }

  @AfterAll
  void tearDown() {
    MockBukkit.unmock();
  }

  @Test
  @DisplayName("Configuration cache should be reloaded when manager is reloaded.")
  void testReloadRegistry() {
    EnchantableBlockRegistry registry = manager.getRegistry();
    var registration = new DummyEnchantableRegistration(plugin, Set.of(), Set.of(Material.DIRT));
    registry.register(registration);
    var config = registration.getConfig();
    assertThat("Config must be cached", registration.getConfig(), is(config));
    registry.reload();
    assertThat("Config cached must be cleared", registration.getConfig(), is(not(config)));
  }

  @Test
  @DisplayName("ItemStack should be validated when creating blocks.")
  void testCreateBlockItemValidation() {
    ItemStack stack = new ItemStack(Material.AIR);
    var enchantableBlock = manager.createBlock(block, stack);
    assertThat("Item must not be air", enchantableBlock, nullValue());

    stack.setType(Material.WOODEN_AXE);
    enchantableBlock = manager.createBlock(block, stack);
    assertThat("Non-block materials are not supported", enchantableBlock, nullValue());

    stack.setType(Material.DIRT);
    enchantableBlock = manager.createBlock(block, stack);
    assertThat("Item must be enchanted", enchantableBlock, nullValue());
  }

  @Test
  @DisplayName("EnchantableBlocks should be creatable from an item and in-world block.")
  void testCreateBlock() {
    ItemStack stack = new ItemStack(Material.DIRT);
    stack.addUnsafeEnchantment(Enchantment.DIG_SPEED, 1);
    var enchantableBlock = manager.createBlock(block, stack);
    assertThat("Material must be supported by a registration", enchantableBlock, nullValue());

    stack.setType(Material.COAL_ORE);
    enchantableBlock = manager.createBlock(blockDisabledWorld, stack);
    assertThat("EnchantableBlock should not be created if disabled", enchantableBlock, nullValue());

    enchantableBlock = manager.createBlock(block, stack);
    assertThat("EnchantableBlock should be created if supported", enchantableBlock, notNullValue());
    assertThat(
        "Item should match creation stack",
        enchantableBlock.getItemStack(),
        isSimilar(stack));
  }

  @Test
  @DisplayName("Undefined blocks should return null.")
  void testGetBlockUndefined() {
    assertThat(
        "Nonexistent block should return null",
        manager.getBlock(block),
        nullValue());
  }

  @Test
  @DisplayName("Blocks in disabled worlds should return null.")
  void testGetBlock() {
    ItemStack stack = new ItemStack(Material.COAL_ORE);
    stack.addUnsafeEnchantment(Enchantment.DIG_SPEED, 1);
    EnchantableBlock enchantableBlock = manager.createBlock(block, stack);

    assertThat(
        "Enabled valid block must be retrievable",
        manager.getBlock(block),
        is(enchantableBlock));

    plugin.getConfig().set(DISABLED_WORLD_PATH, true);
    manager.getRegistry().reload();
    enchantableBlock = manager.createBlock(blockDisabledWorld, stack);
    assertThat(
        "Enabled valid block must be retrievable",
        manager.getBlock(blockDisabledWorld),
        is(enchantableBlock));

    plugin.getConfig().set(DISABLED_WORLD_PATH, false);
    manager.getRegistry().reload();
    assertThat(
        "Valid block in disabled world must return null",
        manager.getBlock(blockDisabledWorld),
        nullValue());
  }

  @Test
  @DisplayName("Destroying undefined blocks should return null.")
  void testDestroyBlockUndefined() {
    assertThat(
        "Nonexistent block should return null",
        manager.destroyBlock(block),
        nullValue());
  }

  @Test
  @DisplayName("Destroying blocks with invalid data should return in-memory item if possible.")
  void testDestroyBlockInvalidData() {
    ItemStack stack = new ItemStack(Material.COAL_ORE);
    stack.addUnsafeEnchantment(Enchantment.DIG_SPEED, 1);
    var enchantableBlock = manager.createBlock(block, stack);
    assertThat("EnchantableBlock must not be null", enchantableBlock, notNullValue());
    Region region = new Region(block);
    manager.saveFileCache.put(region, null);

    assertThat(
        "Null save data should return null",
        manager.destroyBlock(block),
        nullValue());

    manager.saveFileCache.invalidate(region);
    enchantableBlock = manager.createBlock(block, stack);
    assertThat("EnchantableBlock must not be null", enchantableBlock, notNullValue());
    String chunkPath = EnchantableBlockManager.getChunkPath(block);
    Objects.requireNonNull(manager.saveFileCache.get(region))
        .getStorage().set(chunkPath, "not a section");
    block.setType(Material.DIRT);

    assertThat(
        "Invalid save data should return null for invalid block type",
        manager.destroyBlock(block),
        nullValue());

    manager.saveFileCache.invalidate(region);
    enchantableBlock = manager.createBlock(block, stack);
    assertThat("EnchantableBlock must not be null", enchantableBlock, notNullValue());
    Objects.requireNonNull(manager.saveFileCache.get(region))
        .getStorage().set(chunkPath, "not a section");
    block.setType(Material.COAL_ORE);

    assertThat(
        "Invalid save data should still return in-memory item if available",
        manager.destroyBlock(block),
        isSimilar(stack));
  }

  @Test
  @DisplayName("Destroying valid blocks should return creation item.")
  void testDestroyBlock() {
    ItemStack stack = new ItemStack(Material.COAL_ORE);
    stack.addUnsafeEnchantment(Enchantment.SILK_TOUCH, 1);
    var enchantableBlock = manager.createBlock(block, stack);
    assertThat("EnchantableBlock must not be null", enchantableBlock, notNullValue());
    block.setType(Material.DIRT);

    assertThat(
        "Invalid block type should return null",
        manager.destroyBlock(block),
        nullValue());

    enchantableBlock = manager.createBlock(block, stack);
    assertThat("EnchantableBlock must not be null", enchantableBlock, notNullValue());
    block.setType(Material.COAL_ORE);

    assertThat(
        "Valid block should return creation item",
        manager.destroyBlock(block),
        isSimilar(stack));
  }

  private void setUpChunks() {
    chunk = block.getChunk();
    Region region = new Region(chunk);
    RegionStorage storage = Objects.requireNonNull(manager.saveFileCache.get(region)).getStorage();
    chunkBad = block.getWorld().getChunkAt(chunk.getX() + 1, chunk.getZ() + 1);
    storage.set(EnchantableBlockManager.getChunkPath(chunkBad.getBlock(0, 0, 0)), null);
    var section = storage.getConfigurationSection(EnchantableBlockManager.getChunkPath(block));
    if (section == null) {
      section = storage.createSection(EnchantableBlockManager.getChunkPath(block));
    }
    section.set("badpath", "not a config section");
    section.set("bad block path.stuff", "value");
    section.set("bad_block_path.stuff", "extreme value");
    section.set("1_1_1", "bad value");
    section.set("1_2_1.itemstack", "bad value");
    ItemStack stack = new ItemStack(Material.COAL_ORE);
    stack.addUnsafeEnchantment(Enchantment.DIG_SPEED, 1);
    section.set(EnchantableBlockManager.getBlockPath(block) + ".itemstack", stack);
    block.setType(stack.getType());
    section.set("0_1_0.itemstack", stack);
  }

  @Test
  void testLoadChunkBlocks() {
    setUpChunks();

    var invalidConfigSection = new PatternCountHandler("Invalid ConfigurationSection .*");
    plugin.getLogger().addHandler(invalidConfigSection);
    var unparseableCoordinates = new PatternCountHandler("Unparseable coordinates .*");
    plugin.getLogger().addHandler(unparseableCoordinates);
    var invalidSave = new PatternCountHandler("Removed invalid save .*");
    plugin.getLogger().addHandler(invalidSave);
    manager.loadChunkBlocks(chunk);
    assertThat(
        "Expected 2 non-ConfigurationSetting values",
        invalidConfigSection.getMatches(),
        is(2));
    assertThat(
        "Expected 2 unparseable coordinates",
        unparseableCoordinates.getMatches(),
        is(2));
    assertThat(
        "Expected 2 invalid items or blocks",
        invalidSave.getMatches(),
        is(2));
    var enchantableBlock = manager.getBlock(this.block);
    assertThat("Block must be loaded", enchantableBlock, is(notNullValue()));
    assertDoesNotThrow(() -> manager.loadChunkBlocks(chunkBad));
  }

  @Test
  void testUnloadChunkBlocks() {
    setUpChunks();

    block.setType(Material.COAL_ORE);
    ItemStack stack = new ItemStack(Material.COAL_ORE);
    stack.addUnsafeEnchantment(Enchantment.DIG_SPEED, 1);
    manager.createBlock(block, stack);

    assertDoesNotThrow(() -> manager.unloadChunkBlocks(chunk));
    assertThat("Block must be unloaded", manager.getBlock(block), is(nullValue()));
    assertDoesNotThrow(() -> manager.unloadChunkBlocks(chunkBad));
  }

  @Test
  void testExpireCache() {
    // MockBukkit can't unload chunks, so if a block has been created in the world the chunk is loaded.
    Region key = new Region("not_a_world", 0, 0);
    RegionStorageData storage = manager.saveFileCache.get(key);

    assertThat("Cached value must not be null", storage, is(notNullValue()));

    manager.expireCache();
    storage = manager.saveFileCache.get(key, false);

    assertThat("Cache must be cleaned after values expire", storage, is(nullValue()));
  }

  @Test
  void testDataHolder() {
    var data = manager.new RegionStorageData(new RegionStorage(plugin, new Region(block)));
    assertThat("New data should not be dirty", data.isDirty(), is(false));

    data.setDirty();
    assertThat("Data must be dirty once set", data.isDirty());

    data.clean();
    assertThat("Data must not be dirty after clean", data.isDirty(), is(false));

    data = manager.new RegionStorageData(new RegionStorage(plugin, new Region(block)));
    block.setType(Material.COAL_ORE);
    ItemStack stack = new ItemStack(Material.COAL_ORE);
    stack.addUnsafeEnchantment(Enchantment.DIG_SPEED, 1);
    var enchantableBlock = manager.createBlock(this.block, stack);
    assertThat("EnchantableBlock must not be null", enchantableBlock, notNullValue());
    enchantableBlock.setDirty(true);
    assertThat("Block must be dirty", enchantableBlock.isDirty());

    assertThat("Data must be dirty if blocks are dirty", data.isDirty());
    // Test twice to hit cached value
    assertThat("Data must be dirty if blocks are dirty", data.isDirty());


    assertThat("Block must be dirty", enchantableBlock.isDirty());
    data.clean();
    assertThat("Data must not be dirty after clean", data.isDirty(), is(false));
    assertThat("Block must be dirty", enchantableBlock.isDirty(), is(false));


  }

}