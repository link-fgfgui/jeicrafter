package com.jeicrafter.client;

import com.jeicrafter.Constants;
import com.jeicrafter.api.BookmarkAction;
import com.jeicrafter.api.BookmarkActionContext;
import com.jeicrafter.api.BookmarkActionExecution;
import com.jeicrafter.api.BookmarkActionResult;
import com.jeicrafter.config.JeiCrafterConfig;
import mezz.jei.api.constants.RecipeTypes;
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
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Executes a non-crafting, non-tag recipe by opening the nearest workstation of the recipe's type
 * found in the loaded world, transferring the required inputs (and fuel for furnace-family menus)
 * into it, waiting for the output, and extracting it.
 * <p>
 * Registered as a built-in {@link BookmarkAction} with the same priority as the crafting action;
 * it is only selected when the crafting action does not support the recipe (i.e. any non-crafting
 * category with a workstation).
 * <p>
 * Both "open the workstation" and "transfer the required items" are toggleable in the config.
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
		if (!JeiCrafterConfig.autoOpenWorkstation()) {
			return false;
		}
		return !findWorkstationItem(context).isEmpty();
	}

	@Override
	public BookmarkActionExecution start(BookmarkActionContext context) {
		return new WorkstationExecution(context);
	}

	/** Returns the first workstation (recipe catalyst) item for the context's recipe type. */
	static ItemStack findWorkstationItem(BookmarkActionContext context) {
		IRecipeCategory<?> category = context.recipeCategory();
		return context.jeiRuntime().getRecipeManager()
			.createRecipeCatalystLookup(category.getRecipeType())
			.getItemStack()
			.findFirst()
			.orElse(ItemStack.EMPTY);
	}

	private static final class WorkstationExecution implements BookmarkActionExecution {
		private static final int OPEN_TIMEOUT_TICKS = 60;
		private static final int TRANSFER_TIMEOUT_TICKS = 100;
		private static final int COOK_TIMEOUT_TICKS = 800;

		private final BookmarkActionContext context;
		private final AbstractContainerMenu originalContainer;
		private final ItemStack workstationItem;
		private final ItemStack output;
		private WorkstationState state;
		private int waitTicks;
		private int stateId;
		private int resultCountBefore;
		private BlockPos targetPos;
		private int oldSelectedSlot = -1;

		private WorkstationExecution(BookmarkActionContext context) {
			this.context = context;
			this.originalContainer = context.container();
			this.output = context.output();
			this.workstationItem = findWorkstationItem(context);
			this.state = WorkstationState.OPEN;
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
					targetPos = WorkstationScanner.findNearest(workstationItem, JeiCrafterConfig.workstationMaxDistance());
					if (targetPos == null) {
						message(player, "jeicrafter.message.no_workstation", workstationName(), JeiCrafterConfig.workstationMaxDistance());
						log("no workstation for %s within range", workstationName());
						return terminal(BookmarkActionResult.FAILURE);
					}
					message(player, "jeicrafter.message.opening_workstation", workstationName());
					// Swap to an empty hotbar slot so a block item in hand cannot be placed instead
					// of opening the workstation GUI, then restore the original selection.
					oldSelectedSlot = player.getInventory().selected;
					selectEmptyHotbarSlot(player);
					BlockHitResult hit = new BlockHitResult(
						Vec3.atCenterOf(targetPos),
						Direction.UP,
						targetPos,
						false
					);
					InteractionResult result = minecraft.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
					restoreSelectedSlot(player);
					log("OPEN result=%s target=%s", result, targetPos);
					state = WorkstationState.WAIT_OPEN;
					waitTicks = 0;
					return BookmarkActionResult.RUNNING;
				}
				case WAIT_OPEN -> {
					AbstractContainerMenu current = player.containerMenu;
					if (current != originalContainer) {
						log("OPENED container=%s", current.getClass().getSimpleName());
						state = WorkstationState.TRANSFER;
						waitTicks = 0;
						return BookmarkActionResult.RUNNING;
					}
					return BookmarkActionResult.RUNNING;
				}
				case TRANSFER -> {
					if (!JeiCrafterConfig.autoTransferItems()) {
						// Open the workstation but leave it for manual filling.
						log("TRANSFER skipped by config, leaving workstation open");
						return terminal(BookmarkActionResult.SUCCESS);
					}
					AbstractContainerMenu current = player.containerMenu;
					IRecipeTransferManager transferManager = context.jeiRuntime().getRecipeTransferManager();
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
					// For furnace-family menus, also add fuel if the fuel slot is empty.
					if (current instanceof AbstractFurnaceMenu furnaceMenu && fuelNeeded(furnaceMenu, player)) {
						addFuel(furnaceMenu, player);
					}
					stateId = current.getStateId();
					state = WorkstationState.WAIT_TRANSFER;
					waitTicks = 0;
					return BookmarkActionResult.RUNNING;
				}
				case WAIT_TRANSFER -> {
					AbstractContainerMenu current = player.containerMenu;
					// Proceed once the server has applied the transfer (stateId changed). As a
					// fallback, if the transfer was a no-op (inputs already in place) the stateId
					// never changes, so proceed after a short grace window.
					boolean synced = current.getStateId() != stateId;
					if (synced || waitTicks > 10) {
						log("TRANSFER_DONE synced=%s stateId=%d", synced, current.getStateId());
						state = WorkstationState.WAIT_COOK;
						waitTicks = 0;
						return BookmarkActionResult.RUNNING;
					}
					return BookmarkActionResult.RUNNING;
				}
				case WAIT_COOK -> {
					AbstractContainerMenu current = player.containerMenu;
					Slot resultSlot = findResultSlot(current);
					if (resultSlot != null && resultSlot.hasItem() && resultSlot.getItem().is(output.getItem())) {
						log("COOK_COMPLETE slot=%d result=%s", resultSlot.index, resultSlot.getItem());
						resultCountBefore = countInventory(output);
						clickResult(current, resultSlot.index);
						stateId = current.getStateId();
						state = WorkstationState.WAIT_EXTRACT;
						waitTicks = 0;
						return BookmarkActionResult.RUNNING;
					}
					return BookmarkActionResult.RUNNING;
				}
				case WAIT_EXTRACT -> {
					AbstractContainerMenu current = player.containerMenu;
					if (current.getStateId() != stateId) {
						int resultCount = countInventory(output);
						boolean success = resultCount > resultCountBefore;
						if (success && JeiCrafterConfig.autoCloseWorkstation()) {
							player.closeContainer();
						}
						log("EXTRACT success=%s resultCount=%d", success, resultCount);
						return terminal(success ? BookmarkActionResult.SUCCESS : BookmarkActionResult.FAILURE);
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
			if (player != null && player.containerMenu != originalContainer) {
				player.closeContainer();
			}
			AutoCraftManager.reanchorSession();
		}

		/** Returns a terminal result and re-anchors the session's container for the enclosing chain. */
		private BookmarkActionResult terminal(BookmarkActionResult result) {
			AutoCraftManager.reanchorSession();
			return result;
		}

		private int currentTimeout() {
			return switch (state) {
				case WAIT_COOK -> COOK_TIMEOUT_TICKS;
				case WAIT_OPEN -> OPEN_TIMEOUT_TICKS;
				default -> TRANSFER_TIMEOUT_TICKS;
			};
		}

		/** Finds the recipe output slot: the furnace result slot, or the first non-player slot with the output. */
		private Slot findResultSlot(AbstractContainerMenu container) {
			if (container instanceof AbstractFurnaceMenu) {
				Slot slot = container.getSlot(AbstractFurnaceMenu.RESULT_SLOT);
				return slot != null && slot.hasItem() ? slot : null;
			}
			for (Slot slot : container.slots) {
				if (slot.container instanceof Inventory) {
					continue; // player inventory slots are not recipe outputs
				}
				if (slot.hasItem() && slot.getItem().is(output.getItem())) {
					return slot;
				}
			}
			return null;
		}

		private static boolean fuelNeeded(AbstractFurnaceMenu menu, LocalPlayer player) {
			Slot fuelSlot = menu.getSlot(AbstractFurnaceMenu.FUEL_SLOT);
			return !fuelSlot.hasItem() && findFuelSlot(player) != -1;
		}

		private static int findFuelSlot(LocalPlayer player) {
			Inventory inv = player.getInventory();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				ItemStack stack = inv.getItem(i);
				if (!stack.isEmpty() && AbstractFurnaceBlockEntity.isFuel(stack)) {
					return i;
				}
			}
			return -1;
		}

		/**
		 * Shift-clicks a fuel item from the player inventory so the furnace menu moves it to the
		 * fuel slot. {@code findFuelSlot} returns a player-inventory index (0-8 hotbar, 9-35 main);
		 * the furnace menu's player slots are offset (3-29 main, 30-38 hotbar), so the index must be
		 * mapped to the container slot index used by the click packet.
		 */
		private static void addFuel(AbstractFurnaceMenu menu, LocalPlayer player) {
			int inventoryIndex = findFuelSlot(player);
			if (inventoryIndex == -1 || player.containerMenu != menu) {
				return;
			}
			// Inventory.items: 0-8 hotbar, 9-35 main. Furnace container: main 3-29, hotbar 30-38.
			int containerSlotIndex;
			if (inventoryIndex < 9) {
				containerSlotIndex = 30 + inventoryIndex; // hotbar
			} else {
				containerSlotIndex = 3 + (inventoryIndex - 9); // main inventory
			}
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft.gameMode != null) {
				minecraft.gameMode.handleInventoryMouseClick(
					menu.containerId, containerSlotIndex, 0, ClickType.QUICK_MOVE, player
				);
			}
		}

		private void clickResult(AbstractContainerMenu container, int slotIndex) {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft.gameMode != null) {
				minecraft.gameMode.handleInventoryMouseClick(
					container.containerId, slotIndex, 0, ClickType.QUICK_MOVE, context.player()
				);
			}
		}

		/** Switches the selected hotbar slot to the first empty one (so block items aren't placed). */
		private static void selectEmptyHotbarSlot(LocalPlayer player) {
			Inventory inv = player.getInventory();
			for (int i = 0; i < 9; i++) {
				if (inv.getItem(i).isEmpty()) {
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

		private int countInventory(ItemStack wanted) {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft.player == null || wanted.isEmpty()) {
				return 0;
			}
			int count = 0;
			for (ItemStack stack : minecraft.player.getInventory().items) {
				if (stack.is(wanted.getItem())) {
					count += stack.getCount();
				}
			}
			return count;
		}

		private String workstationName() {
			return workstationItem.isEmpty() ? "?" : workstationItem.getHoverName().getString();
		}

		private static void message(LocalPlayer player, String translationKey, Object... args) {
			player.displayClientMessage(Component.translatable(translationKey, args), false);
		}

		private static void log(String format, Object... args) {
			Constants.LOG.info("[AutoCraft] workstation " + format, args);
		}
	}

	private enum WorkstationState {
		OPEN, WAIT_OPEN, TRANSFER, WAIT_TRANSFER, WAIT_COOK, WAIT_EXTRACT
	}
}
