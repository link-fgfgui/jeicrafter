package com.jeicrafter.client;

import com.jeicrafter.Constants;
import com.jeicrafter.api.BookmarkAction;
import com.jeicrafter.api.BookmarkActionContext;
import com.jeicrafter.api.BookmarkActionExecution;
import com.jeicrafter.api.BookmarkActionResult;
import com.jeicrafter.config.JeiCrafterConfig;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.helpers.IStackHelper;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.transfer.IRecipeTransferManager;
import mezz.jei.common.transfer.RecipeTransferUtil;
import mezz.jei.library.plugins.jei.tags.ITagInfoRecipe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Executes a non-crafting, non-tag recipe against a workstation (a JEI recipe catalyst block).
 * <p>
 * Registered as a built-in {@link BookmarkAction} with the same priority as the crafting action;
 * it is only selected when the crafting action does not support the recipe (i.e. any non-crafting
 * category with a workstation).
 * <p>
 * Two paths, both ending in the same "insert-and-stop" transfer step:
 * <ul>
 *   <li>When the player's currently open container already belongs to one of the recipe's catalyst
 *   blocks ("对应方块已打开"), or when a valid JEI recipe transfer handler exists for the open
 *   container and category ("有有效的handle"), the action starts directly at the transfer step.</li>
 *   <li>Otherwise, when {@code autoOpenWorkstation} is enabled, the nearest matching workstation
 *   block in the loaded world is opened first, and the action then continues into the same transfer
 *   step.</li>
 * </ul>
 * The transfer step inserts the recipe's required inputs into the open workstation — through JEI's
 * standard recipe transfer when a handler is registered, otherwise by split-clicking exactly the
 * required amounts in as a fallback — and then stops. Inserting is the design intent for
 * non-crafting recipes ("插入就停"): the mod never waits for the machine to produce and never
 * extracts the output. Whether the insert actually happens is controlled by the
 * {@code autoTransferItems} config option (when disabled the action only opens/acknowledges the
 * workstation and stops without inserting anything).
 * <p>
 * The path is terminal: it sets {@link AutoCraftManager#stopWhenCurrentFrameCompletes()} so the
 * whole auto-craft session ends when this frame completes, handing control back to the player.
 * The action re-anchors the session's expected container whenever it changes the player's
 * container, so the enclosing dependency chain can continue analysing the player inventory.
 */
public final class WorkstationBookmarkAction implements BookmarkAction {

	@Override
	public int priority() {
		return Integer.MIN_VALUE;
	}

	@Override
	public boolean supports(BookmarkActionContext context) {
		IRecipeCategory<?> category = context.recipeCategory();
		if (RecipeTypes.CRAFTING.equals(category.getRecipeType())) {
			return false; // handled by the built-in crafting action
		}
		if (context.recipe() instanceof ITagInfoRecipe) {
			return false; // tag bookmarks are unwrapped by the resolver
		}
		// The matching workstation is already open: its container belongs to one of the recipe's
		// catalyst blocks, so run like the workbench regardless of the auto-open config.
		if (isWorkstationOpen(context)) {
			return true;
		}
		// The open container accepts this category through a registered JEI transfer handler:
		// treat it like an open workstation and run like the workbench.
		if (hasValidTransferHandler(context)) {
			return true;
		}
		if (!JeiCrafterConfig.autoOpenWorkstation()) {
			return false;
		}
		return !findWorkstationItems(context).isEmpty();
	}

	@Override
	public BookmarkActionExecution start(BookmarkActionContext context) {
		return new WorkstationExecution(context);
	}

	/** Returns all recipe catalyst (workstation) items for the context's recipe type, in registration order. */
	static List<ItemStack> findWorkstationItems(BookmarkActionContext context) {
		return context.jeiRuntime().getRecipeManager()
			.createRecipeCatalystLookup(context.recipeCategory().getRecipeType())
			.getItemStack()
			.filter(stack -> !stack.isEmpty())
			.toList();
	}

	/** The first non-empty catalyst item, or {@link ItemStack#EMPTY} (used for messages). */
	private static ItemStack representativeItem(List<ItemStack> items) {
		for (ItemStack item : items) {
			if (!item.isEmpty()) {
				return item;
			}
		}
		return ItemStack.EMPTY;
	}

	/**
	 * Whether the player's currently open container belongs to one of the recipe's catalyst
	 * blocks. A block container's non-player slots are backed by that block's own block entity
	 * (e.g. a {@code FurnaceMenu} is backed by the {@code AbstractFurnaceBlockEntity} at the
	 * furnace), so matching the backing block entity's block against the catalyst items is exact
	 * and needs no menu-type registry mapping. Works for vanilla and block-entity backed modded
	 * machines alike; the player-inventory region of the menu is ignored automatically.
	 */
	static boolean isWorkstationOpen(BookmarkActionContext context) {
		List<ItemStack> catalysts = findWorkstationItems(context);
		if (catalysts.isEmpty()) {
			return false;
		}
		for (Slot slot : context.container().slots) {
			if (slot.container instanceof BlockEntity blockEntity) {
				Item blockItem = blockEntity.getBlockState().getBlock().asItem();
				for (ItemStack catalyst : catalysts) {
					if (catalyst.is(blockItem)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** Whether JEI has a registered recipe transfer handler for the open container and recipe category. */
	static boolean hasValidTransferHandler(BookmarkActionContext context) {
		@SuppressWarnings({"rawtypes", "unchecked"})
		Optional<?> handler = context.jeiRuntime().getRecipeTransferManager()
			.getRecipeTransferHandler(context.container(), (IRecipeCategory) context.recipeCategory());
		return handler.isPresent();
	}

	private static final class WorkstationExecution implements BookmarkActionExecution {
		private static final int OPEN_TIMEOUT_TICKS = 60;
		private static final int TRANSFER_TIMEOUT_TICKS = 100;

		private final BookmarkActionContext context;
		private final AbstractContainerMenu originalContainer;
		private final List<ItemStack> workstationItems;
		private final ItemStack workstationItem;
		private WorkstationState state;
		private int waitTicks;
		private int stateId;
		private BlockPos targetPos;
		private int oldSelectedSlot = -1;

		private WorkstationExecution(BookmarkActionContext context) {
			this.context = context;
			this.originalContainer = context.container();
			this.workstationItems = findWorkstationItems(context);
			this.workstationItem = representativeItem(workstationItems);
			// If the matching workstation is already open (its container belongs to a catalyst
			// block) or the open container has a valid transfer handler for this category, act
			// like the workbench: transfer straight into the current container, no block search.
			this.state = (isWorkstationOpen(context) || hasValidTransferHandler(context))
				? WorkstationState.TRANSFER
				: WorkstationState.OPEN;
			log("execution start state=%s catalysts=%d", state, workstationItems.size());
		}

		@Override
		public BookmarkActionResult tick() {
			LocalPlayer player = context.player();
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft.gameMode == null) {
				return terminal(BookmarkActionResult.FAILURE);
			}
			if (waitTicks++ > currentTimeout()) {
				return terminal(BookmarkActionResult.FAILURE);
			}

			switch (state) {
				case OPEN -> {
					targetPos = findNearestWorkstation();
					if (targetPos == null) {
						message(player, "jeicrafter.message.no_workstation", workstationName(), distanceText(JeiCrafterConfig.workstationMaxDistance()));
						log("no workstation for %s within range %.1f", workstationName(), JeiCrafterConfig.workstationMaxDistance());
						return terminal(BookmarkActionResult.FAILURE);
					}
					message(player, "jeicrafter.message.opening_workstation", workstationName());
					// Swap to an empty hotbar slot so a block item in hand cannot be placed
					// instead of opening the workstation GUI, then restore the selection.
					oldSelectedSlot = player.getInventory().selected;
					selectEmptyHotbarSlot(player);
					BlockHitResult hit = new BlockHitResult(
						Vec3.atCenterOf(targetPos),
						Direction.UP,
						targetPos,
						false
					);
					InteractionResult result = minecraft.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
					log("OPEN result=%s target=%s", result, targetPos);
					state = WorkstationState.WAIT_OPEN;
					waitTicks = 0;
					return BookmarkActionResult.RUNNING;
				}
				case WAIT_OPEN -> {
					AbstractContainerMenu current = player.containerMenu;
					if (current != originalContainer) {
						restoreSelectedSlot(player);
						// The workstation GUI is open: continue into the same transfer step the
						// already-open branch uses, so whether to fill (autoTransferItems) is
						// decided in exactly one place.
						log("OPENED container=%s, continuing to transfer", current.getClass().getSimpleName());
						state = WorkstationState.TRANSFER;
						waitTicks = 0;
						return BookmarkActionResult.RUNNING;
					}
					return BookmarkActionResult.RUNNING;
				}
				case TRANSFER -> {
					// The config decides whether the workstation is auto-filled: when disabled,
					// just leave it open (or already open) and hand control back — insert-and-
					// stop without the insert.
					if (!JeiCrafterConfig.autoTransferItems()) {
						log("TRANSFER skipped by config, leaving workstation open");
						AutoCraftManager.stopWhenCurrentFrameCompletes();
						return BookmarkActionResult.SUCCESS;
					}
					AbstractContainerMenu current = player.containerMenu;
					IRecipeTransferManager transferManager = context.jeiRuntime().getRecipeTransferManager();
					@SuppressWarnings({"rawtypes", "unchecked"})
					Optional<?> transferHandler = transferManager.getRecipeTransferHandler(current, (IRecipeCategory) context.recipeCategory());
					if (transferHandler.isEmpty()) {
						// No JEI recipe transfer handler is registered for this category
						// (typical for third-party machines): fall back to split-clicking
						// exactly the required input counts into the workstation.
						log("no JEI transfer handler for category=%s, using split-click transfer", context.recipeCategory().getRecipeType());
						if (!transferBySplitClick(current, player)) {
							message(player, "jeicrafter.message.transfer_failed");
							log("split-click transfer failed");
							return terminal(BookmarkActionResult.FAILURE);
						}
					} else {
						boolean transferred = RecipeTransferUtil.transferRecipe(
							transferManager,
							current,
							context.recipeLayout(),
							player,
							false
						);
						if (!transferred) {
							message(player, "jeicrafter.message.transfer_failed");
							log("transfer failed");
							return terminal(BookmarkActionResult.FAILURE);
						}
					}
					stateId = current.getStateId();
					state = WorkstationState.WAIT_TRANSFER;
					waitTicks = 0;
					return BookmarkActionResult.RUNNING;
				}
				case WAIT_TRANSFER -> {
					AbstractContainerMenu current = player.containerMenu;
					// Wait for the server to apply the transfer (stateId changed). As a
					// fallback, if the transfer was a no-op (inputs already in place) the stateId
					// never changes, so proceed after a short grace window.
					// Insert-and-stop: never wait for the machine to produce and never extract.
					boolean synced = current.getStateId() != stateId;
					if (synced || waitTicks > 10) {
						log("TRANSFER_DONE synced=%s stateId=%d - inputs inserted, handing off to player", synced, current.getStateId());
						AutoCraftManager.stopWhenCurrentFrameCompletes();
						return BookmarkActionResult.SUCCESS;
					}
					return BookmarkActionResult.RUNNING;
				}
				default -> {
					return terminal(BookmarkActionResult.FAILURE);
				}
			}
		}

		@Override
		public void cancel() {
			// Best-effort restore: return to the player inventory if a workstation is open.
			LocalPlayer player = context.player();
			if (player != null) {
				restoreSelectedSlot(player);
				if (player.containerMenu != originalContainer) {
					player.closeContainer();
				}
			}
			AutoCraftManager.reanchorSession();
		}

		/** Returns a terminal result and re-anchors the session's container for the enclosing chain. */
		private BookmarkActionResult terminal(BookmarkActionResult result) {
			LocalPlayer player = context.player();
			if (player != null) {
				restoreSelectedSlot(player);
			}
			AutoCraftManager.reanchorSession();
			return result;
		}

		private int currentTimeout() {
			return switch (state) {
				case WAIT_OPEN -> OPEN_TIMEOUT_TICKS;
				default -> TRANSFER_TIMEOUT_TICKS;
			};
		}

		/** Nearest matching workstation block across every catalyst item, within the config distance. */
		private BlockPos findNearestWorkstation() {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft.player == null) {
				return null;
			}
			Vec3 eye = minecraft.player.getEyePosition();
			BlockPos nearest = null;
			double bestDistanceSquared = Double.MAX_VALUE;
			for (ItemStack catalyst : workstationItems) {
				BlockPos pos = WorkstationScanner.findNearest(catalyst, JeiCrafterConfig.workstationMaxDistance());
				if (pos == null) {
					continue;
				}
				double distanceSquared = eye.distanceToSqr(Vec3.atCenterOf(pos));
				if (distanceSquared < bestDistanceSquared) {
					bestDistanceSquared = distanceSquared;
					nearest = pos;
				}
			}
			return nearest;
		}

		/**
		 * Generic fallback transfer for recipe categories without a registered
		 * {@link IRecipeTransferHandler} (common for third-party machines). For each recipe
		 * input, split-clicks (PICKUP) exactly the required item count from the player inventory
		 * into the workstation's empty input slot. The action stops after the fill is applied
		 * (insert-and-stop; the caller never waits for the machine nor extracts). Fails when a
		 * required material is unavailable, when a target machine slot is occupied, or when the
		 * workstation has no empty input slot left.
		 */
		private boolean transferBySplitClick(AbstractContainerMenu container, LocalPlayer player) {
			List<RecipeInput> required = requiredRecipeInputs();
			if (required.isEmpty()) {
				log("split-click transfer: recipe has no item inputs");
				return true;
			}
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft.gameMode == null) {
				return false;
			}
			IStackHelper stackHelper = context.jeiRuntime().getJeiHelpers().getStackHelper();
			List<Slot> machineSlots = machineSlots(container);
			for (int i = 0; i < required.size(); i++) {
				RecipeInput input = required.get(i);
				int needed = input.count();
				Slot source = findSourceSlot(container, stackHelper, input.item(), needed);
				if (source == null) {
					log("split-click transfer missing material=%s x%d", stackName(input.item()), needed);
					return false;
				}
				Slot target = emptyMachineSlot(machineSlots, i);
				if (target == null) {
					log("split-click transfer no empty machine slot for input %d", i);
					return false;
				}
				int sourceId = source.index;
				int targetId = target.index;
				int stackCount = source.getItem().getCount();
				// Take the whole stack onto the cursor.
				click(minecraft, container, sourceId, 0, player);
				if (stackCount == needed) {
					// Drop the whole stack into the machine slot.
					click(minecraft, container, targetId, 0, player);
				} else {
					// Place exactly `needed` items, one per right-click, then return the
					// remainder to the (now empty) source slot.
					for (int k = 0; k < needed; k++) {
						click(minecraft, container, targetId, 1, player);
					}
					click(minecraft, container, sourceId, 0, player);
				}
			}
			log("split-click transfer complete inputs=%d", required.size());
			return true;
		}

		/** Finds a player-inventory slot holding at least {@code needed} items matching {@code wanted}. */
		private static Slot findSourceSlot(AbstractContainerMenu container, IStackHelper stackHelper, ItemStack wanted, int needed) {
			for (Slot slot : container.slots) {
				if (!(slot.container instanceof Inventory)) {
					continue; // only the player-inventory region can supply items
				}
				ItemStack stack = slot.getItem();
				if (!stack.isEmpty() && stack.getCount() >= needed
					&& stackHelper.isEquivalent(stack, wanted, UidContext.Ingredient)) {
					return slot;
				}
			}
			return null;
		}

		/** All non-player slots of the open container, in order (a heuristic for the machine's own slots). */
		private static List<Slot> machineSlots(AbstractContainerMenu container) {
			List<Slot> slots = new ArrayList<>();
			for (Slot slot : container.slots) {
				if (!(slot.container instanceof Inventory)) {
					slots.add(slot);
				}
			}
			return slots;
		}

		/** The {@code index}-th machine slot if it exists and is empty; null otherwise (no empty slot). */
		private static Slot emptyMachineSlot(List<Slot> machineSlots, int index) {
			if (index >= machineSlots.size()) {
				return null;
			}
			Slot slot = machineSlots.get(index);
			return slot.getItem().isEmpty() ? slot : null;
		}

		/** Sends one PICKUP click packet ({@code button} 0 = left, 1 = right). */
		private static void click(Minecraft minecraft, AbstractContainerMenu container, int slotId, int button, LocalPlayer player) {
			if (minecraft.gameMode != null) {
				minecraft.gameMode.handleInventoryMouseClick(
					container.containerId, slotId, button, ClickType.PICKUP, player
				);
			}
		}

		/** Collects the recipe's item inputs in order, with the exact count each input slot requires. */
		private List<RecipeInput> requiredRecipeInputs() {
			List<RecipeInput> required = new ArrayList<>();
			IStackHelper stackHelper = context.jeiRuntime().getJeiHelpers().getStackHelper();
			for (IRecipeSlotView slot : context.recipeLayout().getRecipeSlotsView().getSlotViews(RecipeIngredientRole.INPUT)) {
				ItemStack wanted = pickPreferredVariation(slot, stackHelper);
				if (wanted == null || wanted.isEmpty() || wanted.getCount() <= 0) {
					continue;
				}
				int count = wanted.getCount();
				ItemStack key = wanted.copy();
				key.setCount(1);
				required.add(new RecipeInput(key, count));
			}
			return required;
		}

		/**
		 * For an OR-type input slot (e.g. "any dye"), prefers a variation the player actually
		 * has so the split-click can move it; falls back to the first non-empty variation.
		 */
		private ItemStack pickPreferredVariation(IRecipeSlotView slot, IStackHelper stackHelper) {
			List<ItemStack> variations = slot.getIngredients(VanillaTypes.ITEM_STACK).toList();
			LocalPlayer player = context.player();
			for (ItemStack variation : variations) {
				if (variation.isEmpty()) {
					continue;
				}
				for (ItemStack stack : player.getInventory().items) {
					if (!stack.isEmpty() && stackHelper.isEquivalent(stack, variation, UidContext.Ingredient)) {
						return variation;
					}
				}
			}
			for (ItemStack variation : variations) {
				if (!variation.isEmpty()) {
					return variation;
				}
			}
			return null;
		}

		private static String stackName(ItemStack stack) {
			return stack.isEmpty() ? "empty" : stack.getHoverName().getString() + " x" + stack.getCount();
		}

		private String workstationName() {
			return workstationItem.isEmpty() ? "?" : workstationItem.getHoverName().getString();
		}

		/** Formats a (possibly fractional) distance without a trailing ".0" for whole numbers. */
		private static String distanceText(double distance) {
			return distance == Math.floor(distance) ? String.valueOf((long) distance) : String.valueOf(distance);
		}

		/** One recipe input: the item to place and how many of it that input slot needs. */
		private record RecipeInput(ItemStack item, int count) {
		}

		/** Switches the selected hotbar slot to an empty one or non-block slot (so block items aren't placed). */
		private static void selectEmptyHotbarSlot(LocalPlayer player) {
			Inventory inv = player.getInventory();
			for (int i = 0; i < 9; i++) {
				if (inv.getItem(i).isEmpty()) {
					inv.selected = i;
					player.connection.send(new ServerboundSetCarriedItemPacket(i));
					return;
				}
			}
			// Fallback: if no hotbar slot is completely empty, prefer a non-BlockItem slot
			for (int i = 0; i < 9; i++) {
				if (!(inv.getItem(i).getItem() instanceof net.minecraft.world.item.BlockItem)) {
					inv.selected = i;
					player.connection.send(new ServerboundSetCarriedItemPacket(i));
					return;
				}
			}
		}

		/** Restores the hotbar slot selected before the workstation was opened. */
		private void restoreSelectedSlot(LocalPlayer player) {
			if (oldSelectedSlot >= 0 && player.getInventory().selected != oldSelectedSlot) {
				player.getInventory().selected = oldSelectedSlot;
				player.connection.send(new ServerboundSetCarriedItemPacket(oldSelectedSlot));
			}
		}

		private static void message(LocalPlayer player, String translationKey, Object... args) {
			player.displayClientMessage(Component.translatable(translationKey, args), false);
		}

		private static void log(String format, Object... args) {
			Constants.LOG.info("[AutoCraft] workstation " + String.format(format, args));
		}
	}

	private enum WorkstationState {
		OPEN, WAIT_OPEN, TRANSFER, WAIT_TRANSFER
	}
}
