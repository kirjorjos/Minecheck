package net.minecheck.loader.legacyfabric.mixin;

import java.util.Locale;
import net.minecraft.util.Language;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Makes this line's language codes case-insensitive.
 *
 * <p>1.4.7 ships its translations as {@code lang/xx_YY.lang} and reads the code verbatim, so the
 * lowercase {@code en_us} a modern {@code options.txt} carries finds no file: the language load
 * throws inside the client's startup and the run never reaches a world. The code is canonicalised
 * before the lookup instead, which is what the newer lines' case-insensitive resource lookup does.
 */
@Mixin(Language.class)
public class LanguageCodeMixin {

  @ModifyVariable(method = "setCode", at = @At("HEAD"), argsOnly = true)
  private String canonicalCode(String code) {
    if (code != null && code.matches("[a-z]{2}_[a-z]{2}")) {
      return code.substring(0, 2) + "_" + code.substring(3).toUpperCase(Locale.ROOT);
    }
    return code;
  }
}
