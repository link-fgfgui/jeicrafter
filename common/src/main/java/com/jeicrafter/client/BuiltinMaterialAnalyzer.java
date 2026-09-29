package com.jeicrafter.client;

import com.jeicrafter.api.BookmarkActionContext;
import com.jeicrafter.api.MaterialAnalyzer;
import com.jeicrafter.api.RecipeGraphAccess;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.helpers.IStackHelper;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in material check: player inventory plus the open crafting grid, greedy per-slot matching.
 */
final class BuiltinMaterialAnalyzer implements MaterialAnalyzer {
	static final BuiltinMaterialAnalyzer INSTANCE = new BuiltinMaterialAnalyzer();

	private BuiltinMaterialAnalyzer() {
	}

	@Override
	public int priority() {
		return Integer.MIN_VALUE;
	}

	@Override
	public boolean supports(BookmarkActionContext context) {
		return true;
	}

	@Override
	public Map<ItemStack, Integer> findMissing(BookmarkActionContext context, RecipeGraphAccess graph) {
		IStackHelper stackHelper = context.jeiRuntime().getJeiHelpers().getStackHelper();
		Map<ItemStack, Integer> pool = countAvailable(context.container(), context.player(), stackHelper);
		Map<ItemStack, Integer> missing = new LinkedHashMap<>();

		for (IRecipeSlotView slot : context.recipeLayout().getRecipeSlotsView().getSlotViews(RecipeIngredientRole.INPUT)) {
			List<ItemStack> variations = slot.getIngredients(VanillaTypes.ITEM_STACK).toList();
			boolean satisfied = false;
			for (ItemStack variation : variations) {
				if (variation.isEmpty()) {
					continue;
				}
				int need = Math.max(1, variation.getCount());
				ItemStack key = findEquivalentKey(pool, variation, stackHelper);
				int count = key == null ? 0 : pool.getOrDefault(key, 0);
				if (count >= need) {
					pool.put(key, count - need);
					satisfied = true;
					break;
				}
			}
			if (satisfied) {
				continue;
			}
			ItemStack wanted = firstGraphProducible(variations, graph);
			if (wanted == null) {
				for (ItemStack variation : variations) {
					if (!variation.isEmpty()) {
						wanted = variation;
						break;
					}
				}
			}
			if (wanted != null) {
				int need = Math.max(1, wanted.getCount());
				ItemStack key = findEquivalentKey(missing, wanted, stackHelper);
				if (key == null) {
					key = keyOf(wanted);
				}
				missing.merge(key, need, Integer::sum);
			}
		}
		return missing;
	}

	static int countInventory(ItemStack wanted, LocalPlayer player, IStackHelper stackHelper) {
		if (wanted.isEmpty() || player == null) {
			return 0;
		}
		Map<ItemStack, Integer> pool = new HashMap<>();
		for (ItemStack stack : player.getInventory().items) {
			addToPool(pool, stack, stackHelper);
		}
		ItemStack key = findEquivalentKey(pool, wanted, stackHelper);
		return key == null ? 0 : pool.getOrDefault(key, 0);
	}

	private static ItemStack firstGraphProducible(List<ItemStack> variations, RecipeGraphAccess graph) {
		for (ItemStack variation : variations) {
			if (!variation.isEmpty() && graph.canProduce(variation)) {
				return variation;
			}
		}
		return null;
	}

	private static Map<ItemStack, Integer> countAvailable(AbstractContainerMenu container, LocalPlayer player, IStackHelper stackHelper) {
		Map<ItemStack, Integer> pool = new HashMap<>();
		if (player != null) {
			for (ItemStack stack : player.getInventory().items) {
				addToPool(pool, stack, stackHelper);
			}
		}
		for (Slot slot : container.slots) {
			if (slot.container instanceof CraftingContainer) {
				addToPool(pool, slot.getItem(), stackHelper);
			}
		}
		return pool;
	}

	private static void addToPool(Map<ItemStack, Integer> pool, ItemStack stack, IStackHelper stackHelper) {
		if (stack.isEmpty()) {
			return;
		}
		ItemStack key = findEquivalentKey(pool, stack, stackHelper);
		if (key == null) {
			key = keyOf(stack);
		}
		pool.merge(key, stack.getCount(), Integer::sum);
	}

	private static ItemStack findEquivalentKey(Map<ItemStack, Integer> stacks, ItemStack wanted, IStackHelper stackHelper) {
		if (stackHelper == null) {
			for (ItemStack existing : stacks.keySet()) {
				if (ItemStack.isSameItemSameTags(existing, wanted)) {
					return existing;
				}
			}
			return null;
		}
		for (ItemStack existing : stacks.keySet()) {
			if (stackHelper.isEquivalent(existing, wanted, UidContext.Ingredient)) {
				return existing;
			}
		}
		return null;
	}

	private static ItemStack keyOf(ItemStack stack) {
		ItemStack key = stack.copy();
		key.setCount(1);
		return key;
	}
}
