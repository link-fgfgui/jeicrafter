package com.jeicrafter.client;

import com.jeicrafter.Constants;
import com.jeicrafter.api.BookmarkAction;
import com.jeicrafter.api.BookmarkActionContext;
import com.jeicrafter.api.BookmarkActionExecution;
import com.jeicrafter.api.BookmarkActionResult;
import com.jeicrafter.api.MaterialAnalyzer;
import com.jeicrafter.api.RecipeGraph;
import com.jeicrafter.api.RecipeGraphAccess;
import com.jeicrafter.api.RecipeGraphContext;
import com.jeicrafter.api.RecipeRequest;
import com.jeicrafter.api.RecipeStep;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.recipe.transfer.IRecipeTransferManager;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.common.transfer.RecipeTransferUtil;
import mezz.jei.gui.bookmarks.RecipeBookmark;
import mezz.jei.library.plugins.jei.tags.ITagInfoRecipe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Recipe-graph resolver and action runner.
 * <p>
 * A {@link RecipeGraph} supplies the recipe tree (the built-in graph walks JEI recipe bookmarks;
 * plugins may replace it). A {@link MaterialAnalyzer} decides whether a step's inputs are already
 * available. When they are, a {@link BookmarkAction} executes the step.
 * <p>
 * Transfer uses JEI's own recipe transfer mechanism (server-validated, multiplayer-safe);
 * completing a crafting-table recipe is done by shift-clicking the result slot.
 */
public final class AutoCraftManager {

	/** Max recursion depth to prevent infinite loops when recipes reference each other. */
	private static final int MAX_DEPTH = 16;
	/** Max actions per trigger to prevent runaway recipe chains. */
	private static final int MAX_ACTIONS = 128;
	private static final BookmarkAction BUILTIN_CRAFTING_ACTION = new CraftingBookmarkAction();
	private static final BookmarkAction BUILTIN_WORKSTATION_ACTION = new WorkstationBookmarkAction();
	private static final RecipeGraph BUILTIN_GRAPH = BuiltinRecipeGraph.INSTANCE;
	private static final MaterialAnalyzer BUILTIN_ANALYZER = BuiltinMaterialAnalyzer.INSTANCE;
	private static final List<BookmarkAction> ACTIONS = new CopyOnWriteArrayList<>();
	private static final List<RecipeGraph> GRAPHS = new CopyOnWriteArrayList<>();
	private static final List<MaterialAnalyzer> ANALYZERS = new CopyOnWriteArrayList<>();

	private static final long BOOKMARK_CACHE_TTL_MS = 500L;
	private static volatile long lastBookmarkCacheTime = 0;
	private static final Map<ItemStackKey, Boolean> BOOKMARK_CACHE = new ConcurrentHashMap<>();

