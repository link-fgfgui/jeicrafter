package com.jeicrafter.client;

import com.jeicrafter.Constants;
import com.jeicrafter.api.BookmarkAction;
import com.jeicrafter.api.BookmarkActionContext;
import com.jeicrafter.api.BookmarkActionExecution;
import com.jeicrafter.api.BookmarkActionResult;
import com.jeicrafter.mixin.MixinJeiBookmarkOverlay;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.helpers.IStackHelper;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.transfer.IRecipeTransferManager;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.common.transfer.RecipeTransferUtil;
import mezz.jei.gui.bookmarks.BookmarkList;
import mezz.jei.gui.bookmarks.IBookmark;
import mezz.jei.gui.bookmarks.RecipeBookmark;
import mezz.jei.gui.overlay.elements.IElement;
import mezz.jei.library.plugins.jei.tags.ITagInfoRecipe;
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
import net.minecraft.world.item.crafting.CraftingRecipe;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Bookmark dependency resolver and action runner.
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
	/** Max actions per trigger to prevent runaway recipe chains. */
	private static final int MAX_ACTIONS = 128;
	private static final BookmarkAction BUILTIN_CRAFTING_ACTION = new CraftingBookmarkAction();
	private static final BookmarkAction BUILTIN_WORKSTATION_ACTION = new WorkstationBookmarkAction();
	private static final List<BookmarkAction> ACTIONS = new CopyOnWriteArrayList<>();

	/** Runtime injected by the JEI plugin via {@code onRuntimeAvailable}; no longer accesses JEI internal classes. */
	private static volatile IJeiRuntime runtime;
	private static Session session;
	private static long nextSessionId;
	private static final int WAIT_TIMEOUT_TICKS = 200;

	private AutoCraftManager() {
	}

	/** Implementation backing {@link com.jeicrafter.api.JeiCrafterApi#registerAction(BookmarkAction)}. */
	public static void registerAction(BookmarkAction action) {
		if (!ACTIONS.contains(action)) {
			ACTIONS.add(action);
			ACTIONS.sort(Comparator.comparingInt(BookmarkAction::priority).reversed());
		}
	}

	/** Implementation backing {@link com.jeicrafter.api.JeiCrafterApi#unregisterAction(BookmarkAction)}. */
	public static boolean unregisterAction(BookmarkAction action) {
		return ACTIONS.remove(action);
	}

	/**
	 * Re-anchors the active session's expected container after a workstation action changed it
	 * (e.g. closed a furnace and returned to the player inventory). Call after the workstation
	 * action completes or is cancelled.
	 */
	public static void reanchorSession() {
		if (session != null) {
			LocalPlayer player = Minecraft.getInstance().player;
			if (player != null && player.containerMenu != session.container) {
				log("session=%d CONTAINER_REANCHOR %s -> %s", session.id,
					session.container.getClass().getSimpleName(), player.containerMenu.getClass().getSimpleName());
				session.container = player.containerMenu;
			}
		}
	}

	public static boolean isRunning() {
		return session != null;
	}

	public static void cancel() {
		if (session != null) {
			abortExecution(session);
			log("session=%d CANCEL", session.id);
			session = null;
		}
	}

	/** For the JEI plugin to inject/clear the runtime. */
	public static void jeicrafter$setRuntime(IJeiRuntime jeiRuntime) {
		runtime = jeiRuntime;
		log("JEI_RUNTIME %s", jeiRuntime == null ? "UNAVAILABLE" : "AVAILABLE");
		if (jeiRuntime == null && session != null) {
			fail("JEI runtime became unavailable");
		}
	}

	/** Entry point when only the item is known (recursive material resolution): looks the bookmark up by output.
	 *
	 * @return true if the item is a representative item of a recipe bookmark and crafting succeeded;
	 *         false if not bookmarked (break) or crafting failed.
	 */
	public static boolean tryCraft(ItemStack target) {
		if (session != null) {
			log("session=%d ignored new request while busy", session.id);
			return true;
		}
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return false;
		}
		RecipeBookmark<?, ?> bookmark = findRunnableBookmark(target, player);
		if (bookmark == null) {
			return false;
		}
		return tryCraft(bookmark);
	}

	/**
	 * Entry point when the exact bookmark is known (bookmark bar click).
	 * <p>
	 * Takes the bookmark itself rather than its output item: {@link #findBookmark} returns the first bookmark
	 * with an equivalent output, so routing a click through an ItemStack would run a different recipe whenever
	 * several bookmarks share the same output.
	 */
	public static boolean tryCraft(RecipeBookmark<?, ?> bookmark) {
		if (session != null) {
			log("session=%d ignored new request while busy", session.id);
			return true;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return false;
		}
		ItemStack target = getOutput(bookmark);
		if (isTagInfoRecipe(bookmark)) {
			RecipeBookmark<?, ?> resolved = resolveCraftableBookmark(target);
			if (resolved == null) {
				message(minecraft.player, "jeicrafter.message.no_crafting_recipe", target.getHoverName());
				fail("tag bookmarked item has no crafting recipe: " + stackName(target));
				return true;
			}
			bookmark = resolved;
		}
		if (!canExecute(bookmark, minecraft.player)) {
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

	public static boolean isRunnable(ItemStack target) {
		LocalPlayer player = Minecraft.getInstance().player;
		return player != null && findRunnableBookmark(target, player) != null;
	}

	/**
	 * Whether this bookmark is something auto-craft can execute: a crafting recipe, or a tag bookmark that
	 * can be unwrapped into one. Other categories are left to JEI's default click handling.
	 */
	public static boolean isCraftable(RecipeBookmark<?, ?> bookmark) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return false;
		}
		if (isTagInfoRecipe(bookmark)) {
			bookmark = resolveCraftableBookmark(getOutput(bookmark));
		}
		return bookmark != null && canExecute(bookmark, player);
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
		if (current.execution != null) {
			// An execution is running. It manages its own container transitions (e.g. the
			// workstation action opens a furnace, changing the player's container), so the
			// container-stability check is intentionally skipped here.
			BookmarkActionResult result;
			try {
				result = Objects.requireNonNull(current.execution.tick(), "action result");
			} catch (RuntimeException exception) {
				Constants.LOG.error("[AutoCraft] session={} action threw", current.id, exception);
				fail("action threw " + exception.getClass().getSimpleName());
				return;
			}
			if (result == BookmarkActionResult.SUCCESS) {
				completeFrame();
			} else if (result == BookmarkActionResult.FAILURE) {
				fail("action failed");
			}
			return;
		}
		if (container != current.container) {
			fail("container changed from " + current.container.getClass().getSimpleName() + " to " + container.getClass().getSimpleName());
			return;
		}

		RecipeBookmark<?, ?> bookmark = current.frames.peek();
		if (bookmark == null) {
			finish();
			return;
		}
		log("session=%d STATE ANALYZE depth=%d containerState=%d", current.id, current.frames.size(), container.getStateId());
		IRecipeLayoutDrawable<?> layout = createLayout(bookmark);
		if (layout == null) {
			fail("recipe layout unavailable");
			return;
		}
		Map<ItemStack, Integer> missing = findMissing(layout, container);
		if (!missing.isEmpty()) {
			Map.Entry<ItemStack, Integer> entry = missing.entrySet().iterator().next();
			RecipeBookmark<?, ?> sub = findRunnableBookmark(entry.getKey(), player);
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
		if (++current.actions > MAX_ACTIONS) {
			fail("action limit exceeded=" + MAX_ACTIONS);
			return;
		}
		BookmarkActionContext context = createActionContext(current.id, current.frames.size(), bookmark, layout, player);
		BookmarkAction action = findAction(context);
		if (action == null) {
			message(player, "jeicrafter.message.not_crafting_recipe");
			fail("no action for recipe category=" + bookmark.getRecipeCategory().getRecipeType());
			return;
		}
		try {
			current.execution = Objects.requireNonNull(action.start(context), "action execution");
		} catch (RuntimeException exception) {
			Constants.LOG.error("[AutoCraft] session={} could not start action", current.id, exception);
			fail("action start threw " + exception.getClass().getSimpleName());
			return;
		}
		log("session=%d ACTION_START depth=%d action=%s", current.id, current.frames.size(), action.getClass().getName());
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
			ItemStack wanted = firstRunnableBookmarkedVariation(variations);
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

	/** Returns the first variation with a bookmark backed by a registered action; null if none. */
	private static ItemStack firstRunnableBookmarkedVariation(List<ItemStack> variations) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return null;
		}
		for (ItemStack variation : variations) {
			if (!variation.isEmpty() && findRunnableBookmark(variation, player) != null) {
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

	private static boolean canExecute(RecipeBookmark<?, ?> bookmark, LocalPlayer player) {
		IRecipeLayoutDrawable<?> layout = createLayout(bookmark);
		if (layout == null) {
			return false;
		}
		return findAction(createActionContext(0, 1, bookmark, layout, player)) != null;
	}

	private static BookmarkActionContext createActionContext(
		long sessionId,
		int depth,
		RecipeBookmark<?, ?> bookmark,
		IRecipeLayoutDrawable<?> layout,
		LocalPlayer player
	) {
		return new BookmarkActionContext(
			sessionId,
			depth,
			Objects.requireNonNull(runtime(), "JEI runtime"),
			player,
			player.containerMenu,
			bookmark.getRecipeCategory(),
			bookmark.getRecipe(),
			layout,
			getOutput(bookmark)
		);
	}

	private static BookmarkAction findAction(BookmarkActionContext context) {
		for (BookmarkAction action : ACTIONS) {
			if (supports(action, context)) {
				return action;
			}
		}
		if (supports(BUILTIN_CRAFTING_ACTION, context)) {
			return BUILTIN_CRAFTING_ACTION;
		}
		return supports(BUILTIN_WORKSTATION_ACTION, context) ? BUILTIN_WORKSTATION_ACTION : null;
	}

	private static boolean supports(BookmarkAction action, BookmarkActionContext context) {
		try {
			return action.supports(context);
		} catch (RuntimeException exception) {
			Constants.LOG.error("[AutoCraft] action {} threw from supports", action.getClass().getName(), exception);
			return false;
		}
	}

	/** A JEI tag-info bookmark is not itself a craftable recipe; it stands for a tag's representative item. */
	private static boolean isTagInfoRecipe(RecipeBookmark<?, ?> bookmark) {
		return bookmark.getRecipe() instanceof ITagInfoRecipe;
	}

	/**
	 * Resolves a concrete crafting {@link RecipeBookmark} whose output matches the given item,
	 * by querying JEI's crafting recipe lookup with an output focus. Used to "unwrap" a tag bookmark
	 * into the real crafting recipe for its representative item so the normal craft flow can run.
	 */
	private static RecipeBookmark<?, ?> resolveCraftableBookmark(ItemStack item) {
		IJeiRuntime jeiRuntime = runtime();
		if (jeiRuntime == null || item.isEmpty()) {
			return null;
		}
		IFocus<ItemStack> focus = jeiRuntime.getJeiHelpers().getFocusFactory()
			.createFocus(RecipeIngredientRole.OUTPUT, VanillaTypes.ITEM_STACK, item);
		IRecipeManager recipeManager = jeiRuntime.getRecipeManager();
		IRecipeCategory<CraftingRecipe> category = recipeManager.getRecipeCategory(RecipeTypes.CRAFTING);
		return recipeManager.createRecipeLookup(RecipeTypes.CRAFTING)
			.limitFocus(List.of(focus))
			.get()
			.map(recipe -> {
				Optional<IRecipeLayoutDrawable<CraftingRecipe>> layout = recipeManager.createRecipeLayoutDrawable(
					category, recipe, jeiRuntime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup());
				return layout.map(l -> RecipeBookmark.create(l, jeiRuntime.getIngredientManager())).orElse(null);
			})
			.filter(Objects::nonNull)
			.findFirst()
			.orElse(null);
	}

	/**
	 * Finds the recipe bookmark whose output matches the target item across all recipe bookmarks.
	 * Only recognizes RecipeBookmark (with full recipe data); plain item bookmarks (IngredientBookmark) do not count.
	 */
	private static RecipeBookmark<?, ?> findBookmark(ItemStack target) {
		return findMatchingBookmarks(target).stream().findFirst().orElse(null);
	}

	private static RecipeBookmark<?, ?> findRunnableBookmark(ItemStack target, LocalPlayer player) {
		for (RecipeBookmark<?, ?> bookmark : findMatchingBookmarks(target)) {
			RecipeBookmark<?, ?> candidate = bookmark;
			if (isTagInfoRecipe(candidate)) {
				candidate = resolveCraftableBookmark(target);
			}
			if (candidate != null && canExecute(candidate, player)) {
				return candidate;
			}
		}
		return null;
	}

	private static List<RecipeBookmark<?, ?>> findMatchingBookmarks(ItemStack target) {
		if (target.isEmpty()) {
			return List.of();
		}
		IJeiRuntime jeiRuntime = runtime();
		if (jeiRuntime == null) {
			return List.of();
		}
		List<RecipeBookmark<?, ?>> matches = new java.util.ArrayList<>();
		BookmarkList bookmarkList = ((MixinJeiBookmarkOverlay) jeiRuntime.getBookmarkOverlay()).jeicrafter$getBookmarkList();
		for (IElement<?> element : bookmarkList.getElements()) {
			Optional<IBookmark> bookmark = element.getBookmark();
			if (bookmark.isPresent() && bookmark.get() instanceof RecipeBookmark<?, ?> recipeBookmark) {
				Optional<ItemStack> output = recipeBookmark.getRecipeOutput().getIngredient(VanillaTypes.ITEM_STACK);
				IStackHelper stackHelper = stackHelper();
				if (output.isPresent() && stackHelper != null && stackHelper.isEquivalent(output.get(), target, UidContext.Ingredient)) {
					matches.add(recipeBookmark);
				}
			}
		}
		return matches;
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
		current.execution = null;
		if (current.frames.isEmpty()) {
			finish();
		}
	}

	private static void finish() {
		if (session != null) {
			log("session=%d FINISH actions=%d", session.id, session.actions);
		}
		session = null;
	}

	private static void fail(String reason) {
		if (session != null) {
			Constants.LOG.warn("[AutoCraft] session={} FAIL {}", session.id, reason);
			abortExecution(session);
		}
		session = null;
	}

	private static void abortExecution(Session current) {
		if (current.execution != null) {
			try {
				current.execution.cancel();
			} catch (RuntimeException exception) {
				Constants.LOG.error("[AutoCraft] session={} action threw while cancelling", current.id, exception);
			}
			current.execution = null;
		}
	}

	private static void log(String format, Object... args) {
		Constants.LOG.info("[AutoCraft] " + format, args);
	}

	private static String stackName(ItemStack stack) {
		return stack.isEmpty() ? "empty" : stack.getHoverName().getString() + " x" + stack.getCount();
	}

	private static final class Session {
		private final long id;
		private final Deque<RecipeBookmark<?, ?>> frames = new ArrayDeque<>();
		private int actions;
		private AbstractContainerMenu container;
		private BookmarkActionExecution execution;

		private Session(long id, RecipeBookmark<?, ?> root, AbstractContainerMenu container) {
			this.id = id;
			this.container = container;
			frames.push(root);
		}
	}

	private static final class CraftingBookmarkAction implements BookmarkAction {
		@Override
		public int priority() {
			return Integer.MIN_VALUE;
		}

		@Override
		public boolean supports(BookmarkActionContext context) {
			return RecipeTypes.CRAFTING.equals(context.recipeCategory().getRecipeType());
		}

		@Override
		public BookmarkActionExecution start(BookmarkActionContext context) {
			return new CraftingExecution(context);
		}
	}

	private static final class CraftingExecution implements BookmarkActionExecution {
		private final BookmarkActionContext context;
		private CraftingState state;
		private int stateId;
		private int waitTicks;
		private ItemStack resultItem = ItemStack.EMPTY;
		private int resultCountBefore;

		private CraftingExecution(BookmarkActionContext context) {
			this.context = context;
			AbstractContainerMenu container = context.container();
			if (!(container instanceof CraftingMenu) && !(container instanceof InventoryMenu)) {
				message(context.player(), "jeicrafter.message.need_crafting_table");
				state = CraftingState.FAILED;
				return;
			}
			IRecipeTransferManager transferManager = context.jeiRuntime().getRecipeTransferManager();
			boolean transferred = RecipeTransferUtil.transferRecipe(
				transferManager,
				container,
				context.recipeLayout(),
				context.player(),
				false
			);
			if (!transferred) {
				message(context.player(), "jeicrafter.message.transfer_failed");
				state = CraftingState.FAILED;
				return;
			}
			stateId = container.getStateId();
			state = CraftingState.WAIT_TRANSFER;
			log("session=%d STATE WAIT_TRANSFER frameDepth=%d stateId=%d", context.sessionId(), context.depth(), stateId);
		}

		@Override
		public BookmarkActionResult tick() {
			if (state == CraftingState.FAILED) {
				return BookmarkActionResult.FAILURE;
			}
			AbstractContainerMenu container = context.container();
			if (context.player().containerMenu != container) {
				return BookmarkActionResult.FAILURE;
			}
			if (waitTicks++ > WAIT_TIMEOUT_TICKS) {
				return BookmarkActionResult.FAILURE;
			}
			if (state == CraftingState.WAIT_TRANSFER) {
				if (container.getStateId() != stateId && container.getSlot(0).hasItem()) {
					log("session=%d STATE CLICK_RESULT transferStateId=%d output=%s", context.sessionId(), container.getStateId(), stackName(container.getSlot(0).getItem()));
					state = CraftingState.CLICK_RESULT;
					waitTicks = 0;
				}
				return BookmarkActionResult.RUNNING;
			}
			if (state == CraftingState.CLICK_RESULT) {
				resultItem = context.output();
				resultCountBefore = countInventory(resultItem);
				clickCraftResult(container, context.player());
				stateId = container.getStateId();
				state = CraftingState.WAIT_RESULT;
				waitTicks = 0;
				log("session=%d STATE WAIT_RESULT stateId=%d expected=%s before=%d", context.sessionId(), stateId, stackName(resultItem), resultCountBefore);
				return BookmarkActionResult.RUNNING;
			}
			if (container.getStateId() != stateId) {
				int resultCount = countInventory(resultItem);
				return resultCount > resultCountBefore ? BookmarkActionResult.SUCCESS : BookmarkActionResult.FAILURE;
			}
			return BookmarkActionResult.RUNNING;
		}
	}

	private enum CraftingState {
		WAIT_TRANSFER, CLICK_RESULT, WAIT_RESULT, FAILED
	}

	private static IJeiRuntime runtime() {
		return runtime;
	}
}
