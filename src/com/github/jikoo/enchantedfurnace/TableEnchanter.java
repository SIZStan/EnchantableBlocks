package com.github.jikoo.enchantedfurnace;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Random;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;

/**
 * Handles enchantments in enchantment tables.
 * 
 * @author Jikoo
 */
public class TableEnchanter implements Listener {

	private final EnchantedFurnace plugin;
	private final Random random;

	public TableEnchanter(EnchantedFurnace plugin, Random random) {
		this.plugin = plugin;
		this.random = random;
	}

	@EventHandler(ignoreCancelled = false)
	public void onPrepareItemEnchant(PrepareItemEnchantEvent event) {
		if (event.getItem().getEnchantments().size() == 0
				&& event.getItem().getType().equals(Material.FURNACE)
				&& event.getItem().getAmount() == 1
				&& plugin.getEnchantments().size() > 0
				&& event.getEnchanter().hasPermission("enchantedfurnace.enchant.table")) {
			event.setCancelled(false);
			for (int i = 0; i < 3; i++) {
				event.getExpLevelCostsOffered()[i] = getButtonLevel(i, event.getEnchantmentBonus());
			}
		}
	}

	private int getButtonLevel(int slot, int shelves) {
		// Vanilla - get levels to display in table buttons
		if (shelves > 15) {
			shelves = 15;
		}
		int i = random.nextInt(8) + 1 + (shelves >> 1) + random.nextInt(shelves + 1);
		if (slot == 0) {
			return Math.max(i / 3, 1);
		}
		if (slot == 1) {
			return (i * 2 / 3 + 1);
		}
		return Math.max(i, shelves * 2);
	}

	@EventHandler
	public void onEnchantItem(EnchantItemEvent event) {
		if (event.getItem().getType() != Material.FURNACE
				|| event.getItem().getAmount() != 1
				|| !event.getEnchanter().hasPermission("enchantedfurnace.enchant.table")) {
			return;
		}
		int effectiveLevel = getEnchantingLevel(event.getExpLevelCost());
		HashSet<Enchantment> possibleEnchants = plugin.getEnchantments();
		Iterator<Enchantment> iterator = possibleEnchants.iterator();
		while (iterator.hasNext()) {
			if (getEnchantmentLevel(iterator.next(), effectiveLevel) == 0) {
				iterator.remove();
			}
		}
		boolean firstRun = true;
		while (firstRun || random.nextDouble() < ((effectiveLevel / Math.pow(2, event.getEnchantsToAdd().size())) / 50) && possibleEnchants.size() > 0) {
			firstRun = false;
			Enchantment ench = getWeightedEnchant(possibleEnchants);
			event.getEnchantsToAdd().put(ench, getEnchantmentLevel(ench, effectiveLevel));
			possibleEnchants.remove(ench);
			iterator = possibleEnchants.iterator();
			while (iterator.hasNext()) {
				if (!plugin.areEnchantmentsCompatible(ench, iterator.next())) {
					iterator.remove();
				}
			}
		}
	}

	private int getEnchantingLevel(int displayedLevel) {
		// Vanilla: enchant level = button level + rand(enchantabity / 4 + 1) + rand(enchantabity / 4 + 1) + 1
		double enchantability = plugin.getFurnaceEnchantability() / 4 + 1;
		int enchantingLevel = displayedLevel + 1 + randomInt(enchantability) + randomInt(enchantability);
		// Vanilla: random enchantability bonus 85-115%
		double bonus = (random.nextDouble() + random.nextDouble() - 1) * 0.15 + 1;
		enchantingLevel = (int) (enchantingLevel * bonus + 0.5);
		return enchantingLevel < 1 ? 1 : enchantingLevel;
	}

	private int randomInt(double cap) {
		return (int) (random.nextDouble() * cap);
	}

	private int getEnchantmentLevel(Enchantment enchant, int lvl) {
		// Not worried about high end cap reducing silk/fortune rates

		// Enchantments use upper value if within multiple ranges. Why there's a larger range at all, I don't know.
		if (enchant.equals(Enchantment.DIG_SPEED)) {
			// Efficiency 1:1–51 2:11–61 3:21–71 4:31–81 5:41–91
			return lvl < 1 ? 0 : lvl < 11 ? 1 : lvl < 21 ? 2 : lvl < 31 ? 3 : lvl < 41 ? 4 : 5;
		}
		if (enchant.equals(Enchantment.DURABILITY)) {
			// Unbreaking 1:5-55 2:13-63 3:21-71
			return lvl < 5 ? 0 : lvl < 13 ? 1 : lvl < 21 ? 2 : 3;
		}
		if (enchant.equals(Enchantment.LOOT_BONUS_BLOCKS)) {
			// Fortune 1:15-65 2:24-74 3:33-83
			return lvl < 15 ? 0 : lvl < 24 ? 1 : lvl < 33 ? 2 : 3;
		}
		if (enchant.equals(Enchantment.SILK_TOUCH)) {
			// Silk Touch 1:15-65
			return lvl < 15 ? 0 : 1;
		}
		return 0;
	}

	private int getWeight(Enchantment enchant) {
		if (enchant.equals(Enchantment.DIG_SPEED)) {
			return 10;
		}
		if (enchant.equals(Enchantment.DURABILITY)) {
			return 5;
		}
		if (enchant.equals(Enchantment.LOOT_BONUS_BLOCKS)) {
			return 2;
		}
		if (enchant.equals(Enchantment.SILK_TOUCH)) {
			return 1;
		}
		return 0;
	}

	private Enchantment getWeightedEnchant(HashSet<Enchantment> enchants) {
		int randInt = 0;
		for (Enchantment ench : enchants) {
			randInt += getWeight(ench);
		}
		if (randInt <= 0) {
			// Gets hit sometimes cause I'm bad at code apparently.
			return Enchantment.DIG_SPEED;
		}
		randInt = random.nextInt(randInt) + 1;
		for (Enchantment ench : enchants) {
			int weight = getWeight(ench);
			if (randInt >= weight) {
				randInt -= weight;
			} else {
				return ench;
			}
		}
		// Shouldn't ever hit this, but it's here just in case. Efficiency is a safe default.
		return Enchantment.DIG_SPEED;
	}
}
