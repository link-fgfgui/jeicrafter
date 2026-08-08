package com.jeicrafter.client;

import com.jeicrafter.Constants;
import com.jeicrafter.mixin.MixinJeiBookmarkOverlay;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.helpers.IStackHelper;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.transfer.IRecipeTransferManager;
import mezz.jei.api.runtime.IJeiRuntime;
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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Core auto-crafting logic based on recipe bookmarks.
 * <p>
 * Algorithm: when holding the designated key and clicking an item,
 * if it is bookmarked as a representative item of a recipe bookmark — auto-detect whether materials are sufficient:
 * sufficient → transfer materials, wait for server sync, then complete crafting; insufficient → recursively apply the above to missing materials.
 * <p>
 * Transfer uses JEI's own recipe transfer mechanism (server-validated, multiplayer-safe);
 * "completing crafting" is done by shift-clicking the result slot (also goes through server click packets).
 */
public final class AutoCraftManager {

	/** Max recursion depth to prevent infinite loops when recipe bookmarks reference each other. */
	private static final int MAX_DEPTH = 16;
	/** Max crafts per trigger to prevent runaway recipe chains. */
	private static final int MAX_CRAFTS = 128;

	/** Runtime injected by the JEI plugin via {@code onRuntimeAvailable}; no longer accesses JEI internal classes. */
	private static volatile IJeiRuntime runtime;
	private static Session session;
	private static long nextSessionId;
	private static final int WAIT_TIMEOUT_TICKS = 200;

	private AutoCraftManager() {
	}

	/** For the JEI plugin to inject/clear the runtime. */
	public static void jeicrafter$setRuntime(IJeiRuntime jeiRuntime) {
		runtime = jeiRuntime;
		log("JEI_RUNTIME %s", jeiRuntime == null ? "UNAVAILABLE" : "AVAILABLE");
		if (jeiRuntime == null && session != null) {
			fail("JEI runtime became unavailable");
		}
	}

	/** Entry point: attempt to auto-craft the target item.
	 *
	 * @return true if the item is a representative item of a recipe bookmark and crafting succeeded;
	 *         false if not bookmarked (break) or crafting failed.
	 */
	public static boolean tryCraft(ItemStack target) {
		if (session != null) {
			log("session=%d ignored new request while busy", session.id);
			return true;
		}
		RecipeBookmark<?, ?> bookmark = findBookmark(target);
		if (bookmark == null) {
			return false;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return false;
		}
		session = new Session(++nextSessionId, bookmark, minecraft.player.containerMenu);
		log("session=%d START target=%s", session.id, stackName(target));
		advance();
		return true;
	}

	/** Called from the client tick so network/container synchronization can complete between actions. */
	public static void tick() {
		if (session != null) {
			advance();
		}
	}

	/** Whether the target item is bookmarked as a representative item (crafting output) of any recipe bookmark. */
	public static boolean isBookmarked(ItemStack target) {
		return findBookmark(target) != null;
	}

