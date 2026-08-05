package com.jeicrafter.client;

import com.jeicrafter.mixin.MixinJeiBookmarkOverlay;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.transfer.IRecipeTransferManager;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.common.Internal;
import mezz.jei.common.transfer.RecipeTransferUtil;
import mezz.jei.gui.bookmarks.BookmarkList;
import mezz.jei.gui.bookmarks.IBookmark;
import mezz.jei.gui.bookmarks.RecipeBookmark;
import mezz.jei.gui.overlay.elements.IElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 基于书签(recipe bookmark)的自动合成核心逻辑。
 * <p>
 * 算法:按着指定按键点击一个物品时,
 * 如果它作为代表物品被收藏为 recipe bookmark —— 自动检测转移配方材料够不够:
 * 够 → 转移材料,完成合成;不够 → 对缺失材料递归进行上述判断;否则(没有对应书签)break。
 * <p>
 * 转移使用 JEI 自身的配方转移机制(服务端校验、多人游戏安全),
 * "完成合成"通过 shift 点击合成结果格实现(同样走服务端点击包)。
 */
public final class AutoCraftManager {

	/** 递归深度上限,防止书签配方互相引用时死循环。 */
	private static final int MAX_DEPTH = 16;
	/** 单次触发最多执行的合成次数,防止极端配方链失控。 */
	private static final int MAX_CRAFTS = 128;
	/** 单层材料缺口循环上限。 */
	private static final int MAX_CHECK_ITERATIONS = 64;

	private AutoCraftManager() {
	}

	/**
	 * 入口:尝试自动合成目标物品。
	 *
	 * @return 该物品是某个 recipe bookmark 的代表物品且合成流程成功时为 true;
	 * 不是书签(break)或合成失败时为 false。
	 */
	public static boolean tryCraft(ItemStack target) {
		RecipeBookmark<?, ?> bookmark = findBookmark(target);
		if (bookmark == null) {
			return false;
		}
		return craftRecursive(bookmark, 0, new int[]{0});
	}

	/**
	 * 目标物品是否被收藏为某个 recipe bookmark 的代表物品(合成产物)。
	 */
	public static boolean isBookmarked(ItemStack target) {
		return findBookmark(target) != null;
	}

	/**
	 * 递归合成:
	 * 1. 检测配方材料是否足够转移;
	 * 2. 够 → 转移 + 完成合成,返回 true;
	 * 3. 不够 → 对缺失材料递归本判断,递归失败则 break 返回 false。
	 */
	private static boolean craftRecursive(RecipeBookmark<?, ?> bookmark, int depth, int[] budget) {
		if (depth > MAX_DEPTH) {
			return false;
		}
		if (++budget[0] > MAX_CRAFTS) {
			return false;
		}

		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (player == null) {
			return false;
		}

		// 只有合成类容器(3x3 合成台 / 玩家 2x2 背包合成)支持"转移 + shift 点击结果格"的合成动作
		AbstractContainerMenu container = player.containerMenu;
		if (!(container instanceof CraftingMenu) && !(container instanceof InventoryMenu)) {
			message(player, "jeicrafter.message.need_crafting_table");
			return false;
		}

		IRecipeCategory<?> category = bookmark.getRecipeCategory();
		if (!RecipeTypes.CRAFTING.equals(category.getRecipeType())) {
			message(player, "jeicrafter.message.not_crafting_recipe");
			return false;
		}

		IRecipeLayoutDrawable<?> layout = createLayout(bookmark);
		if (layout == null) {
			return false;
		}

		// 循环检测材料缺口并递归补齐:子合成可能消耗父配方所需材料,因此每次递归后重新检查
		int iterations = 0;
		while (true) {
			if (++iterations > MAX_CHECK_ITERATIONS) {
				return false;
			}
			Map<ItemStack, Integer> missing = findMissing(layout, container);
			if (missing.isEmpty()) {
				break;
			}
			for (Map.Entry<ItemStack, Integer> entry : missing.entrySet()) {
				ItemStack material = entry.getKey();
				int missingCount = entry.getValue();

				// 材料自身也要被收藏为 recipe bookmark 才能递归合成,否则 break
				RecipeBookmark<?, ?> subBookmark = findBookmark(material);
				if (subBookmark == null) {
					message(player, "jeicrafter.message.missing_material", material.getHoverName());
					return false;
				}

				int perCraft = getOutputCount(subBookmark);
				int crafts = Math.max(1, (missingCount + perCraft - 1) / perCraft);
				for (int i = 0; i < crafts; i++) {
					if (!craftRecursive(subBookmark, depth + 1, budget)) {
						return false;
					}
				}
			}
		}

		// 材料足够 → 通过 JEI 的配方转移机制把材料放入合成格(服务端校验并移动物品)
		IRecipeTransferManager transferManager = Internal.getJeiRuntime().getRecipeTransferManager();
		boolean transferred = RecipeTransferUtil.transferRecipe(transferManager, container, layout, player, false);
		if (!transferred) {
			message(player, "jeicrafter.message.transfer_failed");
			return false;
		}

		// 完成合成:shift 点击合成结果格(0 号槽),服务端完成实际合成
		clickCraftResult(container, player);
		return true;
	}

