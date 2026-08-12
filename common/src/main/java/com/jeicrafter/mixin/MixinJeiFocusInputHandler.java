package com.jeicrafter.mixin;

import com.jeicrafter.Constants;
import com.jeicrafter.client.AutoCraftManager;
import com.jeicrafter.client.JeiCrafterKeys;
import mezz.jei.common.input.IInternalKeyMappings;
import mezz.jei.gui.bookmarks.IBookmark;
import mezz.jei.gui.bookmarks.RecipeBookmark;
import mezz.jei.gui.input.CombinedRecipeFocusSource;
import mezz.jei.gui.input.IClickableIngredientInternal;
import mezz.jei.gui.input.IUserInputHandler;
import mezz.jei.gui.input.UserInput;
import mezz.jei.gui.input.handlers.FocusInputHandler;
import mezz.jei.gui.input.handlers.SameElementInputHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Optional;

/**
 * Intercepts JEI item clicks (FocusInputHandler covers item list, bookmark bar, recipe screen, and container slots).
 * <p>
 * When holding the designated key + left-clicking an item:
 * - The click must land on a recipe bookmark element in the bookmark bar (only bookmark bar elements carry a
 *   {@link mezz.jei.gui.bookmarks.IBookmark}; item list, recipe screen and container slots do not) → otherwise break,
 *   preserve default JEI behavior;
 * - If that bookmark is a crafting recipe auto-craft can run → claim ownership during simulation (mouse press),
 *   run recursive auto-craft on that exact bookmark during execution (mouse release), and consume the click.
 */
@Mixin(value = FocusInputHandler.class, remap = false)
public class MixinJeiFocusInputHandler {

	@Shadow
	@Final
	@SuppressWarnings("unused")
	private CombinedRecipeFocusSource focusSource;

	@Inject(method = "handleClick", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeicrafter$tryAutoCraft(UserInput input, IInternalKeyMappings keyBindings, CallbackInfoReturnable<Optional<IUserInputHandler>> cir) {
		// Designated key not held or not a left-click → break, let JEI handle it
		if (!JeiCrafterKeys.isCraftKeyDown() || !input.is(keyBindings.getLeftClick())) {
			return;
		}

		List<IClickableIngredientInternal<?>> ingredientsUnderMouse = this.focusSource.getIngredientUnderMouse(input, keyBindings).toList();
		Constants.LOG.info("[AutoCraft] clicked candidate count={}", ingredientsUnderMouse.size());
		for (IClickableIngredientInternal<?> clicked : ingredientsUnderMouse) {
			// Only bookmark bar elements carry a bookmark; item list, recipe screen and container slots do not.
			// Require the click to be on a recipe bookmark element, so Z+left-click only triggers from the bookmark bar.
			Optional<IBookmark> elementBookmark = clicked.getElement().getBookmark();
			if (elementBookmark.isEmpty() || !(elementBookmark.get() instanceof RecipeBookmark<?, ?> recipeBookmark)) {
				Constants.LOG.info("[AutoCraft] candidate skip: hasBookmark={} isRecipeBookmark=false", elementBookmark.isPresent());
				continue;
			}
			// Category auto-craft cannot execute → break, preserve default behavior
			if (!AutoCraftManager.isCraftable(recipeBookmark)) {
				Constants.LOG.info("[AutoCraft] candidate skip: category={}", recipeBookmark.getRecipeCategory().getRecipeType());
				continue;
			}
			if (!input.isSimulate()) {
				// Mouse release: actually execute auto-craft. Pass the bookmark itself — resolving it back from
				// its output item would run the first bookmark with an equivalent output, not the clicked one.
				AutoCraftManager.tryCraft(recipeBookmark);
			}
			// Claim ownership during simulation phase to ensure JEI won't process this click on release
			Constants.LOG.info("[AutoCraft] consumed click, simulate={}", input.isSimulate());
			cir.setReturnValue(Optional.of(new SameElementInputHandler((IUserInputHandler) (Object) this, clicked::isMouseOver)));
			return;
		}
	}
}