	/** Runtime injected by the JEI plugin via {@code onRuntimeAvailable}. */
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
			BOOKMARK_CACHE.clear();
		}
	}

	/** Implementation backing {@link com.jeicrafter.api.JeiCrafterApi#unregisterAction(BookmarkAction)}. */
	public static boolean unregisterAction(BookmarkAction action) {
		boolean removed = ACTIONS.remove(action);
		if (removed) {
			BOOKMARK_CACHE.clear();
		}
		return removed;
	}

	/** Implementation backing {@link com.jeicrafter.api.JeiCrafterApi#registerRecipeGraph(RecipeGraph)}. */
	public static void registerRecipeGraph(RecipeGraph graph) {
		if (!GRAPHS.contains(graph)) {
			GRAPHS.add(graph);
			GRAPHS.sort(Comparator.comparingInt(RecipeGraph::priority).reversed());
			BOOKMARK_CACHE.clear();
		}
	}

	/** Implementation backing {@link com.jeicrafter.api.JeiCrafterApi#unregisterRecipeGraph(RecipeGraph)}. */
	public static boolean unregisterRecipeGraph(RecipeGraph graph) {
		boolean removed = GRAPHS.remove(graph);
		if (removed) {
			BOOKMARK_CACHE.clear();
		}
		return removed;
	}

	/** Implementation backing {@link com.jeicrafter.api.JeiCrafterApi#registerMaterialAnalyzer(MaterialAnalyzer)}. */
	public static void registerMaterialAnalyzer(MaterialAnalyzer analyzer) {
		if (!ANALYZERS.contains(analyzer)) {
			ANALYZERS.add(analyzer);
			ANALYZERS.sort(Comparator.comparingInt(MaterialAnalyzer::priority).reversed());
			BOOKMARK_CACHE.clear();
		}
	}

	/** Implementation backing {@link com.jeicrafter.api.JeiCrafterApi#unregisterMaterialAnalyzer(MaterialAnalyzer)}. */
	public static boolean unregisterMaterialAnalyzer(MaterialAnalyzer analyzer) {
		boolean removed = ANALYZERS.remove(analyzer);
		if (removed) {
			BOOKMARK_CACHE.clear();
		}
		return removed;
	}

	public static RecipeGraph builtinRecipeGraph() {
		return BUILTIN_GRAPH;
	}

	public static MaterialAnalyzer builtinMaterialAnalyzer() {
		return BUILTIN_ANALYZER;
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

	/**
	 * Entry point when only the item is known: the highest-priority graph that supports the
	 * request supplies the root step and is bound for the rest of the session.
	 *
	 * @return true if a graph resolved a runnable recipe and crafting started (or another
	 *         request is already running); false if no graph has a recipe for the target
	 */
	public static boolean tryCraft(ItemStack target) {
		if (session != null) {
			log("session=%d ignored new request while busy", session.id);
			return true;
		}
		return tryCraft(RecipeRequest.forItem(target));
	}

	/**
	 * Entry point when the exact bookmark is known (bookmark bar click).
	 * <p>
	 * Takes the bookmark itself rather than its output item so several bookmarks sharing the
	 * same output still run the clicked recipe.
	 */
	public static boolean tryCraft(RecipeBookmark<?, ?> bookmark) {
		if (session != null) {
			log("session=%d ignored new request while busy", session.id);
			return true;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || runtime() == null) {
			return false;
		}
		ItemStack target = BuiltinRecipeGraph.outputOf(bookmark, runtime());
		if (bookmark.getRecipe() instanceof ITagInfoRecipe) {
			ResolvedRoot resolved = resolveRoot(RecipeRequest.forItem(target));
			if (resolved == null) {
				message(minecraft.player, "jeicrafter.message.no_crafting_recipe", target.getHoverName());
				fail("tag bookmarked item has no crafting recipe: " + stackName(target));
				return true;
			}
			return startSession(resolved);
		}
		return tryCraft(RecipeRequest.forRecipe(target, bookmark.getRecipeCategory(), bookmark.getRecipe()));
	}

	/** Starts a request through the registered recipe graphs. */
	public static boolean tryCraft(RecipeRequest request) {
		if (session != null) {
			log("session=%d ignored new request while busy", session.id);
			return true;
		}
		ResolvedRoot resolved = resolveRoot(request);
		if (resolved == null) {
			return false;
		}
		return startSession(resolved);
	}

	/**
	 * Starts a request from a pre-resolved root step, binding {@code graph} for child lookups.
	 * Plugins that own a full recipe tree should pass the same graph instance they registered.
	 */
	public static boolean tryCraft(RecipeStep root, RecipeGraph graph) {
		if (session != null) {
			log("session=%d ignored new request while busy", session.id);
			return true;
		}
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || runtime() == null) {
			return false;
		}
		if (!hasAction(root, player)) {
			return false;
		}
		return startSession(new ResolvedRoot(Objects.requireNonNull(graph, "graph"), root));
	}

	/** Called from the client tick so network/container synchronization can complete between actions. */
	public static void tick() {
		if (session != null) {
			advance();
		}
	}

	/**
	 * Whether any registered recipe graph can produce the target (used to colour missing JEI
	 * transfer slots that a graph can craft). Results are cached for 500ms to eliminate per-frame
	 * recipe resolution overhead during tooltip rendering.
	 */
	public static boolean isBookmarked(ItemStack target) {
		if (target.isEmpty()) {
			return false;
		}
		long now = System.currentTimeMillis();
		if (now - lastBookmarkCacheTime > BOOKMARK_CACHE_TTL_MS) {
			BOOKMARK_CACHE.clear();
			lastBookmarkCacheTime = now;
		}
		ItemStackKey key = new ItemStackKey(target);
		Boolean cached = BOOKMARK_CACHE.get(key);
		if (cached != null) {
			return cached;
		}
		boolean result = resolveRoot(RecipeRequest.forItem(target)) != null;
		BOOKMARK_CACHE.put(key, result);
		return result;
	}

	public static boolean isRunnable(ItemStack target) {
		return isBookmarked(target);
	}

	/** Whether a registered recipe graph can resolve {@code request} to a runnable step. */
	public static boolean isRunnable(RecipeRequest request) {
		return resolveRoot(request) != null;
	}

	/**
	 * Whether this bookmark is something auto-craft can execute through a registered graph.
	 */
	public static boolean isCraftable(RecipeBookmark<?, ?> bookmark) {
		IJeiRuntime jeiRuntime = runtime();
		LocalPlayer player = Minecraft.getInstance().player;
		if (jeiRuntime == null || player == null) {
			return false;
		}
		ItemStack target = BuiltinRecipeGraph.outputOf(bookmark, jeiRuntime);
		RecipeRequest request = bookmark.getRecipe() instanceof ITagInfoRecipe
			? RecipeRequest.forItem(target)
			: RecipeRequest.forRecipe(target, bookmark.getRecipeCategory(), bookmark.getRecipe());
		return resolveRoot(request) != null;
	}

	static boolean hasAction(RecipeStep step, LocalPlayer player) {
		IJeiRuntime jeiRuntime = runtime();
		if (jeiRuntime == null || player == null) {
			return false;
		}
		return findAction(createActionContext(0, 1, step, player)) != null;
	}

	/**
	 * State machine advances at most one step per tick. After recipe transfer and result extraction,
	 * must wait for container sync; after a child step completes, re-analyze the parent against the
	 * actual inventory.
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

		RecipeStep step = current.frames.peek();
		if (step == null) {
			finish();
			return;
		}
		log("session=%d STATE ANALYZE depth=%d containerState=%d", current.id, current.frames.size(), container.getStateId());
		IJeiRuntime jeiRuntime = runtime();
		if (jeiRuntime == null) {
			fail("JEI runtime unavailable");
			return;
		}
		BookmarkActionContext context = createActionContext(current.id, current.frames.size(), step, player);
		MaterialAnalyzer analyzer = findAnalyzer(context);
		Map<ItemStack, Integer> missing;
		try {
			missing = Objects.requireNonNull(analyzer.findMissing(context, current.graphAccess), "missing materials");
		} catch (RuntimeException exception) {
			Constants.LOG.error("[AutoCraft] session={} analyzer threw", current.id, exception);
			fail("analyzer threw " + exception.getClass().getSimpleName());
			return;
		}
		if (!missing.isEmpty()) {
			Map.Entry<ItemStack, Integer> entry = missing.entrySet().iterator().next();
			Optional<RecipeStep> child = current.graphAccess.resolve(entry.getKey());
			if (child.isEmpty()) {
				ItemStack requester = step.output();
				Component requesterName = requester.isEmpty() ? entry.getKey().getHoverName() : requester.getHoverName();
				message(player, "jeicrafter.message.missing_material", requesterName, entry.getKey().getHoverName());
				fail("missing uncraftable material=" + stackName(entry.getKey()));
				return;
			}
			if (current.frames.size() >= MAX_DEPTH || containsIdentity(current.frames, child.get())) {
				fail("craft limit/cycle depth=" + current.frames.size());
				return;
			}
			current.frames.push(child.get());
			log("session=%d PUSH depth=%d material=%s missing=%d", current.id, current.frames.size(), stackName(entry.getKey()), entry.getValue());
			return;
		}
		if (++current.actions > MAX_ACTIONS) {
			fail("action limit exceeded=" + MAX_ACTIONS);
			return;
		}
		BookmarkAction action = findAction(context);
		if (action == null) {
			message(player, "jeicrafter.message.not_crafting_recipe");
			fail("no action for recipe category=" + step.recipeCategory().getRecipeType());
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

	private static boolean startSession(ResolvedRoot resolved) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return false;
		}
		session = new Session(++nextSessionId, resolved.graph, resolved.step, minecraft.player.containerMenu);
		log("session=%d START target=%s graph=%s", session.id, stackName(resolved.step.output()), resolved.graph.getClass().getName());
		advance();
		return true;
	}

	private static ResolvedRoot resolveRoot(RecipeRequest request) {
		RecipeGraphContext context = graphContext();
		if (context == null) {
			return null;
		}
		for (RecipeGraph graph : GRAPHS) {
			if (supports(graph, request, context)) {
				// First supporting graph owns the request: empty resolve does not fall through.
				return resolveOwned(graph, request, context);
			}
		}
		return resolveOwned(BUILTIN_GRAPH, request, context);
	}

	private static ResolvedRoot resolveOwned(RecipeGraph graph, RecipeRequest request, RecipeGraphContext context) {
		Optional<RecipeStep> step;
		try {
			step = Objects.requireNonNull(graph.resolve(request, context), "graph resolve");
		} catch (RuntimeException exception) {
			Constants.LOG.error("[AutoCraft] graph {} threw from resolve", graph.getClass().getName(), exception);
			return null;
		}
		if (step.isEmpty()) {
			return null;
		}
		LocalPlayer player = context.player().orElse(null);
		if (!hasAction(step.get(), player)) {
			return null;
		}
		return new ResolvedRoot(graph, step.get());
	}

	private static RecipeGraphContext graphContext() {
		IJeiRuntime jeiRuntime = runtime();
		if (jeiRuntime == null) {
			return null;
		}
		LocalPlayer player = Minecraft.getInstance().player;
		AbstractContainerMenu container = player == null ? null : player.containerMenu;
		return new RecipeGraphContext(jeiRuntime, player, container);
	}

	private static BookmarkActionContext createActionContext(long sessionId, int depth, RecipeStep step, LocalPlayer player) {
		return new BookmarkActionContext(
			sessionId,
			depth,
			Objects.requireNonNull(runtime(), "JEI runtime"),
			player,
			player.containerMenu,
			step
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

	private static MaterialAnalyzer findAnalyzer(BookmarkActionContext context) {
		for (MaterialAnalyzer analyzer : ANALYZERS) {
			if (supports(analyzer, context)) {
				return analyzer;
			}
		}
		return BUILTIN_ANALYZER;
	}

	private static boolean supports(BookmarkAction action, BookmarkActionContext context) {
		try {
			return action.supports(context);
		} catch (RuntimeException exception) {
			Constants.LOG.error("[AutoCraft] action {} threw from supports", action.getClass().getName(), exception);
			return false;
		}
	}

	private static boolean supports(RecipeGraph graph, RecipeRequest request, RecipeGraphContext context) {
		try {
			return graph.supports(request, context);
		} catch (RuntimeException exception) {
			Constants.LOG.error("[AutoCraft] graph {} threw from supports", graph.getClass().getName(), exception);
			return false;
		}
	}

	private static boolean supports(MaterialAnalyzer analyzer, BookmarkActionContext context) {
		try {
			return analyzer.supports(context);
		} catch (RuntimeException exception) {
			Constants.LOG.error("[AutoCraft] analyzer {} threw from supports", analyzer.getClass().getName(), exception);
			return false;
		}
	}

	private static boolean containsIdentity(Deque<RecipeStep> frames, RecipeStep candidate) {
		Object identity = candidate.identity();
		for (RecipeStep frame : frames) {
			if (Objects.equals(frame.identity(), identity)) {
				return true;
			}
		}
		return false;
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

	/**
	 * Marks the current session so that when the running action frame completes, the whole
	 * dependency chain ends instead of continuing into parent frames. Intended for actions that
	 * hand control back to the player (e.g. opening a workstation GUI); unlike {@link #cancel()}
	 * it does not close any GUI the action opened.
	 */
	public static void stopWhenCurrentFrameCompletes() {
		if (session != null) {
			session.stopOnComplete = true;
		}
	}

	private static void completeFrame() {
		Session current = session;
		RecipeStep completed = current.frames.pop();
		log("session=%d CRAFT_COMPLETE depth=%d output=%s", current.id, current.frames.size(), stackName(completed.output()));
		current.execution = null;
		if (current.frames.isEmpty() || current.stopOnComplete) {
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
		Constants.LOG.info("[AutoCraft] " + String.format(format, args));
	}

	private static String stackName(ItemStack stack) {
		return stack.isEmpty() ? "empty" : stack.getHoverName().getString() + " x" + stack.getCount();
	}

	public static IJeiRuntime runtime() {
		return runtime;
	}

	private static final class Session {
		private final long id;
		private final RecipeGraph graph;
		private final RecipeGraphAccess graphAccess;
		private final Deque<RecipeStep> frames = new ArrayDeque<>();
		private int actions;
		private AbstractContainerMenu container;
		private BookmarkActionExecution execution;
		/** When set, the session ends once the current frame completes (see {@link #stopWhenCurrentFrameCompletes()}). */
		private boolean stopOnComplete;

		private Session(long id, RecipeGraph graph, RecipeStep root, AbstractContainerMenu container) {
			this.id = id;
			this.graph = graph;
			this.graphAccess = new SessionGraphAccess(this);
			this.container = container;
			frames.push(root);
		}
	}

	private static final class SessionGraphAccess implements RecipeGraphAccess {
		private final Session session;

		private SessionGraphAccess(Session session) {
			this.session = session;
		}

		@Override
		public boolean canProduce(ItemStack stack) {
			return resolve(stack).isPresent();
		}

		@Override
		public Optional<RecipeStep> resolve(ItemStack stack) {
			RecipeGraphContext context = graphContext();
			if (context == null) {
				return Optional.empty();
			}
			RecipeStep parent = session.frames.peek();
			RecipeRequest request = parent == null
				? RecipeRequest.forItem(stack)
				: RecipeRequest.forDependency(stack, parent);
			try {
				Optional<RecipeStep> step = Objects.requireNonNull(session.graph.resolve(request, context), "graph resolve");
				if (step.isEmpty()) {
					return Optional.empty();
				}
				LocalPlayer player = context.player().orElse(null);
				return hasAction(step.get(), player) ? step : Optional.empty();
			} catch (RuntimeException exception) {
				Constants.LOG.error("[AutoCraft] session={} graph threw from child resolve", session.id, exception);
				return Optional.empty();
			}
		}
	}

	private record ItemStackKey(Item item, CompoundTag tag) {
		private ItemStackKey(ItemStack stack) {
			this(stack.getItem(), stack.hasTag() ? stack.getTag() : null);
		}
	}

	private record ResolvedRoot(RecipeGraph graph, RecipeStep step) {
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
				// The transfer is already in place (grid pre-arranged, or a no-op transfer) the
				// server may send no state change at all. Fall back after a short grace period so
				// the ready result is not skipped — mirrors the workstation action.
				boolean synced = container.getStateId() != stateId;
				if ((synced || waitTicks > 10) && container.getSlot(0).hasItem()) {
					log("session=%d STATE CLICK_RESULT transferStateId=%d output=%s", context.sessionId(), container.getStateId(), stackName(container.getSlot(0).getItem()));
					state = CraftingState.CLICK_RESULT;
					waitTicks = 0;
				}
				return BookmarkActionResult.RUNNING;
			}
			if (state == CraftingState.CLICK_RESULT) {
				resultItem = context.output();
				resultCountBefore = BuiltinMaterialAnalyzer.countInventory(
					resultItem,
					context.player(),
					context.jeiRuntime().getJeiHelpers().getStackHelper()
				);
				clickCraftResult(container, context.player());
				stateId = container.getStateId();
				state = CraftingState.WAIT_RESULT;
				waitTicks = 0;
				log("session=%d STATE WAIT_RESULT stateId=%d expected=%s before=%d", context.sessionId(), stateId, stackName(resultItem), resultCountBefore);
				return BookmarkActionResult.RUNNING;
			}
			if (container.getStateId() != stateId) {
				int resultCount = BuiltinMaterialAnalyzer.countInventory(
					resultItem,
					context.player(),
					context.jeiRuntime().getJeiHelpers().getStackHelper()
				);
				return resultCount > resultCountBefore ? BookmarkActionResult.SUCCESS : BookmarkActionResult.FAILURE;
			}
			return BookmarkActionResult.RUNNING;
		}
	}

	private enum CraftingState {
		WAIT_TRANSFER, CLICK_RESULT, WAIT_RESULT, FAILED
	}
}