	/**
	 * State machine advances at most one step per tick. After recipe transfer and result extraction,
	 * must wait for container sync; after sub-recipe completes, re-analyze the parent recipe's actual inventory.
	 */
	private static void advance() {
		Session current = session;
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (player == null) {
			fail("player unavailable");
			return;
		}
		AbstractContainerMenu container = player.containerMenu;
		if (container != current.container) {
			fail("container changed from " + current.container.getClass().getSimpleName() + " to " + container.getClass().getSimpleName());
			return;
		}
		if (!(container instanceof CraftingMenu) && !(container instanceof InventoryMenu)) {
			message(player, "jeicrafter.message.need_crafting_table");
			fail("container is not crafting: " + container.getClass().getSimpleName());
			return;
		}
		if (current.waitTicks > WAIT_TIMEOUT_TICKS) {
			fail("timeout state=" + current.state + " containerState=" + container.getStateId());
			return;
		}
		if (current.state == State.WAIT_TRANSFER || current.state == State.WAIT_RESULT) {
			current.waitTicks++;
			if (current.state == State.WAIT_TRANSFER && container.getStateId() != current.stateId && container.getSlot(0).hasItem()) {
				log("session=%d STATE CLICK_RESULT transferStateId=%d output=%s", current.id, container.getStateId(), stackName(container.getSlot(0).getItem()));
				current.state = State.CLICK_RESULT;
				current.waitTicks = 0;
			} else if (current.state == State.WAIT_RESULT && container.getStateId() != current.stateId) {
				int resultCount = countInventory(current.resultItem);
				if (resultCount > current.resultCountBefore) {
					completeFrame();
				} else {
					fail("result not received item=" + stackName(current.resultItem) + " before=" + current.resultCountBefore + " after=" + resultCount);
				}
			}
			return;
		}
		if (current.state == State.CLICK_RESULT) {
			current.resultItem = getOutput(current.frames.peek());
			current.resultCountBefore = countInventory(current.resultItem);
			clickCraftResult(container, player);
			current.stateId = container.getStateId();
			current.state = State.WAIT_RESULT;
			current.waitTicks = 0;
			log("session=%d STATE WAIT_RESULT stateId=%d expected=%s before=%d", current.id, current.stateId, stackName(current.resultItem), current.resultCountBefore);
			return;
		}

		RecipeBookmark<?, ?> bookmark = current.frames.peek();
		if (bookmark == null) {
			finish();
			return;
		}
		log("session=%d STATE ANALYZE depth=%d containerState=%d", current.id, current.frames.size(), container.getStateId());
		IRecipeCategory<?> category = bookmark.getRecipeCategory();
		if (!RecipeTypes.CRAFTING.equals(category.getRecipeType())) {
			message(player, "jeicrafter.message.not_crafting_recipe");
			fail("recipe category is not crafting: " + category.getRecipeType());
			return;
		}
		IRecipeLayoutDrawable<?> layout = createLayout(bookmark);
		if (layout == null) {
			fail("recipe layout unavailable");
			return;
		}
		Map<ItemStack, Integer> missing = findMissing(layout, container);
		if (!missing.isEmpty()) {
			Map.Entry<ItemStack, Integer> entry = missing.entrySet().iterator().next();
			RecipeBookmark<?, ?> sub = findBookmark(entry.getKey());
			if (sub == null) {
				// The nearest recipe (top of the frame stack) is what asked for this material,
				// so surface it in the warning rather than tracing to the outermost request.
				ItemStack requester = getOutput(bookmark);
				Component requesterName = requester.isEmpty() ? entry.getKey().getHoverName() : requester.getHoverName();
				message(player, "jeicrafter.message.missing_material", requesterName, entry.getKey().getHoverName());
				fail("missing unbookmarked material=" + stackName(entry.getKey()));
				return;
			}
			if (current.frames.size() >= MAX_DEPTH || current.frames.contains(sub)) {
				fail("craft limit/cycle depth=" + current.frames.size());
				return;
			}
			current.frames.push(sub);
			log("session=%d PUSH depth=%d material=%s missing=%d", current.id, current.frames.size(), stackName(entry.getKey()), entry.getValue());
			return;
		}
		IJeiRuntime jeiRuntime = runtime();
		if (jeiRuntime == null) {
			fail("JEI runtime unavailable");
			return;
		}
		IRecipeTransferManager transferManager = jeiRuntime.getRecipeTransferManager();
		if (++current.crafts > MAX_CRAFTS) {
			fail("craft limit exceeded=" + MAX_CRAFTS);
			return;
		}
		boolean transferred = RecipeTransferUtil.transferRecipe(transferManager, container, layout, player, false);
		if (!transferred) {
			message(player, "jeicrafter.message.transfer_failed");
			fail("recipe transfer rejected");
			return;
		}
		current.stateId = container.getStateId();
		current.state = State.WAIT_TRANSFER;
		current.waitTicks = 0;
		log("session=%d STATE WAIT_TRANSFER frameDepth=%d stateId=%d", current.id, current.frames.size(), current.stateId);
		return;
	}

