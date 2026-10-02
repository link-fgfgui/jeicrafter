package com.jeicrafter.client;

import com.jeicrafter.Constants;
import com.jeicrafter.mixin.MixinJeiBookmarkOverlay;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.helpers.IStackHelper;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IBookmarkOverlay;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.gui.bookmarks.BookmarkList;
import mezz.jei.gui.bookmarks.IBookmark;
import mezz.jei.gui.bookmarks.IngredientBookmark;
import mezz.jei.gui.bookmarks.RecipeBookmark;
import mezz.jei.gui.input.MouseUtil;
import mezz.jei.gui.overlay.bookmarks.BookmarkOverlay;
import mezz.jei.library.plugins.jei.tags.ITagInfoRecipe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;

/**
 * Reorders bookmarks when triggered by the player while hovering over the bookmark overlay.
 * <p>
 * Sorting rules:
 * <ul>
 *   <li>Recipe bookmarks are treated by their displayed representative item.</li>
 *   <li>Identical items are grouped contiguously together.</li>
 *   <li>Bookmarked recipes with upstream/downstream crafting relationships are sorted in dependency order (materials before products).</li>
 *   <li>Unrelated bookmarks retain their relative positions in the list.</li>
 * </ul>
 */
public final class BookmarkSortManager {

	private BookmarkSortManager() {
	}

