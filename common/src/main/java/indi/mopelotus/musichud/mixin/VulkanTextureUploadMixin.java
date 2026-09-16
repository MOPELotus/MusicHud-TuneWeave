package indi.mopelotus.musichud.mixin;

import com.mojang.renderpearl.api.textures.GpuTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.nio.ByteBuffer;

/** Fixes the 26.3 Vulkan staging alignment when glyph and RGBA uploads share a buffer. */
@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder", remap = false)
public abstract class VulkanTextureUploadMixin {
    @ModifyArgs(method = "writeToTexture", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanTransientMemory;uploadStaging" +
                    "(Ljava/nio/ByteBuffer;JI)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
    private void alignTextureUpload(Args args, GpuTexture destination, ByteBuffer source,
                                    int mipLevel, int layer, int x, int y, int width, int height) {
        // Vanilla requests byte alignment; Vulkan requires a multiple of the
        // destination texel block size (VUID-vkCmdCopyBufferToImage-dstImage-07975).
        long alignment = args.get(1);
        args.set(1, Math.max(alignment, destination.getFormat().blockSize()));
    }
}
