package indi.mopelotus.musichud.client.companion;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryUtil;

/** Read SDL's thread-local GL state on the calling render thread. */
final class RenderThreadGlState {
    private RenderThreadGlState() {}

    static long window() {
        // Ixeris forwards SDLVideo.SDL_GL_GetCurrentWindow to the event thread.
        // A thread-local GL query must stay on the thread that owns the context.
        return JNI.invokeP(SDLVideo.Functions.GL_GetCurrentWindow);
    }

    static long context() {
        return JNI.invokeP(SDLVideo.Functions.GL_GetCurrentContext);
    }

    static String error() {
        return MemoryUtil.memUTF8Safe(JNI.invokeP(SDLError.Functions.GetError));
    }

    static void restoreFramebufferBindings() {
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
