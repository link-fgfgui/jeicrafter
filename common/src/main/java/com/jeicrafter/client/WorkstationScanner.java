package com.jeicrafter.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * Scans loaded chunks for blocks matching a workstation item (a JEI recipe catalyst).
 * <p>
 * Used by both the workstation highlight (collect all matches across the loaded world) and the
 * workstation auto-craft action (find the nearest match to open). Only fully loaded
 * {@link LevelChunk}s are considered; empty sections are skipped, so a chunk without a
 * matching block is cheap to traverse.
 */
public final class WorkstationScanner {
	private WorkstationScanner() {
	}

	/**
	 * Collects every block position in the loaded world whose block matches
	 * {@code workstationItem}. Iterates all chunks within {@code radiusChunks} of the player's
	 * chunk (use the client render distance to cover the whole loaded map). Safe to call off-thread
	 * as long as the inputs (chunk source, player position, section range) were captured on the
	 * client thread.
	 */
	public static Set<BlockPos> scanAll(ClientChunkCache chunkSource, int playerChunkX, int playerChunkZ, int radiusChunks, int minSectionY, int maxSectionY, ItemStack workstationItem) {
		Set<BlockPos> found = new HashSet<>();
		for (int chunkDX = -radiusChunks; chunkDX <= radiusChunks; chunkDX++) {
			for (int chunkDZ = -radiusChunks; chunkDZ <= radiusChunks; chunkDZ++) {
				int chunkX = playerChunkX + chunkDX;
				int chunkZ = playerChunkZ + chunkDZ;
				LevelChunk chunk = chunkSource.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
				if (chunk == null) {
					continue;
				}
				for (int sectionY = minSectionY; sectionY < maxSectionY; sectionY++) {
					LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY));
					if (section == null || section.hasOnlyAir()) {
						continue;
					}
					scanSection(section, chunkX, chunkZ, sectionY, workstationItem, found);
				}
			}
		}
		return found;
	}

	/**
	 * Finds the nearest block position in the loaded world whose block matches
	 * {@code workstationItem} and is within {@code maxRadius} of the player, or null if none.
	 * <p>
	 * Must be called on the client thread (it reads {@link Minecraft} state directly).
	 * <p>
	 * The caller supplies the search radius (from the config). Going beyond the vanilla
	 * server's interaction limit is pointless: the caller opens the workstation by
	 * right-clicking it, and the server validates that interaction against reach, so
	 * anything farther could never be opened anyway.
	 */
	public static BlockPos findNearest(ItemStack workstationItem, double maxRadius) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		if (level == null || minecraft.player == null || workstationItem.isEmpty()) {
			return null;
		}
		ClientChunkCache chunkSource = level.getChunkSource();
		// Match the server's interaction check: distance from the EYE position to the block's center.
		Vec3 playerPos = minecraft.player.getEyePosition();
		int radiusChunks = (int) (maxRadius / 16.0) + 1;
		int playerChunkX = SectionPos.blockToSectionCoord(playerPos.x);
		int playerChunkZ = SectionPos.blockToSectionCoord(playerPos.z);
		double radiusSquared = maxRadius * maxRadius;

		int minSectionY = level.getMinSection();
		int maxSectionY = level.getMaxSection();
		BlockPos nearest = null;
		double nearestDistanceSquared = Double.MAX_VALUE;

		for (int chunkDX = -radiusChunks; chunkDX <= radiusChunks; chunkDX++) {
			for (int chunkDZ = -radiusChunks; chunkDZ <= radiusChunks; chunkDZ++) {
				int chunkX = playerChunkX + chunkDX;
				int chunkZ = playerChunkZ + chunkDZ;
				LevelChunk chunk = chunkSource.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
				if (chunk == null) {
					continue;
				}
				for (int sectionY = minSectionY; sectionY < maxSectionY; sectionY++) {
					LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY));
					if (section == null || section.hasOnlyAir()) {
						continue;
					}
					BlockPos candidate = scanSectionNearest(section, chunkX, chunkZ, sectionY, workstationItem, playerPos, radiusSquared);
					if (candidate != null) {
						double distance = playerPos.distanceToSqr(Vec3.atCenterOf(candidate));
						if (distance < nearestDistanceSquared) {
							nearest = candidate;
							nearestDistanceSquared = distance;
						}
					}
				}
			}
		}
		return nearest;
	}

	/** Collects every matching position in the section into {@code found}. */
	private static void scanSection(
		LevelChunkSection section,
		int chunkX,
		int chunkZ,
		int sectionY,
		ItemStack workstationItem,
		Set<BlockPos> found
	) {
		int baseX = SectionPos.sectionToBlockCoord(chunkX);
		int baseZ = SectionPos.sectionToBlockCoord(chunkZ);
		int baseY = SectionPos.sectionToBlockCoord(sectionY);
		for (int dx = 0; dx < 16; dx++) {
			for (int dy = 0; dy < 16; dy++) {
				for (int dz = 0; dz < 16; dz++) {
					BlockState state = section.getBlockState(dx, dy, dz);
					if (state.isAir() || !workstationItem.is(state.getBlock().asItem())) {
						continue;
					}
					found.add(new BlockPos(baseX + dx, baseY + dy, baseZ + dz));
				}
			}
		}
	}

	private static BlockPos scanSectionNearest(
		LevelChunkSection section,
		int chunkX,
		int chunkZ,
		int sectionY,
		ItemStack workstationItem,
		Vec3 playerPos,
		double radiusSquared
	) {
		int baseX = SectionPos.sectionToBlockCoord(chunkX);
		int baseZ = SectionPos.sectionToBlockCoord(chunkZ);
		int baseY = SectionPos.sectionToBlockCoord(sectionY);
		BlockPos nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		for (int dx = 0; dx < 16; dx++) {
			for (int dy = 0; dy < 16; dy++) {
				for (int dz = 0; dz < 16; dz++) {
					BlockState state = section.getBlockState(dx, dy, dz);
					if (state.isAir() || !workstationItem.is(state.getBlock().asItem())) {
						continue;
					}
					BlockPos pos = new BlockPos(baseX + dx, baseY + dy, baseZ + dz);
					double distance = playerPos.distanceToSqr(Vec3.atCenterOf(pos));
					if (distance <= radiusSquared && distance < nearestDistance) {
						nearest = pos;
						nearestDistance = distance;
					}
				}
			}
		}
		return nearest;
	}
}
