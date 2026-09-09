package com.gatehousemc.platform.fabric.mixin;

import com.gatehousemc.platform.fabric.GatehouseMod;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.PlayerManager;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

@Mixin(PlayerManager.class)
public abstract class PlayerManagerMixin {
    @Inject(method = "checkCanJoin", at = @At("RETURN"), cancellable = true, require = 1)
    private void gatehousemc$afterCheckCanJoin(SocketAddress address, GameProfile profile,
                                                    CallbackInfoReturnable<Text> callback) {
        Text result = callback.getReturnValue();
        if (result == null || !(result.getContent() instanceof TranslatableTextContent translatable)) return;
        if (!"multiplayer.disconnect.not_whitelisted".equals(translatable.getKey())) return;
        callback.setReturnValue(GatehouseMod.handleWhitelistDenial(profile));
    }
}
