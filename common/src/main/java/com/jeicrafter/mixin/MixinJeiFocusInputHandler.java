package com.jeicrafter.mixin;

import com.jeicrafter.Constants;
import com.jeicrafter.client.AutoCraftManager;
import com.jeicrafter.client.JeiCrafterKeys;
import com.mojang.blaze3d.platform.InputConstants;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.common.input.IInternalKeyMappings;
import mezz.jei.gui.input.CombinedRecipeFocusSource;
import mezz.jei.gui.input.IClickableIngredientInternal;
import mezz.jei.gui.input.IUserInputHandler;
import mezz.jei.gui.input.UserInput;
import mezz.jei.gui.input.handlers.FocusInputHandler;
import mezz.jei.gui.input.handlers.SameElementInputHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
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
 * - If the item is bookmarked as a recipe bookmark's representative item → claim ownership during simulation (mouse press),
 *   run recursive auto-craft during execution (mouse release), and consume the click;
 * - Otherwise break, do nothing, preserve default JEI behavior.
 */
@Mixin(value = FocusInputHandler.class, remap = false)
public class MixinJeiFocusInputHandler {

	@Shadow
	@Final
	@SuppressWarnings("unused")
	private CombinedRecipeFocusSource focusSource;

	@Inject(method = "handleClick", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeicrafter$tryAutoCraft(UserInput input, IInternalKeyMappings keyBindings, CallbackInfoReturnable<Optional<IUserInputHandler>> cir) {
		// Print full diagnostic on every handleClick, for diagnosing "key not recognized / binding lost" issues
		logInputDiagnostic(input, keyBindings);

		// Designated key not held or not a left-click → break, let JEI handle it
		if (!JeiCrafterKeys.isCraftKeyDown() || !input.is(keyBindings.getLeftClick())) {
			return;
		}

		List<IClickableIngredientInternal<?>> ingredientsUnderMouse = this.focusSource.getIngredientUnderMouse(input, keyBindings).toList();
		Constants.LOG.info("[AutoCraft] clicked candidate count={}", ingredientsUnderMouse.size());
		for (IClickableIngredientInternal<?> clicked : ingredientsUnderMouse) {
			Optional<ItemStack> clickedStack = clicked.getElement().getTypedIngredient().getIngredient(VanillaTypes.ITEM_STACK);
			// Not bookmarked as a recipe bookmark → break, preserve default behavior
			if (clickedStack.isEmpty() || !AutoCraftManager.isBookmarked(clickedStack.get())) {
				Constants.LOG.info("[AutoCraft] candidate skip: hasStack={} bookmarked={}", clickedStack.isPresent(), clickedStack.map(AutoCraftManager::isBookmarked).orElse(false));
				continue;
			}
			if (!input.isSimulate()) {
				// Mouse release: actually execute auto-craft
				AutoCraftManager.tryCraft(clickedStack.get());
			}
			// Claim ownership during simulation phase to ensure JEI won't process this click on release
			Constants.LOG.info("[AutoCraft] consumed click, simulate={}", input.isSimulate());
			cir.setReturnValue(Optional.of(new SameElementInputHandler((IUserInputHandler) (Object) this, clicked::isMouseOver)));
			return;
		}
	}

	/**
	 * Full diagnostic of a click input and craft key binding state:
	 * - input key type/value/phase (simulate=press, release=execute)/modifiers
	 * - CRAFT key mapping's bound key, isDown state, whether the underlying GLFW physical key is actually held
	 */
	private static void logInputDiagnostic(UserInput input, IInternalKeyMappings keyBindings) {
		InputConstants.Key inputKey = input.getKey();
		InputConstants.Key bound = ((KeyMappingAccessor) JeiCrafterKeys.CRAFT).jeicrafter$getKey();
		boolean rawHeld = bound.getType() == InputConstants.Type.KEYSYM
			&& InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(), bound.getValue());
		boolean isLeft = input.is(keyBindings.getLeftClick());
		boolean isRight = input.is(keyBindings.getRightClick());
		Constants.LOG.info(
			"[AutoCraft] DIAG input='{}'(type={},code={}) left={} right={} simulate={} mods=0x{} | craft.isDown={} rawHeld={} bound='{}'(code={}) save='{}' unbound={}",
			inputKey.getDisplayName().getString(),
			inputKey.getType(),
			inputKey.getValue(),
			isLeft,
			isRight,
			input.isSimulate(),
			Integer.toHexString(input.getModifiers()),
			JeiCrafterKeys.CRAFT.isDown(),
			rawHeld,
			bound.getDisplayName().getString(),
			bound.getValue(),
			JeiCrafterKeys.CRAFT.saveString(),
			JeiCrafterKeys.CRAFT.isUnbound()
		);
	}
}
