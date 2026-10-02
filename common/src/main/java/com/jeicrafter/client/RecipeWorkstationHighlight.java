package com.jeicrafter.client;

import com.jeicrafter.Constants;
import com.jeicrafter.config.JeiCrafterConfig;
import com.jeicrafter.mixin.MixinJeiRecipesGui;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.gui.recipes.IRecipeGuiLogic;
import mezz.jei.gui.recipes.RecipesGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stateful highlight of all matching recipe workstations across the loaded world.
 * <p>
 * Triggered from the JEI recipe screen via the designated highlight key. Scans every loaded
 * chunk within a configurable radius for blocks matching the current recipe category's
 * workstations (JEI "recipe catalysts" — all of them, not just the first), and holds the result
 * until the recipe screen closes or a highlighted block is right-clicked.
 * <p>
 * The scan runs on a background thread: chunk sections are read-only here and the client chunk
 * cache's {@code getChunk(..., load=false)} never forces loads, so it is safe off-thread.
 */
public final class RecipeWorkstationHighlight {
	/** Color: bright yellow-green, readable over most terrain. */
	private static final float RED = 0.2F;
	private static final float GREEN = 1.0F;
	private static final float BLUE = 0.3F;
	private static final float ALPHA = 0.9F;

	private static volatile Set<BlockPos> positions = Collections.emptySet();
	private static volatile List<ItemStack> workstations = List.of();
	private static volatile boolean scanning;

	/**
	 * Monotonic scan generation. Incremented on every {@link #clear()} and on every new
	 * {@link #trigger(List, IRecipeCategory)}; a background scan only commits its result if its
	 * captured generation still matches, so a scan started before a clear (or superseded by a
	 * newer catalyst-set request) can never resurrect or overwrite the highlight, and no stale
	 * thread clears another thread's in-flight flag.
	 */
	private static volatile long generation;

	private RecipeWorkstationHighlight() {
	}

	/** Whether a highlight is currently being shown or scanned. */
	public static boolean isActive() {
		return !positions.isEmpty() || scanning;
	}

	/** The first workstation block item currently being highlighted (for the HUD tooltip). */
	public static ItemStack workstation() {
		for (ItemStack item : workstations) {
			if (!item.isEmpty()) {
				return item;
			}
		}
		return ItemStack.EMPTY;
	}

	public static Set<BlockPos> positions() {
		return positions;
	}

	public static float red() {
		return RED;
	}

	public static float green() {
		return GREEN;
	}

	public static float blue() {
		return BLUE;
	}

	public static float alpha() {
		return ALPHA;
	}

	/**
	 * Clears the current highlight and any in-flight scan. Called when the recipe screen closes,
	 * on world unload, or when JEI runtime becomes unavailable.
	 */
	public static void clear() {
		generation++;
		positions = Collections.emptySet();
		workstations = List.of();
		scanning = false;
	}

