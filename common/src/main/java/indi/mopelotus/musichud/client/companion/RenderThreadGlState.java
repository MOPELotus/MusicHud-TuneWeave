package indi.mopelotus.musichud.client.companion;

import com.mojang.blaze3d.opengl.GlStateManager;
import org.lwjgl.opengl.GL33C;

/** Reconcile ModernUI's direct GL work before desktop framebuffer operations. */
final class RenderThreadGlState {
    private RenderThreadGlState() {}

    static void restoreFramebufferBindings() {
        // Vulkan has no current GLFW GL context or GL framebuffer cache.
        if (org.lwjgl.glfw.GLFW.glfwGetCurrentContext() == 0L) return;
        // ModernUI/Arc3D submits screen drawing during extraction and binds FBOs
        // directly. RenderPearl's cache may still name the previous desktop FBO,
        // causing its transparent clear to skip a DRAW binding that is now stale.
        // Reconcile actual GL state with the cache before any desktop clear/draw.
        GL33C.glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER,
                GlStateManager.getFrameBuffer(GL33C.GL_READ_FRAMEBUFFER));
        GL33C.glBindFramebuffer(GL33C.GL_DRAW_FRAMEBUFFER,
                GlStateManager.getFrameBuffer(GL33C.GL_DRAW_FRAMEBUFFER));
    }
}
