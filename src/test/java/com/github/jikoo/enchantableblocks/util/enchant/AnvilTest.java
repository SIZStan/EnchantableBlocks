package com.github.jikoo.enchantableblocks.util.enchant;

import static org.hamcrest.CoreMatchers.both;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.everyItem;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

import be.seeseemelk.mockbukkit.MockBukkit;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Repairable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for item combination.
 *
 * <p>As a developer, I want to be able to combine items and
 * enchanted books in the same fashion as an anvil.
 *
 * <p><b>Feature:</b> Calculate item combination results
 * <br><b>Given</b> I am a user
 * <br><b>When</b> I attempt to combine two items
 * <br><b>Then</b> the items should be combined in a vanilla fashion
 * <br><b>And</b> the combinations should ignore vanilla limitations where specified
 */
@DisplayName("Feature: Calculate item combinations for anvils.")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnvilTest {

  private static final Material BASE_MAT = Material.DIAMOND_SHOVEL;
  private static final int MAX_DAMAGE = BASE_MAT.getMaxDurability() - 1;
  private static final Material REPAIR_MAT = Material.DIAMOND;
  private static final Material INCOMPATIBLE_MAT = Material.STONE;

  @BeforeAll
  void beforeAll() {
    MockBukkit.mock();
    EnchantmentHelper.wrapCanEnchant();
    EnchantmentHelper.setupToolEnchants();
  }

  private static ItemStack maxDamageItem() {
    return prepareItem(new ItemStack(BASE_MAT), MAX_DAMAGE, 0);
  }

  private static ItemStack prepareItem(ItemStack itemStack, int damage, int repairCost) {
    ItemMeta itemMeta = itemStack.getItemMeta();

    if (damage != 0) {
      Damageable damageable = requireDamageable(itemMeta);
      damageable.setDamage(damage);
    }

    if (repairCost != 0) {
      Repairable repairable = requireRepairable(itemMeta);
      repairable.setRepairCost(repairCost);
    }

    itemStack.setItemMeta(itemMeta);

    return itemStack;
  }

  private static Damageable requireDamageable(@Nullable ItemMeta itemMeta) {
    assertThat("Meta may not be null", itemMeta, notNullValue());
    assertThat("Item must be damageable", itemMeta, instanceOf(Damageable.class));

    return (Damageable) itemMeta;
  }

  private static Repairable requireRepairable(@Nullable ItemMeta itemMeta) {
    assertThat("Meta may not be null", itemMeta, notNullValue());
    assertThat("Item must be repairable", itemMeta, instanceOf(Repairable.class));

    return (Repairable) itemMeta;
  }

  private static void applyEnchantments(ItemStack itemStack,
      @NotNull Map<Enchantment, Integer> enchantments) {
    ItemMeta itemMeta = itemStack.getItemMeta();
    assertThat("Meta may not be null", itemMeta, notNullValue());
    BiConsumer<Enchantment, Integer> metaAddEnchant = addEnchant(itemMeta);
    enchantments.forEach(metaAddEnchant);
    itemStack.setItemMeta(itemMeta);
  }

  private static BiConsumer<Enchantment, Integer> addEnchant(@NotNull ItemMeta meta) {
    if (meta instanceof EnchantmentStorageMeta) {
      return (enchantment, level) -> ((EnchantmentStorageMeta) meta).addStoredEnchant(enchantment,
          level, true);
    }

    return (enchantment, integer) -> meta.addEnchant(enchantment, integer, true);
  }

  @DisplayName("Damageable items should be repaired.")
  @Nested
  class RepairTest {

    @DisplayName("Items should be repaired using repair material.")
    @ParameterizedTest
    @ValueSource(ints = {1, 64})
    void testRepairWithMaterial(int repairMats) {
      ItemStack damagedStack = maxDamageItem();
      AnvilResult result = AnvilOperation.VANILLA.apply(damagedStack,
          new ItemStack(REPAIR_MAT, repairMats));

      ItemStack resultItem = result.getResult();
      int damage = requireDamageable(resultItem.getItemMeta()).getDamage();

      assertThat("Number of items to consume should be specified",
          result.getRepairCount(), greaterThan(0));
      assertThat("Item should be repaired.",
          damage, is(Math.max(0,
              MAX_DAMAGE - result.getRepairCount() * (BASE_MAT.getMaxDurability() / 4))));
      assertThat("Number of items to consume should not exceed number of available items",
          result.getRepairCount(), lessThanOrEqualTo(repairMats));
    }

    @DisplayName("Items should be repaired by combination.")
    @Test
    void testRepairWithMerge() {
      ItemStack damagedStack = maxDamageItem();
      AnvilResult result = AnvilOperation.VANILLA.apply(damagedStack.clone(), damagedStack.clone());

      int damage = requireDamageable(damagedStack.getItemMeta()).getDamage();
      int maxDurability = damagedStack.getType().getMaxDurability();
      int remainingDurability = maxDurability - damage;
      int bonusDurability = maxDurability * 12 / 100;
      int expectedDurability = 2 * remainingDurability + bonusDurability;
      int expectedDamage = maxDurability - expectedDurability;

      damage = requireDamageable(result.getResult().getItemMeta()).getDamage();
      assertThat("Items' durability should be added with a bonus of 12% of max durability", damage,
          is(expectedDamage));
      assertThat("Number of items to consume should not be specified", result.getRepairCount(),
          is(0));
    }

  }

  @DisplayName("Enchantments on items should be combined in various scenarios.")
  @ParameterizedTest
  @MethodSource("getCombineScenarios")
  void testCombine(ItemStack base, ItemStack added, AnvilOperation operation) {

    Map<Enchantment, Integer> enchantments = new HashMap<>();
    enchantments.put(Enchantment.DIG_SPEED, 4);
    enchantments.put(Enchantment.SILK_TOUCH, 1);

    applyEnchantments(base, enchantments);
    applyEnchantments(added, enchantments);

    AnvilResult result = operation.apply(base, added);

    if (!operation.getMaterialCombines().test(base, added)) {
      assertThat("Result must be empty", result.getResult().getType(), is(Material.AIR));
      return;
    }

    assertThat("Result must be of original type", result.getResult().getType(), equalTo(BASE_MAT));

    enchantments = new HashMap<>();
    enchantments.put(Enchantment.DIG_SPEED, 5);
    boolean isVanilla = operation == AnvilOperation.VANILLA;
    enchantments.put(Enchantment.SILK_TOUCH, isVanilla ? 1 : 2);

    assertThat("Enchantments must be merged with result",
        result.getResult().getEnchantments().entrySet(),
        both(everyItem(is(in(enchantments.entrySet())))).and(
            containsInAnyOrder(enchantments.entrySet().toArray())));
    assertThat("Number of items to consume should not be specified", result.getRepairCount(),
        is(0));

    int repairCost = requireRepairable(base.getItemMeta()).getRepairCost() + requireRepairable(
        added.getItemMeta()).getRepairCost();
    int cost =
        (added.getType() == Material.ENCHANTED_BOOK ? isVanilla ? 9 : 13 : isVanilla ? 13 : 21)
            + repairCost;
    assertThat("Operation cost is correct", result.getCost(), is(cost));

    operation.setRenameText("sample text");
    var renameResult = operation.apply(base, added);
    assertThat("Rename adds 1 to cost", renameResult.getCost(), is(cost + 1));
  }

  private Stream<Arguments> getCombineScenarios() {
    AtomicInteger scenarioIndex = new AtomicInteger();
    return Stream.generate(() -> {
      int index = scenarioIndex.getAndIncrement();
      return Arguments.of(scenarioBase(index), scenarioAdded(index), scenarioOp(index));
    }).limit(40);
  }

  private static ItemStack scenarioBase(int scenario) {
    ItemStack base = new ItemStack(BASE_MAT);

    int[] baseCosts = new int[]{0, 2, 0, 1, 5};
    int baseCost = baseCosts[scenario % 5];

    prepareItem(base, 0, baseCost);

    return base;
  }

  private static ItemStack scenarioAdded(int scenario) {
    Material[] addedMats = new Material[]{Material.ENCHANTED_BOOK, BASE_MAT, REPAIR_MAT,
        INCOMPATIBLE_MAT};
    ItemStack added = new ItemStack(addedMats[(scenario % 20) / 5]);

    int[] addedCosts = new int[]{0, 0, 10, 1, 1};
    int addedCost = addedCosts[scenario % 5];

    prepareItem(added, 0, addedCost);

    return added;
  }

  private static AnvilOperation scenarioOp(int scenario) {
    if (scenario % 40 < 20) {
      return AnvilOperation.VANILLA;
    }

    AnvilOperation operation = new AnvilOperation();
    operation.setCombineEnchants(true);
    operation.setEnchantApplies(Enchantment::canEnchantItem);
    operation.setEnchantConflicts((a, b) -> false);
    operation.setEnchantMaxLevel(a -> Short.MAX_VALUE);
    operation.setMaterialCombines((a, b) -> true);
    operation.setMergeRepairs(true);
    operation.setMaterialRepairs(AnvilRepairMaterial::repairs);

    return operation;
  }

  @DisplayName("Vanilla AnvilOperation constant should not be manipulable.")
  @Test
  void AnvilOperationConstantTest() {
    assertThrows(UnsupportedOperationException.class,
        () -> AnvilOperation.VANILLA.setCombineEnchants(false));
    assertThrows(UnsupportedOperationException.class,
        () -> AnvilOperation.VANILLA.setEnchantApplies((a, b) -> false));
    assertThrows(UnsupportedOperationException.class,
        () -> AnvilOperation.VANILLA.setEnchantConflicts((a, b) -> false));
    assertThrows(UnsupportedOperationException.class,
        () -> AnvilOperation.VANILLA.setEnchantMaxLevel((a) -> 0));
    assertThrows(UnsupportedOperationException.class,
        () -> AnvilOperation.VANILLA.setMaterialCombines((a, b) -> false));
    assertThrows(UnsupportedOperationException.class,
        () -> AnvilOperation.VANILLA.setMaterialRepairs((a, b) -> false));
    assertThrows(UnsupportedOperationException.class,
        () -> AnvilOperation.VANILLA.setMergeRepairs(false));
  }

  @AfterAll
  void afterAll() {
    MockBukkit.unmock();
  }

}