	/**
	 * 计算配方中每种材料还缺多少(贪心按槽匹配,与 JEI 的转移逻辑一致)。
	 * 返回值为 缺失物品 -> 缺失数量(空 = 材料足够)。
	 */
	private static Map<ItemStack, Integer> findMissing(IRecipeLayoutDrawable<?> layout, AbstractContainerMenu container) {
		Map<ItemStack, Integer> pool = countAvailable(container);
		Map<ItemStack, Integer> missing = new HashMap<>();

		for (IRecipeSlotView slot : layout.getRecipeSlotsView().getSlotViews(RecipeIngredientRole.INPUT)) {
			List<ItemStack> variations = slot.getIngredients(VanillaTypes.ITEM_STACK).toList();
			boolean satisfied = false;
			for (ItemStack variation : variations) {
				if (variation.isEmpty()) {
					continue;
				}
				ItemStack key = keyOf(variation);
				int count = pool.getOrDefault(key, 0);
				if (count > 0) {
					pool.put(key, count - 1);
					satisfied = true;
					break;
				}
			}
			if (!satisfied) {
				for (ItemStack variation : variations) {
					if (!variation.isEmpty()) {
						missing.merge(keyOf(variation), 1, Integer::sum);
						break;
					}
				}
			}
		}
		return missing;
	}

	/** 统计可用物品:玩家主背包 + 合成格(不含结果格)。 */
	private static Map<ItemStack, Integer> countAvailable(AbstractContainerMenu container) {
		Map<ItemStack, Integer> pool = new HashMap<>();
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return pool;
		}
		for (ItemStack stack : player.getInventory().items) {
			addToPool(pool, stack);
		}
		for (Slot slot : container.slots) {
			if (slot.container instanceof CraftingContainer) {
				addToPool(pool, slot.getItem());
			}
		}
		return pool;
	}

	private static void addToPool(Map<ItemStack, Integer> pool, ItemStack stack) {
		if (!stack.isEmpty()) {
			pool.merge(keyOf(stack), stack.getCount(), Integer::sum);
		}
	}

	/** 以 (物品, NBT) 作为归类键,数量归一为 1。 */
	private static ItemStack keyOf(ItemStack stack) {
		ItemStack key = stack.copy();
		key.setCount(1);
		return key;
	}

	/** 子配方单次合成产出数量(用于计算需要递归合成几次)。 */
	private static int getOutputCount(RecipeBookmark<?, ?> bookmark) {
		IRecipeLayoutDrawable<?> layout = createLayout(bookmark);
		if (layout == null) {
			return 1;
		}
		return layout.getRecipeSlotsView().getSlotViews(RecipeIngredientRole.OUTPUT).stream()
			.findFirst()
			.flatMap(IRecipeSlotView::getDisplayedItemStack)
			.map(ItemStack::getCount)
			.orElse(1);
	}

	/** 根据书签配方构建可用的配方布局(与 JEI 内部 RecipeBookmarkElement 相同的方式)。 */
	private static IRecipeLayoutDrawable<?> createLayout(RecipeBookmark<?, ?> bookmark) {
		IJeiRuntime runtime = Internal.getJeiRuntime();
		IRecipeCategory<?> category = bookmark.getRecipeCategory();
		Object recipe = bookmark.getRecipe();
		@SuppressWarnings({"unchecked", "rawtypes"})
		Optional<IRecipeLayoutDrawable<?>> layout = (Optional) runtime.getRecipeManager().createRecipeLayoutDrawable(
			(IRecipeCategory) category,
			recipe,
			runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup()
		);
		return layout.orElse(null);
	}

	/**
	 * 在所有 recipe bookmark 中查找产物与目标物品匹配的那个。
	 * 只认 RecipeBookmark(带完整配方数据),纯物品收藏(IngredientBookmark)不算。
	 */
	private static RecipeBookmark<?, ?> findBookmark(ItemStack target) {
		if (target.isEmpty()) {
			return null;
		}
		IJeiRuntime runtime = Internal.getJeiRuntime();
		BookmarkList bookmarkList = ((MixinJeiBookmarkOverlay) runtime.getBookmarkOverlay()).jeicrafter$getBookmarkList();
		for (IElement<?> element : bookmarkList.getElements()) {
			Optional<IBookmark> bookmark = element.getBookmark();
			if (bookmark.isPresent() && bookmark.get() instanceof RecipeBookmark<?, ?> recipeBookmark) {
				Optional<ItemStack> output = recipeBookmark.getRecipeOutput().getIngredient(VanillaTypes.ITEM_STACK);
				if (output.isPresent() && ItemStack.isSameItemSameTags(output.get(), target)) {
					return recipeBookmark;
				}
			}
		}
		return null;
	}

	/** 完成合成:shift 点击 0 号结果槽。本地模拟 + 发送服务端点击包(与玩家点击等价)。 */
	private static void clickCraftResult(AbstractContainerMenu container, LocalPlayer player) {
		Minecraft minecraft = Minecraft.getInstance();
		container.clicked(0, 0, ClickType.QUICK_MOVE, player);
		if (minecraft.gameMode != null) {
			minecraft.gameMode.handleInventoryMouseClick(container.containerId, 0, 0, ClickType.QUICK_MOVE, player);
		}
	}

	private static void message(LocalPlayer player, String translationKey, Object... args) {
		player.displayClientMessage(Component.translatable(translationKey, args), false);
	}
}
