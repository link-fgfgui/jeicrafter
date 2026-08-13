package com.jeicrafter.client;

import com.jeicrafter.Constants;
import com.jeicrafter.config.JeiCrafterConfig;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.Set;

/**
 * Stateful highlight of all matching recipe workstations across the loaded world.
 * <p>
 * Triggered from the JEI recipe screen via the designated highlight key. Scans every loaded
 * chunk within a configurable radius for blocks matching the current recipe category's
 * workstations (JEI "recipe catalysts"), and holds the result until the recipe screen closes.
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
	private static volatile ItemStack workstation = ItemStack.EMPTY;
	private static volatile boolean scanning;

	private RecipeWorkstationHighlight() {
	}

	/** Whether a highlight is currently being shown or scanned. */
	public static boolean isActive() {
		return !positions.isEmpty() || scanning;
	}

	/** The workstation block item currently being highlighted (for the HUD tooltip). */
	public static ItemStack workstation() {
		return workstation;
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
		positions = Collections.emptySet();
		workstation = ItemStack.EMPTY;
		scanning = false;
	}

	/**
	 * Starts an asynchronous scan for the given workstation item. Ignores empty items and
	 * requests when the feature is disabled.
	 * <p>
	 * Minecraft's client thread-safety rules forbid touching {@link Minecraft} from another thread,
	 * so the {@link ClientLevel}, chunk source and player position are captured here on the caller
	 * (render) thread before the scan thread starts. Only chunk data reads happen off-thread.
	 */
	public static void trigger(ItemStack workstationItem, IRecipeCategory<?> category) {
		if (workstationItem.isEmpty() || !JeiCrafterConfig.enableWorkstationHighlight()) {
			return;
		}
		// A held key re-fires screen keyPressed on every OS key-repeat (~5/s), so the same trigger
		// would otherwise wipe the highlight and spawn a new scan every 200ms while the key is held.
		// Ignore re-triggers while a scan is in flight, or while the same workstation is already
		// highlighted.
		if (scanning || !positions.isEmpty() && workstation.is(workstationItem.getItem())) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		if (level == null || minecraft.player == null) {
			return;
		}
		workstation = workstationItem.copy();
		scanning = true;
		positions = Collections.emptySet();
		Constants.LOG.info("[Highlight] scanning for workstation={} category={}", workstationItem.getHoverName().getString(), category.getRecipeType());

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
				Set<BlockPos> found = WorkstationScanner.scanAll(chunkSource, playerChunkX, playerChunkZ, radiusChunks, minSectionY, maxSectionY, workstationItem);
				positions = Collections.unmodifiableSet(found);
				Constants.LOG.info("[Highlight] scan complete: found {} workstation{} (={})", found.size(), found.size() == 1 ? "" : "s", found.isEmpty() ? "none" : found.iterator().next());
			} catch (Throwable throwable) {
				Constants.LOG.error("[Highlight] scan failed", throwable);
			} finally {
				scanning = false;
			}
		}, "jeicrafter-workstation-scan").start();
	}
}
