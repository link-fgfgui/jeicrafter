package com.jeicrafter.mixin;

import com.jeicrafter.client.AutoCraftManager;
import com.jeicrafter.client.JeiCrafterKeys;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.common.input.IInternalKeyMappings;
import mezz.jei.gui.input.CombinedRecipeFocusSource;
import mezz.jei.gui.input.IClickableIngredientInternal;
import mezz.jei.gui.input.IUserInputHandler;
import mezz.jei.gui.input.UserInput;
import mezz.jei.gui.input.handlers.FocusInputHandler;
import mezz.jei.gui.input.handlers.SameElementInputHandler;
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
 * 拦截 JEI 对物品的点击(FocusInputHandler 覆盖物品列表、书签栏、配方界面与容器槽)。
 * <p>
 * 按住指定按键 + 左键点击物品时:
 * - 物品被收藏为 recipe bookmark 的代表物品 → 模拟阶段(鼠标按下)声明接管本次点击,
 *   执行阶段(鼠标松开)运行递归自动合成,并消费该点击;
 * - 否则 break,不干预,保持 JEI 默认行为。
 */
@Mixin(value = FocusInputHandler.class, remap = false)
public class MixinJeiFocusInputHandler {

	@Shadow
	@Final
	@SuppressWarnings("unused")
	private CombinedRecipeFocusSource focusSource;

	@Inject(method = "handleClick", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeicrafter$tryAutoCraft(UserInput input, IInternalKeyMappings keyBindings, CallbackInfoReturnable<Optional<IUserInputHandler>> cir) {
		// 未按住指定按键或不是左键点击 → break,交给 JEI 默认处理
		if (!JeiCrafterKeys.CRAFT.isDown() || !input.is(keyBindings.getLeftClick())) {
			return;
		}

		List<IClickableIngredientInternal<?>> ingredientsUnderMouse = this.focusSource.getIngredientUnderMouse(input, keyBindings).toList();
		for (IClickableIngredientInternal<?> clicked : ingredientsUnderMouse) {
			Optional<ItemStack> clickedStack = clicked.getElement().getTypedIngredient().getIngredient(VanillaTypes.ITEM_STACK);
			// 没有被收藏为 recipe bookmark → break,保持默认行为
			if (clickedStack.isEmpty() || !AutoCraftManager.isBookmarked(clickedStack.get())) {
				continue;
			}
			if (!input.isSimulate()) {
				// 鼠标松开:真正执行自动合成
				AutoCraftManager.tryCraft(clickedStack.get());
			}
			// 模拟阶段就声明接管,确保松开鼠标时 JEI 不再处理该点击
			cir.setReturnValue(Optional.of(new SameElementInputHandler((IUserInputHandler) (Object) this, clicked::isMouseOver)));
			return;
		}
	}
}