	/**
	 * Extracts workstation catalysts from JEI's active recipe category and triggers the highlight scan.
	 * Returns true if the key event was consumed (valid non-crafting workstation category found).
	 */
	public static boolean tryHighlight(RecipesGui recipesGui) {
		if (!JeiCrafterConfig.enableWorkstationHighlight() || !(recipesGui instanceof MixinJeiRecipesGui accessor)) {
			return false;
		}
		IRecipeGuiLogic logic = accessor.jeicrafter$getLogic();
		if (logic == null) {
			return false;
		}
		IRecipeCategory<?> category = logic.getSelectedRecipeCategory();
		if (category == null || RecipeTypes.CRAFTING.equals(category.getRecipeType())) {
			return false;
		}
		List<ItemStack> workstations = logic.getRecipeCatalysts(category)
			.map(ITypedIngredient::getIngredient)
			.filter(ItemStack.class::isInstance)
			.map(ItemStack.class::cast)
			.toList();
		if (workstations.isEmpty()) {
			return false;
		}
		trigger(workstations, category);
		if (JeiCrafterConfig.closeGuiOnWorkstationHighlight()) {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft.player != null) {
				minecraft.player.closeContainer();
			} else {
				minecraft.setScreen(null);
			}
		}
		return true;
	}

	/**
	 * Starts an asynchronous scan for the given workstation items (all recipe catalysts).
	 * Ignores empty lists and requests when the feature is disabled.
	 * <p>
	 * Minecraft's client thread-safety rules forbid touching {@link Minecraft} from another thread,
	 * so the {@link ClientLevel}, chunk source and player position are captured here on the caller
	 * (render) thread before the scan thread starts. Only chunk data reads happen off-thread.
	 */
	public static void trigger(List<ItemStack> workstationItems, IRecipeCategory<?> category) {
		List<ItemStack> items = workstationItems.stream().filter(stack -> !stack.isEmpty()).toList();
		if (items.isEmpty() || !JeiCrafterConfig.enableWorkstationHighlight()) {
			return;
		}
		// A held key re-fires screen keyPressed on every OS key-repeat (~5/s), so repeat triggers
		// for the SAME catalyst set (already highlighted or still scanning) are ignored: starting
		// a fresh scan for them every 200ms would wipe the highlight and spawn scan floods.
		// A DIFFERENT catalyst set always supersedes the in-flight scan — its generation bump
		// discards any stale result and keeps the older thread from clearing the new flag.
		if (sameCatalysts(workstations, items) && (scanning || !positions.isEmpty())) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		if (level == null || minecraft.player == null) {
			return;
		}
		workstations = List.copyOf(items);
		scanning = true;
		positions = Collections.emptySet();
		// Bump so this scan supersedes any in-flight scan for a different catalyst set.
		final long scanGeneration = ++generation;
		Constants.LOG.info("[Highlight] scanning for {} workstation(s) category={}", items.size(), category.getRecipeType());

		ClientChunkCache chunkSource = level.getChunkSource();
		Vec3 playerPos = minecraft.player.position();
		int playerChunkX = SectionPos.blockToSectionCoord(playerPos.x);
		int playerChunkZ = SectionPos.blockToSectionCoord(playerPos.z);
		// Scan every loaded chunk: the render distance is the client's chunk load radius.
		int radiusChunks = minecraft.options.getEffectiveRenderDistance() + 1;
		int minSectionY = level.getMinSection();
		int maxSectionY = level.getMaxSection();

		// Chunk data reads are safe off-thread; each position is only reported once.
		new Thread(() -> {
			try {
				Set<BlockPos> found = new HashSet<>();
				for (ItemStack item : items) {
					found.addAll(WorkstationScanner.scanAll(chunkSource, playerChunkX, playerChunkZ, radiusChunks, minSectionY, maxSectionY, item));
				}
				// Only commit if no clear() (or a newer scan) has invalidated this generation.
				if (scanGeneration == generation && sameCatalysts(workstations, items)) {
					positions = Collections.unmodifiableSet(found);
					Constants.LOG.info("[Highlight] scan complete: found {} workstation{} (={})", found.size(), found.size() == 1 ? "" : "s", found.isEmpty() ? "none" : found.iterator().next());
				}
			} catch (Throwable throwable) {
				Constants.LOG.error("[Highlight] scan failed", throwable);
			} finally {
				// Only this generation may clear the in-flight flag; a newer scan owns it now.
				if (scanGeneration == generation) {
					scanning = false;
				}
			}
		}, "jeicrafter-workstation-scan").start();
	}

	/** Whether two catalyst item lists cover the same blocks (item identity, ignoring NBT/count). */
	private static boolean sameCatalysts(List<ItemStack> a, List<ItemStack> b) {
		if (a.size() != b.size()) {
			return false;
		}
		List<ItemStack> remaining = new ArrayList<>(b);
		for (ItemStack item : a) {
			if (item.isEmpty()) {
				continue;
			}
			boolean matched = false;
			for (int i = 0; i < remaining.size(); i++) {
				ItemStack other = remaining.get(i);
				if (!other.isEmpty() && item.is(other.getItem())) {
					remaining.remove(i);
					matched = true;
					break;
				}
			}
			if (!matched) {
				return false;
			}
		}
		return true;
	}
}