	/**
	 * Calculates how much of each material is missing from the recipe (greedy per-slot matching, consistent with JEI's transfer logic).
	 * Returns missing item -> missing count (empty = materials sufficient).
	 * <p>
	 * For slots accepting multiple variations (e.g. dyes/planks, OR-type ingredients), prefers the variation
	 * that is also bookmarked as a recipe bookmark when reporting missing, ensuring the recursion can actually
	 * craft that material; falls back to the first variation if none is bookmarked.
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
				ItemStack key = findEquivalentKey(pool, variation);
				int count = key == null ? 0 : pool.getOrDefault(key, 0);
				if (count > 0) {
					pool.put(key, count - 1);
					satisfied = true;
					break;
				}
			}
			if (satisfied) {
				continue;
			}
			// Missing: prefer variation with a recipe bookmark, otherwise fall back to first available
			ItemStack wanted = firstBookmarkedVariation(variations);
			if (wanted == null) {
				for (ItemStack variation : variations) {
					if (!variation.isEmpty()) {
						wanted = variation;
						break;
					}
				}
			}
			if (wanted != null) {
				ItemStack key = findEquivalentKey(missing, wanted);
				if (key == null) {
					key = keyOf(wanted);
				}
				missing.merge(key, 1, Integer::sum);
			}
		}
		return missing;
	}

	/** Returns the first variation in the list that is bookmarked as a recipe bookmark; null if none. */
	private static ItemStack firstBookmarkedVariation(List<ItemStack> variations) {
		for (ItemStack variation : variations) {
			if (!variation.isEmpty() && findBookmark(variation) != null) {
				return variation;
			}
		}
		return null;
	}