	/**
	 * Tries to sort bookmarks if the mouse is currently hovering over the visible bookmark overlay.
	 *
	 * @return {@code true} if the key press was consumed in the bookmark area; {@code false} otherwise.
	 */
	public static boolean trySortBookmarks() {
		IJeiRuntime runtime = AutoCraftManager.runtime();
		if (runtime == null) {
			return false;
		}
		IBookmarkOverlay overlay = runtime.getBookmarkOverlay();
		if (!(overlay instanceof BookmarkOverlay bookmarkOverlay)) {
			return false;
		}
		if (!bookmarkOverlay.isListDisplayed()) {
			return false;
		}
		double mouseX = MouseUtil.getX();
		double mouseY = MouseUtil.getY();
		if (!bookmarkOverlay.isMouseOver(mouseX, mouseY)) {
			return false;
		}
		BookmarkList bookmarkList = ((MixinJeiBookmarkOverlay) bookmarkOverlay).jeicrafter$getBookmarkList();
		if (bookmarkList == null || bookmarkList.isEmpty()) {
			return false;
		}
		if (!(bookmarkList instanceof IBookmarkListAccessor accessor)) {
			return false;
		}

		boolean changed = sortBookmarks(accessor, runtime);
		if (changed) {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft.getSoundManager() != null) {
				minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
			}
		}
		return true;
	}

	/**
	 * Performs topological dependency sorting and identical-item grouping on the bookmark list.
	 *
	 * @param accessor the bookmark list accessor
	 * @param runtime  the active JEI runtime
	 * @return {@code true} if the list ordering changed and was persisted; {@code false} otherwise
	 */
	public static boolean sortBookmarks(IBookmarkListAccessor accessor, IJeiRuntime runtime) {
		List<IBookmark> originalList = accessor.jeicrafter$getBookmarksList();
		if (originalList.size() <= 1) {
			return false;
		}

		IIngredientManager ingredientManager = runtime.getIngredientManager();
		IStackHelper stackHelper = runtime.getJeiHelpers().getStackHelper();

		int n = originalList.size();
		List<BookmarkNode> nodes = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			nodes.add(buildNode(i, originalList.get(i), runtime, ingredientManager, stackHelper));
		}

		// Group nodes by canonical key
		Map<String, List<BookmarkNode>> keyToNodes = new LinkedHashMap<>();
		for (BookmarkNode node : nodes) {
			keyToNodes.computeIfAbsent(node.canonicalKey, k -> new ArrayList<>()).add(node);
		}

		// Build dependency graph on canonical keys
		Set<String> allKeys = keyToNodes.keySet();
		Map<String, Set<String>> outgoing = new HashMap<>();
		Map<String, Set<String>> incoming = new HashMap<>();
		Map<String, Set<String>> undirected = new HashMap<>();

		for (String k : allKeys) {
			outgoing.put(k, new HashSet<>());
			incoming.put(k, new HashSet<>());
			undirected.put(k, new HashSet<>());
		}

		for (BookmarkNode node : nodes) {
			String productKey = node.canonicalKey;
			for (String inKey : node.inputKeys) {
				if (allKeys.contains(inKey) && !inKey.equals(productKey)) {
					// inKey is an ingredient for productKey -> inKey comes BEFORE productKey
					outgoing.get(inKey).add(productKey);
					incoming.get(productKey).add(inKey);
					undirected.get(inKey).add(productKey);
					undirected.get(productKey).add(inKey);
				}
			}
		}

		// Find connected components on undirected graph of keys
		Set<String> visited = new HashSet<>();
		List<ComponentInfo> components = new ArrayList<>();

		for (String key : allKeys) {
			if (!visited.contains(key)) {
				Set<String> componentKeys = new HashSet<>();
				Queue<String> queue = new ArrayDeque<>();
				queue.add(key);
				visited.add(key);
				int earliestIndex = Integer.MAX_VALUE;

				while (!queue.isEmpty()) {
					String curr = queue.poll();
					componentKeys.add(curr);
					for (BookmarkNode node : keyToNodes.get(curr)) {
						if (node.originalIndex < earliestIndex) {
							earliestIndex = node.originalIndex;
						}
					}
					for (String neighbor : undirected.get(curr)) {
						if (visited.add(neighbor)) {
							queue.add(neighbor);
						}
					}
				}

				components.add(new ComponentInfo(earliestIndex, componentKeys));
			}
		}

		// Order components by their earliest appearance in the original bookmark list
		components.sort(Comparator.comparingInt(ComponentInfo::earliestIndex));

		// Sort items inside each component and concatenate
		List<IBookmark> finalSortedList = new ArrayList<>(n);
		for (ComponentInfo componentInfo : components) {
			Set<String> componentKeys = componentInfo.keys;
			List<String> sortedKeys = topologicalSort(componentKeys, outgoing, incoming, keyToNodes);
			for (String k : sortedKeys) {
				for (BookmarkNode node : keyToNodes.get(k)) {
					finalSortedList.add(node.bookmark);
				}
			}
		}

		if (!finalSortedList.equals(originalList)) {
			accessor.jeicrafter$setBookmarksList(finalSortedList);
			Constants.LOG.info("[JeiCrafter] Sorted {} bookmarks", finalSortedList.size());
			return true;
		}
		return false;
	}

	private static List<String> topologicalSort(
		Set<String> component,
		Map<String, Set<String>> outgoing,
		Map<String, Set<String>> incoming,
		Map<String, List<BookmarkNode>> keyToNodes
	) {
		Map<String, Integer> inDegree = new HashMap<>();
		Map<String, Integer> earliestIndex = new HashMap<>();

		for (String k : component) {
			int deg = 0;
			for (String inc : incoming.get(k)) {
				if (component.contains(inc)) {
					deg++;
				}
			}
			inDegree.put(k, deg);

			int minIdx = Integer.MAX_VALUE;
			for (BookmarkNode node : keyToNodes.get(k)) {
				if (node.originalIndex < minIdx) {
					minIdx = node.originalIndex;
				}
			}
			earliestIndex.put(k, minIdx);
		}

		PriorityQueue<String> readyQueue = new PriorityQueue<>(Comparator.comparingInt(earliestIndex::get));
		for (String k : component) {
			if (inDegree.get(k) == 0) {
				readyQueue.add(k);
			}
		}

		List<String> result = new ArrayList<>(component.size());
		Set<String> placed = new HashSet<>();

		while (result.size() < component.size()) {
			if (!readyQueue.isEmpty()) {
				String curr = readyQueue.poll();
				result.add(curr);
				placed.add(curr);

				for (String out : outgoing.get(curr)) {
					if (component.contains(out) && !placed.contains(out)) {
						int remaining = inDegree.get(out) - 1;
						inDegree.put(out, remaining);
						if (remaining == 0) {
							readyQueue.add(out);
						}
					}
				}
			} else {
				// Cycle fallback: pick the unplaced key with lowest earliestIndex
				String best = null;
				int bestIdx = Integer.MAX_VALUE;
				for (String k : component) {
					if (!placed.contains(k)) {
						int idx = earliestIndex.get(k);
						if (idx < bestIdx) {
							bestIdx = idx;
							best = k;
						}
					}
				}
				if (best != null) {
					result.add(best);
					placed.add(best);
					for (String out : outgoing.get(best)) {
						if (component.contains(out) && !placed.contains(out)) {
							int remaining = inDegree.get(out) - 1;
							inDegree.put(out, remaining);
							if (remaining == 0) {
								readyQueue.add(out);
							}
						}
					}
				}
			}
		}

		return result;
	}

	private static BookmarkNode buildNode(
		int index,
		IBookmark bookmark,
		IJeiRuntime runtime,
		IIngredientManager ingredientManager,
		IStackHelper stackHelper
	) {
		String key = "";
		ItemStack repItem = ItemStack.EMPTY;
		Set<String> inputs = new HashSet<>();

		if (bookmark instanceof RecipeBookmark<?, ?> rb) {
			repItem = BuiltinRecipeGraph.outputOf(rb, runtime);
			if (repItem.isEmpty()) {
				repItem = rb.getRecipeOutput().getIngredient(VanillaTypes.ITEM_STACK).orElse(ItemStack.EMPTY);
			}
			if (!repItem.isEmpty()) {
				key = getItemKey(repItem, ingredientManager);
			} else {
				key = getIngredientKey(rb.getRecipeOutput(), ingredientManager);
			}

			if (!(rb.getRecipe() instanceof ITagInfoRecipe)) {
				try {
					IRecipeLayoutDrawable<?> layout = createLayout(rb.getRecipeCategory(), rb.getRecipe(), runtime);
					if (layout != null) {
						for (IRecipeSlotView slot : layout.getRecipeSlotsView().getSlotViews(RecipeIngredientRole.INPUT)) {
							List<ItemStack> itemVariations = slot.getIngredients(VanillaTypes.ITEM_STACK).toList();
							for (ItemStack var : itemVariations) {
								if (!var.isEmpty()) {
									String inKey = getItemKey(var, ingredientManager);
									if (!inKey.isEmpty()) {
										inputs.add(inKey);
									}
								}
							}
							slot.getAllIngredients().forEach(typed -> {
								if (typed.getType() != VanillaTypes.ITEM_STACK) {
									String inKey = getIngredientKey(typed, ingredientManager);
									if (!inKey.isEmpty()) {
										inputs.add(inKey);
									}
								}
							});
						}
					}
				} catch (Exception exception) {
					Constants.LOG.error("[BookmarkSorter] Failed to extract inputs for recipe {}", rb.getRecipeUid(), exception);
				}
			}
		} else if (bookmark instanceof IngredientBookmark<?> ib) {
			Optional<ItemStack> itemOpt = ib.getIngredient().getIngredient(VanillaTypes.ITEM_STACK);
			if (itemOpt.isPresent() && !itemOpt.get().isEmpty()) {
				repItem = itemOpt.get();
				key = getItemKey(repItem, ingredientManager);
			} else {
				key = getIngredientKey(ib.getIngredient(), ingredientManager);
			}
		} else {
			ITypedIngredient<?> typed = bookmark.getElement().getTypedIngredient();
			if (typed != null) {
				Optional<ItemStack> itemOpt = typed.getIngredient(VanillaTypes.ITEM_STACK);
				if (itemOpt.isPresent() && !itemOpt.get().isEmpty()) {
					repItem = itemOpt.get();
					key = getItemKey(repItem, ingredientManager);
				} else {
					key = getIngredientKey(typed, ingredientManager);
				}
			} else {
				key = "unknown:" + index;
			}
		}

		if (key.isEmpty()) {
			key = "empty:" + index;
		}

		BookmarkNode node = new BookmarkNode(index, bookmark, key, repItem);
		node.inputKeys.addAll(inputs);
		return node;
	}

	private static String getItemKey(ItemStack stack, IIngredientManager ingredientManager) {
		if (stack.isEmpty()) {
			return "";
		}
		IIngredientHelper<ItemStack> helper = ingredientManager.getIngredientHelper(VanillaTypes.ITEM_STACK);
		return helper.getUniqueId(stack, UidContext.Ingredient);
	}

	private static String getIngredientKey(ITypedIngredient<?> typed, IIngredientManager ingredientManager) {
		if (typed == null) {
			return "";
		}
		return getTypedUniqueId(typed, ingredientManager);
	}

	@SuppressWarnings("unchecked")
	private static <T> String getTypedUniqueId(ITypedIngredient<T> typed, IIngredientManager ingredientManager) {
		IIngredientHelper<T> helper = ingredientManager.getIngredientHelper(typed.getType());
		return helper.getUniqueId(typed.getIngredient(), UidContext.Ingredient);
	}

	private static IRecipeLayoutDrawable<?> createLayout(IRecipeCategory<?> category, Object recipe, IJeiRuntime runtime) {
		@SuppressWarnings({"unchecked", "rawtypes"})
		Optional<IRecipeLayoutDrawable<?>> layout = (Optional) runtime.getRecipeManager().createRecipeLayoutDrawable(
			(IRecipeCategory) category,
			recipe,
			runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup()
		);
		return layout.orElse(null);
	}

	private record ComponentInfo(int earliestIndex, Set<String> keys) {
	}

	private static final class BookmarkNode {
		final int originalIndex;
		final IBookmark bookmark;
		final String canonicalKey;
		final ItemStack representativeItem;
		final Set<String> inputKeys = new HashSet<>();

		BookmarkNode(int originalIndex, IBookmark bookmark, String canonicalKey, ItemStack representativeItem) {
			this.originalIndex = originalIndex;
			this.bookmark = bookmark;
			this.canonicalKey = canonicalKey;
			this.representativeItem = representativeItem;
		}
	}
}