	/** Counts available items: player main inventory + crafting grid (excluding result slot). */
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
			ItemStack key = findEquivalentKey(pool, stack);
			if (key == null) {
				key = keyOf(stack);
			}
			pool.merge(key, stack.getCount(), Integer::sum);
		}
	}

	private static ItemStack findEquivalentKey(Map<ItemStack, Integer> stacks, ItemStack wanted) {
		IStackHelper stackHelper = stackHelper();
		for (ItemStack existing : stacks.keySet()) {
			if (stackHelper != null && stackHelper.isEquivalent(existing, wanted, UidContext.Ingredient)) {
				return existing;
			}
		}
		return null;
	}

	private static IStackHelper stackHelper() {
		IJeiRuntime jeiRuntime = runtime();
		return jeiRuntime == null ? null : jeiRuntime.getJeiHelpers().getStackHelper();
	}

	/** Uses (item, NBT) as the grouping key, normalized to count 1. */
	private static ItemStack keyOf(ItemStack stack) {
		ItemStack key = stack.copy();
		key.setCount(1);
		return key;
	}

	private static ItemStack getOutput(RecipeBookmark<?, ?> bookmark) {
		Optional<ItemStack> bookmarkOutput = bookmark.getRecipeOutput().getIngredient(VanillaTypes.ITEM_STACK);
		if (bookmarkOutput.isPresent()) {
			return bookmarkOutput.get().copy();
		}
		IRecipeLayoutDrawable<?> layout = createLayout(bookmark);
		if (layout == null) {
			return ItemStack.EMPTY;
		}
		return layout.getRecipeSlotsView().getSlotViews(RecipeIngredientRole.OUTPUT).stream()
			.findFirst()
			.flatMap(IRecipeSlotView::getDisplayedItemStack)
			.map(ItemStack::copy)
			.orElse(ItemStack.EMPTY);
	}

	private static int countInventory(ItemStack wanted) {
		if (wanted.isEmpty()) {
			return 0;
		}
		Map<ItemStack, Integer> pool = new HashMap<>();
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return 0;
		}
		for (ItemStack stack : player.getInventory().items) {
			addToPool(pool, stack);
		}
		ItemStack key = findEquivalentKey(pool, wanted);
		return key == null ? 0 : pool.getOrDefault(key, 0);
	}

	/** Builds a usable recipe layout from the bookmark recipe (same approach as JEI's internal RecipeBookmarkElement). */
	private static IRecipeLayoutDrawable<?> createLayout(RecipeBookmark<?, ?> bookmark) {
		IJeiRuntime jeiRuntime = runtime();
		if (jeiRuntime == null) {
			return null;
		}
		IRecipeCategory<?> category = bookmark.getRecipeCategory();
		Object recipe = bookmark.getRecipe();
		@SuppressWarnings({"unchecked", "rawtypes"})
		Optional<IRecipeLayoutDrawable<?>> layout = (Optional) jeiRuntime.getRecipeManager().createRecipeLayoutDrawable(
			(IRecipeCategory) category,
			recipe,
			jeiRuntime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup()
		);
		return layout.orElse(null);
	}

	/**
	 * Finds the recipe bookmark whose output matches the target item across all recipe bookmarks.
	 * Only recognizes RecipeBookmark (with full recipe data); plain item bookmarks (IngredientBookmark) do not count.
	 */
	private static RecipeBookmark<?, ?> findBookmark(ItemStack target) {
		if (target.isEmpty()) {
			return null;
		}
		IJeiRuntime jeiRuntime = runtime();
		if (jeiRuntime == null) {
			return null;
		}
		BookmarkList bookmarkList = ((MixinJeiBookmarkOverlay) jeiRuntime.getBookmarkOverlay()).jeicrafter$getBookmarkList();
		for (IElement<?> element : bookmarkList.getElements()) {
			Optional<IBookmark> bookmark = element.getBookmark();
			if (bookmark.isPresent() && bookmark.get() instanceof RecipeBookmark<?, ?> recipeBookmark) {
				Optional<ItemStack> output = recipeBookmark.getRecipeOutput().getIngredient(VanillaTypes.ITEM_STACK);
				IStackHelper stackHelper = stackHelper();
				if (output.isPresent() && stackHelper != null && stackHelper.isEquivalent(output.get(), target, UidContext.Ingredient)) {
					return recipeBookmark;
				}
			}
		}
		return null;
	}

	/** Completes crafting: shift-clicks slot 0 result slot (sends server click packet only; server responds with sync). */
	private static void clickCraftResult(AbstractContainerMenu container, LocalPlayer player) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.gameMode != null) {
			minecraft.gameMode.handleInventoryMouseClick(container.containerId, 0, 0, ClickType.QUICK_MOVE, player);
		}
	}

	private static void message(LocalPlayer player, String translationKey, Object... args) {
		player.displayClientMessage(Component.translatable(translationKey, args), false);
	}

	private static void completeFrame() {
		Session current = session;
		RecipeBookmark<?, ?> completed = current.frames.pop();
		log("session=%d CRAFT_COMPLETE depth=%d output=%s", current.id, current.frames.size(), stackName(completed.getRecipeOutput().getIngredient(VanillaTypes.ITEM_STACK).orElse(ItemStack.EMPTY)));
		current.state = State.ANALYZE;
		current.waitTicks = 0;
		if (current.frames.isEmpty()) {
			finish();
		}
	}

	private static void finish() {
		if (session != null) {
			log("session=%d FINISH crafts=%d", session.id, session.crafts);
		}
		session = null;
	}

	private static void fail(String reason) {
		if (session != null) {
			Constants.LOG.warn("[AutoCraft] session={} FAIL {}", session.id, reason);
		}
		session = null;
	}

	private static void log(String format, Object... args) {
		Constants.LOG.info("[AutoCraft] " + format, args);
	}

	private static String stackName(ItemStack stack) {
		return stack.isEmpty() ? "empty" : stack.getHoverName().getString() + " x" + stack.getCount();
	}

	private enum State {
		ANALYZE, WAIT_TRANSFER, CLICK_RESULT, WAIT_RESULT
	}

	private static final class Session {
		private final long id;
		private final Deque<RecipeBookmark<?, ?>> frames = new ArrayDeque<>();
		private State state = State.ANALYZE;
		private int stateId;
		private int waitTicks;
		private int crafts;
		private final AbstractContainerMenu container;
		private ItemStack resultItem = ItemStack.EMPTY;
		private int resultCountBefore;

		private Session(long id, RecipeBookmark<?, ?> root, AbstractContainerMenu container) {
			this.id = id;
			this.container = container;
			frames.push(root);
		}
	}

	private static IJeiRuntime runtime() {
		return runtime;
	}
}
